package com.geely.drivemem;

import com.geely.drivemem.hvac.EffortTable;
import com.geely.drivemem.hvac.EffortTable.Column;
import com.geely.drivemem.hvac.EffortTable.Fit;
import com.geely.drivemem.hvac.EffortTable.State;

import org.junit.Test;
import static org.junit.Assert.*;

/** The comfort ruler's list of marks, the fit and the one-tap rule. */
public class EffortTableTest {

    private static final int FACE = EffortTable.DIR_FACE, GLASS = EffortTable.DIR_GLASS,
        FEET = EffortTable.DIR_FEET;

    private static State cool(boolean ac, boolean recirc, int dir, int fan, float sp) {
        return new State(true, ac, sp, fan, dir, recirc);
    }

    private static int settingsChanged(Column a, Column b) {
        int n = 0;
        if (a.machine != b.machine) n++;
        if (a.recirc != b.recirc) n++;
        if (a.direction != b.direction) n++;
        if (a.fan != b.fan) n++;
        if (a.machine && b.machine && a.setpointC != b.setpointC) n++;
        return n;
    }

    // ---- the list ----------------------------------------------------------

    @Test public void theListHasTenCoolAndFiveWarmMarks() {
        Column[] t = EffortTable.build();
        assertEquals(16, t.length);
        assertEquals(10, EffortTable.at(t, 10).level);
        assertEquals(-5, EffortTable.at(t, -5).level);
        assertFalse(EffortTable.at(t, 0).power);
    }

    @Test public void c1IsOutsideAirAndC2TurnsTheAcOnAtTheGlass() {
        Column c1 = EffortTable.column(1), c2 = EffortTable.column(2);
        assertFalse(c1.machine);
        assertEquals(1, c1.fan);
        assertTrue(c2.machine);
        assertEquals(1, c2.fan);
        assertEquals(GLASS, c2.direction);
        assertFalse(c2.recirc);
        assertEquals(24f, c2.setpointC, 0f);
    }

    @Test public void theMoveToTheFaceTakesTheFanOneStepDown() {
        Column c5 = EffortTable.column(5), c6 = EffortTable.column(6);
        assertEquals(GLASS, c5.direction);
        assertEquals(3, c5.fan);
        assertEquals(FACE, c6.direction);
        assertEquals(2, c6.fan);
    }

    @Test public void theEndsAreAllOut() {
        Column c10 = EffortTable.column(10), w5 = EffortTable.column(-5);
        assertEquals(8, c10.fan);
        assertEquals(EffortTable.TEMP_MIN, c10.setpointC, 0f);
        assertEquals(EffortTable.TEMP_MAX, w5.setpointC, 0f);
        assertEquals(EffortTable.DIR_FACE_FEET, w5.direction);
    }

    @Test public void oneTapChangesOneSettingButAtTheKnownSteps() {
        Column[] t = EffortTable.build();
        for (int k = 2; k <= EffortTable.COOL_MAX; k++) {
            int n = settingsChanged(EffortTable.at(t, k - 1), EffortTable.at(t, k));
            boolean two = k == 2 || k == 6 || k == 9 || k == 10;
            assertEquals("C" + (k - 1) + " -> C" + k, two ? 2 : 1, n);
        }
        for (int k = 2; k <= EffortTable.WARM_MAX; k++) {
            int n = settingsChanged(EffortTable.at(t, -(k - 1)), EffortTable.at(t, -k));
            assertEquals("W" + (k - 1) + " -> W" + k, k == 5 ? 3 : 1, n);
        }
    }

    @Test public void everySetpointIsClearOfTheMiddle() {
        Column[] t = EffortTable.build();
        for (Column c : t) {
            if (!c.machine) continue;
            assertEquals(c.toString(), c.cold(), c.setpointC < EffortTable.MIDDLE_C);
            assertTrue(c.toString(), Math.abs(c.setpointC - EffortTable.MIDDLE_C) >= 0.5f);
        }
    }

    // ---- one list for all weather ------------------------------------------

    @Test public void c4OnlyTurnsRecirculationOn() {
        Column c3 = EffortTable.column(3), c4 = EffortTable.column(4);
        assertFalse(c3.recirc);
        assertTrue(c4.recirc);
        assertEquals(1, settingsChanged(c3, c4));
        for (int k = 4; k <= EffortTable.COOL_MAX; k++) assertTrue("C" + k, EffortTable.column(k).recirc);
    }

    @Test public void theGentleWarmMarksBlowOnTheFeet() {
        for (int k = 1; k <= 4; k++) assertEquals(FEET, EffortTable.column(-k).direction);
        assertEquals(EffortTable.DIR_FACE_FEET, EffortTable.column(-5).direction);
    }

    @Test public void theWarmSideNeverRecirculates() {
        for (int k = 1; k <= EffortTable.WARM_MAX; k++) {
            assertFalse(EffortTable.column(-k).recirc);
            assertTrue(EffortTable.column(-k).machine);
        }
    }

    // ---- the fit -----------------------------------------------------------

