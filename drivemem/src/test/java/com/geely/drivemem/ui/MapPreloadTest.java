package com.geely.drivemem.ui;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MapPreloadTest {

    private static final int W = MapPreload.DEFAULT_W, H = MapPreload.DEFAULT_H, PAD = 40;

    /** What RouteView draws for these points: centre, projection and fit
     * from every point (RouteView.project + rebuild). */
    private static List<MapTiles.Tile> drawn(double[] lat, double[] lon) {
        double[] c = RouteGeometry.centre(lat, lon);
        double[][] xy = RouteGeometry.project(lat, lon, c[0], c[1]);
        RouteGeometry.Fit f = RouteGeometry.fit(xy[0], xy[1], W, H, PAD, 200);
        return MapTiles.cover(c[0], c[1], f.x(0), f.y(0), f.pxPerM, W, H);
    }

    private static List<MapTiles.Tile> preloaded(double[] lat, double[] lon) {
        double minLat = lat[0], maxLat = lat[0], minLon = lon[0], maxLon = lon[0];
        for (int i = 1; i < lat.length; i++) {
            minLat = Math.min(minLat, lat[i]); maxLat = Math.max(maxLat, lat[i]);
            minLon = Math.min(minLon, lon[i]); maxLon = Math.max(maxLon, lon[i]);
        }
        return MapPreload.tilesFor(minLat, minLon, maxLat, maxLon, W, H, PAD);
    }

    private static void assertSameTiles(List<MapTiles.Tile> a, List<MapTiles.Tile> b) {
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) assertEquals(a.get(i).key(), b.get(i).key());
    }

    // The point of the class: the tiles fetched ahead are the ones the trip
    // screen draws, for short and long trips. Neutral places, no real ones.
    @Test public void preloadsTheTilesTheTripScreenDraws() {
        double[][] starts = {{30.0, -40.0}, {-23.0, 10.0}, {51.0, 5.0}};
        double[] spans = {0.0005, 0.01, 0.05, 0.4};
        for (double[] s : starts) {
            for (double span : spans) {
                int n = 50;
                double[] lat = new double[n], lon = new double[n];
                for (int i = 0; i < n; i++) {
                    double t = i / (double) (n - 1);
                    lat[i] = s[0] + span * Math.sin(t * 3) * 0.7;
                    lon[i] = s[1] + span * t;
                }
                List<MapTiles.Tile> want = drawn(lat, lon);
                assertFalse(want.isEmpty());
                assertSameTiles(want, preloaded(lat, lon));
            }
        }
    }

    // A screen-sized map is a few dozen tiles, not an area download.
    @Test public void aTripMapIsAFewDozenTiles() {
        double[] lat = {30.0, 30.3}, lon = {-40.0, -39.5};
        int n = MapPreload.tilesFor(30.0, -40.0, 30.3, -39.5, W, H, PAD).size();
        assertTrue("tiles " + n, n > 0 && n <= 40);
        assertSameTiles(drawn(lat, lon), preloaded(lat, lon));
    }

    @Test public void setKeyChangesWithTheSet() {
        List<MapTiles.Tile> a = MapPreload.tilesFor(30.0, -40.0, 30.001, -39.999, W, H, PAD);
        List<MapTiles.Tile> b = MapPreload.tilesFor(30.0, -40.0, 30.001, -39.999, W, H, PAD);
        List<MapTiles.Tile> c = MapPreload.tilesFor(30.0, -40.0, 30.2, -39.7, W, H, PAD);
        assertEquals(MapPreload.setKey(a), MapPreload.setKey(b));
        assertFalse(MapPreload.setKey(a).equals(MapPreload.setKey(c)));
        assertEquals("", MapPreload.setKey(java.util.Collections.emptyList()));
    }

    @Test public void boxRoundTrips() {
        double[] b = {30.123456, -40.5, 30.2, -40.1};
        assertArrayEquals(b, MapPreload.parseBox(MapPreload.boxString(b)), 1e-9);
        assertNull(MapPreload.parseBox("1,2,3"));
        assertNull(MapPreload.parseBox("a,b,c,d"));
    }

    @Test public void onlyPngIsSaved() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0};
        assertTrue(TileCache.isPng(png));
        assertFalse(TileCache.isPng("<html>error</html>".getBytes()));
        assertFalse(TileCache.isPng(null));
    }
}
