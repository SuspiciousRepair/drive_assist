package com.geely.drivemem;

import com.geely.drivemem.car.CarActor;
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

    // Config > Drive lets the owner pick High as the level. "Back to
    // Config's level" from High was then High again: no way out.
    @Test public void toggleLeavesHighWhenConfigLevelIsHigh() {
        assertEquals(Modes.REGEN_MID, RegenTap.target(Modes.REGEN_HIGH, false, Modes.REGEN_HIGH));
        assertEquals(Modes.REGEN_HIGH, RegenTap.target(Modes.REGEN_MID, false, Modes.REGEN_HIGH));
        assertEquals(Modes.REGEN_HIGH, RegenTap.target(Modes.REGEN_LOW, false, Modes.REGEN_HIGH));
    }

    @Test public void levelComesFromAGoodHubReadingOnly() {
        assertEquals(Modes.REGEN_LOW, RegenTap.level(CarActor.Reading.ok(Modes.REGEN_LOW)));
        assertEquals(Modes.REGEN_HIGH, RegenTap.level(CarActor.Reading.ok(Modes.REGEN_HIGH)));
        assertEquals(0, RegenTap.level(null));
        assertEquals(0, RegenTap.level(CarActor.Reading.NOT_AVAILABLE));
        assertEquals(0, RegenTap.level(CarActor.Reading.error("watch failed")));
        assertEquals(0, RegenTap.level(CarActor.Reading.ok("ERR")));
        assertEquals(0, RegenTap.level(CarActor.Reading.ok(12345)));
    }

    // tap() writes only for a known level: no reading is not "level 0".
    @Test public void onlyRealLevelsAreKnown() {
        assertTrue(RegenTap.known(Modes.REGEN_LOW));
        assertTrue(RegenTap.known(Modes.REGEN_MID));
        assertTrue(RegenTap.known(Modes.REGEN_HIGH));
        assertFalse(RegenTap.known(0));
        assertFalse(RegenTap.known(RegenTap.level(CarActor.Reading.NOT_AVAILABLE)));
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

    @Test public void fallbackPrefersActiveBoostBaselineOverSavedFallback() {
        assertEquals(Modes.REGEN_LOW, RegenTap.fallbackLevel(Modes.REGEN_LOW, Modes.REGEN_MID));
        assertEquals(Modes.REGEN_MID, RegenTap.fallbackLevel(0, Modes.REGEN_MID));
        assertEquals(Modes.REGEN_LOW, RegenTap.fallbackLevel(12345, Modes.REGEN_LOW));
    }

    @Test public void light() {
        assertFalse(RegenTap.lit(Modes.REGEN_LOW, false));
        assertTrue(RegenTap.lit(Modes.REGEN_HIGH, false));
        assertTrue(RegenTap.lit(Modes.REGEN_LOW, true));
    }
}
