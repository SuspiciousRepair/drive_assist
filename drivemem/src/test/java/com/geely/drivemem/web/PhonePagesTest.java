package com.geely.drivemem.web;

import com.geely.drivemem.util.Prefs;

import org.junit.Test;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** The settings a phone can type. The phone token goes over plain HTTP, so
 * this form must not hold a setting that gives control of the car. */
public class PhonePagesTest {

    @Test public void theUpdateAddressIsNotEditableFromAPhone() {
        for (PhonePages.Field f : PhonePages.FIELDS) assertNotEquals("update_url", f.key);
    }

    @Test public void everyEditableFieldIsOneAPhoneRestoreMaySet() {
        assertTrue(PhonePages.FIELDS.length > 0);
        for (PhonePages.Field f : PhonePages.FIELDS) assertTrue(f.key, Prefs.isPhoneRestorable(f.key));
    }
}
