package com.geely.drivemem.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.util.Log;

import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.util.Prefs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Saves the trip screen's map on the car before anybody opens it, so a
 * trip that ends in a garage (no signal) still shows its map there.
 *
 * Only the tiles the trip screen will show are fetched, never an area
 * around the car: the tile usage policy (TileCache) forbids bulk downloads.
 * Three moments:
 * <ul>
 * <li>while driving, at most every {@link #DRIVE_GAP_MS}: the map of the
 *     trip so far, when that set of tiles changed. Some of these are not the
 *     final map (the zoom drops as the trip grows), the price of having a
 *     map when the end has no signal;</li>
 * <li>at trip end: the final map;</li>
 * <li>a trip end that could not fetch everything stays pending (at most
 *     {@link #MAX_PENDING}, kept across restarts) and is tried again when a
 *     network comes back and when the next trip starts moving.</li>
 * </ul>
 * Tiles already on the disk and fresh are not fetched again, so asking for
 * the same map twice costs nothing but a look at the disk. */
public final class MapPreload {
    private static final String TAG = "MapPreload";
    static final long DRIVE_GAP_MS = 60_000L;
    static final int MAX_PENDING = 5;
    /** The trip screen's map size when it was never opened on this car:
     * measured on the 1920 x 1080 panel. */
    static final int DEFAULT_W = 1395, DEFAULT_H = 530;
    private static final String KEY_W = "map_preload_w", KEY_H = "map_preload_h";
    private static final String KEY_PENDING = "map_preload_pending";

    private MapPreload() {}

    // The open trip's box, hub thread only.
    private static double minLat, minLon, maxLat, maxLon;
    private static boolean any;
    private static long lastRunMs = -1;
    private static String lastSet = "";

    /** A new trip: no box yet. Hub thread. */
    public static void reset() {
        any = false;
        lastRunMs = -1;
        lastSet = "";
    }

    /** A route point of the open trip. Hub thread; cheap: a box update,
     * and once a minute the tile maths and a hand-off to the tile thread. */
    public static void onPoint(Context ctx, double lat, double lon, long now) {
        if (!Prefs.getOsmMapsEnabled(ctx)) return;
        if (!any) {
            minLat = maxLat = lat; minLon = maxLon = lon; any = true;
            retryPending(ctx);   // the car is moving, so likely online again
        } else {
            minLat = Math.min(minLat, lat); maxLat = Math.max(maxLat, lat);
            minLon = Math.min(minLon, lon); maxLon = Math.max(maxLon, lon);
        }
        if (lastRunMs >= 0 && now - lastRunMs < DRIVE_GAP_MS) return;
        lastRunMs = now;
        int[] size = size(ctx);
        List<MapTiles.Tile> tiles = tilesFor(minLat, minLon, maxLat, maxLon,
            size[0], size[1], pad(ctx));
        String set = setKey(tiles);
        if (set.equals(lastSet)) return;
        lastSet = set;
        TileCache.get(ctx).prefetch(tiles, null);
    }

    /** The trip was saved as tripId: fetch its final map, and keep it
     * pending until every tile is on the disk. Any thread. */
    public static void onTripSaved(Context ctx, long tripId) {
        if (!Prefs.getOsmMapsEnabled(ctx)) return;
        double[] box = null;
        try {
            Cursor c = CarDb.get(ctx).db().rawQuery(
                "SELECT MIN(lat), MIN(lon), MAX(lat), MAX(lon), COUNT(*) FROM trip_point WHERE trip_id = ?",
                new String[]{String.valueOf(tripId)});
            try {
                if (c.moveToFirst() && c.getInt(4) >= 2) {
                    box = new double[]{c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getDouble(3)};
                }
            } finally { c.close(); }
        } catch (Throwable t) {
            Log.w(TAG, "trip box: " + t);
        }
        if (box == null) return;
        String entry = boxString(box);
        synchronized (MapPreload.class) {
            List<String> p = pending(ctx);
            p.remove(entry);
            p.add(entry);
            while (p.size() > MAX_PENDING) p.remove(0);
            savePending(ctx, p);
        }
        fetchPending(ctx, entry);
    }

    /** Tries every pending trip map again. Any thread. */
    static void retryPending(Context ctx) {
        if (!Prefs.getOsmMapsEnabled(ctx)) return;
        List<String> p;
        synchronized (MapPreload.class) { p = pending(ctx); }
        for (String entry : p) fetchPending(ctx, entry);
    }

    private static void fetchPending(Context ctx, String entry) {
        if (!Prefs.getOsmMapsEnabled(ctx)) return;
        double[] b = parseBox(entry);
        if (b == null) { done(ctx, entry); return; }
        int[] size = size(ctx);
        TileCache.get(ctx).prefetch(tilesFor(b[0], b[1], b[2], b[3], size[0], size[1], pad(ctx)),
            ok -> { if (ok) done(ctx, entry); });
    }

    private static void done(Context ctx, String entry) {
        synchronized (MapPreload.class) {
            List<String> p = pending(ctx);
            if (p.remove(entry)) savePending(ctx, p);
        }
    }

    /** The trip screen tells its map size, so the tiles fetched are the
     * ones it will draw. */
    static void rememberSize(Context ctx, int w, int h) {
        if (w <= 0 || h <= 0) return;
        SharedPreferences f = Prefs.file(ctx);
        if (f.getInt(KEY_W, 0) == w && f.getInt(KEY_H, 0) == h) return;
        f.edit().putInt(KEY_W, w).putInt(KEY_H, h).apply();
    }

    private static int[] size(Context ctx) {
        SharedPreferences f = Prefs.file(ctx);
        return new int[]{f.getInt(KEY_W, DEFAULT_W), f.getInt(KEY_H, DEFAULT_H)};
    }

    /** RouteView's pad (40 dp). */
    private static int pad(Context ctx) {
        return Math.round(40 * ctx.getResources().getDisplayMetrics().density);
    }

    /** The tiles RouteView draws for a route inside this box, on a w x h
     * view: its projection centre (the box centre), its fit (200 m at
     * least across) and its tile cover. A route's box corners project to
     * the box of its points, so the corners alone give the same fit. */
    static List<MapTiles.Tile> tilesFor(double minLat, double minLon, double maxLat, double maxLon,
                                        int w, int h, int pad) {
        double[] lat = {minLat, maxLat}, lon = {minLon, maxLon};
        double[] c = RouteGeometry.centre(lat, lon);
        double[][] xy = RouteGeometry.project(lat, lon, c[0], c[1]);
        RouteGeometry.Fit f = RouteGeometry.fit(xy[0], xy[1], w, h, pad, 200);
        return MapTiles.cover(c[0], c[1], f.x(0), f.y(0), f.pxPerM, w, h);
    }

    /** One string per set of tiles: zoom and corners. */
    static String setKey(List<MapTiles.Tile> tiles) {
        if (tiles.isEmpty()) return "";
        MapTiles.Tile a = tiles.get(0), b = tiles.get(tiles.size() - 1);
        return a.key() + ":" + b.key();
    }

    static String boxString(double[] b) {
        return String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", b[0], b[1], b[2], b[3]);
    }

    static double[] parseBox(String s) {
        String[] p = s.split(",");
        if (p.length != 4) return null;
        try {
            double[] b = new double[4];
            for (int i = 0; i < 4; i++) b[i] = Double.parseDouble(p[i]);
            return b;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> pending(Context ctx) {
        List<String> out = new ArrayList<>();
        String s = Prefs.file(ctx).getString(KEY_PENDING, "");
        for (String e : s.split(";")) if (!e.isEmpty()) out.add(e);
        return out;
    }

    private static void savePending(Context ctx, List<String> p) {
        Prefs.file(ctx).edit().putString(KEY_PENDING, String.join(";", p)).apply();
    }
}
