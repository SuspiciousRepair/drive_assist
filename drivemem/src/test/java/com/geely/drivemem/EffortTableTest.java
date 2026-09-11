package com.geely.drivemem;

import com.geely.drivemem.hvac.EffortTable;
import com.geely.drivemem.hvac.EffortTable.Column;

import org.junit.Test;
import static org.junit.Assert.*;

public class EffortTableTest {

    // ---- freeColumns() -----------------------------------------------------

    @Test public void freeColumnsColdAtFreshLimitBoundaryIsZero() {
        assertEquals(0, EffortTable.freeColumns(28f, true));
    }

    @Test public void freeColumnsColdJustBelowLimitIsOne() {
        assertEquals(1, EffortTable.freeColumns(27.99f, true));
    }

    @Test public void freeColumnsColdMidRangeIsTwo() {
        assertEquals(2, EffortTable.freeColumns(24f, true));
    }

    @Test public void freeColumnsColdCapsAtThree() {
        assertEquals(3, EffortTable.freeColumns(16f, true));
        assertEquals(3, EffortTable.freeColumns(-40f, true));
    }

    @Test public void freeColumnsWarmAtCabinTargetBoundaryIsZero() {
        assertEquals(0, EffortTable.freeColumns(23f, false));
    }

    @Test public void freeColumnsWarmJustAboveTargetIsOne() {
        assertEquals(1, EffortTable.freeColumns(23.1f, false));
    }

    @Test public void freeColumnsWarmCapsAtThree() {
        assertEquals(3, EffortTable.freeColumns(29f, false));
        assertEquals(3, EffortTable.freeColumns(90f, false));
    }

    @Test public void freeColumnsWarmExtremeColdOutsideIsZero() {
        assertEquals(0, EffortTable.freeColumns(-40f, false));
    }

    // ---- column() : off column ---------------------------------------------

    @Test public void columnLevelZeroIsOff() {
        Column c = EffortTable.column(20f, 0);
        assertFalse(c.power);
        assertFalse(c.machine);
        assertTrue(Float.isNaN(c.setpointC));
        assertEquals(0, c.fan);
        assertEquals(EffortTable.DIR_GLASS, c.direction);
        assertFalse(c.recirc);
        assertFalse(c.cold());
        assertFalse(c.warm());
    }

    // ---- column() : cold side ------------------------------------------------

    @Test public void freeColdColumnGoesToFaceNoRecirc() {
        // outC=16 -> free=3, so level=2 is a free (fan-only) column.
        Column c = EffortTable.column(16f, 2);
        assertFalse(c.machine);
        assertEquals(EffortTable.DIR_FACE, c.direction);
        assertFalse(c.recirc);
        assertTrue(c.cold());
    }

    @Test public void firstMachineColdColumnGoesToGlassNoRecirc() {
        // outC=16 -> free=3, so level=4 (free+1) is the first machine column.
        Column c = EffortTable.column(16f, 4);
        assertTrue(c.machine);
        assertEquals(EffortTable.DIR_GLASS, c.direction);
        assertFalse(c.recirc);
        assertEquals(18f, c.setpointC, 0f);
    }

    @Test public void harderColdColumnOnHotDayGoesToFaceWithRecirc() {
        // outC=30 -> free=0, level=5 is far past the first machine column and
        // outside is hotter than the cabin target: recirc kicks in.
        Column c = EffortTable.column(30f, 5);
        assertTrue(c.machine);
        assertEquals(EffortTable.DIR_FACE, c.direction);
        assertTrue(c.recirc);
        assertEquals(17f, c.setpointC, 0f);
    }

    @Test public void coldRecircFalseWhenOutsideAirIsNotALiability() {
        // outC=16 -> free=3, level=5 is past the first machine column (free+1=4)
        // but outside is below CABIN_TARGET_C, so recirc stays off.
        Column c = EffortTable.column(16f, 5);
        assertTrue(c.machine);
        assertEquals(EffortTable.DIR_FACE, c.direction);
        assertFalse(c.recirc);
    }

    @Test public void coldFanTableMatchesC1ThroughC5() {
        int[] expected = {1, 2, 4, 6, 8};
        for (int level = 1; level <= 5; level++) {
            assertEquals("C" + level, expected[level - 1], EffortTable.column(26f, level).fan);
        }
    }

    @Test public void setpointClampGuaranteesDistinctWholeDegreesNearRail() {
        // outC=-10 -> free=3 (capped), so levels 4 and 5 are the only two
        // machine columns. Without the "at least n distinct setpoints" clamp,
        // the naive formula would start the block deep below TEMP_MIN.
        assertEquals(3, EffortTable.freeColumns(-10f, true));
        Column c4 = EffortTable.column(-10f, 4);
        Column c5 = EffortTable.column(-10f, 5);
        assertEquals(18f, c4.setpointC, 0f);
        assertEquals(17f, c5.setpointC, 0f);
        assertEquals(EffortTable.DIR_GLASS, c4.direction);
        assertEquals(EffortTable.DIR_FACE, c5.direction);
    }

