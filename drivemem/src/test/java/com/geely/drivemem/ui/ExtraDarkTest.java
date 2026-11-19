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

    @Test public void hoursStepWrapsAroundMidnight() {
        assertTrue(TelemetryLookSection.stepMin(23 * 60 + 30, 30) == 0);
        assertTrue(TelemetryLookSection.stepMin(0, -30) == 23 * 60 + 30);
        assertTrue(TelemetryLookSection.hhmm(21 * 60).equals("21:00"));
        assertTrue(TelemetryLookSection.hhmm(6 * 60 + 30).equals("06:30"));
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

    @Test public void shadeDarkensButLeavesTheNumbersReadable() {
        // #130: the backlight floor alone looked unchanged to a user
        assertTrue("darker than the floor alone", ExtraDark.SHADE_ALPHA >= 0.5f);
        assertTrue("not black: speed still readable", ExtraDark.SHADE_ALPHA < 1f);
    }
}
