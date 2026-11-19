package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ValetDetailTest {
    private static final long M = 60_000L;

    @Test public void movingTimeIsClippedToTheInterval() {
        // Valet 0..100 min; moves 10..13, 50..52, and one running past the end.
        long[] s = {10 * M, 50 * M, 98 * M}, e = {13 * M, 52 * M, 105 * M};
        assertEquals(7 * M, ValetDetail.movingMs(s, e, 0, 100 * M));
    }

    @Test public void overlappingMovesCountOnce() {
        long[] s = {10 * M, 12 * M}, e = {15 * M, 14 * M};
        assertEquals(5 * M, ValetDetail.movingMs(s, e, 0, 100 * M));
    }

    @Test public void clipsOverlappingTheIntervalAreKept() {
        long vs = 100 * M, ve = 200 * M;
        assertTrue(ValetDetail.clipInWindow(150 * M, 60, vs, ve));
        assertTrue(ValetDetail.clipInWindow(99 * M + 30_000, 60, vs, ve));   // started just before
        assertFalse(ValetDetail.clipInWindow(90 * M, 60, vs, ve));
        assertFalse(ValetDetail.clipInWindow(201 * M, 60, vs, ve));
        assertTrue(ValetDetail.clipInWindow(99 * M + 30_000, -1, vs, ve));   // length unknown
    }

    // A 1 minute move in a 10 hour night still gets a block you can see.
    @Test public void shortMovesStayVisible() {
        float[] b = ValetDetail.blocks(0, 600 * M, new long[]{300 * M}, new long[]{301 * M}, 1000, 6);
        assertEquals(2, b.length);
        assertEquals(6, b[1] - b[0], 1e-3);
        assertEquals(500.8f, (b[0] + b[1]) / 2, 0.5f);
    }

    @Test public void blocksStayInsideTheBar() {
        float[] b = ValetDetail.blocks(0, 100 * M, new long[]{0, 99 * M}, new long[]{0, 100 * M}, 1000, 6);
        assertEquals(0, b[0], 1e-3);
        assertEquals(1000, b[3], 1e-3);
        assertTrue(b[2] >= 0 && b[1] <= 1000);
    }

    @Test public void topSpeedIsRedAbove20() {
        assertFalse(ValetDetail.fast(20));
        assertTrue(ValetDetail.fast(40.7));
    }
}
