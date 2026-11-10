package com.geely.drivemem.util;

import org.junit.Test;
import static org.junit.Assert.*;

/** Issues #12/#16: "Restore at start" defaults ON only where the old
 * always-enforce behaviour was already in use (an updated install with a
 * saved value), so an update changes nothing for the owner and a new user
 * starts with the app leaving their car alone. */
public class PrefsRestoreDefaultTest {
    @Test public void updatedInstallWithSavedValueKeepsRestoring() {
        assertTrue(Prefs.defaultRestore(true, true));
    }
    @Test public void updatedInstallWithNothingSavedIsOff() {
        assertFalse(Prefs.defaultRestore(true, false));
    }
    @Test public void freshInstallIsOffEvenWithAValue() {
        assertFalse(Prefs.defaultRestore(false, true));
    }
    @Test public void eachSwitchGuardsItsOwnValue() {
        assertEquals("drive", Prefs.restoreValueKey(Prefs.RESTORE_DRIVE));
        assertEquals("regen", Prefs.restoreValueKey(Prefs.RESTORE_REGEN));
        assertEquals("aeb_on", Prefs.restoreValueKey(Prefs.RESTORE_AEB));
        assertEquals("avas_on", Prefs.restoreValueKey(Prefs.RESTORE_AVAS));
    }
}
