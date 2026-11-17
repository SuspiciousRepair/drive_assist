package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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

    @Test public void dimLevelIsAQuarterAboveTheFloor() {
        assertEquals(20, ExtraDark.dimLevel(82));
        assertEquals(63, ExtraDark.dimLevel(255));
        assertEquals("never under the panel floor", ExtraDark.MIN_LEVEL, ExtraDark.dimLevel(30));
    }

    @Test public void neverBrighterThanTheCar() {
        assertEquals(10, ExtraDark.dimLevel(10));
        assertEquals(5, ExtraDark.dimLevel(5));
        for (int car = 0; car <= 255; car++) {
            assertTrue("level " + car, ExtraDark.dimLevel(car) <= car);
        }
    }
}
