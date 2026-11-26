package com.geely.drivemem.state;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ValetAutoEndTest {

    @Test public void dropOffWithPhonePresentNeverAutoEnds() {
        ValetAutoEnd ae = new ValetAutoEnd();
        long t = 1_000_000L;
        ae.reset(t, true);
        // Owner opens door and lingers for 2 minutes handing car to valet -> still disarmed
        ae.onDoorOpened(t + 5_000L);
        assertFalse(ae.onTick(t + 65_000L, true, 100.0, 0.0));
        assertFalse(ae.onTick(t + 125_000L, true, 100.5, 20.0));
        assertFalse(ae.isArmed());
    }

    @Test public void armsAfterPhoneAbsentThenEndsOnDoorPlusOneMinute() {
        ValetAutoEnd ae = new ValetAutoEnd();
        long t = 1_000_000L;
        ae.reset(t, true);
        // Phone disconnects and stays away 20 s -> armed
        assertFalse(ae.onTick(t + 10_000L, false, 100.0, 0.0));
        assertFalse(ae.isArmed());
        assertFalse(ae.onTick(t + 30_000L, false, 100.0, 0.0));
        assertTrue(ae.isArmed());

        // Owner walks past car for 70 s WITHOUT opening door or moving -> must NOT end
        assertFalse(ae.onTick(t + 100_000L, true, 100.0, 0.0));
        assertFalse(ae.onTick(t + 170_000L, true, 100.0, 0.0));
        // Owner leaves again -> return timer resets
        assertFalse(ae.onTick(t + 175_000L, false, 100.0, 0.0));

        // Owner actually returns: opens door, phone connects 5 s later, stays 60 s -> auto-ends!
        ae.onDoorOpened(t + 300_000L);
        assertFalse(ae.onTick(t + 305_000L, true, 100.0, 0.0));
        assertFalse(ae.onTick(t + 360_000L, true, 100.0, 0.0)); // 55 s < 60 s
        assertTrue(ae.onTick(t + 365_000L, true, 100.0, 0.0));  // 60 s -> end!
    }

    @Test public void endsAfterDriving150mWithTrustedPhone() {
        ValetAutoEnd ae = new ValetAutoEnd();
        long t = 2_000_000L;
        ae.reset(t, false);
        assertFalse(ae.onTick(t + 20_000L, false, 50.0, 0.0));
        assertTrue(ae.isArmed());

        // Phone connects and owner pulls away immediately without waiting 60 s
        assertFalse(ae.onTick(t + 100_000L, true, 50.00, 15.0));
        assertFalse(ae.onTick(t + 110_000L, true, 50.08, 30.0)); // ~80 m
        assertTrue(ae.onTick(t + 120_000L, true, 50.16, 30.0));  // 160 m >= 150 m -> end!
    }

    @Test public void speedIntegrationTriggersEvenBeforeOdometerTicks() {
        ValetAutoEnd ae = new ValetAutoEnd();
        long t = 3_000_000L;
        ae.reset(t, false);
        assertFalse(ae.onTick(t + 25_000L, false, 50.0, 0.0));
        assertTrue(ae.isArmed());

        // 36 km/h = 10 m/s -> 16 s = 160 m even if odo hasn't stepped yet
        assertFalse(ae.onTick(t + 100_000L, true, 50.0, 36.0));
        assertFalse(ae.onTick(t + 108_000L, true, 50.0, 36.0)); // 80 m
        assertTrue(ae.onTick(t + 116_000L, true, 50.0, 36.0));  // 160 m -> end!
    }
}
