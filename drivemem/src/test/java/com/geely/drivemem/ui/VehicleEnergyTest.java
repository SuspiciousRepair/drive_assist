package com.geely.drivemem.ui;

import org.junit.Test;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class VehicleEnergyTest {
    @Test public void missingDataStaysUnknownInsteadOfLookingEmpty() {
        VehicleEnergy energy = new VehicleEnergy(Collections.emptyMap(), null, null);
        assertNull(energy.battery);
        assertNull(energy.range);
        assertNull(energy.chargePower);
        assertNull(energy.charging);
    }

    @Test public void disconnectedPlugWinsOverLatchedChargingCurrent() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("charge_a", 11.4f);
        snapshot.put("charge_v", 245f);
        VehicleEnergy energy = new VehicleEnergy(snapshot, 0, 1);
        assertEquals(Boolean.FALSE, energy.charging);
        assertEquals(0, energy.chargePower, 0.001);
    }

    @Test public void reportsActualChargePowerAndAcceptsEmptyBattery() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("battery", 0);
        snapshot.put("range", 0f);
        snapshot.put("charge_a", 16f);
        snapshot.put("charge_v", 230f);
        VehicleEnergy energy = new VehicleEnergy(snapshot, 1, 1);
        assertEquals(0, energy.battery, 0.001);
        assertEquals(0, energy.range, 0.001);
        assertEquals(3.68, energy.chargePower, 0.001);
    }

    @Test public void partialSnapshotDoesNotKeepOldValuesOrInventPower() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("charge_a", 16f);
        snapshot.put("battery", Float.NaN);
        snapshot.put("range", -1f);
        VehicleEnergy energy = new VehicleEnergy(snapshot, 1, 1);
        assertNull(energy.chargePower);
        assertNull(energy.battery);
        assertNull(energy.range);
        assertNull(VehicleEnergy.number(Double.POSITIVE_INFINITY, 0, 100));
        assertNull(VehicleEnergy.number(101, 0, 100));
    }
}
