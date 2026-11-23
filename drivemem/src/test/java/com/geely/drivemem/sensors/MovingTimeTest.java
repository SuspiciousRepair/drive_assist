package com.geely.drivemem.sensors;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MovingTimeTest {

    private static final double NO = Double.NaN;

    @Test
    public void theGapBeforeAMovingSampleCounts() {
        long[] ts = {0, 30_000, 60_000, 90_000, 120_000};
        double[] speed = {0, 12, 40, 0, 8};
        // 0->30 s, 30->60 s and 90->120 s; the stop at 90 s does not.
        assertEquals(90_000, MovingTime.movingMs(ts, speed));
    }

    @Test
    public void aLongGapIsTheAppNotSamplingNotDriving() {
        long[] ts = {0, 30_000, 30_000 + MovingTime.MAX_GAP_MS + 1, 30_000 + MovingTime.MAX_GAP_MS + 30_001};
        double[] speed = {5, 5, 5, 5};
        assertEquals(60_000, MovingTime.movingMs(ts, speed));
    }

    @Test
    public void aParkedDayIsZeroNotUnknown() {
        assertEquals(0, MovingTime.movingMs(new long[]{0, 30_000, 60_000}, new double[]{0, 0, 0}));
    }

    @Test
    public void noSpeedAtAllIsNotKnown() {
        assertEquals(-1, MovingTime.movingMs(new long[]{0, 30_000}, new double[]{NO, NO}));
        assertEquals(-1, MovingTime.movingMs(new long[0], new double[0]));
    }

    @Test
    public void aSampleWithNoSpeedDoesNotCount() {
        long[] ts = {0, 30_000, 60_000};
        double[] speed = {10, NO, 10};
        assertEquals(30_000, MovingTime.movingMs(ts, speed));
    }

    @Test
    public void movingSpeedIsDistanceOverMovingTime() {
        // Real day, 2026-11-18: 25.4 km in 102 moving minutes. Distance over
        // the 195 minutes its trips stayed open read 7.8 km/h.
        assertEquals(14.9, MovingTime.kmh(25.4, 102), 0.05);
    }

    @Test
    public void underAMinuteOfMovingGivesNoSpeed() {
        assertEquals(0, MovingTime.kmh(0.4, 0.5), 1e-9);
        assertEquals(0, MovingTime.kmh(10, -1), 1e-9);
        assertEquals(0, MovingTime.kmh(0, 30), 1e-9);
    }
}
