package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TripRouteStatsTest {

    @Test public void cumulativeDistanceAddsEachStep() {
        // 0.01 deg of latitude is about 1.112 km.
        double[] km = TripRouteStats.cumulativeKm(
            new double[]{0, 0.01, 0.02}, new double[]{0, 0, 0});
        assertEquals(0, km[0], 1e-9);
        assertEquals(1.112, km[1], 0.001);
        assertEquals(2.224, km[2], 0.002);
    }

    @Test public void medianDropsSpikesAndKeepsSlopes() {
        double[] out = TripRouteStats.medianSmooth(new double[]{10, 11, 40, 13, 14, 15}, 5);
        // Near the ends the window is shorter (and even): the upper middle wins.
        assertArrayEquals(new double[]{11, 13, 13, 14, 15, 14}, out, 1e-9);
    }

    @Test public void stopsAreLeftOutOfMovingTime() {
        // 5 s steps, then a 90 s stop at a light, then 5 s steps again.
        long[] ts = {0, 5_000, 10_000, 100_000, 105_000};
        assertEquals(15_000, TripRouteStats.movingMs(ts));
    }

    // 0.0005 deg of latitude is about 55.6 m; every 5 s that is 40 km/h.
    private static double[] steps(int n, double from) {
        double[] lat = new double[n];
        for (int i = 0; i < n; i++) lat[i] = from + i * 0.0005;
        return lat;
    }

    @Test public void averageSpeedComesFromThePointsOwnDistance() {
        long[] ts = {0, 5_000, 10_000, 15_000};
        assertEquals(40.0, TripRouteStats.avgMovingKmh(steps(4, 0), new double[4], ts), 0.1);
    }

    // The defect: a 30 km trip whose points cover 3 minutes showed 600 km/h
    // (odometer km over the points' moving time).
    @Test public void aGpsDropoutDoesNotInflateTheAverageSpeed() {
        int n = 37;                       // 3 minutes of points, 5 s apart
        double[] lat = new double[2 * n];
        long[] ts = new long[2 * n];
        for (int i = 0; i < n; i++) {
            lat[i] = i * 0.0005;          // before the dropout
            ts[i] = i * 5_000L;
            lat[n + i] = 0.25 + i * 0.0005;   // about 26 km further on, 30 min later
            ts[n + i] = 1_800_000L + i * 5_000L;
        }
        double kmh = TripRouteStats.avgMovingKmh(lat, new double[2 * n], ts);
        assertEquals(40.0, kmh, 0.1);
    }

    @Test public void aStopDoesNotLowerTheMovingAverage() {
        // 5 s steps, a 90 s stop without moving, then 5 s steps again.
        double[] lat = {0, 0.0005, 0.0010, 0.0010, 0.0015};
        long[] ts = {0, 5_000, 10_000, 100_000, 105_000};
        assertEquals(40.0, TripRouteStats.avgMovingKmh(lat, new double[5], ts), 0.1);
    }

    @Test public void noAverageSpeedWithoutPointsCloseInTime() {
        assertTrue(Double.isNaN(TripRouteStats.avgMovingKmh(new double[]{0, 0.1}, new double[2], new long[]{0, 600_000})));
        assertTrue(Double.isNaN(TripRouteStats.avgMovingKmh(new double[0], new double[0], new long[0])));
    }

    @Test public void missingHeightsAreFilled() {
        double n = Double.NaN;
        assertArrayEquals(new double[]{5, 5, 6, 6}, TripRouteStats.fillGaps(new double[]{n, 5, 6, n}), 1e-9);
        assertNull(TripRouteStats.fillGaps(new double[]{n, n}));
    }

    @Test public void indexAtKmPicksTheNearestPoint() {
        double[] km = {0, 1, 2, 3};
        assertEquals(0, TripRouteStats.indexAtKm(km, -1));
        assertEquals(1, TripRouteStats.indexAtKm(km, 1.4));
        assertEquals(2, TripRouteStats.indexAtKm(km, 1.6));
        assertEquals(3, TripRouteStats.indexAtKm(km, 9));
    }

    @Test public void durationsReadShort() {
        assertEquals("45 s", TripRouteStats.duration(45_000));
        assertEquals("12 min", TripRouteStats.duration(12 * 60_000));
        assertEquals("1 h 05 min", TripRouteStats.duration(65 * 60_000));
    }
}
