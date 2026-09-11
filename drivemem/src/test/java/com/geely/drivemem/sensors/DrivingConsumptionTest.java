package com.geely.drivemem.sensors;

import org.junit.Test;
import static org.junit.Assert.*;

public class DrivingConsumptionTest {
    @Test public void parkedAcExcludedButTrafficStopsCount() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(0, 100, 0, 4, false, 2, 0, Double.NaN);
        c.add(15000, 101, 20, 8, false, .2, 0, Double.NaN);
        c.add(30000, 101, 0, 8, false, .1, 0, Double.NaN);
        c.add(45000, 102, 10, 8, false, .2, .05, Double.NaN);
        c.add(60000, 102, 0, 4, false, 3, 0, Double.NaN);
        assertEquals(.5, c.totalSpent, 1e-9);
        assertEquals(2, c.distance[0], 1e-9);
        assertEquals(22.5, DrivingConsumption.per100km(c.spent[0], c.regen[0], c.distance[0]), 1e-9);
    }

    @Test public void legacyDoesNotBridgeParkChargingOrDirectSamples() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(0, 100, 0, 8, false, Double.NaN, Double.NaN, 12);
        c.add(15000, 100, 0, 8, false, Double.NaN, Double.NaN, 12);
        assertEquals(.05, c.totalSpent, 1e-9);
        c.add(20000, 100, 0, 4, false, Double.NaN, Double.NaN, 12);
        c.add(30000, 100, 0, 8, false, Double.NaN, Double.NaN, 12);
        c.add(35000, 100, 0, 8, true, Double.NaN, Double.NaN, 12);
        c.add(40000, 100, 0, 8, false, Double.NaN, Double.NaN, 12);
        c.add(45000, 100, 0, 8, false, .1, 0, 12);
        c.add(50000, 100, 0, 8, false, Double.NaN, Double.NaN, 12);
        assertEquals(.15, c.totalSpent, 1e-9);
    }

    @Test public void unknownStationaryGearIsNotAssumedDriving() {
        assertFalse(DrivingConsumption.isDriving(null, 0, false));
        assertTrue(DrivingConsumption.isDriving(null, 10, false));
        assertFalse(DrivingConsumption.isDriving(4, 10, false));
        assertTrue(DrivingConsumption.isDriving(8, 0, false));
        assertFalse(DrivingConsumption.isDriving(8, 0, true));
    }

    @Test public void legacyGapsAndOdometerStartupDoNotInflateConsumption() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(0, 100, 20, 8, false, Double.NaN, Double.NaN, 12);
        c.add(120000, 101, 20, 8, false, Double.NaN, Double.NaN, 12);
        assertEquals(0, c.totalSpent, 0);
        c.add(135000, 0, 20, 8, false, .1, 0, 12);
        c.add(150000, 102, 20, 8, false, .1, 0, 12);
        assertEquals(1, c.distance[0], 1e-9);
    }
}
