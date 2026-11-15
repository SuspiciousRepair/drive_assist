package com.geely.drivemem.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The home screen's cycling Regen button (issue #15). */
public class ModesTest {
    @Test public void nextRegenStepsLowMidHighThenWraps() {
        assertEquals(Modes.REGEN_MID, Modes.nextRegen(Modes.REGEN_LOW));
        assertEquals(Modes.REGEN_HIGH, Modes.nextRegen(Modes.REGEN_MID));
        assertEquals(Modes.REGEN_LOW, Modes.nextRegen(Modes.REGEN_HIGH));
    }

    @Test public void nextRegenFromUnknownStartsAtLow() {
        // The car read can fail (0) or return something unexpected.
        assertEquals(Modes.REGEN_LOW, Modes.nextRegen(0));
        assertEquals(Modes.REGEN_LOW, Modes.nextRegen(12345));
    }
}
