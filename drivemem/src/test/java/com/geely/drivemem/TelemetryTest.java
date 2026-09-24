package com.geely.drivemem;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.Telemetry;
import com.geely.drivemem.sensors.EnergyIntegrator;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Map;

public class TelemetryTest {

    @Test public void parkTimerLabelKnownCodes() {
        assertEquals("30 min", Telemetry.parkTimerLabel(0x02));
        assertEquals("1 h", Telemetry.parkTimerLabel(0x04));
        assertEquals("2 h", Telemetry.parkTimerLabel(0x06));
        // 3h/4h/5h: measured live from the car's own menu on 2026-09-02,
        // not extrapolated — see the comment above parkTimerLabel().
        assertEquals("3 h", Telemetry.parkTimerLabel(0x08));
        assertEquals("4 h", Telemetry.parkTimerLabel(0x0A));
        assertEquals("5 h", Telemetry.parkTimerLabel(0x0C));
        assertEquals("Ilimitado", Telemetry.parkTimerLabel(0x13));
    }

    @Test public void parkTimerLabelUnknownCodeFallsBackToHex() {
        assertEquals("cod 0x7F", Telemetry.parkTimerLabel(0x7F));
    }

    @Test public void parkTimerCodeRoundTripsWithLabel() {
        for (int code : new int[]{0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x13}) {
            String label = Telemetry.parkTimerLabel(code);
            assertEquals(code, Telemetry.parkTimerCode(label));
        }
    }

    @Test public void parkTimerCodeDefaultsToUnlimited() {
        assertEquals(0x13, Telemetry.parkTimerCode(null));
        assertEquals(0x13, Telemetry.parkTimerCode("nonsense"));
    }

    @Test public void gearLabelKnownValues() {
        assertEquals("N", Telemetry.gearLabel(1));
        assertEquals("R", Telemetry.gearLabel(2));
        assertEquals("P", Telemetry.gearLabel(4));
        assertEquals("D", Telemetry.gearLabel(8));
    }

    @Test public void snapshotDoesNotDrainEnergyAccumulatorsWhileTickDrains() {
        EnergyIntegrator.resetForTesting();
        Telemetry.resetForTesting();

        // Accumulate positive power (spent energy)
        // 20 kW over 2 seconds = 20 * (2/3600) = 0.01111 kWh
        EnergyIntegrator.onPowerReading(1_000, 20.0);
        EnergyIntegrator.onPowerReading(3_000, 20.0);

        CarAccess dummyCar = new CarAccess();

        // Calling snapshot() multiple times must not drain accumulated energy
        Map<String, Object> snap1 = Telemetry.snapshot(dummyCar, false);
        Map<String, Object> snap2 = Telemetry.snapshot(dummyCar, false);

        assertFalse("snapshot must not contain energy_spent_kwh", snap1.containsKey("energy_spent_kwh"));
        assertFalse("snapshot must not contain energy_spent_kwh", snap2.containsKey("energy_spent_kwh"));
        assertNull("snapshot must not advance lastPowerSoc", Telemetry.getLastPowerSocForTesting());
        assertEquals("snapshot must not advance lastPowerSocAtMs", 0L, Telemetry.getLastPowerSocAtMsForTesting());

        // Now call tick() - should drain and report the accumulated energy
        Map<String, Object> tick1 = Telemetry.tick(dummyCar, false);
        assertTrue("tick must contain energy_spent_kwh", tick1.containsKey("energy_spent_kwh"));
        float spent1 = (Float) tick1.get("energy_spent_kwh");
        assertTrue("tick must drain accumulated energy (>0)", spent1 > 0.005f);

        // Calling tick() a second time without new readings must yield 0 drained energy
        Map<String, Object> tick2 = Telemetry.tick(dummyCar, false);
        float spent2 = (Float) tick2.get("energy_spent_kwh");
        assertEquals("subsequent tick without readings must be 0", 0.0f, spent2, 0.0001f);
    }
}
