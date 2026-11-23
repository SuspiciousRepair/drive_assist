package com.geely.drivemem.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;

import java.io.File;
import java.io.FileOutputStream;

/** Album covers kept on the head unit, one file per song
 * (CarMediaSessions.trackKey: artist + title, the same for every player).
 *
 * A cover seen once comes back without the phone: a song that Bluetooth
 * plays with no cover shows the one CarPlay sent for it before, and a cover
 * URL is downloaded once. Kept: the MAX_FILES songs heard last, each a JPEG
 * of at most MAX_SIDE px (about 30-60 KB, about 10 MB in all), in the app's
 * cache folder, which Android may empty when storage runs low.
 *
 * Disk work only: call it off the UI thread. */
public final class CoverCache {
    private static final String TAG = CarAccess.TAG;
    static final int MAX_FILES = 200;
    static final int MAX_SIDE = 512;

    private CoverCache() {}

    private static File file(Context ctx, String trackId) {
        return new File(new File(ctx.getCacheDir(), "covers"), LyricsClient.safeName(trackId) + ".jpg");
    }

    /** The kept cover of this song, or null. A hit counts as heard now. */
    public static Bitmap get(Context ctx, String trackId) {
        if (trackId == null) return null;
        File f = file(ctx, trackId);
        if (!f.isFile()) return null;
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b != null) //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
        return b;
    }

    /** Keeps this cover for the song, if it is not kept yet. */
    public static void put(Context ctx, String trackId, Bitmap b) {
        if (trackId == null || b == null || b.getWidth() <= 0 || b.getHeight() <= 0) return;
        File f = file(ctx, trackId);
        if (f.isFile()) return;
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

    /** The size to keep: the longer side at most maxSide, same shape. */
    static int[] scaled(int w, int h, int maxSide) {
        int side = Math.max(w, h);
        if (side <= maxSide) return new int[]{w, h};
        return new int[]{Math.max(1, Math.round(w * (float) maxSide / side)),
                         Math.max(1, Math.round(h * (float) maxSide / side))};
    }
}
