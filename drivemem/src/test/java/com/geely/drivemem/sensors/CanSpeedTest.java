package com.geely.drivemem.sensors;

import org.junit.Test;

import static org.junit.Assert.*;

/** CanSpeed: the raw count is 1/64 m/s, not km/h as roadcast's catalog says. */
public class CanSpeedTest {

    @Test public void rawIsSixtyFourthsOfAMetrePerSecond() {
        CanSpeed s = new CanSpeed();
        assertTrue(s.onSignal(CanSpeed.SPEED, 936, true));      // top of the 2026-11-14 drive
        assertEquals(52.65f, s.kmh(), 1e-3);
        s.onSignal(CanSpeed.SPEED, 64, true);                    // 1 m/s
        assertEquals(3.6f, s.kmh(), 1e-4);
    }

    @Test public void unknownOrInvalidIsNull() {
        CanSpeed s = new CanSpeed();
        assertNull(s.kmh());
        s.onSignal(CanSpeed.SPEED, 500, true);
        s.onSignal(CanSpeed.INVALID, 1, true);
        assertNull(s.kmh());
        s.onSignal(CanSpeed.INVALID, 0, true);
        assertNotNull(s.kmh());
        s.onSignal(CanSpeed.SPEED, 500, false);                  // not observed yet
        assertNull(s.kmh());
    }

    @Test public void otherSignalsAreNotItsBusiness() {
        assertFalse(new CanSpeed().onSignal("ESC_BrakePedalSwitchStatus", 1, true));
    }

    @Test public void resetForgets() {
        CanSpeed s = new CanSpeed();
        s.onSignal(CanSpeed.SPEED, 500, true);
        s.reset();
        assertNull(s.kmh());
    }
}
