package com.geely.drivemem.web;

import com.geely.drivemem.util.Prefs;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The settings a phone can type. The phone token goes over plain HTTP, so
 * this form holds no ADB, phone-access or command switch. The update
 * address is here by the owner's choice. */
public class PhonePagesTest {

    @Test public void theUpdateAddressIsEditableFromAPhone() {
        boolean found = false;
        for (PhonePages.Field f : PhonePages.FIELDS) found |= "update_url".equals(f.key);
        assertTrue(found);
    }

    @Test public void everyEditableFieldIsOneAPhoneRestoreMaySet() {
        assertTrue(PhonePages.FIELDS.length > 0);
        for (PhonePages.Field f : PhonePages.FIELDS) assertTrue(f.key, Prefs.isPhoneRestorable(f.key));
    }

    @Test public void versionBlockNamesBothApps() {
        assertEquals("Drive Assist 20261126-0922-7c526d54 (29928262)\nModeHelper 20261125-1752-8e1a964e (29927332)",
            PhonePages.versionText("20261126-0922-7c526d54", 29928262, "20261125-1752-8e1a964e", 29927332, "x"));
    }

    @Test public void versionBlockSaysWhenModeHelperIsMissing() {
        assertEquals("Drive Assist v1 (5)\nModeHelper: not installed",
            PhonePages.versionText("v1", 5, null, -1, "ModeHelper: not installed"));
    }
}
