package com.geely.drivemem;

import com.geely.drivemem.controls.RegenTap;
import com.geely.drivemem.util.Modes;

import org.junit.Test;
import static org.junit.Assert.*;

// The Regen button on the Turbo card and on the floating panel (issue #15).
public class RegenTapTest {
    @Test public void toggleGoesToHighThenBackToConfigLevel() {
        assertEquals(Modes.REGEN_HIGH, RegenTap.target(Modes.REGEN_LOW, false, Modes.REGEN_MID));
        assertEquals(Modes.REGEN_HIGH, RegenTap.target(Modes.REGEN_MID, false, Modes.REGEN_MID));
        assertEquals(Modes.REGEN_MID, RegenTap.target(Modes.REGEN_HIGH, false, Modes.REGEN_MID));
        assertEquals(Modes.REGEN_LOW, RegenTap.target(Modes.REGEN_HIGH, false, Modes.REGEN_LOW));
    }

    @Test public void cycleStepsThroughAllThree() {
        assertEquals(Modes.nextRegen(Modes.REGEN_LOW), RegenTap.target(Modes.REGEN_LOW, true, Modes.REGEN_MID));
        assertEquals(Modes.nextRegen(Modes.REGEN_HIGH), RegenTap.target(Modes.REGEN_HIGH, true, Modes.REGEN_MID));
    }

    @Test public void segmentsFillWithTheLevel() {
        assertEquals(1, RegenTap.segments(Modes.REGEN_LOW));
        assertEquals(2, RegenTap.segments(Modes.REGEN_MID));
        assertEquals(3, RegenTap.segments(Modes.REGEN_HIGH));
        assertEquals(0, RegenTap.segments(0));
        assertEquals(com.geely.drivemem.R.drawable.ic_regen_low, RegenTap.icon(Modes.REGEN_LOW));
        assertEquals(com.geely.drivemem.R.drawable.ic_regen_mid, RegenTap.icon(Modes.REGEN_MID));
        assertEquals(com.geely.drivemem.R.drawable.ic_regen_high, RegenTap.icon(Modes.REGEN_HIGH));
        assertEquals(com.geely.drivemem.R.drawable.ic_regen_unknown, RegenTap.icon(0));
    }

    @Test public void light() {
        assertFalse(RegenTap.lit(Modes.REGEN_LOW, false));
        assertTrue(RegenTap.lit(Modes.REGEN_HIGH, false));
        assertTrue(RegenTap.lit(Modes.REGEN_LOW, true));
    }
}
