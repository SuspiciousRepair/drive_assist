package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import com.geely.drivemem.util.Prefs;

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
 * tried again by itself: soon at first, then less often, up to every
 * {@link #RETRY_MS} ({@link #retryDelayMs}), and at once when the car gets
 * a network again. Before this, a failed tile only came back if something
 * else redrew the map, so a gap made while the car was joining a network
 * stayed blank for as long as the screen was open.
 *
 * Every view that asks for a tile is told when to look again, not only
 * the first one: a screen opened again (a new view) while the tile was
 * still loading, or still waiting for its retry, used to get null and no
 * call back, so its gap stayed blank (charging map, 2026-11-20). The retry
 * clock is elapsedRealtime, which a clock set by the network cannot move.
 * "Network back" listens to every network, not the default one: with an
 * always-on VPN the default network stays the VPN while Wi-Fi comes and
 * goes, so that call never came. */
final class TileCache {
    private static final String TAG = "TileCache";
    static final String URL_FMT = "https://tile.openstreetmap.org/%d/%d/%d.png";
    /** Disk tiles younger than this are not fetched again. */
    static final long FRESH_MS = 30L * 24 * 3600_000L;
    /** The first retry of a failed tile; each later one waits twice as long. */
    static final long FIRST_RETRY_MS = 5_000L;
    /** The longest wait between two tries of a failed tile. */
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
    // Everyone who asked for a tile that is loading: all told when it ends.
    private final Map<String, Set<Runnable>> askers = new HashMap<>();
    // elapsedRealtime of the next try of a failed tile.
    private final Map<String, Long> retryAt = new HashMap<>();
    // elapsedRealtime of the redraw already posted for each waiting view.
    private final Map<Runnable, Long> wakeAt = new HashMap<>();
    private final Map<String, Integer> failures = new HashMap<>();
    // The views waiting on a failed tile, told to redraw when the network
    // comes back.
    private final Set<Runnable> waiting = new HashSet<>();
    // Tiles a prefetch is fetching now: any thread.
    private final Set<String> prefetching = java.util.Collections.synchronizedSet(new HashSet<>());
    private final Context app;
    private int writes;

    private TileCache(Context app) {
        this.app = app;
        dir = new File(app.getCacheDir(), "map-tiles");
        String ver = "?";
        try {
            ver = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        userAgent = "DriveAssist/" + ver + " (+https://github.com/SuspiciousRepair/drive_assist)";
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                app.getSystemService(Context.CONNECTIVITY_SERVICE);
            // Any network with internet, not the default one: see the class
            // comment (always-on VPN).
            if (cm != null) cm.registerNetworkCallback(
                new android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                new android.net.ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(android.net.Network n) {
                        if (!Prefs.getOsmMapsEnabled(TileCache.this.app)) return;
                        main.post(TileCache.this::retryNow);
                        MapPreload.retryPending(TileCache.this.app);
                    }
                });
        } catch (Exception e) {
            Log.w(TAG, "no network callback: " + e);
        }
    }

    /** How long a tile waits for its next try after it failed this many
     * times in a row: FIRST_RETRY_MS, doubled each time, at most RETRY_MS. */
    static long retryDelayMs(int failures) {
        if (failures <= 1) return FIRST_RETRY_MS;
        int shift = Math.min(failures - 1, 20);
        return Math.min(RETRY_MS, FIRST_RETRY_MS << shift);
    }

    /** Whether a view needs a new redraw posted for a tile due at
     * {@code dueAt}: not when one already posted ({@code postedAt}, null
     * for none) comes first, and is not past. */
    static boolean needsWake(Long postedAt, long now, long dueAt) {
        return postedAt == null || postedAt < now || postedAt > dueAt;
    }

    /** Redraws this view when the tile is due, unless a redraw already
     * posted comes first. Main thread. */
    private void wakeAt(Runnable view, long dueAt) {
        waiting.add(view);
        long now = android.os.SystemClock.elapsedRealtime();
        if (!needsWake(wakeAt.get(view), now, dueAt)) return;
        wakeAt.put(view, dueAt);
        main.postDelayed(() -> {
            Long w = wakeAt.get(view);
            if (w != null && w == dueAt) wakeAt.remove(view);
            view.run();
        }, Math.max(0L, dueAt - now));
    }

    /** A network is back: every failed tile may be tried again now. */
    private void retryNow() {
        if (!Prefs.getOsmMapsEnabled(app)) return;
        if (retryAt.isEmpty()) return;
        retryAt.clear();
        failures.clear();
        wakeAt.clear();
        java.util.List<Runnable> run = new java.util.ArrayList<>(waiting);
        waiting.clear();
        for (Runnable r : run) r.run();
    }

    /** The tile's picture, or null for now: then it is loaded in the
     * background and onReady runs on the main thread when it is here.
     * Call on the main thread. Opt-in via Prefs.getOsmMapsEnabled. */
    Bitmap get(int z, int x, int y, Runnable onReady) {
        if (!Prefs.getOsmMapsEnabled(app)) return null;
        String key = z + "/" + x + "/" + y;
        Bitmap b = mem.get(key);
        if (b != null) return b;
        if (pending.contains(key)) {
            // Loading for another view: this one is told too.
            if (onReady != null) askers.get(key).add(onReady);
            return null;
        }
        Long at = retryAt.get(key);
        if (at != null && android.os.SystemClock.elapsedRealtime() < at) {
            // Waiting for its retry: look again then.
            if (onReady != null) wakeAt(onReady, at);
            return null;
        }
        pending.add(key);
        Set<Runnable> who = new HashSet<>();
        if (onReady != null) who.add(onReady);
        askers.put(key, who);
        net.execute(() -> {
            Bitmap got = load(z, x, y);
            main.post(() -> {
                pending.remove(key);
                Set<Runnable> told = askers.remove(key);
                if (told == null) told = new HashSet<>();
                if (got != null) {
                    mem.put(key, got);
                    retryAt.remove(key);
                    failures.remove(key);
                    // Nothing failed is left: let go of the waiting views.
                    if (retryAt.isEmpty()) { waiting.clear(); wakeAt.clear(); }
                    for (Runnable r : told) r.run();
                    return;
                }
                // Failed: come back by itself, so the gap does not stay
                // until something else redraws the map.
                int n = failures.containsKey(key) ? failures.get(key) + 1 : 1;
                failures.put(key, n);
                long due = android.os.SystemClock.elapsedRealtime() + retryDelayMs(n);
                retryAt.put(key, due);
                for (Runnable r : told) wakeAt(r, due);
            });
        });
        return null;
    }

    /** Puts these tiles on the disk, for a screen that is not open yet
     * (MapPreload). Nothing is drawn. Tiles on the disk and fresh are
     * skipped. One task on one tile thread, one tile after the other, so
     * a screen that is open keeps the other thread. Stops at the first
     * failure: with no network the rest would only wait for timeouts.
     * onDone (may be null) gets true when every tile is on the disk; it
     * runs on the tile thread. Any thread. */
    void prefetch(java.util.List<MapTiles.Tile> tiles, java.util.function.Consumer<Boolean> onDone) {
        if (!Prefs.getOsmMapsEnabled(app)) { if (onDone != null) onDone.accept(false); return; }
        if (tiles.isEmpty()) { if (onDone != null) onDone.accept(true); return; }
        net.execute(() -> {
            boolean ok = true;
            for (MapTiles.Tile t : tiles) {
                File f = file(t.z, t.x, t.y);
                if (f.isFile() && System.currentTimeMillis() - f.lastModified() < FRESH_MS) continue;
                String key = t.key();
                // Another prefetch has it: not on the disk yet, so not ok.
                if (!prefetching.add(key)) { ok = false; continue; }
                try {
                    byte[] png = fetch(t.z, t.x, t.y);
                    if (png == null || !isPng(png)) { ok = false; break; }
                    save(f, png);
                } finally {
                    prefetching.remove(key);
                }
            }
            if (onDone != null) onDone.accept(ok);
        });
    }

    /** The PNG signature: a tile, not an error page. */
    static boolean isPng(byte[] b) {
        return b != null && b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
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
        if (!Prefs.getOsmMapsEnabled(app)) return null;
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

    /** Bytes of map kept on the disk. Walks the folder: call off the main
     * thread. */
    long diskBytes() {
        java.util.List<File> all = new java.util.ArrayList<>();
        collect(dir, all);
        long total = 0;
        for (File f : all) total += f.length();
        return total;
    }

    /** Forgets every saved tile, in memory and on the disk (Config →
     * System → Trip routes → Clear saved map). Maps download again when
     * next shown. The memory part runs on the main thread, the disk part
     * on the tile threads. */
    void clear(Runnable onDone) {
        main.post(() -> {
            mem.evictAll();
            retryAt.clear();
            failures.clear();
            wakeAt.clear();
            net.execute(() -> {
                java.util.List<File> all = new java.util.ArrayList<>();
                collect(dir, all);
                for (File f : all) f.delete();
                if (onDone != null) main.post(onDone);
            });
        });
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
