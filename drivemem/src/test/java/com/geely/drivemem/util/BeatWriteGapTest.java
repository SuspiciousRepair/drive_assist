package com.geely.drivemem.util;

import org.junit.Test;
import static org.junit.Assert.*;

/** Heartbeats are written at most every MIN_WRITE_GAP_MS. */
public class BeatWriteGapTest {
    private static final long GAP = Beat.MIN_WRITE_GAP_MS;

    @Test public void firstMarkWrites() {
        assertTrue(Beat.dueToWrite(0L, 1_000L, GAP));
    }
    @Test public void markInsideGapIsSkipped() {
        assertFalse(Beat.dueToWrite(1_000L, 1_000L + GAP - 1, GAP));
    }
    @Test public void markAfterGapWrites() {
        assertTrue(Beat.dueToWrite(1_000L, 1_000L + GAP, GAP));
    }
    @Test public void clockGoingBackwardsWrites() {
        assertTrue(Beat.dueToWrite(50_000L, 10_000L, GAP));
    }
    @Test public void gapIsWellUnderShortestWatchdogLimit() {
        assertTrue(GAP * 3 <= BootReceiver.STALE_OUTTEMP_MS);
    }
}
