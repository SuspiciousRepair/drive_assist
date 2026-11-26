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

    // Prices typed on a phone keyboard, in either decimal style.
    @Test public void parseMoneyTakesCommaOrDotDecimals() {
        assertEquals(12.5, PhoneServer.parseMoney("12,50"), 1e-9);
        assertEquals(12.5, PhoneServer.parseMoney("12.50"), 1e-9);
        assertEquals(12.5, PhoneServer.parseMoney(" 12,5 "), 1e-9);
        assertEquals(1234.5, PhoneServer.parseMoney("1.234,50"), 1e-9);
        assertEquals(1234.5, PhoneServer.parseMoney("1,234.50"), 1e-9);
        assertEquals(8.0, PhoneServer.parseMoney("8"), 1e-9);
        assertEquals(null, PhoneServer.parseMoney(""));
        assertEquals(null, PhoneServer.parseMoney(null));
        assertEquals(-1.0, PhoneServer.parseMoney("R$ 5"), 1e-9);
        assertEquals(-1.0, PhoneServer.parseMoney("abc"), 1e-9);
        assertEquals(-1.0, PhoneServer.parseMoney("-3"), 1e-9);
    }
}
