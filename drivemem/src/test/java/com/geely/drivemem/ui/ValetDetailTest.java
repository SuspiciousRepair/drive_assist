package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

    @Test public void attentionLevelDistinguishesCleanCautionAndAlert() {
        // Clean: <= 20 km/h, good score, zero events
        assertEquals(0, ValetDetail.attentionLevel(12.0, 94, 0, 0, 0, 0));
        assertEquals(0, ValetDetail.attentionLevel(0.0, null, null, null, null, null));
        // Caution (amber): any harsh event or driver score < 80
        assertEquals(1, ValetDetail.attentionLevel(15.0, 88, 1, 0, 0, 0));
        assertEquals(1, ValetDetail.attentionLevel(15.0, 74, 0, 0, 0, 0));
        // Alert (red): speed > 20 km/h or driver score < 60
        assertEquals(2, ValetDetail.attentionLevel(28.5, 92, 0, 0, 0, 0));
        assertEquals(2, ValetDetail.attentionLevel(14.0, 52, 0, 2, 0, 0));
    }

    @Test public void findClipForTimeSeeksThreeSecondsBeforeHit() {
        long[] starts = {100 * M, 102 * M};
        int[] secs = {60, 60};
        // 20 s into clip 0 -> seek to 17 s
        int[] hit = ValetDetail.findClipForTime(100 * M + 20_000L, starts, secs);
        assertNotNull(hit);
        assertEquals(0, hit[0]);
        assertEquals(17_000, hit[1]);
        // 1 s into clip 1 -> clamped to 0 ms
        int[] early = ValetDetail.findClipForTime(102 * M + 1_000L, starts, secs);
        assertNotNull(early);
        assertEquals(1, early[0]);
        assertEquals(0, early[1]);
        // Gap between clips -> null
        assertNull(ValetDetail.findClipForTime(101 * M + 30_000L, starts, secs));
    }
}
