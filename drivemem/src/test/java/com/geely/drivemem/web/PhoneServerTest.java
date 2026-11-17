package com.geely.drivemem.web;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PhoneServerTest {
    // The trip id comes from the address bar: digits only, or nothing.
    @Test public void tripIdIsDigitsOnly() {
        assertEquals(42L, PhoneServer.tripId("42"));
        assertEquals(-1L, PhoneServer.tripId(null));
        assertEquals(-1L, PhoneServer.tripId(""));
        assertEquals(-1L, PhoneServer.tripId("-3"));
        assertEquals(-1L, PhoneServer.tripId("1 OR 1=1"));
        assertEquals(-1L, PhoneServer.tripId("9999999999999999999"));
    }
}
