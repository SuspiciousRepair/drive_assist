package com.geely.drivemem;

import org.junit.Test;
import static org.junit.Assert.*;

/** The clip-recovery process must not start the app's car tracking a second time. */
public class DriveMemApplicationProcessTest {
    private static final String PKG = "com.geely.drivemem";

    @Test public void mainProcessStartsTracking() {
        assertTrue(DriveMemApplication.isMainProcess(PKG, PKG));
    }
    @Test public void clipRecoveryProcessDoesNot() {
        assertFalse(DriveMemApplication.isMainProcess(PKG + ":cliprecovery", PKG));
    }
    @Test public void unknownNameCountsAsMain() {
        assertTrue(DriveMemApplication.isMainProcess(null, PKG));
    }
}