    @Test public void everyMarkFitsItself() {
        Column[] t = EffortTable.build();
        for (Column c : t) {
            Fit f = EffortTable.fit(t, State.of(c));
            assertEquals(c.toString(), c.level, f.level);
            assertTrue(c.toString(), f.exact);
        }
    }

    @Test public void offIsZeroWhateverTheOtherSettingsAre() {
        Fit f = EffortTable.fit(EffortTable.build(), new State(false, true, 17f, 8, FACE, true));
        assertEquals(0, f.level);
        assertTrue(f.exact);
    }

    @Test public void aHandStateIsHollowOnTheNearestMark() {
        Column[] t = EffortTable.build();
        // C8 but the fan one step up by hand.
        Fit f = EffortTable.fit(t, cool(true, true, FACE, 5, 24f));
        assertFalse(f.exact);
        assertTrue(f.level >= 6 && f.level <= 8);
    }

    @Test public void aColdSetpointIsTheCoolSideEvenOnACoolEvening() {
        // Issue #6: 17 C outside, AC at 18 C is still cooling.
        Fit f = EffortTable.fit(EffortTable.build(), cool(true, false, FACE, 8, 18f));
        assertTrue(f.level > 0);
    }

    @Test public void aWarmSetpointIsTheWarmSide() {
        Column[] t = EffortTable.build();
        Fit f = EffortTable.fit(t, new State(true, true, 28f, 3, FEET, false));
        assertEquals(-4, f.level);
        assertTrue(f.exact);
    }

    // ---- the tap: on a mark ------------------------------------------------

    @Test public void fromOffTheFirstTapIsAlwaysTheFirstMark() {
        Column[] t = EffortTable.build();
        assertEquals(1, EffortTable.fit(t, EffortTable.step(t, State.OFF, +1)).level);
        assertEquals(-1, EffortTable.fit(t, EffortTable.step(t, State.OFF, -1)).level);
    }

    @Test public void tapsWalkTheWholeRulerOneMarkAtATime() {
        Column[] t = EffortTable.build();
        State s = State.of(EffortTable.at(t, -5));
        for (int want = -4; want <= 10; want++) {
            s = EffortTable.step(t, s, +1);
            Fit f = EffortTable.fit(t, s);
            assertEquals(want, f.level);
            assertTrue(f.exact);
        }
        for (int want = 9; want >= -5; want--) {
            s = EffortTable.step(t, s, -1);
            assertEquals(want, EffortTable.fit(t, s).level);
        }
    }

    @Test public void theEndsDoNotMove() {
        Column[] t = EffortTable.build();
        State top = State.of(EffortTable.at(t, 10)), bottom = State.of(EffortTable.at(t, -5));
        assertTrue(EffortTable.step(t, top, +1).same(top));
        assertTrue(EffortTable.step(t, bottom, -1).same(bottom));
    }

    @Test public void theFanNeverMovesMoreThanTwoPlacesInOneTap() {
        int[] fans = {1, 2, 3, 4, 6, 8};
        Column[] t = EffortTable.build();
        State s = State.of(EffortTable.at(t, 1));
        for (int i = 0; i < 12; i++) {
            State n = EffortTable.step(t, s, +1);
            int a = java.util.Arrays.binarySearch(fans, s.fan), b = java.util.Arrays.binarySearch(fans, n.fan);
            assertTrue(s.fan + " -> " + n.fan, Math.abs(a - b) <= 1);
            s = n;
        }
    }

    // ---- the tap: a hand on the car's own climate screen (issue #209) ------

    /** Fan 1 and the AC turned on by hand, air to the face. */
    private static final State HAND = new State(true, true, 24f, 1, FACE, false);

    @Test public void issue209CoolerDoesNotMakeTheFanJump() {
        Column[] t = EffortTable.build();
        assertFalse(EffortTable.fit(t, HAND).exact);
        State n = EffortTable.step(t, HAND, +1);
        assertEquals(1, n.fan);
        assertTrue(n.machine);
        // Only the air direction moved: this is now C2 exactly.
        Fit f = EffortTable.fit(t, n);
        assertEquals(2, f.level);
        assertTrue(f.exact);
    }

    @Test public void issue209WarmerDoesNotMakeTheFanJump() {
        Column[] t = EffortTable.build();
        State n = EffortTable.step(t, HAND, -1);
        assertEquals(1, n.fan);
        assertEquals(FACE, n.direction);
        // Only the AC went off: this is C1 exactly.
        Fit f = EffortTable.fit(t, n);
        assertEquals(1, f.level);
        assertTrue(f.exact);
    }

    @Test public void offAMarkOnlyTheSettingBehindMovesForward() {
        Column[] t = EffortTable.build();
        // C7 (recirculation, face, fan 3) with the fan turned down to 1 by hand.
        State hand = cool(true, true, FACE, 1, 24f);
        State n = EffortTable.step(t, hand, +1);
        assertEquals(2, n.fan);
        assertEquals(FACE, n.direction);
        assertTrue(n.recirc);
        assertEquals(24f, n.setpointC, 0f);
    }

