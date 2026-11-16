package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RouteGeometryTest {

    @Test public void speedBandsIncludeTheirUpperLimit() {
        assertEquals(0, RouteGeometry.band(0));
        assertEquals(0, RouteGeometry.band(20));
        assertEquals(1, RouteGeometry.band(20.1));
        assertEquals(2, RouteGeometry.band(80));
        assertEquals(3, RouteGeometry.band(110));
        assertEquals(4, RouteGeometry.band(140));
    }

    // 0.01 deg of latitude is about 1112 m; at -23.5 a degree of longitude
    // is cos(23.5) = 0.917 of that.
    @Test public void projectionIsMetresAroundTheCentreNorthUp() {
        double[][] xy = RouteGeometry.project(
            new double[]{-23.51, -23.50}, new double[]{-46.61, -46.60});
        assertEquals(-556, xy[1][0], 2);   // south of centre
        assertEquals(556, xy[1][1], 2);    // north of centre
        assertEquals(-510, xy[0][0], 2);   // west
        assertEquals(510, xy[0][1], 2);    // east
    }

    @Test public void fitKeepsAspectAndPadding() {
        // 1000 m wide, 500 m tall, into 1000 x 1000 px with 100 px padding.
        RouteGeometry.Fit f = RouteGeometry.fit(
            new double[]{-500, 500}, new double[]{-250, 250}, 1000, 1000, 100);
        assertEquals(0.8, f.pxPerM, 1e-9);
        assertEquals(100, f.x(-500), 1e-3);
        assertEquals(900, f.x(500), 1e-3);
        assertEquals(300, f.y(250), 1e-3);   // north is up
        assertEquals(700, f.y(-250), 1e-3);
    }

    @Test public void tinyRouteIsNotBlownUp() {
        RouteGeometry.Fit f = RouteGeometry.fit(new double[]{0, 5}, new double[]{0, 5}, 1000, 1000, 0);
        assertTrue(f.pxPerM <= 1000 / 200.0 + 1e-9);
    }

    @Test public void scaleBarLengthsAreRound() {
        assertEquals(500, RouteGeometry.niceLengthM(740), 1e-9);
        assertEquals(2000, RouteGeometry.niceLengthM(2600), 1e-9);
        assertEquals(1000, RouteGeometry.niceLengthM(1000), 1e-9);
        assertEquals("2 km", RouteGeometry.lengthLabel(2000));
        assertEquals("500 m", RouteGeometry.lengthLabel(500));
    }

    @Test public void aSegmentHasTheBandOfItsMeanSpeed() {
        assertEquals(0, RouteGeometry.segmentBand(new double[]{10, 30}, 0));    // mean 20: red
        assertEquals(1, RouteGeometry.segmentBand(new double[]{10, 40}, 0));    // mean 25: orange
    }

    @Test public void oneRunWhenEverySegmentHasTheSameBand() {
        assertArrayEquals(new int[]{0, 3}, RouteGeometry.bandRuns(new double[]{60, 62, 70, 65}));
    }

    @Test public void aNewRunStartsWhereTheBandChanges() {
        // Segment means: 10 (red), 10 (red), 35 (orange), 60 (green), 60 (green).
        double[] speed = {10, 10, 10, 60, 60, 60};
        assertArrayEquals(new int[]{0, 2, 3, 5}, RouteGeometry.bandRuns(speed));
    }

    // The runs must give every segment the same colour as one line per segment did.
    @Test public void runsColourEachSegmentAsBefore() {
        double[] speed = {0, 15, 30, 55, 90, 120, 100, 40, 40, 5, 0, 80};
        int[] runs = RouteGeometry.bandRuns(speed);
        assertEquals(0, runs[0]);
        assertEquals(speed.length - 1, runs[runs.length - 1]);
        for (int j = 0; j + 1 < runs.length; j++) {
            assertTrue(runs[j] < runs[j + 1]);
            int band = RouteGeometry.segmentBand(speed, runs[j]);
            for (int i = runs[j]; i < runs[j + 1]; i++) {
                assertEquals(band, RouteGeometry.segmentBand(speed, i));
            }
            if (j + 2 < runs.length) {
                assertTrue(band != RouteGeometry.segmentBand(speed, runs[j + 1]));
            }
        }
    }

    @Test public void noRunsWithFewerThanTwoPoints() {
        assertEquals(0, RouteGeometry.bandRuns(new double[0]).length);
        assertEquals(0, RouteGeometry.bandRuns(new double[]{50}).length);
    }

    @Test public void decimateDropsPointsCloserThanTheLimit() {
        // Half a pixel per point: one kept point every 2 px, and both ends.
        float[] x = new float[21], y = new float[21];
        for (int i = 0; i < x.length; i++) x[i] = i * 0.5f;
        assertArrayEquals(new int[]{0, 4, 8, 12, 16, 20}, RouteGeometry.decimate(x, y, 0, 20, 2f));
    }

    @Test public void decimateKeepsPointsThatAreFarApart() {
        float[] x = {0, 10, 20, 30}, y = {0, 5, 0, 5};
        assertArrayEquals(new int[]{0, 1, 2, 3}, RouteGeometry.decimate(x, y, 0, 3, 2f));
    }

    @Test public void decimateAlwaysKeepsBothEndsOfTheRange() {
        float[] x = {0, 0.1f, 0.2f, 0.3f, 50}, y = new float[5];
        assertArrayEquals(new int[]{1, 3}, RouteGeometry.decimate(x, y, 1, 3, 2f));
        assertArrayEquals(new int[]{2}, RouteGeometry.decimate(x, y, 2, 2, 2f));
    }

    // No kept point is further than the limit from a dropped one's place in line.
    @Test public void decimateNeverMovesTheLineByMoreThanTheLimit() {
        float[] x = new float[200], y = new float[200];
        for (int i = 0; i < x.length; i++) { x[i] = i * 0.7f; y[i] = (float) Math.sin(i / 9.0) * 30; }
        int[] keep = RouteGeometry.decimate(x, y, 0, 199, 2f);
        assertTrue(keep.length < 200);
        int k = 0;
        for (int i = 0; i < x.length; i++) {
            if (k + 1 < keep.length && keep[k + 1] <= i) k++;
            double d = Math.hypot(x[i] - x[keep[k]], y[i] - y[keep[k]]);
            assertTrue("point " + i, d < 2.0 + 1e-3);
        }
    }
}
