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

    @Test public void markAndLight() {
        assertEquals("●", RegenTap.mark(Modes.REGEN_LOW, false));
        assertFalse(RegenTap.lit(Modes.REGEN_LOW, false));
        assertTrue(RegenTap.lit(Modes.REGEN_HIGH, false));
        assertEquals("◦", RegenTap.mark(Modes.REGEN_LOW, true));
        assertEquals("◉", RegenTap.mark(Modes.REGEN_MID, true));
        assertEquals("●", RegenTap.mark(Modes.REGEN_HIGH, true));
        assertTrue(RegenTap.lit(Modes.REGEN_LOW, true));
    }
}
