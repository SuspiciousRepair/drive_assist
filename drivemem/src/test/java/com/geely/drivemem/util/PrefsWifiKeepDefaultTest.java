package com.geely.drivemem.util;

import org.junit.Test;
import static org.junit.Assert.*;

/** Issue #14: "Keep Wi-Fi on" defaults ON only for an install that already
 * existed (so an updated car keeps its network), OFF for a fresh install. */
public class PrefsWifiKeepDefaultTest {
    @Test public void freshInstallIsNotAnUpdate() {
        assertFalse(Prefs.wasUpdated(1_000L, 1_000L));
    }

    @Test public void replacedInstallIsAnUpdate() {
        assertTrue(Prefs.wasUpdated(1_000L, 5_000L));
    }

    @Test public void clockSkewBackwardsIsNotAnUpdate() {
        assertFalse(Prefs.wasUpdated(5_000L, 1_000L));
    }
}
