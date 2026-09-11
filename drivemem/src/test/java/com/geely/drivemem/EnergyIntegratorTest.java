package com.geely.drivemem;

import com.geely.drivemem.sensors.EnergyIntegrator;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class EnergyIntegratorTest {

    private static final double EPSILON = 1e-6;

    @Before
    public void setUp() {
        EnergyIntegrator.resetForTesting();
    }

    @Test
    public void testPureDischargeConsumption() {
        // t=0s: 20 kW, t=2s: 40 kW -> dt = 2s = 2/3600 h = 1/1800 h
        // Avg power = (20 + 40) / 2 = 30 kW
        // Expected spent = 30 * (2 / 3600) = 60 / 3600 = 1/60 kWh ~ 0.0166667 kWh
        // Expected regen = 0.0
        // Expected net = 1/60 kWh
        EnergyIntegrator.onPowerReading(10_000, 20.0);
        EnergyIntegrator.onPowerReading(12_000, 40.0);

        EnergyIntegrator.WindowSnapshot snap = EnergyIntegrator.drainWindow(15_000, null);
        assertEquals(1, snap.sampleCount);
        assertEquals(40.0, snap.instantPowerKw, EPSILON);
        assertEquals(40.0, snap.instantPowerSpentKw, EPSILON);
        assertEquals(0.0, snap.instantPowerRegenKw, EPSILON);
        assertEquals(1.0 / 60.0, snap.spentKwh, EPSILON);
        assertEquals(0.0, snap.regenKwh, EPSILON);
        assertEquals(1.0 / 60.0, snap.netKwh, EPSILON);
    }

    @Test
    public void testPureRegenRecovery() {
        // t=0s: -10 kW, t=2s: -30 kW -> dt = 2s
        // Avg regen power = 20 kW
        // Expected regen = 20 * (2 / 3600) = 40 / 3600 = 1/90 kWh ~ 0.0111111 kWh
        // Expected spent = 0.0
        // Expected net = -1/90 kWh
        EnergyIntegrator.onPowerReading(10_000, -10.0);
        EnergyIntegrator.onPowerReading(12_000, -30.0);

        EnergyIntegrator.WindowSnapshot snap = EnergyIntegrator.drainWindow(15_000, null);
        assertEquals(1, snap.sampleCount);
        assertEquals(-30.0, snap.instantPowerKw, EPSILON);
        assertEquals(0.0, snap.instantPowerSpentKw, EPSILON);
        assertEquals(30.0, snap.instantPowerRegenKw, EPSILON);
        assertEquals(0.0, snap.spentKwh, EPSILON);
        assertEquals(1.0 / 90.0, snap.regenKwh, EPSILON);
        assertEquals(-1.0 / 90.0, snap.netKwh, EPSILON);
    }

    @Test
    public void testZeroCrossingSymmetric() {
        // t=0s: +20 kW, t=2s: -20 kW
        // Zero crossing is at t=1s (middle).
        // Positive triangle: base 1s, height 20 kW -> area = (20 / 2) * (1 / 3600) = 10 / 3600 = 1/360 kWh
        // Negative triangle: base 1s, height 20 kW -> area = (20 / 2) * (1 / 3600) = 10 / 3600 = 1/360 kWh
        // Expected spent = 1/360 kWh
        // Expected regen = 1/360 kWh
        // Expected net = 0.0
        EnergyIntegrator.onPowerReading(10_000, 20.0);
        EnergyIntegrator.onPowerReading(12_000, -20.0);

        EnergyIntegrator.WindowSnapshot snap = EnergyIntegrator.drainWindow(15_000, null);
        assertEquals(1, snap.sampleCount);
        assertEquals(1.0 / 360.0, snap.spentKwh, EPSILON);
        assertEquals(1.0 / 360.0, snap.regenKwh, EPSILON);
        assertEquals(0.0, snap.netKwh, EPSILON);
    }

    @Test
    public void testZeroCrossingAsymmetric() {
        // t=0s: -10 kW, t=2s: +30 kW -> range = 40 kW
        // Zero crossing fraction for negative = 10 / 40 = 0.25 (0.5s)
        // Fraction for positive = 30 / 40 = 0.75 (1.5s)
        // Regen: (10 / 2) * (0.5 / 3600) = 2.5 / 3600 kWh = 5 / 7200 = 1 / 1440 kWh
        // Spent: (30 / 2) * (1.5 / 3600) = 22.5 / 3600 kWh = 45 / 7200 = 9 / 1440 kWh
        // Net: 9/1440 - 1/1440 = 8/1440 = 1/180 kWh = 20 kW * (2 / 3600) = 1/180 kWh
        EnergyIntegrator.onPowerReading(10_000, -10.0);
        EnergyIntegrator.onPowerReading(12_000, 30.0);

        EnergyIntegrator.WindowSnapshot snap = EnergyIntegrator.drainWindow(15_000, null);
        assertEquals(1, snap.sampleCount);
        assertEquals(9.0 / 1440.0, snap.spentKwh, EPSILON);
        assertEquals(1.0 / 1440.0, snap.regenKwh, EPSILON);
        assertEquals(8.0 / 1440.0, snap.netKwh, EPSILON);
    }

    @Test
    public void testGapRejection() {
        // Gap > MAX_GAP_MS (10s): e.g. 15s between samples
        EnergyIntegrator.onPowerReading(10_000, 50.0);
        EnergyIntegrator.onPowerReading(25_000, 50.0); // 15s later -> gap!

        EnergyIntegrator.WindowSnapshot snap = EnergyIntegrator.drainWindow(15_000, null);
        // The gap sample must NOT be integrated into energy
        assertEquals(0, snap.sampleCount);
        assertEquals(0.0, snap.spentKwh, EPSILON);
        assertEquals(0.0, snap.regenKwh, EPSILON);
        assertEquals(0.0, snap.netKwh, EPSILON);
    }

    @Test
    public void testTripAccumulation() {
        EnergyIntegrator.startTrip();

        // Step 1: 0 to 2s: accelerate 0 -> 40 kW
        EnergyIntegrator.onPowerReading(10_000, 0.0);
        EnergyIntegrator.onPowerReading(12_000, 40.0); // spent = 20 * (2/3600) = 40/3600

        // Step 2: 2 to 4s: regen 40 -> -20 kW (zero crossing at 40/60 = 2/3 of 2s = 4/3 s)
        EnergyIntegrator.onPowerReading(14_000, -20.0);

        // Step 3: 4 to 6s: continue regen -20 -> -10 kW (avg -15 kW for 2s)
        EnergyIntegrator.onPowerReading(16_000, -10.0);

        EnergyIntegrator.TripSnapshot trip = EnergyIntegrator.endTrip();
        assertEquals(3, trip.sampleCount);
        assertTrue("Spent must be positive", trip.spentKwh > 0);
        assertTrue("Regen must be positive", trip.regenKwh > 0);
        assertEquals(trip.spentKwh - trip.regenKwh, trip.netKwh, EPSILON);

        // Window drain must have also accumulated
        EnergyIntegrator.WindowSnapshot snap = EnergyIntegrator.drainWindow(15_000, null);
        assertEquals(3, snap.sampleCount);
        assertEquals(trip.spentKwh, snap.spentKwh, EPSILON);
        assertEquals(trip.regenKwh, snap.regenKwh, EPSILON);

        // Next window drain must be reset to zero
        EnergyIntegrator.WindowSnapshot nextSnap = EnergyIntegrator.drainWindow(null);
        assertEquals(0, nextSnap.sampleCount);
        assertEquals(0.0, nextSnap.spentKwh, EPSILON);
        assertEquals(0.0, nextSnap.regenKwh, EPSILON);
    }

    @Test
    public void testCurrentTripDuringActiveDrive() {
        EnergyIntegrator.startTrip();

        // Window 1: 0 to 4s
        EnergyIntegrator.onPowerReading(10_000, 20.0);
        EnergyIntegrator.onPowerReading(12_000, 40.0); // 30 kW avg * 2s
        EnergyIntegrator.onPowerReading(14_000, 20.0); // 30 kW avg * 2s
        
        EnergyIntegrator.TripSnapshot live1 = EnergyIntegrator.currentTrip();
        assertEquals(2, live1.sampleCount);
        assertTrue(live1.spentKwh > 0);
        assertEquals(0.0, live1.regenKwh, EPSILON);

        // Window 1 drains at 15s
        EnergyIntegrator.WindowSnapshot snap1 = EnergyIntegrator.drainWindow(15_000, null);
        assertEquals(live1.spentKwh, snap1.spentKwh, EPSILON);

        // Window 2: 15s to 19s (regen)
        EnergyIntegrator.onPowerReading(17_000, -30.0);
        EnergyIntegrator.onPowerReading(19_000, -10.0); // 20 kW avg regen * 2s

        // Live trip totals must persist across window drain!
        EnergyIntegrator.TripSnapshot live2 = EnergyIntegrator.currentTrip();
        assertEquals(4, live2.sampleCount); // 2 from win1 (10-12, 12-14) + 2 in win2 (14-17, 17-19)
        assertTrue(live2.spentKwh >= live1.spentKwh);
        assertTrue(live2.regenKwh > 0);
        assertEquals(live2.spentKwh - live2.regenKwh, live2.netKwh, EPSILON);

        // Window 2 drains
        EnergyIntegrator.WindowSnapshot snap2 = EnergyIntegrator.drainWindow(30_000, null);
        assertEquals(2, snap2.sampleCount);
        assertEquals(live2.spentKwh - live1.spentKwh, snap2.spentKwh, EPSILON);
        assertEquals(live2.regenKwh, snap2.regenKwh, EPSILON);

        // End trip matches live2
        EnergyIntegrator.TripSnapshot endTrip = EnergyIntegrator.endTrip();
        assertEquals(live2.spentKwh, endTrip.spentKwh, EPSILON);
        assertEquals(live2.regenKwh, endTrip.regenKwh, EPSILON);
        assertEquals(live2.netKwh, endTrip.netKwh, EPSILON);
    }
}
