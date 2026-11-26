package com.geely.drivemem.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SecurityLockTest {

    @Test public void pinValidationAcceptsFourToSixDigitsOnly() {
        assertTrue(SecurityLock.isValidPin("0000"));
        assertTrue(SecurityLock.isValidPin("12345"));
        assertTrue(SecurityLock.isValidPin("987654"));
        assertFalse(SecurityLock.isValidPin(null));
        assertFalse(SecurityLock.isValidPin(""));
        assertFalse(SecurityLock.isValidPin("123"));
        assertFalse(SecurityLock.isValidPin("1234567"));
        assertFalse(SecurityLock.isValidPin("12a4"));
        assertFalse(SecurityLock.isValidPin(" 1234"));
    }

    @Test public void saltedHashVerifiesAndNeverReusesSalt() {
        String[] a = SecurityLock.hashPin("2468");
        String[] b = SecurityLock.hashPin("2468");
        assertNotEquals(a[0], b[0]);
        assertNotEquals(a[1], b[1]);
        assertTrue(SecurityLock.verifyHash("2468", a[0], a[1]));
        assertTrue(SecurityLock.verifyHash("2468", b[0], b[1]));
        assertFalse(SecurityLock.verifyHash("2469", a[0], a[1]));
        assertFalse(SecurityLock.verifyHash("2468", b[0], a[1]));
    }

    @Test public void corruptOrEmptyHashesAlwaysReject() {
        assertFalse(SecurityLock.verifyHash("1234", "", ""));
        assertFalse(SecurityLock.verifyHash("1234", "zz", "00"));
        assertFalse(SecurityLock.verifyHash("1234", "abc", "0011"));
        assertFalse(SecurityLock.verifyHash(null, "0011", "0011"));
    }

    @Test public void homeResetRadiusMatches100mBoundary() {
        double homeLat = -23.550520, homeLon = -46.633308;
        // ~40 m north (~0.00036 deg latitude)
        assertTrue(SecurityLock.withinHomeResetRadius(homeLat + 0.00036, homeLon, homeLat, homeLon));
        // ~220 m north (~0.002 deg latitude)
        assertFalse(SecurityLock.withinHomeResetRadius(homeLat + 0.0020, homeLon, homeLat, homeLon));
    }

    @Test public void securitySecretsAreNeverBackedUpOrPhoneRestorable() {
        String[] secretKeys = {
            Prefs.KEY_SECURITY_PIN_HASH,
            Prefs.KEY_SECURITY_PIN_SALT,
            Prefs.KEY_SECURITY_HOME_LAT,
            Prefs.KEY_SECURITY_HOME_LON,
            Prefs.KEY_SECURITY_LOCKOUT_UNTIL
        };
        for (String k : secretKeys) {
            assertFalse("not backed up: " + k, Prefs.isBackedUp(k));
            assertFalse("not phone restorable: " + k, Prefs.isPhoneRestorable(k));
        }
        String[] policyKeys = {
            Prefs.KEY_SECURITY_LOCK_VALET,
            Prefs.KEY_SECURITY_LOCK_SETTINGS,
            Prefs.KEY_SECURITY_BT_MAC,
            Prefs.KEY_SECURITY_BT_NAME,
            Prefs.KEY_SECURITY_BEACON_UUID,
            Prefs.KEY_SECURITY_BEACON_NAME,
            Prefs.KEY_SECURITY_TRUST_CARPLAY,
            Prefs.KEY_SECURITY_CARPLAY_SERIAL,
            Prefs.KEY_SECURITY_TRUST_WIFI,
            Prefs.KEY_SECURITY_WIFI_SSIDS,
            Prefs.KEY_SECURITY_AUTO_END_VALET
        };
        for (String k : policyKeys) {
            assertFalse("blocked from phone restore: " + k, Prefs.isPhoneRestorable(k));
        }
    }

    @Test public void trustedWifiNeedsTheRouterMacNotOnlyTheName() {
        java.util.List<SecurityLock.WifiEntry> trusted = SecurityLock.parseWifiEntries(
            "HomeNet\taa:bb:cc:00:11:22,AA:BB:CC:00:11:33\nPhoneHotspot\t12:34:56:78:9a:bc");
        assertEquals(2, trusted.size());
        // Name and router MAC match (Android quotes the SSID; case does not matter).
        assertEquals("HomeNet", SecurityLock.matchWifi("\"homenet\"", "AA:BB:CC:00:11:22", trusted));
        assertEquals("HomeNet", SecurityLock.matchWifi("HomeNet", "aa:bb:cc:00:11:33", trusted));
        // A hotspot named like the home network, but another router: rejected.
        assertEquals("", SecurityLock.matchWifi("HomeNet", "de:ad:be:ef:00:01", trusted));
        // The right MAC under another name: rejected.
        assertEquals("", SecurityLock.matchWifi("CafeGuest", "aa:bb:cc:00:11:22", trusted));
        // Android hides the MAC without location permission: never a match.
        assertEquals("", SecurityLock.matchWifi("HomeNet", "02:00:00:00:00:00", trusted));
        assertEquals("", SecurityLock.matchWifi("HomeNet", null, trusted));
        assertEquals("", SecurityLock.matchWifi("<unknown ssid>", "aa:bb:cc:00:11:22", trusted));
    }

    @Test public void oldNameOnlyWifiEntriesAreDroppedAndMergeKeepsEveryRouter() {
        // The old format stored names only: those lines must not trust anything.
        assertTrue(SecurityLock.parseWifiEntries("HomeNet\nPhoneHotspot").isEmpty());

        java.util.List<SecurityLock.WifiEntry> list = SecurityLock.parseWifiEntries("HomeNet\taa:bb:cc:00:11:22");
        list = SecurityLock.mergeWifiEntry(list, "homenet",
            java.util.Arrays.asList("AA:BB:CC:00:11:22", "aa:bb:cc:00:11:44", "bad"));
        assertEquals(1, list.size());
        assertEquals(java.util.Arrays.asList("aa:bb:cc:00:11:22", "aa:bb:cc:00:11:44"), list.get(0).bssids);

        String raw = SecurityLock.formatWifiEntries(list);
        assertEquals("aa:bb:cc:00:11:44",
            SecurityLock.parseWifiEntries(raw).get(0).bssids.get(1));
    }

    @Test public void parsesAppleIBeaconUuidAndRejectsNonBeaconData() {
        byte[] packet = new byte[] {
            0x02, 0x15,
            (byte) 0x55, (byte) 0x0e, (byte) 0x84, (byte) 0x00,
            (byte) 0xe2, (byte) 0x9b,
            (byte) 0x41, (byte) 0xd4,
            (byte) 0xa7, (byte) 0x16,
            (byte) 0x44, (byte) 0x66, (byte) 0x55, (byte) 0x44, (byte) 0x00, (byte) 0x00,
            0x00, 0x64, 0x00, 0x01, (byte) 0xc5
        };
        org.junit.Assert.assertEquals("550e8400-e29b-41d4-a716-446655440000",
            SecurityLock.parseIBeaconUuid(packet));
        org.junit.Assert.assertNull(SecurityLock.parseIBeaconUuid(new byte[]{0x02, 0x15, 0x01}));
        org.junit.Assert.assertNull(SecurityLock.parseIBeaconUuid(new byte[23]));
    }
}
