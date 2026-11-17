package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExtraDarkTest {
    private static final long QUIET = ExtraDark.DIM_AFTER_MS;

    @Test public void dimsOnlyWhenOnAtNightDrivingAndQuiet() {
        assertTrue(ExtraDark.shouldDim(true, true, false, QUIET));
        assertFalse("setting off", ExtraDark.shouldDim(false, true, false, QUIET));
        assertFalse("daytime", ExtraDark.shouldDim(true, false, false, QUIET));
        assertFalse("parked", ExtraDark.shouldDim(true, true, true, QUIET));
        assertFalse("touched recently", ExtraDark.shouldDim(true, true, false, QUIET - 1));
    }

    @Test public void goesAsDarkAsThePanelAllows() {
        // the system clamps any override to the panel's floor (10 of 255)
        assertTrue(ExtraDark.BACKLIGHT_FLOOR > 0f);
        assertTrue(ExtraDark.BACKLIGHT_FLOOR <= 10f / 255f);
        // the shade darkens further but never hides the screen completely
        assertTrue(ExtraDark.SHADE_ALPHA >= 0.5f && ExtraDark.SHADE_ALPHA < 1f);
    }
}
