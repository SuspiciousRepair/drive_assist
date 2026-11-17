package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarActorTestHarness;
import com.geely.drivemem.state.CarState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/** EnergyIntegrator drops OBD2 samples by EnergyHub's choice (energy.source),
 * not by the CAN reader's heartbeat alone. */
public class EnergyIntegratorSourceGateTest {
    private CarActorTestHarness h;
    private CanHubTest.FakeSource can;

    @Before public void setUp() {
        EnergyIntegrator.resetForTesting();
        CarState.setParkedForTesting(false);
        h = new CarActorTestHarness();
        can = new CanHubTest.FakeSource();
        CanHub.register(h.actor(), can);
        ObdHub.register(h.actor(), new EnergyHubTest.FakeObd());
        EnergyHub.register(h.actor());
    }

    @After public void tearDown() {
        EnergyIntegrator.resetForTesting();
        CarState.setParkedForTesting(true);
    }

    /** The daemon is up (heartbeats) but has no usable volts or amps: the
     * hub says obd2, so the OBD2 samples must be integrated. They used to be
     * dropped, and the trip fell back to the estimate. */
    @Test public void canLiveWithoutPowerKeepsObd2() {
        can.kw = null; can.v = null; can.a = null;   // live stays true
        h.tickNow();
        assertEquals("obd2", h.actor().get(EnergyHub.SOURCE).value);
        assertFalse(EnergyHub.canIsSource(h.actor()));

        EnergyIntegrator.onObd2Power(10_000, 20.0, h.actor());
        EnergyIntegrator.onObd2Power(12_000, 40.0, h.actor());

        EnergyIntegrator.WindowSnapshot snap = EnergyIntegrator.drainWindow(15_000, null);
        assertEquals(1, snap.sampleCount);
        assertEquals(1.0 / 60.0, snap.spentKwh, 1e-6);   // 30 kW for 2 s
    }

    /** CAN live with a power value: CAN is the source, OBD2 is dropped, so
     * the same energy is not integrated twice. */
    @Test public void canWithPowerDropsObd2() {
        h.tickNow();
        assertEquals("can", h.actor().get(EnergyHub.SOURCE).value);
        assertTrue(EnergyHub.canIsSource(h.actor()));

        EnergyIntegrator.onObd2Power(10_000, 20.0, h.actor());
        EnergyIntegrator.onObd2Power(12_000, 40.0, h.actor());

        EnergyIntegrator.WindowSnapshot snap = EnergyIntegrator.drainWindow(15_000, null);
        assertEquals(0, snap.sampleCount);
        assertEquals(0.0, snap.spentKwh, 1e-9);
    }

    @Test public void noHubKeepsObd2() {
        assertFalse(EnergyHub.canIsSource(null));
        EnergyIntegrator.onObd2Power(10_000, 20.0, null);
        EnergyIntegrator.onObd2Power(12_000, 40.0, null);
        assertEquals(1, EnergyIntegrator.drainWindow(15_000, null).sampleCount);
    }
}
