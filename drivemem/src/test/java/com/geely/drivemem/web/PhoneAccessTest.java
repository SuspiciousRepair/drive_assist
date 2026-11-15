package com.geely.drivemem.web;

import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class PhoneAccessTest {

    @Test public void whenItRuns() {
        // The QR code on screen opens it in every mode.
        for (String m : new String[]{PhoneAccess.OFF, PhoneAccess.AUTO, PhoneAccess.ON, "junk"}) {
            assertTrue(m, PhoneAccess.shouldRun(m, true, false));
        }
        assertFalse(PhoneAccess.shouldRun(PhoneAccess.OFF, false, true));
        assertFalse(PhoneAccess.shouldRun(PhoneAccess.AUTO, false, false));
        assertTrue(PhoneAccess.shouldRun(PhoneAccess.AUTO, false, true));
        assertTrue(PhoneAccess.shouldRun(PhoneAccess.ON, false, false));
        assertFalse("an unknown mode is Off", PhoneAccess.shouldRun("junk", false, true));
    }

    @Test public void tokens() {
        String a = PhoneAccess.newToken(), b = PhoneAccess.newToken();
        assertEquals(43, a.length());                  // 32 bytes, base64url, no padding
        assertTrue(a.matches("[A-Za-z0-9_-]+"));       // safe in a URL and a cookie
        assertNotEquals(a, b);
        assertTrue(PhoneAccess.tokenMatches(a, a));
        assertFalse(PhoneAccess.tokenMatches(b, a));
        assertFalse(PhoneAccess.tokenMatches(a.substring(1), a));
        assertFalse(PhoneAccess.tokenMatches(null, a));
        assertFalse(PhoneAccess.tokenMatches("", ""));
    }

    @Test public void onlyPrivateAddresses() throws Exception {
        for (String ok : new String[]{"192.168.50.20", "10.1.2.3", "172.16.5.4", "127.0.0.1", "169.254.1.1", // pii: allow (example private addresses)
                "fe80::1", "fd12:3456::1"}) {
            assertTrue(ok, PhoneAccess.isPrivate(InetAddress.getByName(ok)));
        }
        for (String no : new String[]{"8.8.8.8", "100.64.0.1", "2001:4860::8888"}) {
            assertFalse(no, PhoneAccess.isPrivate(InetAddress.getByName(no)));
        }
        assertFalse(PhoneAccess.isPrivate(null));
    }

    @Test public void qrUrl() {
        assertEquals("http://192.168.50.20:8765/pair?t=abc", PhoneAccess.url("192.168.50.20", "abc")); // pii: allow (example address)
    }
}
