package com.geely.drivemem.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;

import java.io.File;
import java.io.FileOutputStream;

/** Album covers kept on the head unit, one file per album ({@link #key}:
 * artist + album, the same for every player). A song with no album name
 * gets a file of its own (its track key).
 *
 * A cover seen once comes back without the phone: a song that Bluetooth
 * plays with no cover shows the one CarPlay sent for its album before, and a
 * cover URL is downloaded once. Kept: the MAX_FILES albums heard last, each a JPEG
 * of at most MAX_SIDE px (about 30-60 KB, about 10 MB in all), in the app's
 * cache folder, which Android may empty when storage runs low.
 *
 * Disk work only: call it off the UI thread. */
public final class CoverCache {
    private static final String TAG = CarAccess.TAG;
    static final int MAX_FILES = 200;
    static final int MAX_SIDE = 512;

    private CoverCache() {}

    /** The file key of a song's cover: its album (artist + album, case and
     * spaces ignored), or the song's own track key when it has no album. */
    public static String key(String artist, String album, String trackId) {
        if (album == null || album.trim().isEmpty()) return trackId;
        String s = (artist == null ? "" : artist.trim().toLowerCase(java.util.Locale.ROOT)) + "\n"
            + album.trim().toLowerCase(java.util.Locale.ROOT);
        return "a-" + java.util.UUID.nameUUIDFromBytes(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static File file(Context ctx, String key) { return file(ctx.getCacheDir(), key); }

    static File file(File cacheDir, String key) {
        return new File(new File(cacheDir, "covers"), LyricsClient.safeName(key) + ".jpg");
    }

    /** The kept cover for this key, or null. A hit counts as heard now. */
    public static Bitmap get(Context ctx, String key) {
        if (key == null) return null;
        File f = file(ctx, key);
        if (!f.isFile()) return null;
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b != null) //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
        return b;
    }

    /** Keeps this cover for the key, in place of the one kept before: the
     * player's newest cover is the right one (a grey "no cover" picture
     * kept once must not stay forever). */
    public static void put(Context ctx, String key, Bitmap b) {
        if (key == null || b == null || b.getWidth() <= 0 || b.getHeight() <= 0) return;
        File f = file(ctx, key);
        File dir = f.getParentFile();
        if (dir == null || !(dir.isDirectory() || dir.mkdirs())) return;
        int[] wh = scaled(b.getWidth(), b.getHeight(), MAX_SIDE);
        Bitmap small = wh[0] == b.getWidth() ? b : Bitmap.createScaledBitmap(b, wh[0], wh[1], true);
        File tmp = new File(dir, f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            small.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (Throwable t) {
            Log.w(TAG, "cover cache: " + t);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        FileCache.trim(dir, MAX_FILES);
    }

    /** Forgets the kept cover for this key (a wrong one the owner asked to
     * reload). True when a file was there. One file delete, so the UI
     * thread may call it: the reload after it must not find the file. */
    public static boolean remove(Context ctx, String key) { return remove(ctx.getCacheDir(), key); }

    static boolean remove(File cacheDir, String key) {
        return key != null && file(cacheDir, key).delete();
    }

    /** The size to keep: the longer side at most maxSide, same shape. */
    static int[] scaled(int w, int h, int maxSide) {
        int side = Math.max(w, h);
        if (side <= maxSide) return new int[]{w, h};
        return new int[]{Math.max(1, Math.round(w * (float) maxSide / side)),
                         Math.max(1, Math.round(h * (float) maxSide / side))};
    }
}
