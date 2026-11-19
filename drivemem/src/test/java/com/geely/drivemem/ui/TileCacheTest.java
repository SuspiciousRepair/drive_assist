package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
}
