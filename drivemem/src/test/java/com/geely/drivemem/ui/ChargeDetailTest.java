package com.geely.drivemem.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The charge page's arithmetic. */
public class ChargeDetailTest {
    private static final long H = 3_600_000L, MIN = 60_000L;
    private static final float N = Float.NaN;

    @Test public void idleRunsFromChargeEndToNextDrive() {
        assertEquals(100 * MIN, ChargeDetail.idleAtFullMs(100, 10 * H, 10 * H + 100 * MIN, 20 * H, false));
    }

    @Test public void idleRunsUntilNowWhenTheCarHasNotMoved() {
        assertEquals(2 * H, ChargeDetail.idleAtFullMs(100, 10 * H, -1, 12 * H, false));
    }

    @Test public void noIdleBelowFullOrWhileCharging() {
        assertEquals(-1, ChargeDetail.idleAtFullMs(80, 10 * H, 11 * H, 12 * H, false));
        assertEquals(-1, ChargeDetail.idleAtFullMs(100, 10 * H, -1, 12 * H, true));
    }

    @Test public void nearestSkipsMissingValues() {
        long[] ts = {0, MIN, 2 * MIN, 3 * MIN};
        float[] v = {1, N, N, 4};
        assertEquals(0, ChargeDetail.nearest(ts, v, MIN));          // 1 min back beats 2 min forward
        assertEquals(3, ChargeDetail.nearest(ts, v, 2 * MIN + 30_000));
    }

    @Test public void nearestIgnoresSamplesBeyondTheGap() {
        long[] ts = {0, 20 * MIN};
        float[] v = {1, 2};
        assertEquals(-1, ChargeDetail.nearest(ts, v, 10 * MIN));
        assertEquals(1, ChargeDetail.nearest(ts, v, 18 * MIN));
        assertEquals(-1, ChargeDetail.nearest(new long[0], new float[0], 0));
    }

    @Test public void rangeAndFirstLastSkipNaN() {
        float[] v = {N, 24, 31, 28, N};
        assertArrayEquals(new float[]{24, 31}, ChargeDetail.range(v), 0f);
        assertArrayEquals(new float[]{24, 28}, ChargeDetail.firstLast(v), 0f);
        assertNull(ChargeDetail.range(new float[]{N}));
    }

    @Test public void kwAxisKeepsTheTypeTopAndGrowsPastIt() {
        assertEquals(8f, ChargeDetail.kwAxisMax(6.6f, false), 0f);
        assertEquals(10f, ChargeDetail.kwAxisMax(9.1f, false), 0f);
        assertEquals(80f, ChargeDetail.kwAxisMax(58f, true), 0f);
        assertEquals(100f, ChargeDetail.kwAxisMax(92f, true), 0f);
        assertEquals(8f, ChargeDetail.kwAxisMax(N, false), 0f);
    }

    @Test public void tempAxisStepsAreWholeFives() {
        assertArrayEquals(new float[]{15, 35}, ChargeDetail.tempAxis(new float[]{24, 33}, new float[]{17, 22}), 0f);
        assertArrayEquals(new float[]{20, 40}, ChargeDetail.tempAxis(new float[]{N}, new float[]{21, 22}), 0f);
        assertArrayEquals(new float[]{5, 45}, ChargeDetail.tempAxis(new float[]{8, 41}, new float[0]), 0f);
        assertNull(ChargeDetail.tempAxis(new float[]{N}, new float[0]));
    }
}
