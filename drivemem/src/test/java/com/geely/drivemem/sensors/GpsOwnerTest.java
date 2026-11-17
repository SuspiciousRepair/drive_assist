package com.geely.drivemem.sensors;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GpsOwnerTest {

    // The defect: with MQTT off (the default) nothing asked for GPS updates.
    @Test public void routesAloneAreAConsumerWithMqttOff() {
        assertTrue(GpsOwner.consumer(true, false, false, false));
    }

    @Test public void telemetryCountsOnlyWithABrokerSet() {
        assertTrue(GpsOwner.consumer(false, true, true, false));
        assertFalse(GpsOwner.consumer(false, true, false, false));   // the service stops itself
        assertFalse(GpsOwner.consumer(false, false, true, false));
    }

    @Test public void abrpLocationIsAConsumer() {
        assertTrue(GpsOwner.consumer(false, false, false, true));
    }

    @Test public void nothingOnMeansNoConsumer() {
        assertFalse(GpsOwner.consumer(false, false, false, false));
    }

    @Test public void listensWhileDriving() {
        assertTrue(GpsOwner.want(true, false, 0));
    }

    @Test public void neverListensWithoutAConsumer() {
        assertFalse(GpsOwner.want(false, false, 0));
        assertFalse(GpsOwner.want(false, true, 0));
    }

    @Test public void keepsListeningForAShortStopInPark() {
        assertTrue(GpsOwner.want(true, true, 0));
        assertTrue(GpsOwner.want(true, true, GpsOwner.PARK_GRACE_MS - 1));
    }

    @Test public void stopsListeningWhenParkedForLong() {
        assertFalse(GpsOwner.want(true, true, GpsOwner.PARK_GRACE_MS));
        assertFalse(GpsOwner.want(true, true, 8 * 60 * 60 * 1000L));
    }
}
