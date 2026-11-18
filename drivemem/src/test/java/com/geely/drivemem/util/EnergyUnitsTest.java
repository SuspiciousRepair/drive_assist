package com.geely.drivemem.util;

import org.junit.Test;

import static org.junit.Assert.*;

/** Consumption is shown in whole Wh/km; the app keeps kWh/100 km inside. */
public class EnergyUnitsTest {
    @Test public void kwhPer100kmToWhPerKm() {
        assertEquals(152.0, EnergyUnits.whPerKm(15.2), 1e-9);
        assertEquals("152", EnergyUnits.number(15.2));
        assertEquals("87", EnergyUnits.number(8.66));
    }

    @Test public void fromEnergyAndDistance() {
        assertEquals(150.0, EnergyUnits.whPerKm(1.5, 10), 1e-9);
        assertTrue(Double.isNaN(EnergyUnits.whPerKm(1.5, 0)));
    }
}
