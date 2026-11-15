package com.geely.drivemem.sensors;

import org.junit.Test;

import static org.junit.Assert.*;

/** CanPower: roadcastd signal changes -> power samples for EnergyIntegrator. */
public class CanPowerTest {
    private static final double E = 1e-9;

    @Test public void needsBothVoltsAndAmps() {
        CanPower p = new CanPower();
        assertNull(p.onSignal(CanPower.CURR, 10.0, true, 1000));
        Double kw = p.onSignal(CanPower.VOLT, 400.0, true, 1100);
        assertEquals(4.0, kw, E);   // 400 V x 10 A
    }

    /** Car values from 2026-11-14 while AC charging: negative = into the battery. */
    @Test public void chargingIsNegative() {
        CanPower p = new CanPower();
        p.onSignal(CanPower.VOLT, 403.9, true, 0);
        Double kw = p.onSignal(CanPower.CURR, -16.0, true, 10);
        assertEquals(-6.4624, kw, 1e-6);
        assertNull(p.onSignal(CanPower.OBC_V, 241.5, true, 20));
        assertNull(p.onSignal(CanPower.OBC_A, 28.2, true, 30));
        assertEquals(241.5 * 28.2, p.chargerInputW(30), 1e-6);
    }

    @Test public void invalidOrOutOfBandDropsTheValue() {
        CanPower p = new CanPower();
        p.onSignal(CanPower.VOLT, 400.0, true, 0);
        p.onSignal(CanPower.CURR, 10.0, true, 0);
        assertNull("invalid state", p.onSignal(CanPower.CURR, 10.0, false, 10));
        assertNull("out of band", p.onSignal(CanPower.CURR, 900.0, true, 20));
        assertNull("volts out of band", p.onSignal(CanPower.VOLT, 5.0, true, 30));
    }

    /** roadcastd sends only changes: a steady load must still produce
     * samples, from the heartbeat, or the integrator sees a gap. */
    @Test public void heartbeatReemitsSteadyValues() {
        CanPower p = new CanPower();
        p.onSignal(CanPower.VOLT, 400.0, true, 0);
        p.onSignal(CanPower.CURR, 5.0, true, 0);
        assertNull("too soon after the last sample", p.onHeartbeat(1, 500));
        assertEquals(2.0, p.onHeartbeat(1, 1000), E);
        assertEquals(2.0, p.onHeartbeat(1, 2000), E);
        assertNull("source unavailable", p.onHeartbeat(0, 3000));
        assertFalse(p.live(3000));
    }

    @Test public void staleWithoutHeartbeat() {
        CanPower p = new CanPower();
        p.onSignal(CanPower.VOLT, 400.0, true, 0);
        p.onSignal(CanPower.CURR, 5.0, true, 0);
        assertTrue(p.live(CanPower.STALE_MS));
        assertEquals(2.0, p.powerKw(CanPower.STALE_MS), E);
        assertFalse(p.live(CanPower.STALE_MS + 1));
        assertNull(p.powerKw(CanPower.STALE_MS + 1));
    }

    @Test public void climateBandAndReset() {
        CanPower p = new CanPower();
        p.onSignal(CanPower.VOLT, 400.0, true, 0);
        assertNull(p.onSignal(CanPower.CLIMATE, 1.6, true, 0));
        assertEquals(1.6, p.climateKw(0), E);
        p.onSignal(CanPower.CLIMATE, 99.0, true, 10);
        assertNull(p.climateKw(10));
        p.reset();
        assertFalse(p.live(10));
        assertNull(p.packVolts(10));
    }
}
