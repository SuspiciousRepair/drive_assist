package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The OpenStreetMap tiles under the route maps: memory, then disk, then
 * the network. One per process.
 *
 * tile.openstreetmap.org is free with no key and no count limit, under its
 * tile usage policy (operations.osmfoundation.org/policies/tiles), which
 * this follows: a User-Agent that names the app, at most two downloads at a
 * time, every tile kept on disk and not fetched again for
 * {@link #FRESH_MS}, and no bulk download (only the tiles a screen shows).
 * The map shows the "© OpenStreetMap" credit (RouteView).
 *
 * Kept on disk, a place seen once draws again with no network: the car is
 * often parked where the signal is poor. A tile that cannot be fetched is
 * simply not drawn (the plain background shows, as before the map) and is
 * tried again after {@link #RETRY_MS}. */
final class TileCache {
    private static final String TAG = "TileCache";
    static final String URL_FMT = "https://tile.openstreetmap.org/%d/%d/%d.png";
    /** Disk tiles younger than this are not fetched again. */
    static final long FRESH_MS = 30L * 24 * 3600_000L;
    /** A failed tile waits this long before the next try. */
    static final long RETRY_MS = 2 * 60_000L;
    /** Disk budget; past it the oldest tiles go. */
    static final long DISK_BYTES = 150L * 1024 * 1024;

    private static TileCache instance;

    static synchronized TileCache get(Context c) {
        if (instance == null) instance = new TileCache(c.getApplicationContext());
        return instance;
    }

    private final File dir;
    private final String userAgent;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Two at a time: the tile policy's limit.
    private final ExecutorService net = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "map-tiles");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    // Main thread only.
    private final Set<String> pending = new HashSet<>();
    private final Map<String, Long> failedAt = new HashMap<>();
    private int writes;

    private TileCache(Context app) {
        dir = new File(app.getCacheDir(), "map-tiles");
        String ver = "?";
        try {
            ver = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        userAgent = "DriveAssist/" + ver + " (+https://github.com/SuspiciousRepair/drive_assist)";
    }

    /** The tile's picture, or null for now: then it is loaded in the
     * background and onReady runs on the main thread when it is here.
     * Call on the main thread. */
    Bitmap get(int z, int x, int y, Runnable onReady) {
        String key = z + "/" + x + "/" + y;
        Bitmap b = mem.get(key);
        if (b != null) return b;
        if (pending.contains(key)) return null;
        Long failed = failedAt.get(key);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return null;
        pending.add(key);
        net.execute(() -> {
            Bitmap got = load(z, x, y);
            main.post(() -> {
                pending.remove(key);
                if (got != null) { mem.put(key, got); failedAt.remove(key); }
                else failedAt.put(key, System.currentTimeMillis());
                if (got != null && onReady != null) onReady.run();
            });
        });
        return null;
    }

    private File file(int z, int x, int y) {
        return new File(dir, z + "/" + x + "/" + y + ".png");
    }

    /** Disk first; the network when the disk has none or an old one. An
     * old disk tile still beats nothing when the network fails. */
    private Bitmap load(int z, int x, int y) {
        File f = file(z, x, y);
        Bitmap disk = null;
        if (f.isFile()) {
            disk = BitmapFactory.decodeFile(f.getPath());
            if (disk != null && System.currentTimeMillis() - f.lastModified() < FRESH_MS) return disk;
        }
        byte[] png = fetch(z, x, y);
        if (png == null) return disk;
        Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (b == null) return disk;
        save(f, png);
        return b;
    }

    private byte[] fetch(int z, int x, int y) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(String.format(java.util.Locale.US, URL_FMT, z, x, y))
                .openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", userAgent);
            if (c.getResponseCode() != 200) {
                Log.w(TAG, "tile " + z + "/" + x + "/" + y + ": HTTP " + c.getResponseCode());
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Exception e) {
            Log.w(TAG, "tile " + z + "/" + x + "/" + y + ": " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Written to a temp file and renamed, so a cut write never leaves a
     * broken tile behind. */
    private void save(File f, byte[] png) {
        try {
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(png); }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception e) {
            Log.w(TAG, "save " + f + ": " + e);
        }
        synchronized (this) {
            if (++writes % 100 != 0) return;
        }
        trim();
    }

    /** Past DISK_BYTES, the least recently written tiles go first. */
    private void trim() {
        java.util.List<File> all = new java.util.ArrayList<>();
        collect(dir, all);
        long total = 0;
        for (File f : all) total += f.length();
        if (total <= DISK_BYTES) return;
        all.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : all) {
            if (total <= DISK_BYTES * 8 / 10) break;
            total -= f.length();
            f.delete();
        }
    }

    private static void collect(File d, java.util.List<File> out) {
        File[] list = d.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) collect(f, out);
            else out.add(f);
        }
    }
}
