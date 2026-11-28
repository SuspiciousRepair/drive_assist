package com.geely.drivemem.state;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Which iPhone owns a wired CarPlay session. On this head unit the iPhone
 * is on the USB list for about 0.2 s, then moves behind the car's bridge
 * chip; its serial is caught at that moment. */
public class CarplayStateTest {
    private static final String SN = "00001111AAAA2222BBBB3333";

    @Test public void cleanSerialDropsTheJunkBytesAfterIt() {
        assertEquals(SN, CarplayState.cleanSerial(SN + "���\u0000"));
        assertEquals(SN, CarplayState.cleanSerial("  00001111aaaa2222bbbb3333 "));
        assertEquals("", CarplayState.cleanSerial(null));
    }

    @Test public void theSessionBelongsToTheIphonePluggedInJustBefore() {
        long plugged = 100_000L;
        assertEquals(SN, CarplayState.sessionOwner(SN, plugged, plugged + 2_000L));
        assertEquals(SN, CarplayState.sessionOwner(SN, plugged, plugged + CarplayState.CABLE_TO_SESSION_MS));
    }

    @Test public void aLateOrCablelessSessionHasNoOwner() {
        long plugged = 100_000L;
        // Wireless CarPlay long after an old plug-in.
        assertNull(CarplayState.sessionOwner(SN, plugged, plugged + 60_000L));
        // A session that started before the plug-in.
        assertNull(CarplayState.sessionOwner(SN, plugged, plugged - 1_000L));
        // Nothing plugged in since the app started.
        assertNull(CarplayState.sessionOwner(null, 0, plugged));
    }
}
