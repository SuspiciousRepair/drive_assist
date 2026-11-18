package com.geely.drivemem.ui;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MapTilesTest {

    // A neutral place, no real location.
    private static final double LAT = 30.0, LON = -40.0;

    // The chosen zoom draws its tiles at 1x to 2x: never shrunk.
    @Test public void zoomDrawsTilesAtOneToTwoTimes() {
        for (double pxPerM = 0.001; pxPerM < 5; pxPerM *= 1.37) {
            int z = MapTiles.zoomFor(pxPerM, LAT);
            if (z == MapTiles.MAX_ZOOM || z == 0) continue;
            double s = pxPerM / MapTiles.tilePxPerM(z, LAT);
            assertTrue("scale " + s + " at " + pxPerM, s >= 1 - 1e-9 && s < 2 + 1e-9);
        }
    }

    @Test public void zoomIsClamped() {
        assertEquals(MapTiles.MAX_ZOOM, MapTiles.zoomFor(1e6, LAT));
        assertEquals(0, MapTiles.zoomFor(1e-9, LAT));
        assertEquals(0, MapTiles.zoomFor(0, LAT));
    }

    @Test public void worldOriginIsTopLeft() {
        assertEquals(0, MapTiles.worldX(-180, 0), 1e-9);
        assertEquals(256, MapTiles.worldX(180, 0), 1e-9);
        assertEquals(128, MapTiles.worldY(0, 0), 1e-9);
        assertEquals(0, MapTiles.worldY(85.05112878, 0), 1e-6);
    }

    // The point of the projection change: a route point drawn by RouteView
    // (RouteGeometry.project + Fit) and the same place on the tile under it
    // land on the same screen pixel, also far from the centre.
    @Test public void routePointsLandOnTheirPlaceOnTheTiles() {
        double[] lat = {LAT - 0.4, LAT + 0.4, LAT + 0.1, LAT - 0.37};
        double[] lon = {LON - 0.6, LON + 0.6, LON - 0.2, LON + 0.55};
        double[] c = RouteGeometry.centre(lat, lon);
        double[][] xy = RouteGeometry.project(lat, lon, c[0], c[1]);
        RouteGeometry.Fit f = RouteGeometry.fit(xy[0], xy[1], 1400, 800, 40);
        List<MapTiles.Tile> tiles = MapTiles.cover(c[0], c[1], f.x(0), f.y(0), f.pxPerM, 1400, 800);
        assertFalse(tiles.isEmpty());
        for (int i = 0; i < lat.length; i++) {
            float sx = f.x(xy[0][i]), sy = f.y(xy[1][i]);
            MapTiles.Tile t = tiles.get(0);
            double s = t.size / MapTiles.TILE_PX;
            double wx = MapTiles.worldX(lon[i], t.z), wy = MapTiles.worldY(lat[i], t.z);
            double tx = t.left + (wx - t.x * (double) MapTiles.TILE_PX) * s;
            double ty = t.top + (wy - t.y * (double) MapTiles.TILE_PX) * s;
            assertEquals("x of point " + i, sx, tx, 0.5);
            assertEquals("y of point " + i, sy, ty, 0.5);
        }
    }

    // The tiles cover the whole view, with no gap, and nothing far outside.
    @Test public void tilesCoverTheView() {
        int w = 1200, h = 700;
        List<MapTiles.Tile> tiles = MapTiles.cover(LAT, LON, w / 2.0, h / 2.0, 0.05, w, h);
        float minL = Float.MAX_VALUE, minT = Float.MAX_VALUE, maxR = -Float.MAX_VALUE, maxB = -Float.MAX_VALUE;
        for (MapTiles.Tile t : tiles) {
            minL = Math.min(minL, t.left); minT = Math.min(minT, t.top);
            maxR = Math.max(maxR, t.left + t.size); maxB = Math.max(maxB, t.top + t.size);
            assertTrue(t.left < w && t.top < h && t.left + t.size > 0 && t.top + t.size > 0);
        }
        assertTrue(minL <= 0 && minT <= 0 && maxR >= w && maxB >= h);
        int cols = Math.round((maxR - minL) / tiles.get(0).size);
        int rows = Math.round((maxB - minT) / tiles.get(0).size);
        assertEquals(cols * rows, tiles.size());
    }

    @Test public void noTilesForAnEmptyView() {
        assertTrue(MapTiles.cover(LAT, LON, 0, 0, 0.05, 0, 0).isEmpty());
        assertTrue(MapTiles.cover(LAT, LON, 0, 0, 0, 100, 100).isEmpty());
    }

    // Projection stays plain metres near the centre (the scale bar relies
    // on it), and north of the centre gets the Mercator stretch.
    @Test public void projectionIsMetresAtTheCentre() {
        double[][] xy = RouteGeometry.project(new double[]{LAT + 0.001, LAT + 1},
            new double[]{LON, LON}, LAT, LON);
        assertEquals(111.2, xy[1][0], 0.2);
        assertTrue(xy[1][1] > 111_195 * 1.003);
    }
}
