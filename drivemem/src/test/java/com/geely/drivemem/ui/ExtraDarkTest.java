package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExtraDarkTest {
    private static final long QUIET = ExtraDark.DIM_AFTER_MS;

    @Test public void dimsOnlyWhenOnAtNightInHoursDrivingAndQuiet() {
        assertTrue(ExtraDark.shouldDim(true, true, true, false, QUIET));
        assertFalse("setting off", ExtraDark.shouldDim(false, true, true, false, QUIET));
        assertFalse("daytime", ExtraDark.shouldDim(true, false, true, false, QUIET));
        assertFalse("before its hours (#130)", ExtraDark.shouldDim(true, true, false, false, QUIET));
        assertFalse("parked", ExtraDark.shouldDim(true, true, true, true, QUIET));
        assertFalse("touched recently", ExtraDark.shouldDim(true, true, true, false, QUIET - 1));
    }

    @Test public void hoursMayCrossMidnight() {
        int start = 21 * 60, end = 6 * 60;
        assertFalse("19:00, car dark mode already on: too early", ExtraDark.inHours(19 * 60, start, end));
        assertTrue("21:00 starts", ExtraDark.inHours(21 * 60, start, end));
        assertTrue("23:30", ExtraDark.inHours(23 * 60 + 30, start, end));
        assertTrue("02:00", ExtraDark.inHours(2 * 60, start, end));
        assertFalse("06:00 ends", ExtraDark.inHours(6 * 60, start, end));
    }

    @Test public void hoursLineHasMidnightInTheMiddle() {
        assertTrue(HoursBar.posOf(0) == HoursBar.DAY / 2);
        assertTrue(HoursBar.posOf(12 * 60) == 0);
        assertTrue(HoursBar.minOf(HoursBar.posOf(21 * 60)) == 21 * 60);
        assertTrue("21:00 to 06:00 is one piece of line",
            HoursBar.posOf(21 * 60) < HoursBar.posOf(6 * 60));
    }

    @Test public void hoursHandlesSnapAndNeverCross() {
        assertTrue(HoursBar.snap(44f) == 30);
        assertTrue(HoursBar.snap(-90f) == 0);
        assertTrue(HoursBar.snap(HoursBar.DAY + 90f) == HoursBar.DAY);
        int[] r = HoursBar.move(540, 1080, 0, 1200);
        assertTrue("start stops a step before end", r[0] == 1080 - HoursBar.STEP && r[1] == 1080);
        r = HoursBar.move(540, 1080, 1, 0);
        assertTrue("end stops a step after start", r[1] == 540 + HoursBar.STEP);
        assertTrue(HoursBar.hhmm(6 * 60 + 30).equals("06:30"));
    }

    @Test public void dashboardAndOverlayNeverBothRun() {
        assertTrue("dashboard only", ExtraDark.runs(true, false, false));
        assertFalse(ExtraDark.runs(true, false, true));
        assertTrue("every screen: the overlay", ExtraDark.runs(true, true, true));
        assertFalse("every screen: the Dashboard stays idle", ExtraDark.runs(true, true, false));
        assertFalse("switch off: neither", ExtraDark.runs(false, true, true));
    }

    @Test public void hoursInsideOneDay() {
        assertTrue(ExtraDark.inHours(1 * 60, 0, 5 * 60));
        assertFalse(ExtraDark.inHours(5 * 60, 0, 5 * 60));
        assertTrue("start equal to end: all night", ExtraDark.inHours(12 * 60, 9 * 60, 9 * 60));
    }

    @Test public void goesAsDarkAsThePanelAllows() {
        // the system clamps any override to the panel's floor (10 of 255)
        assertTrue(ExtraDark.BACKLIGHT_FLOOR > 0f);
        assertTrue(ExtraDark.BACKLIGHT_FLOOR <= 10f / 255f);
    }

    @Test public void darknessIsTheWholeRange() {
        assertTrue("0 %: lowest light only, no layer", ExtraDark.shadeAlpha(0) == 0f);
        assertTrue(ExtraDark.shadeAlpha(80) == 0.8f);
        assertTrue("100 %: black", ExtraDark.shadeAlpha(100) == 1f);
        assertTrue("default", ExtraDark.shadeAlpha(70) == ExtraDark.SHADE_ALPHA);
        assertTrue("bad value kept in range", ExtraDark.shadeAlpha(-20) == 0f);
        assertTrue("bad value kept in range", ExtraDark.shadeAlpha(250) == 1f);
    }

    // 2026-11-20: 0 % is backlight 2 (lowest lit), 100 % is 1 (panel off).
    @Test public void onlyFullDarknessTurnsThePanelOff() {
        assertTrue(ExtraDark.backlightLevel(0) == 2);
        assertTrue(ExtraDark.backlightLevel(90) == 2);
        assertTrue(ExtraDark.backlightLevel(100) == 1);
        assertTrue(ExtraDark.panelOff(100));
        assertTrue(!ExtraDark.panelOff(90));
    }

    // The heartbeat to modehelper comes well inside its 15 s timeout.
    @Test public void heartbeatBeatsTheHelperTimeout() {
        assertTrue(ExtraDark.BEAT_MS * 2 < 15_000L);
    }

    @Test public void darknessBarSnapsToSteps() {
        assertTrue(PercentBar.snap(0) == 0);
        assertTrue(PercentBar.snap(44) == 40);
        assertTrue(PercentBar.snap(46) == 50);
        assertTrue(PercentBar.snap(97) == 100);
        assertTrue(PercentBar.snap(130) == 100);
        assertTrue(PercentBar.snap(-8) == 0);
    }

    @Test public void shadeDarkensButLeavesTheNumbersReadable() {
        // #130: the backlight floor alone looked unchanged to a user
        assertTrue("darker than the floor alone", ExtraDark.SHADE_ALPHA >= 0.5f);
        assertTrue("not black: speed still readable", ExtraDark.SHADE_ALPHA < 1f);
    }

    // Review 2026-11-20: a dark screen lights again when its hours end or
    // the car leaves dark mode, not only on a touch.
    @Test public void darkScreenLightsWhenItsTimeIsOver() {
        assertTrue(ExtraDark.stayDark(true, true, true));
        assertTrue("hours over", !ExtraDark.stayDark(true, true, false));
        assertTrue("car left dark mode", !ExtraDark.stayDark(true, false, true));
        assertTrue("mode switched off", !ExtraDark.stayDark(false, true, true));
    }

    // Review 2026-11-20: the whole line (12:00 to 12:00, all day) shows as
    // picked when Config opens again, not as the default.
    @Test public void wholeDayShowsAsTheWholeLine() {
        int[] p = HoursBar.positions(12 * 60, 12 * 60);
        assertTrue(p[0] == 0 && p[1] == HoursBar.DAY);
        p = HoursBar.positions(21 * 60, 21 * 60);
        assertTrue("any start equal to end is all day", p[0] == 0 && p[1] == HoursBar.DAY);
        p = HoursBar.positions(22 * 60, 5 * 60);
        assertTrue(p[0] == HoursBar.posOf(22 * 60) && p[1] == HoursBar.posOf(5 * 60));
    }
}
