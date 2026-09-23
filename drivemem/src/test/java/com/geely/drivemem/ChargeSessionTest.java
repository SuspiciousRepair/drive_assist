package com.geely.drivemem;

import android.content.SharedPreferences;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.state.ChargeSession;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class ChargeSessionTest {

    @Test public void chargingTypeUsesPeakVoltageNotAveragePower() {
        ChargeSession.Summary taperedDc = new ChargeSession.Summary(
            1, 0, 3600_000L, 70, 90, 8.0, 8_000, 401.0, 90, -1, null, false);
        ChargeSession.Summary highPowerAc = new ChargeSession.Summary(
            2, 0, 3600_000L, 20, 80, 22.0, 24_000, 240.0, 90, -1, null, false);
        ChargeSession.Summary legacyUnknown = new ChargeSession.Summary(
            3, 0, 3600_000L, 20, 80, 22.0, 50_000, 90, -1, null, false);

        assertTrue(taperedDc.isDcfc());
        assertFalse(highPowerAc.isDcfc());
        assertFalse(legacyUnknown.isDcfc());
        assertFalse(legacyUnknown.hasChargeVoltage());
    }

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

    @Test public void plugGlitchDoesNotTruncateSession() {
        ChargeSession.resetForTesting();
        CarActor testActor = new CarActor(null);
        testActor.putForTesting("car.is_charging", CarActor.Reading.ok(1));
        CarActor.setInstanceForTesting(testActor);

        try {
            long t0 = 1_000_000L;
            long m0 = 10_000L;

            // 0 -> 1: Session starts normally
            ChargeSession.onChargingEdge(null, true, t0, m0);
            assertTrue(ChargeSession.isCharging());
            assertTrue(ChargeSession.isSessionActive());
            assertFalse(ChargeSession.isChargeGraceScheduled());

            // Plug glitch: car.plug_connected blips 0.
            // In the old code, ChargeSession had a separate listener directly on car.plug_connected
            // that force-closed the session immediately.
            // In the fixed code, that listener is removed, so publishing 0 to car.plug_connected
            // must NOT terminate or schedule grace period for the active charge session.
            EntityBus.publish("car.plug_connected", CarActor.Reading.ok(0));

            assertTrue("Session must remain active despite plug_connected blip", ChargeSession.isSessionActive());
            assertTrue("Session must remain in charging state", ChargeSession.isCharging());
            assertFalse("Grace period must not be scheduled by plug glitch", ChargeSession.isChargeGraceScheduled());

            // Telemetry tick arrives while CarActor's cached is_charging is 1.
            // If wasCharging had drifted to false (e.g. simulated edge drop), onTelemetryTick
            // self-corrects against CarActor's cached value.
            ChargeSession.onChargingEdge(null, false, t0 + 10_000L, m0 + 10_000L);
            assertTrue(ChargeSession.isChargeGraceScheduled()); // drifted into grace

            Map<String, Object> tickData = new HashMap<>();
            tickData.put("battery", 55);
            ChargeSession.onTelemetryTick(null, tickData);

            assertTrue("Telemetry tick self-corrects against cached car.is_charging=1", ChargeSession.isCharging());
            assertTrue("Session remains active after self-correction", ChargeSession.isSessionActive());
            assertFalse("Grace timer cancelled on drift self-correction", ChargeSession.isChargeGraceScheduled());
        } finally {
            CarActor.setInstanceForTesting(null);
            ChargeSession.resetForTesting();
        }
    }

    @Test public void simulatedCrashRecoveryReconstructsSession() {
        ChargeSession.resetForTesting();
        FakeSharedPreferences prefs = new FakeSharedPreferences();

        long t0 = 1_500_000L;
        long m0 = 50_000L;

        // Active charging session before crash:
        ChargeSession.onChargingEdge(null, true, t0, m0);
        ChargeSession.setSessionStateForTesting(true, true, t0, 30, 60, 15_000.0, 25);
        ChargeSession.persistOpenSession(prefs);

        // Verify data was persisted into SharedPreferences
        assertEquals(t0, prefs.getLong("charge_open_start_ms", 0L));
        assertEquals(30, prefs.getInt("charge_open_start_soc", -1));
        assertEquals(60, prefs.getInt("charge_open_soc_end", -1));
        assertEquals(Double.doubleToRawLongBits(15_000.0), prefs.getLong("charge_open_wh_accum", 0L));

        // Crash occurs: in-memory static state wiped (process death / OTA restart)
        ChargeSession.resetForTesting();
        assertFalse("Session state wiped by crash", ChargeSession.isSessionActive());
        assertFalse(ChargeSession.isCharging());
        assertEquals(0L, ChargeSession.currentStartWallMs());
        assertEquals(0.0, ChargeSession.currentKwh(), 0.001);

        // App starts up, recovers open session from preferences
        boolean recovered = ChargeSession.recoverFromPreferences(prefs, null);
        assertTrue("Session must be recovered from preferences", recovered);

        assertTrue("Session is active after recovery", ChargeSession.isSessionActive());
        assertEquals(t0, ChargeSession.currentStartWallMs());
        assertEquals(30, ChargeSession.currentSocStart());
        assertEquals(60, ChargeSession.currentSocEnd());
        assertEquals(15.0, ChargeSession.currentKwh(), 0.001);

        // Once session completes and grace period finalizes, open session in prefs is cleared
        ChargeSession.clearOpenSession(prefs);
        assertFalse(prefs.contains("charge_open_start_ms"));
        assertFalse(prefs.contains("charge_open_wh_accum"));
    }

    @Test public void replayTelemetryAccumulatesEnergyAndMaxVoltage() {
        // rows: {ts_ms, charge_a, charge_v, battery_pct}
        Object[][] rows = {
            {100_000L, 50.0f, 380.0f, 30},
            {160_000L, 50.0f, 382.0f, 32}, // 60s at 50A*382V = 19100W * (60/3600h) = 318.33 Wh
            {220_000L, 50.0f, 405.0f, 35}, // 60s at 50A*405V = 20250W * (60/3600h) = 337.5 Wh
        };
        double[] result = ChargeSession.replayTelemetry(rows, 0.0, 0, Double.NaN, 30);
        assertEquals(655.83, result[0], 1.0);
        assertEquals(2, (int) result[1]); // 2 intervals
        assertEquals(405.0, result[2], 0.001); // peak voltage
        assertEquals(35, (int) result[3]); // latest SoC
    }

    @Test public void commitOrUpdateSessionCapturesStateBeforeReset() {
        ChargeSession.resetForTesting();
        long t0 = 1_000_000L;
        long endMs = t0 + 1_800_000L; // 30 minutes

        // In-progress session with live fields populated
        ChargeSession.setSessionStateForTesting(true, true, t0, 20, 80, 25_000.0, 40);

        // commitOrUpdateSession captures a snapshot before posting to the DB thread
        ChargeSession.SessionSnapshot snap = ChargeSession.captureSnapshot(endMs);

        // Simulate DB write thread racing: resetSessionState runs on main thread before DB worker executes
        ChargeSession.resetForTesting();

        // Live static fields are now zeroed/reset
        assertEquals(0L, ChargeSession.currentStartWallMs());
        assertEquals(-1, ChargeSession.currentSocStart());
        assertEquals(-1, ChargeSession.currentSocEnd());
        assertEquals(0.0, ChargeSession.currentKwh(), 0.001);
        assertFalse(ChargeSession.isSessionActive());

        // Snapshot and resulting Summary MUST still reflect the captured values, immune to live state reset
        assertEquals(t0, snap.startWallMs);
        assertEquals(endMs, snap.endWallMs);
        assertEquals(20, snap.socStart);
        assertEquals(80, snap.socEnd);
        assertEquals(25.0, snap.kwh, 0.001);
        assertEquals(40, snap.sampleCount);
        assertEquals(50_000.0, snap.avgPowerW, 0.001); // 25 kWh in 0.5 hours = 50 kW

        ChargeSession.Summary summary = snap.toSummary(99L);
        assertEquals(99L, summary.id);
        assertEquals(t0, summary.startWallMs);
        assertEquals(endMs, summary.endWallMs);
        assertEquals(20, summary.socStart);
        assertEquals(80, summary.socEnd);
        assertEquals(25.0, summary.kwh, 0.001);
    }

    @Test public void assertCarThreadSafeWhenNoLooper() {
        // When CarActor is null or handler is null, assertCarThread is a safe no-op
        CarActor.setInstanceForTesting(null);
        ChargeSession.onParkExit(null); // should not throw

        CarActor testActor = new CarActor(null);
        CarActor.setInstanceForTesting(testActor);
        ChargeSession.onParkExit(null); // should not throw
        CarActor.setInstanceForTesting(null);
    }

    private static class FakeSharedPreferences implements SharedPreferences {
        final Map<String, Object> map = new HashMap<>();

        @Override public Map<String, ?> getAll() { return map; }
        @Override public String getString(String key, String def) { Object v = map.get(key); return v instanceof String ? (String) v : def; }
        @Override public Set<String> getStringSet(String key, Set<String> def) { return def; }
        @Override public int getInt(String key, int def) { Object v = map.get(key); return v instanceof Integer ? (Integer) v : def; }
        @Override public long getLong(String key, long def) { Object v = map.get(key); return v instanceof Long ? (Long) v : def; }
        @Override public float getFloat(String key, float def) { Object v = map.get(key); return v instanceof Float ? (Float) v : def; }
        @Override public boolean getBoolean(String key, boolean def) { Object v = map.get(key); return v instanceof Boolean ? (Boolean) v : def; }
        @Override public boolean contains(String key) { return map.containsKey(key); }
        @Override public Editor edit() { return new FakeEditor(map); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        static class FakeEditor implements Editor {
            final Map<String, Object> map;
            final Map<String, Object> pending = new HashMap<>();
            final Set<String> removals = new HashSet<>();

            FakeEditor(Map<String, Object> map) { this.map = map; }

            @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> values) { return this; }
            @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            @Override public Editor remove(String key) { removals.add(key); pending.remove(key); return this; }
            @Override public Editor clear() { map.clear(); pending.clear(); removals.clear(); return this; }
            @Override public boolean commit() { apply(); return true; }
            @Override public void apply() {
                for (String k : removals) map.remove(k);
                map.putAll(pending);
            }
        }
    }
}
