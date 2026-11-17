package com.geely.drivemem.web;

import com.geely.drivemem.util.Prefs;

import org.junit.Test;

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
}
