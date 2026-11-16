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
     * The real case, 2026-11-16 14:09: the car woke running C3 (AC on, 20 C,
     * fan 4, recirc, face) at 34 C outside while the ruler still said 0. A
     * re-read fits it to C3, so "Cooler" now gives C4 — not C1 (fan 1, 24 C),
     * which is what the stale 0 produced.
     */
    @Test public void carThatWokeRunningC3FitsC3() {
        EffortTable.Column[] t = EffortTable.build(34f);
        int level = EffortTable.fit(t, 34f, true, true, 20f, 4, EffortTable.DIR_FACE, true);
        assertEquals(3, level);
        EffortTable.Column next = EffortTable.at(t, level + 1);
        assertTrue(next.fan > 4);
        assertTrue(next.setpointC < 20f);
    }
}
