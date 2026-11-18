package com.geely.drivemem.sensors;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BatteryHealthTest {

    private static final ZoneId Z = ZoneId.of("America/Sao_Paulo");

    private static long at(int day, int hour) {
        return ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, Z).toInstant().toEpochMilli();
    }

    @Test
    public void storesTheFirstReading() {
        assertTrue(BatteryHealth.shouldStore(Double.NaN, -1, 100.0, at(18, 10), Z));
    }

    @Test
    public void sameValueSameDayIsNotStoredAgain() {
        assertFalse(BatteryHealth.shouldStore(100.0, at(18, 1), 100.0, at(18, 23), Z));
    }

    @Test
    public void aNewDayOrANewValueIsStored() {
        assertTrue(BatteryHealth.shouldStore(100.0, at(18, 23), 100.0, at(19, 0), Z));
        assertTrue(BatteryHealth.shouldStore(100.0, at(18, 10), 99.9, at(18, 11), Z));
    }
}
