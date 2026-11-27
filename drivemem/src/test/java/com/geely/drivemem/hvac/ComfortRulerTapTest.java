package com.geely.drivemem.hvac;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * The first tap after a quiet spell re-reads the car before it moves; taps
 * inside a burst never do. See ComfortRuler.tap().
 */
public class ComfortRulerTapTest {

    private static final long NOW = 10_000_000L;

    @Test public void firstTapEverReReadsTheCar() {
        assertTrue(ComfortRuler.refitBeforeTap(NOW, 0, false, false));
    }

    @Test public void tapAfterAQuietSpellReReadsTheCar() {
        long last = NOW - ComfortRuler.QUIET_AFTER_TAP_MS;
        assertTrue(ComfortRuler.refitBeforeTap(NOW, last, false, false));
        assertTrue(ComfortRuler.refitBeforeTap(NOW, NOW - 20 * 60_000L, false, false));
    }

    @Test public void tapInsideABurstKeepsItsOwnLevel() {
        assertFalse(ComfortRuler.refitBeforeTap(NOW, NOW - 1_000, false, false));
        assertFalse(ComfortRuler.refitBeforeTap(NOW, NOW - ComfortRuler.QUIET_AFTER_TAP_MS + 1, false, false));
    }

    @Test public void neverReReadsWhileOurWriteIsPendingOrRunning() {
        long quiet = NOW - 20 * 60_000L;
        assertFalse(ComfortRuler.refitBeforeTap(NOW, quiet, true, false));
        assertFalse(ComfortRuler.refitBeforeTap(NOW, quiet, false, true));
    }

    /**
     * The real case, 2026-11-16 14:09: the car woke with the AC on at 34 C
     * outside while the ruler still said 0. A re-read finds the car's mark, so
     * "Cooler" gives the next mark and not C1, which is what the stale 0
     * produced.
     */
    @Test public void carThatWokeRunningFitsItsMark() {
        EffortTable.Column[] t = EffortTable.build();
        EffortTable.State woke = EffortTable.State.of(EffortTable.at(t, 7));
        assertEquals(7, EffortTable.fit(t, woke).level);
        EffortTable.State next = EffortTable.step(t, woke, +1);
        assertEquals(8, EffortTable.fit(t, next).level);
        assertEquals(4, next.fan);
    }
}
