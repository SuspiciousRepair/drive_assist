package com.geely.drivemem.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** #115: the overlay grant opens ADB when it is off, and must close only
 * that window — never ADB the owner keeps on. */
public class LocalAdbGrantTest {
    @Test public void closesTheWindowItOpened() {
        assertTrue(LocalAdb.shouldCloseAfterGrant(true, false, true));
    }

    @Test public void leavesAdbAloneWhenItWasAlreadyOn() {
        assertFalse(LocalAdb.shouldCloseAfterGrant(false, false, true));
    }

    @Test public void keepsAdbOnWhenOwnerWantsItAlwaysOn() {
        assertFalse(LocalAdb.shouldCloseAfterGrant(true, true, true));
    }

    @Test public void nothingToCloseWhenAlreadyOff() {
        assertFalse(LocalAdb.shouldCloseAfterGrant(true, false, false));
    }
}