    // ---- column() : warm side ------------------------------------------------

    @Test public void warmAllOutGoesFaceFeet() {
        Column c = EffortTable.column(26f, -5);
        assertEquals(EffortTable.DIR_FACE_FEET, c.direction);
        assertTrue(c.warm());
    }

    @Test public void warmFirstColumnGlassAndRecircOnColdDay() {
        // outC=16 (< CABIN_TARGET_C) -> free=0, so W1 is a machine column.
        Column c = EffortTable.column(16f, -1);
        assertTrue(c.machine);
        assertEquals(EffortTable.DIR_GLASS, c.direction);
        assertTrue(c.recirc);
        assertEquals(25f, c.setpointC, 0f);
    }

    @Test public void warmFirstColumnFeetNoRecircOnWarmDay() {
        // outC=30 (>= CABIN_TARGET_C) -> W1 is a free (ambient) column.
        Column c = EffortTable.column(30f, -1);
        assertFalse(c.machine);
        assertEquals(EffortTable.DIR_FEET, c.direction);
        assertFalse(c.recirc);
    }

    @Test public void warmMidColumnsGlassFeetOnColdDay() {
        // outC=16 (< CABIN_TARGET_C) -> Defogging physics dictates Glass + Feet
        // for intermediate heating levels W2..W4 to prevent windshield condensation.
        for (int level = -2; level >= -4; level--) {
            Column c = EffortTable.column(16f, level);
            assertEquals("W" + (-level), EffortTable.DIR_GLASS_FEET, c.direction);
        }
    }

    @Test public void warmMidColumnsFeetOnWarmDay() {
        // outC=26 (>= CABIN_TARGET_C) -> Outside air is warm, no glass condensation risk.
        for (int level = -2; level >= -4; level--) {
            Column c = EffortTable.column(26f, level);
            assertEquals("W" + (-level), EffortTable.DIR_FEET, c.direction);
        }
    }

    @Test public void warmMidColumnsNeverRecirculate() {
        for (int level = -2; level >= -4; level--) {
            assertFalse("W" + (-level), EffortTable.column(16f, level).recirc);
            assertFalse("W" + (-level), EffortTable.column(30f, level).recirc);
        }
    }

    @Test public void warmFanTableMatchesW1ThroughW5() {
        int[] expected = {1, 2, 4, 6, 8};
        for (int level = 1; level <= 5; level++) {
            assertEquals("W" + level, expected[level - 1], EffortTable.column(26f, -level).fan);
        }
    }

    // ---- build() / at() ------------------------------------------------------

    @Test public void buildProducesElevenColumnsColdestFirst() {
        Column[] t = EffortTable.build(26f);
        assertEquals(11, t.length);
        assertEquals(5, t[0].level);
        assertEquals(0, t[5].level);
        assertEquals(-5, t[10].level);
    }

    @Test public void atIndexesBothDirections() {
        Column[] t = EffortTable.build(26f);
        assertEquals(3, EffortTable.at(t, 3).level);
        assertEquals(-3, EffortTable.at(t, -3).level);
        assertEquals(0, EffortTable.at(t, 0).level);
    }

    // ---- fit() -----------------------------------------------------------

    @Test public void fitPowerOffIsAlwaysZero() {
        Column[] t = EffortTable.build(26f);
        assertEquals(0, EffortTable.fit(t, 26f, false, true, 17f, 8,
            EffortTable.DIR_FACE, true));
    }

    @Test public void fitExactMatchToTopColdColumn() {
        Column[] t = EffortTable.build(26f);
        Column top = EffortTable.at(t, 5); // setpoint 17, fan 8, face, recirc on
        int level = EffortTable.fit(t, 26f, true, top.machine, top.setpointC, top.fan,
            top.direction, top.recirc);
        assertEquals(5, level);
    }

    @Test public void fitExactMatchToTopWarmColumn() {
        Column[] t = EffortTable.build(26f);
        Column top = EffortTable.at(t, -5); // setpoint 32, fan 8, face+feet
        int level = EffortTable.fit(t, 26f, true, top.machine, top.setpointC, top.fan,
            top.direction, top.recirc);
        assertEquals(-5, level);
    }

    @Test public void fitOffCarCappedAtFreeColumnNotHigher() {
        // outC=20 -> free=3. A car reporting the top column's fan/dir/recirc but
        // with the machine off must still be capped at the free column (3),
        // never read as a near-all-out machine level.
        Column[] t = EffortTable.build(20f);
        Column top = EffortTable.at(t, 5);
        int level = EffortTable.fit(t, 20f, true, false, Float.NaN, top.fan,
            top.direction, top.recirc);
        assertEquals(3, level);
    }

