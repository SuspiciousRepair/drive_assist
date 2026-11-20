package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TileCacheTest {

    // A failed tile is tried again soon at first, so a gap made while the
    // car was joining a network fills in within seconds.
    @Test public void firstRetryIsSoon() {
        assertEquals(TileCache.FIRST_RETRY_MS, TileCache.retryDelayMs(1));
        assertTrue(TileCache.retryDelayMs(1) <= 10_000L);
    }

    // Then less often, never more often than before, and never past RETRY_MS.
    @Test public void retriesBackOffUpToTheCap() {
        long prev = 0;
        for (int n = 1; n <= 100; n++) {
            long d = TileCache.retryDelayMs(n);
            assertTrue("n=" + n, d >= prev);
            assertTrue("n=" + n, d <= TileCache.RETRY_MS);
            prev = d;
        }
        assertEquals(TileCache.RETRY_MS, TileCache.retryDelayMs(100));
        assertEquals(TileCache.RETRY_MS, TileCache.retryDelayMs(Integer.MAX_VALUE));
    }

    @Test public void zeroOrNegativeCountsAsFirst() {
        assertEquals(TileCache.FIRST_RETRY_MS, TileCache.retryDelayMs(0));
        assertEquals(TileCache.FIRST_RETRY_MS, TileCache.retryDelayMs(-3));
    }

    // A view with no redraw posted gets one (the charging map opened again
    // while its tile waited for a retry stayed blank, 2026-11-20).
    @Test public void viewWithNoRedrawGetsOne() {
        assertTrue(TileCache.needsWake(null, 1_000L, 6_000L));
    }

    // One redraw per view is enough when it comes first: several failed
    // tiles do not pile up redraws.
    @Test public void earlierRedrawCovers() {
        assertFalse(TileCache.needsWake(4_000L, 1_000L, 6_000L));
        assertFalse(TileCache.needsWake(6_000L, 1_000L, 6_000L));
    }

    // A redraw posted for later than the tile is due: post an earlier one.
    @Test public void laterRedrawDoesNotCover() {
        assertTrue(TileCache.needsWake(9_000L, 1_000L, 6_000L));
    }

    // A redraw already past (it ran) does not cover a new wait.
    @Test public void pastRedrawDoesNotCover() {
        assertTrue(TileCache.needsWake(500L, 1_000L, 6_000L));
    }
}