    @Test public void offAMarkOnlyTheSettingAheadMovesBack() {
        Column[] t = EffortTable.build();
        // C3 (fresh air, glass, fan 2) with the setpoint at 17 by hand.
        State hand = cool(true, false, GLASS, 2, 17f);
        State n = EffortTable.step(t, hand, -1);
        assertEquals(2, n.fan);
        assertEquals(GLASS, n.direction);
        assertFalse(n.recirc);
        assertTrue(n.setpointC > 17f);
    }

    @Test public void aValueThatIsNotOnTheListIsPutOnItByTheFirstTap() {
        Column[] t = EffortTable.build();
        State hand = cool(true, true, FACE, 5, 24f);       // fan 5 is on no mark
        for (int dir : new int[] {+1, -1}) {
            State n = EffortTable.step(t, hand, dir);
            assertTrue("fan " + n.fan, n.fan != 5);
            assertTrue(EffortTable.fit(t, n).exact);
        }
    }

    @Test public void tapsFromAnyHandStateReachAMarkAndNeverCrossTheMiddle() {
        int[] fans = {1, 2, 3, 4, 5, 6, 7, 8};
        int[] dirs = {FACE, FEET, EffortTable.DIR_FACE_FEET, GLASS, EffortTable.DIR_GLASS_FEET};
        float[] temps = {17f, 20f, 22f, 23f, 24f};
        Column[] t = EffortTable.build();
        for (int fan : fans) for (int d : dirs) for (float sp : temps)
        for (int ac = 0; ac < 2; ac++) for (int rec = 0; rec < 2; rec++) for (int dir = -1; dir <= 1; dir += 2) {
            State s = cool(ac == 1, rec == 1, d, fan, sp);
            boolean cold0 = EffortTable.fit(t, s).level > 0;
            boolean exact = false;
            for (int i = 0; i < 20 && !exact; i++) {
                State n = EffortTable.step(t, s, dir);
                Fit f = EffortTable.fit(t, n);
                exact = f.exact;
                if (n.power && n.machine && cold0 && dir > 0) {
                    assertTrue("crossed to heating from " + label(s), n.setpointC < EffortTable.MIDDLE_C);
                }
                s = n;
            }
            assertTrue("never settled", exact);
        }
    }

    private static String label(State s) {
        return EffortTable.label(EffortTable.build(), s);
    }

    // ---- a touch on the bar -------------------------------------------------

    // The bar Style.effortScale() draws: 600 wide, 8 between two marks.
    private static final float BAR_W = 600f, BAR_GAP = 8f;

    @Test public void aTouchOnTheCoolBarCountsFromTheRightEnd() {
        assertEquals(10, EffortTable.markAt(1f, BAR_W, BAR_GAP, true));
        assertEquals(1, EffortTable.markAt(BAR_W - 1f, BAR_W, BAR_GAP, true));
        // each cool mark is 52.8 wide, 60.8 with its gap; the 4th from the left is C7
        assertEquals(7, EffortTable.markAt(3 * 60.8f + 20f, BAR_W, BAR_GAP, true));
    }

    @Test public void aTouchOnTheWarmBarCountsFromTheLeftEnd() {
        assertEquals(-1, EffortTable.markAt(1f, BAR_W, BAR_GAP, false));
        assertEquals(-5, EffortTable.markAt(BAR_W - 1f, BAR_W, BAR_GAP, false));
        assertEquals(-3, EffortTable.markAt(BAR_W / 2f, BAR_W, BAR_GAP, false));
    }

    @Test public void aFingerPastTheEndGetsTheEndMarkAndNeverOff() {
        assertEquals(10, EffortTable.markAt(-80f, BAR_W, BAR_GAP, true));
        assertEquals(1, EffortTable.markAt(BAR_W + 80f, BAR_W, BAR_GAP, true));
        assertEquals(-1, EffortTable.markAt(-80f, BAR_W, BAR_GAP, false));
        assertEquals(-5, EffortTable.markAt(BAR_W + 80f, BAR_W, BAR_GAP, false));
        assertEquals(10, EffortTable.markAt(5f, 0f, BAR_GAP, true));
    }

    @Test public void everyMarkOfTheBarIsAMarkOfTheList() {
        Column[] t = EffortTable.build();
        for (float x = 0; x < BAR_W; x += 7f) {
            int c = EffortTable.markAt(x, BAR_W, BAR_GAP, true);
            int w = EffortTable.markAt(x, BAR_W, BAR_GAP, false);
            assertEquals(c, EffortTable.fit(t, State.of(EffortTable.at(t, c))).level);
            assertEquals(w, EffortTable.fit(t, State.of(EffortTable.at(t, w))).level);
        }
    }

    // ---- the log -----------------------------------------------------------

    @Test public void theLogLabelKeepsItsShape() {
        Column[] t = EffortTable.build();
        assertEquals("C4@24/f2/rec/glass", EffortTable.label(t, State.of(EffortTable.at(t, 4))));
        assertEquals("C1@--/f1/face", EffortTable.label(t, State.of(EffortTable.at(t, 1))));
        assertEquals("0@off", EffortTable.label(t, State.OFF));
        assertTrue(EffortTable.label(t, cool(true, true, FACE, 5, 24f)).contains("~@24/f5/rec/face"));
    }
}