    @Test public void fitMachineOffAtZeroFreeColumnsCapsAtZero() {
        // outC=35 -> free=0, so every cold column requires the machine. A car
        // whose fan/direction/recirc happen to match the all-out column but
        // whose machine is off must fit to 0, not to a near-all-out level.
        Column[] t = EffortTable.build(35f);
        Column top = EffortTable.at(t, 5);
        int level = EffortTable.fit(t, 35f, true, false, Float.NaN, top.fan,
            top.direction, top.recirc);
        assertEquals(0, level);
    }

    @Test public void fitMachineOnNeverReadsAsFreeColumn() {
        // outC=20 -> free=3. A machine-on car whose other levers land it at a
        // raw level inside the free band must be bumped up to free+1.
        Column[] t = EffortTable.build(20f);
        int level = EffortTable.fit(t, 20f, true, true, 17f, 2,
            EffortTable.DIR_FACE, false);
        assertEquals(4, level);
    }

    @Test public void fitFanOnlyMismatchCostsOnePress() {
        Column[] t = EffortTable.build(26f);
        // Matches the C5 column (setpoint 17, face, recirc on) except fan is
        // one step short (6, C4's fan, instead of 8).
        int level = EffortTable.fit(t, 26f, true, true, 17f, 6,
            EffortTable.DIR_FACE, true);
        assertEquals(4, level);
    }

    @Test public void fitUnsupportedDirectionCostsOnePress() {
        Column[] t = EffortTable.build(26f);
        int level = EffortTable.fit(t, 26f, true, true, 17f, 8, 999, true);
        assertEquals(4, level);
    }

    @Test public void fitRecircMismatchCostsOnlyOnePress() {
        Column[] t = EffortTable.build(26f);
        // Recirc spans C3..C5 at this outC, but a mismatch is still one press.
        int level = EffortTable.fit(t, 26f, true, true, 17f, 8,
            EffortTable.DIR_FACE, false);
        assertEquals(4, level);
    }

    @Test public void fitTakesMaxNotSumAcrossLevers() {
        Column[] t = EffortTable.build(26f);
        // Fan one step off AND an unsupported direction at once: still one
        // press worst-case, not two.
        int level = EffortTable.fit(t, 26f, true, true, 17f, 6, 999, true);
        assertEquals(4, level);
    }

    @Test public void fitNaNSetpointDoesNotCrashWhenMachineOn() {
        Column[] t = EffortTable.build(26f);
        int level = EffortTable.fit(t, 26f, true, true, Float.NaN, 8,
            EffortTable.DIR_FACE, true);
        assertEquals(5, level);
    }

    @Test public void fitGlassFeetInfersWarmSideWhenMachineOff() {
        // When machine is off and direction is glass+feet (6), sideIsCold must return false (warm side)
        Column[] t = EffortTable.build(16f);
        int level = EffortTable.fit(t, 16f, true, false, Float.NaN, 2, EffortTable.DIR_GLASS_FEET, false);
        assertTrue("Expected warm side level <= 0, got: " + level, level <= 0);
    }

    @Test public void fitExactMatchToMidWarmColumnWithGlassFeet() {
        // outC=16 -> W3 has fan=4, machine=true, setpoint=27, dir=DIR_GLASS_FEET, recirc=false
        Column[] t = EffortTable.build(16f);
        Column w3 = EffortTable.at(t, -3);
        assertEquals(EffortTable.DIR_GLASS_FEET, w3.direction);
        int level = EffortTable.fit(t, 16f, true, w3.machine, w3.setpointC, w3.fan,
            w3.direction, w3.recirc);
        assertEquals(-3, level);
    }

    // ---- name() / dirName() ------------------------------------------------

    @Test public void nameFormatsLevels() {
        assertEquals("0", EffortTable.name(0));
        assertEquals("C3", EffortTable.name(3));
        assertEquals("W2", EffortTable.name(-2));
    }

    @Test public void dirNameFormatsKnownAndUnknownValues() {
        assertEquals("face", EffortTable.dirName(EffortTable.DIR_FACE));
        assertEquals("feet", EffortTable.dirName(EffortTable.DIR_FEET));
        assertEquals("face+feet", EffortTable.dirName(EffortTable.DIR_FACE_FEET));
        assertEquals("glass", EffortTable.dirName(EffortTable.DIR_GLASS));
        assertEquals("glass+feet", EffortTable.dirName(EffortTable.DIR_GLASS_FEET));
        assertEquals("glass+feet", EffortTable.dirName(EffortTable.DIR_DEFROST_FLOOR));
        assertEquals("dir999", EffortTable.dirName(999));
    }
}
