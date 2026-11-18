package com.geely.drivemem.ui;

import java.util.ArrayList;
import java.util.List;

/** Pure maths for the map under a route: which OpenStreetMap tiles cover
 * the view, and where each one goes on the screen. Kept out of the View so
 * it can be unit tested; TileCache fetches the pictures.
 *
 * The tiles are Web Mercator, 256 px each, 2^z by 2^z of them at zoom z.
 * RouteGeometry.project uses the same projection (scaled to metres at the
 * centre), so a route point and the road under it land on the same pixel.
 *
 * The zoom is the one whose tiles are drawn at 1x to 2x their size: never
 * shrunk (a shrunk tile has labels too small for this panel), and at most
 * doubled (a tile blown up more than that is blurred). */
public final class MapTiles {
    private MapTiles() {}

    static final int TILE_PX = 256;
    /** The highest zoom tile.openstreetmap.org serves. */
    static final int MAX_ZOOM = 19;

    /** One tile on the screen: its address, and its square in screen px. */
    static final class Tile {
        final int z, x, y;
        final float left, top, size;
        Tile(int z, int x, int y, float left, float top, float size) {
            this.z = z; this.x = x; this.y = y; this.left = left; this.top = top; this.size = size;
        }
        String key() { return z + "/" + x + "/" + y; }
    }

    /** Tile pixels per metre at zoom z, at latitude latDeg. */
    static double tilePxPerM(int z, double latDeg) {
        return TILE_PX * Math.pow(2, z)
            / (2 * Math.PI * RouteGeometry.EARTH_M * Math.cos(Math.toRadians(latDeg)));
    }

    /** The zoom whose tiles are drawn at 1x to 2x for a view of pxPerM
     * screen pixels per metre at latDeg. Clamped to 0..MAX_ZOOM, so a very
     * close view gets the closest tiles, blown up. */
    static int zoomFor(double pxPerM, double latDeg) {
        if (!(pxPerM > 0)) return 0;
        double z = Math.log(pxPerM / tilePxPerM(0, latDeg)) / Math.log(2);
        int zi = (int) Math.floor(z + 1e-9);
        return Math.max(0, Math.min(MAX_ZOOM, zi));
    }

    /** World pixel x of a longitude at zoom z (0 at 180 W). */
    static double worldX(double lonDeg, int z) {
        return (lonDeg + 180) / 360 * TILE_PX * Math.pow(2, z);
    }

    /** World pixel y of a latitude at zoom z (0 at the top, 85.05 N). */
    static double worldY(double latDeg, int z) {
        return (1 - RouteGeometry.mercY(latDeg) / Math.PI) / 2 * TILE_PX * Math.pow(2, z);
    }

    /** The tiles that cover a w x h view. (cLat, cLon) is the centre the
     * route was projected around (RouteGeometry.project), drawn at screen
     * (cx, cy) with pxPerM screen pixels per metre: RouteGeometry.Fit. */
    static List<Tile> cover(double cLat, double cLon, double cx, double cy, double pxPerM,
                            int w, int h) {
        List<Tile> out = new ArrayList<>();
        if (w <= 0 || h <= 0 || !(pxPerM > 0)) return out;
        int z = zoomFor(pxPerM, cLat);
        double s = pxPerM / tilePxPerM(z, cLat);   // screen px per tile px
        double wx = worldX(cLon, z), wy = worldY(cLat, z);
        int n = 1 << z;
        int x0 = (int) Math.floor((wx + (0 - cx) / s) / TILE_PX);
        int x1 = (int) Math.floor((wx + (w - cx) / s) / TILE_PX);
        int y0 = (int) Math.floor((wy + (0 - cy) / s) / TILE_PX);
        int y1 = (int) Math.floor((wy + (h - cy) / s) / TILE_PX);
        x0 = Math.max(0, x0); y0 = Math.max(0, y0);
        x1 = Math.min(n - 1, x1); y1 = Math.min(n - 1, y1);
        float size = (float) (TILE_PX * s);
        for (int ty = y0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                float left = (float) (cx + (tx * (double) TILE_PX - wx) * s);
                float top = (float) (cy + (ty * (double) TILE_PX - wy) * s);
                out.add(new Tile(z, tx, ty, left, top, size));
            }
        }
        return out;
    }
}
