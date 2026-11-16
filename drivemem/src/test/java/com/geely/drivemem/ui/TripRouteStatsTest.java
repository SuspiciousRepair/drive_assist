package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
