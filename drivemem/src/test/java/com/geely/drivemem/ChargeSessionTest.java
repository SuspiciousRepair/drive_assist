package com.geely.drivemem;

import com.geely.drivemem.state.ChargeSession;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChargeSessionTest {

    @Test public void durationLabelUnderAnHour() {
        ChargeSession.Summary s = new ChargeSession.Summary(
            0, 45 * 60 * 1000L, 30, 80, 20.0, 4000, 90, -1);
        assertEquals("0:45", s.durationLabel());
    }

    @Test public void durationLabelOverAnHourPadsMinutes() {
        ChargeSession.Summary s = new ChargeSession.Summary(
            0, (2 * 3600 + 15 * 60) * 1000L, 30, 80, 20.0, 4000, 90, -1);
        assertEquals("2:15", s.durationLabel());
    }

    @Test public void durationLabelExactHourHasNoMinutes() {
        ChargeSession.Summary s = new ChargeSession.Summary(
            0, 3 * 3600 * 1000L, 30, 80, 20.0, 4000, 90, -1);
        assertEquals("3:00", s.durationLabel());
    }

    @Test public void durationSNeverNegative() {
        // end before start shouldn't happen, but the clamp is deliberate —
        // guard it stays a clamp, not a crash or a negative duration label.
        ChargeSession.Summary s = new ChargeSession.Summary(
            10_000, 0, 30, 80, 20.0, 4000, 90, -1);
        assertEquals(0, s.durationS());
    }

    @Test public void costFormatting() {
        ChargeSession.Summary s = new ChargeSession.Summary(
            1, 0, 3600_000L, 20, 80, 25.0, 7000, 90, 100.0, 50.0, false);
        assertEquals(1, s.id);
        assertNotNull(s.cost);
        assertEquals(50.0, s.cost, 0.001);
        assertFalse(s.dismissed);
        assertTrue(s.costLabel().contains("50.00") || s.costLabel().contains("50,00"));
        assertTrue(s.costPerKwhLabel().contains("2.00") || s.costPerKwhLabel().contains("2,00"));
    }

    @Test public void costNullHandling() {
        ChargeSession.Summary s = new ChargeSession.Summary(
            2, 0, 3600_000L, 20, 80, 25.0, 7000, 90, 100.0, null, true);
        assertEquals(2, s.id);
        assertNull(s.cost);
        assertTrue(s.dismissed);
        assertNull(s.costLabel());
        assertNull(s.costPerKwhLabel());
    }

    @Test public void costZeroHandling() {
        ChargeSession.Summary s = new ChargeSession.Summary(
            3, 0, 3600_000L, 20, 80, 25.0, 7000, 90, 100.0, 0.0, false);
        assertEquals(3, s.id);
        assertNotNull(s.cost);
        assertEquals(0.0, s.cost, 0.001);
        assertFalse(s.dismissed);
        assertTrue(s.costLabel().contains("0.00") || s.costLabel().contains("0,00"));
        assertTrue(s.costPerKwhLabel().contains("0.00") || s.costPerKwhLabel().contains("0,00"));
    }

    @Test public void qualificationFilterBoundaries() {
        // MIN_CHARGE_KWH = 0.05, MIN_CHARGE_DURATION_MS = 60_000 (1 min)
        // Discarded if BOTH < 0.05 kWh AND < 60s
        assertFalse(ChargeSession.isQualified(0.0, 0L));
        assertFalse(ChargeSession.isQualified(0.01, 30_000L));
        assertFalse(ChargeSession.isQualified(0.049, 59_999L));

        // Qualifies by energy alone (e.g. DCFC high power short pulse)
        assertTrue(ChargeSession.isQualified(0.05, 0L));
        assertTrue(ChargeSession.isQualified(0.05, 20_000L));
        assertTrue(ChargeSession.isQualified(1.5, 45_000L));

        // Qualifies by duration alone (e.g. trickle float)
        assertTrue(ChargeSession.isQualified(0.0, 60_000L));
        assertTrue(ChargeSession.isQualified(0.01, 60_000L));
        assertTrue(ChargeSession.isQualified(0.02, 120_000L));

        // Qualifies by both
        assertTrue(ChargeSession.isQualified(10.0, 3600_000L));
    }

    @Test public void chargeGracePeriodResumeAndMerge() {
        ChargeSession.resetForTesting();
        long t0 = 1_000_000L;
        long m0 = 10_000L;

        // 0 -> 1: Charge starts
        ChargeSession.onChargingEdge(null, true, t0, m0);
        assertTrue(ChargeSession.isCharging());
        assertTrue(ChargeSession.isSessionActive());
        assertTrue(ChargeSession.isCurrentFlowing());
        assertFalse(ChargeSession.isChargeGraceScheduled());
        assertEquals(t0, ChargeSession.currentStartWallMs());

        // 1 -> 0: Charge drops (e.g. renegotiation / replug pause)
        long t1 = t0 + 40_000L;
        long m1 = m0 + 40_000L;
        ChargeSession.onChargingEdge(null, false, t1, m1);
        assertFalse("Current is not flowing during pause", ChargeSession.isCharging());
        assertTrue("Session remains active in grace period", ChargeSession.isSessionActive());
        assertFalse("Current is not flowing during pause", ChargeSession.isCurrentFlowing());
        assertTrue("Grace period timer scheduled", ChargeSession.isChargeGraceScheduled());
        assertEquals(t1, ChargeSession.currentPauseWallMs());

        // 0 -> 1: Charge resumes within 180s grace period (e.g. 25s later)
        long t2 = t1 + 25_000L;
        long m2 = m1 + 25_000L;
        ChargeSession.onChargingEdge(null, true, t2, m2);
        assertTrue(ChargeSession.isCharging());
        assertTrue(ChargeSession.isSessionActive());
        assertTrue(ChargeSession.isCurrentFlowing());
        assertFalse("Grace timer cancelled on resume", ChargeSession.isChargeGraceScheduled());
        assertEquals("Original start wall time preserved", t0, ChargeSession.currentStartWallMs());
        assertEquals(0L, ChargeSession.currentPauseWallMs());

        // 1 -> 0: Charge finishes
        long t3 = t2 + 60_000L;
        long m3 = m2 + 60_000L;
        ChargeSession.onChargingEdge(null, false, t3, m3);
        assertTrue(ChargeSession.isChargeGraceScheduled());

        // Grace period expires: finalizeSession with simulated 5 kWh
        ChargeSession.setSessionStateForTesting(true, false, t0, 30, 45, 5000.0, 10);
        ChargeSession.finalizeGracePeriod(null);
        assertFalse("Session is closed after finalization", ChargeSession.isSessionActive());
        assertFalse(ChargeSession.isChargeGraceScheduled());
    }

    @Test public void immediateCompletedCardDisplayWithResumeMerge() {
        ChargeSession.resetForTesting();
        long t0 = 100_000L;
        long m0 = 1_000L;

        final ChargeSession.Summary[] lastCompleted = new ChargeSession.Summary[1];
        final boolean[] progressCalled = new boolean[1];

        ChargeSession.setProgressListener(new ChargeSession.ProgressListener() {
            @Override public void onProgress(int socStart, int socNow, long startWallMs, long nowWallMs) {
                progressCalled[0] = true;
            }
            @Override public void onCompleted(ChargeSession.Summary s) {
                lastCompleted[0] = s;
            }
            @Override public void onIdle() {}
        });

        // 1. Charge starts
        ChargeSession.onChargingEdge(null, true, t0, m0);
        assertTrue(ChargeSession.isCharging());

        // Simulate 2 kWh over 2 minutes
        ChargeSession.setSessionStateForTesting(true, true, t0, 20, 35, 2000.0, 8);

        // 2. Charge stops (1 -> 0)
        long t1 = t0 + 120_000L;
        long m1 = m0 + 120_000L;
        ChargeSession.onChargingEdge(null, false, t1, m1);

        // MUST immediately show completed card to driver!
        assertNotNull("Completed summary must be delivered immediately to show completed card", lastCompleted[0]);
        assertEquals(2.0, lastCompleted[0].kwh, 0.001);
        assertEquals(t0, lastCompleted[0].startWallMs);
        assertEquals(t1, lastCompleted[0].endWallMs);
        assertFalse("isCharging is false so card shows completed", ChargeSession.isCharging());
        assertTrue("Session remains active in background grace period", ChargeSession.isSessionActive());
        assertTrue("Grace timer scheduled", ChargeSession.isChargeGraceScheduled());

        // Driver inputs cost immediately: R$ 25,00
        ChargeSession.updateCost(null, lastCompleted[0].id, 25.0);

        // 3. Charger restarts 30s later (0 -> 1)
        progressCalled[0] = false;
        long t2 = t1 + 30_000L;
        long m2 = m1 + 30_000L;
        ChargeSession.onChargingEdge(null, true, t2, m2);

        assertTrue("Card switches back to active charging layout", progressCalled[0]);
        assertTrue(ChargeSession.isCharging());
        assertFalse("Grace timer cancelled", ChargeSession.isChargeGraceScheduled());

        // Accumulates another 3 kWh (total 5 kWh)
        ChargeSession.setSessionStateForTesting(true, true, t0, 20, 55, 5000.0, 20);

        // 4. Charge stops for real (1 -> 0)
        long t3 = t2 + 180_000L;
        long m3 = m2 + 180_000L;
        ChargeSession.onChargingEdge(null, false, t3, m3);

        assertNotNull(lastCompleted[0]);
        assertEquals("Total kWh reflects merged session", 5.0, lastCompleted[0].kwh, 0.001);
        assertNotNull("Entered cost was preserved across resume", lastCompleted[0].cost);
        assertEquals(25.0, lastCompleted[0].cost, 0.001);

        // Clean up
        ChargeSession.setProgressListener(null);
    }

    @Test public void microChargeDiscardedBelowThresholds() {
        ChargeSession.resetForTesting();
        long t0 = 500_000L;
        long m0 = 5_000L;

        // Brief 10-second micro-charge with negligible energy (10 Wh = 0.01 kWh)
        ChargeSession.onChargingEdge(null, true, t0, m0);
        ChargeSession.onChargingEdge(null, false, t0 + 10_000L, m0 + 10_000L);
        assertTrue(ChargeSession.isChargeGraceScheduled());

        // Simulate 0.01 kWh energy accumulated
        ChargeSession.setSessionStateForTesting(true, false, t0, 50, 50, 10.0, 1);

        // Finalize after grace timeout
        ChargeSession.finalizeGracePeriod(null);
        assertFalse("Micro-charge below 0.05 kWh and under 60s must be discarded", ChargeSession.isSessionActive());
        assertFalse("State must be reset to inactive", ChargeSession.isCharging());
    }

    @Test public void parkExitOverridesGracePeriodImmediately() {
        ChargeSession.resetForTesting();
        long t0 = 100_000L;
        long m0 = 1_000L;

        ChargeSession.onChargingEdge(null, true, t0, m0);
        ChargeSession.onChargingEdge(null, false, t0 + 120_000L, m0 + 120_000L);
        assertTrue(ChargeSession.isChargeGraceScheduled());

        // Set state: 2 minutes duration, 2 kWh
        ChargeSession.setSessionStateForTesting(true, false, t0, 40, 45, 2000.0, 8);

        // Vehicle leaves Park
        ChargeSession.onParkExit(null);
        assertFalse("Grace timer must be cancelled immediately on Park exit", ChargeSession.isChargeGraceScheduled());
        assertFalse("Session must be finalized immediately on Park exit", ChargeSession.isSessionActive());
    }
}
