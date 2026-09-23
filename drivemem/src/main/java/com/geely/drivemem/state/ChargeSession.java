package com.geely.drivemem.state;

import com.geely.drivemem.R;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.controls.TurboMode;
import com.geely.drivemem.services.TelemetryService;
import com.geely.drivemem.util.Clips;
import com.geely.drivemem.util.DbMigration;
import com.geely.drivemem.util.Diagnostics;

import android.content.Context;
import android.content.SharedPreferences; // pii: allow (17-char identifier, not a VIN)
import android.database.Cursor;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/** Tracks one charging session at a time using CarActor's telemetry tick.
 * Bounded by 0->1 (start) and 1->0 (end) edge on the charging flag; integrates
 * charge_a, charge_v, and battery data from each tick between edges. Completed
 * sessions are stored in CarDb's charge_session table (migrated from legacy
 * flat file). readLog() provides access to charging history. */
public final class ChargeSession {
    static final String TAG = CarAccess.TAG;
    // Legacy flat file name (migration only; data now in CarDb.charge_session).
    static final String NAME = "charge.log";

    private static final SimpleDateFormat FMT =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    public interface Listener { void onSession(Summary s); }
    private static volatile Listener listener;
    public static void setListener(Listener l) { listener = l; }

    private static volatile boolean subscribed = false;

    /** Subscribes to charging state and telemetry ticks; idempotent. */
    public static synchronized void ensureSubscribed(Context ctx) {
        if (subscribed) return;
        subscribed = true;
        Context app = ctx.getApplicationContext();
        recoverOpenSession(app);
        EntityBus.subscribe("car.is_charging", (key, reading) -> {
            if (reading.status != CarActor.Reading.Status.OK || !(reading.value instanceof Integer)) return;
            boolean charging = (Integer) reading.value == 1;
            onChargingEdge(app, charging);
        });
        EntityBus.subscribe("telemetry.tick", (key, reading) -> {
            if (reading.status == CarActor.Reading.Status.OK) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) reading.value;
                onTelemetryTick(app, data);
            }
        });
        CarState.ensureSubscribed();
        CarState.addListener(parked -> {
            if (!parked) onParkExit(app);
        });
    }

    // Live updates while a session is in progress — separate from Listener,
    // which only fires once, at the end. A card wanting to show "charging
    // now" subscribes here; onIdle() fires whenever there is no active
    // session, so the card knows to hide without waiting for a 1->0 edge it
    // may never see (e.g. cold app start with the car not charging).
    public interface ProgressListener {
        void onProgress(int socStart, int socNow, long startWallMs, long nowWallMs);
        default void onCompleted(Summary s) {}
        void onIdle();
    }
    private static volatile ProgressListener progressListener;
    public static void setProgressListener(ProgressListener l) { progressListener = l; }

    public static final class Summary {
        public final long id;
        public final long startWallMs, endWallMs;
        public final int socStart, socEnd;
        public final double kwh, avgPowerW, maxChargeV;
        public final int samples;
        public final double odoStart;   // km, -1 if unknown — for a future "km driven since" stat
        public final Double cost;       // nullable: user-inputted total cost in currency units
        public final boolean dismissed; // whether the user dismissed the dashboard card

        public Summary(long startWallMs, long endWallMs, int socStart, int socEnd,
                double kwh, double avgPowerW, int samples, double odoStart) {
            this(-1, startWallMs, endWallMs, socStart, socEnd, kwh, avgPowerW,
                Double.NaN, samples, odoStart, null, false);
        }

        public Summary(long id, long startWallMs, long endWallMs, int socStart, int socEnd,
                double kwh, double avgPowerW, int samples, double odoStart, Double cost, boolean dismissed) {
            this(id, startWallMs, endWallMs, socStart, socEnd, kwh, avgPowerW,
                Double.NaN, samples, odoStart, cost, dismissed);
        }

        public Summary(long id, long startWallMs, long endWallMs, int socStart, int socEnd,
                double kwh, double avgPowerW, double maxChargeV, int samples,
                double odoStart, Double cost, boolean dismissed) {
            this.id = id;
            this.startWallMs = startWallMs; this.endWallMs = endWallMs;
            this.socStart = socStart; this.socEnd = socEnd;
            this.kwh = kwh; this.avgPowerW = avgPowerW; this.maxChargeV = maxChargeV;
            this.samples = samples;
            this.odoStart = odoStart;
            this.cost = cost;
            this.dismissed = dismissed;
        }

        /** Pack voltage is the authoritative charging-type discriminator. */
        public boolean isDcfc() { return !Double.isNaN(maxChargeV) && maxChargeV >= 250.0; }
        public boolean hasChargeVoltage() { return !Double.isNaN(maxChargeV) && maxChargeV > 0; }

        public long durationS() { return Math.max(0, (endWallMs - startWallMs) / 1000); }

        // Compact clock-style label: "H:MM" (e.g., "0:45", "2:15").
        // Numbers only, no string resource needed.
        public String durationLabel() {
            long s = durationS();
            long h = s / 3600, m = (s % 3600) / 60;
            return h + ":" + String.format(Locale.US, "%02d", m);
        }

        private static final SimpleDateFormat ROW_FMT =
            new SimpleDateFormat("d MMM  HH:mm", Locale.getDefault());

        // Numbers and dates only, same as Clips.Clip.title() — no words, so
        // no string resource needed here.
        public String title() {
            SimpleDateFormat t = new SimpleDateFormat("HH:mm", Locale.getDefault());
            return ROW_FMT.format(new Date(startWallMs)) + " → " + t.format(new Date(endWallMs));
        }

        // Duration is already on the bar (durationLabel()) — not repeated
        // here. Power in kW, not W, so the row has exactly two units: kWh
        // and kW, matching each other instead of switching scale mid-row.
        public String subtitle(Context ctx) {
            return ctx.getString(R.string.charge_row_subtitle,
                socStart, socEnd, kwh, avgPowerW / 1000.0);
        }

        public String costLabel() {
            if (cost == null) return null;
            return String.format(Locale.getDefault(), "R$ %.2f", cost);
        }

        public String costPerKwhLabel() {
            if (cost == null || kwh <= 0) return null;
            return String.format(Locale.getDefault(), "R$ %.2f/kWh", cost / kwh);
        }
    }

    private static final String PREFS = "drivemem";
    private static final String PREF_CHARGE_OPEN_START_MS = "charge_open_start_ms";
    private static final String PREF_CHARGE_OPEN_START_SOC = "charge_open_start_soc";
    private static final String PREF_CHARGE_OPEN_SOC_END = "charge_open_soc_end";
    private static final String PREF_CHARGE_OPEN_START_SAMPLE_ID = "charge_open_start_sample_id";
    private static final String PREF_CHARGE_OPEN_WH_ACCUM = "charge_open_wh_accum";
    private static final String PREF_CHARGE_OPEN_ROW_ID = "charge_open_row_id";
    private static final String PREF_CHARGE_OPEN_ODO_START = "charge_open_odo_start";
    private static final String PREF_CHARGE_OPEN_MAX_V = "charge_open_max_v";
    private static final String PREF_CHARGE_OPEN_SAMPLE_COUNT = "charge_open_sample_count";
    private static final String PREF_CHARGE_OPEN_COST = "charge_open_cost";

    public static final class SessionSnapshot {
        public final long sessionRowId;
        public final long startWallMs, endWallMs, startSampleId;
        public final int socStart, socEnd, sampleCount;
        public final double kwh, avgPowerW, maxChargeV, odoStart;
        public final Double cost;

        public SessionSnapshot(long sessionRowId, long startWallMs, long endWallMs, long startSampleId,
                int socStart, int socEnd, double kwh, double avgPowerW, double maxChargeV,
                int sampleCount, double odoStart, Double cost) {
            this.sessionRowId = sessionRowId;
            this.startWallMs = startWallMs;
            this.endWallMs = endWallMs;
            this.startSampleId = startSampleId;
            this.socStart = socStart;
            this.socEnd = socEnd;
            this.kwh = kwh;
            this.avgPowerW = avgPowerW;
            this.maxChargeV = maxChargeV;
            this.sampleCount = sampleCount;
            this.odoStart = odoStart;
            this.cost = cost;
        }

        public Summary toSummary(long rowId) {
            return new Summary(rowId, startWallMs, endWallMs, socStart, socEnd,
                kwh, avgPowerW, maxChargeV, sampleCount, odoStart, cost, false);
        }
    }

    public static SessionSnapshot captureSnapshot(long endWallMs) {
        long durationMs = Math.max(0, endWallMs - startWallMs);
        double kwh = whAccum / 1000.0;
        double durationH = durationMs / 3_600_000.0;
        double avgPowerW = (durationH > 0) ? whAccum / durationH : 0;
        return new SessionSnapshot(currentSessionRowId, startWallMs, endWallMs, startSampleId,
            socStart, socEnd, kwh, avgPowerW, maxChargeV, sampleCount, odoStart, currentCost);
    }

    public static final long CHARGE_GRACE_PERIOD_MS = 180_000L;
    public static final double MIN_CHARGE_KWH = 0.05;
    public static final long MIN_CHARGE_DURATION_MS = 60_000L;

    // Ongoing-session state. Only ever touched from TelemetryService's own
    // loop thread / CarActor's serialized HandlerThread.
    private static long startWallMs = 0, pauseWallMs = 0, lastSampleMonoMs = 0, startSampleId = -1;
    private static int socStart = -1, socEnd = -1;
    private static double whAccum = 0;   // running energy, Wh
    private static double maxChargeV = Double.NaN;
    private static double odoStart = -1;
    private static int sampleCount = 0;
    private static boolean wasCharging = false;
    private static boolean sessionActive = false;
    private static long currentSessionRowId = -1;
    private static Double currentCost = null;
    private static Runnable chargeGraceRunnable = null;

    private ChargeSession() {}

    /** Returns whether charging current is actively flowing right now. */
    public static boolean isCharging() { return wasCharging; }
    public static boolean isSessionActive() { return sessionActive; }
    public static boolean isCurrentFlowing() { return wasCharging; }
    public static boolean isChargeGraceScheduled() { return chargeGraceRunnable != null; }
    public static long activeRowId() { return currentSessionRowId; }
    public static int currentSocStart() { return socStart; }
    public static int currentSocEnd() { return socEnd; }
    public static long currentStartWallMs() { return startWallMs; }
    public static long currentPauseWallMs() { return pauseWallMs; }
    public static double currentKwh() { return whAccum / 1000.0; }

    // Nameplate pack capacity: 39.6 kWh (used for time-to-full estimation).
    // See field-catalog.md for details.
    private static final double CAPACITY_WH = 39_600;

    /** Estimates time to 100% based on observed charge rate.
     * Returns null if insufficient data; 0 if already at 100%. */
    public static Long estimateRemainingMs(int socNow, long elapsedMs) {
        if (socNow >= 100) return 0L;
        if (whAccum <= 0 || elapsedMs <= 0) return null;
        double avgPowerW = whAccum / (elapsedMs / 3_600_000.0);
        if (avgPowerW <= 0) return null;
        double remainingWh = (100 - socNow) / 100.0 * CAPACITY_WH;
        return (long) (remainingWh / avgPowerW * 3_600_000.0);
    }

    /** Debug helper: returns in-progress session state. */
    public static String debugState() {
        return "sessionActive=" + sessionActive
            + " wasCharging=" + wasCharging
            + " inGrace=" + (chargeGraceRunnable != null)
            + " startWallMs=" + startWallMs
            + " socStart=" + socStart + " socEnd=" + socEnd
            + " whAccum=" + String.format(Locale.US, "%.3f", whAccum)
            + " samples=" + sampleCount;
    }

    public static void onParkExit(Context ctx) {
        CarActor.assertCarThread();
        if (chargeGraceRunnable != null || (sessionActive && !wasCharging)) {
            Log.i(TAG, "chargesession: car shifted out of Park during pause — finalizing session immediately");
            finalizeGracePeriod(ctx);
        } else if (sessionActive && wasCharging) {
            Log.i(TAG, "chargesession: car shifted out of Park while charging — finalizing session immediately");
            wasCharging = false;
            pauseWallMs = System.currentTimeMillis();
            commitOrUpdateSession(ctx, pauseWallMs);
            finalizeGracePeriod(ctx);
        }
    }

    // Fast edge detection — from CarActor's dedicated "car.is_charging"
    // poll (2s, current-derived, independent of the 15s telemetry cadence).
    // Handles session start/stop bookkeeping and the onProgress/onIdle/
    // onSession notifications that make the live card show/hide promptly.
    public static void onChargingEdge(Context ctx, boolean charging) {
        onChargingEdge(ctx, charging, System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    public static void onChargingEdge(Context ctx, boolean charging, long nowWallMs, long nowMonoMs) {
        CarActor.assertCarThread();
        if (charging == wasCharging) return;   // no edge, nothing to do
        if (charging) {
            // 0 -> 1
            if (chargeGraceRunnable != null || (sessionActive && currentSessionRowId > 0)) {
                if (chargeGraceRunnable != null && ctx != null) {
                    CarActor.get(ctx).cancelOnCarThread(chargeGraceRunnable);
                }
                chargeGraceRunnable = null;
                pauseWallMs = 0;
                lastSampleMonoMs = nowMonoMs;
                wasCharging = true;
                sessionActive = true;
                if (ctx != null) persistOpenSession(ctx);
                Log.i(TAG, "chargesession: charge resumed within grace period, merging into session id=" + currentSessionRowId);
                ProgressListener pl = progressListener;
                if (pl != null) pl.onProgress(socStart, socEnd, startWallMs, nowWallMs);
            } else {
                startWallMs = nowWallMs;
                pauseWallMs = 0;
                startSampleId = (ctx != null) ? CarDb.get(ctx).latestSampleId() : -1;
                lastSampleMonoMs = nowMonoMs;
                CarActor.Reading r = (ctx != null) ? CarActor.get(ctx).get("telemetry.battery") : null;
                Integer soc = (r != null && r.status == CarActor.Reading.Status.OK && r.value instanceof Integer)
                    ? (Integer) r.value : null;
                socStart = (soc != null) ? soc : -1;
                socEnd = socStart;
                whAccum = 0;
                maxChargeV = Double.NaN;
                sampleCount = 0;
                odoStart = -1;
                currentSessionRowId = -1;
                currentCost = null;
                wasCharging = true;
                sessionActive = true;
                if (ctx != null) persistOpenSession(ctx);
                ProgressListener pl = progressListener;
                if (pl != null) pl.onProgress(socStart, socEnd, startWallMs, nowWallMs);
            }
        } else {
            // 1 -> 0: charging stopped. If qualified, commit/update immediately so card displays "Recarga Concluída"
            wasCharging = false;
            pauseWallMs = nowWallMs;
            long durationMs = Math.max(0, pauseWallMs - startWallMs);
            double kwh = whAccum / 1000.0;

            if (isQualified(kwh, durationMs)) {
                commitOrUpdateSession(ctx, pauseWallMs);
            }

            if (chargeGraceRunnable != null && ctx != null) {
                CarActor.get(ctx).cancelOnCarThread(chargeGraceRunnable);
            }
            chargeGraceRunnable = () -> finalizeGracePeriod(ctx);
            if (ctx != null) {
                CarActor.get(ctx).runOnCarThreadDelayed(chargeGraceRunnable, CHARGE_GRACE_PERIOD_MS);
                persistOpenSession(ctx);
            }
            Log.i(TAG, "chargesession: charge stopped, 180s grace period started");
        }
    }

    private static void commitOrUpdateSession(Context ctx, long endWallMs) {
        final SessionSnapshot snap = captureSnapshot(endWallMs);
        if (ctx == null) {
            long rowId = snap.sessionRowId > 0 ? snap.sessionRowId : 1L;
            currentSessionRowId = rowId;
            Summary complete = snap.toSummary(rowId);
            Listener l = listener;
            if (l != null) l.onSession(complete);
            ProgressListener pl = progressListener;
            if (pl != null) pl.onCompleted(complete);
            EntityBus.publish("charge.completed", CarActor.Reading.ok(complete));
            return;
        }
        final long endSampleId = CarDb.get(ctx).latestSampleId();
        CarDb.get(ctx).write(() -> {
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put("start_ms", snap.startWallMs);
                v.put("end_ms", snap.endWallMs);
                v.put("start_sample_id", snap.startSampleId);
                v.put("end_sample_id", endSampleId);
                v.put("soc_start", snap.socStart);
                v.put("soc_end", snap.socEnd);
                v.put("kwh", snap.kwh);
                v.put("avg_power_w", snap.avgPowerW);
                if (!Double.isNaN(snap.maxChargeV)) v.put("max_charge_v", snap.maxChargeV);
                v.put("samples", snap.sampleCount);
                if (snap.odoStart >= 0) v.put("odo_start_km", snap.odoStart);
                v.put("dismissed", 0);
                if (snap.cost != null) v.put("cost", snap.cost);

                long rowId = snap.sessionRowId;
                if (rowId <= 0) {
                    rowId = CarDb.get(ctx).db().insert("charge_session", null, v);
                } else {
                    CarDb.get(ctx).db().update("charge_session", v, "id = ?", new String[]{String.valueOf(rowId)});
                }

                Summary complete = snap.toSummary(rowId);
                android.content.ContentValues event = new android.content.ContentValues();
                event.put("session_id", rowId); event.put("occurred_ms", snap.endWallMs);
                event.put("soc", snap.socEnd); event.put("kwh", snap.kwh);
                CarDb.get(ctx).db().insertWithOnConflict("charge_stop_event", null, event,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE);

                final long finalRowId = rowId;
                CarActor actor = CarActor.get(ctx);
                Runnable publishRunnable = () -> {
                    if (currentSessionRowId <= 0 && sessionActive) {
                        currentSessionRowId = finalRowId;
                        persistOpenSession(ctx);
                    }
                    Listener l = listener;
                    if (l != null) l.onSession(complete);
                    ProgressListener pl = progressListener;
                    if (pl != null) pl.onCompleted(complete);
                    EntityBus.publish("charge.completed", CarActor.Reading.ok(complete));
                };
                if (actor != null) {
                    actor.runOnCarThread(publishRunnable);
                } else {
                    publishRunnable.run();
                }
            } catch (Throwable t) {
                Log.w(TAG, "chargesession commitOrUpdateSession: " + t);
            }
        });
    }

    public static void finalizeGracePeriod(Context ctx) {
        CarActor.assertCarThread();
        if (chargeGraceRunnable != null) {
            if (ctx != null) CarActor.get(ctx).cancelOnCarThread(chargeGraceRunnable);
            chargeGraceRunnable = null;
        }

        if (!sessionActive) return;

        long endWallMs = (pauseWallMs > 0) ? pauseWallMs : System.currentTimeMillis();
        long durationMs = Math.max(0, endWallMs - startWallMs);
        double kwh = whAccum / 1000.0;

        if (isQualified(kwh, durationMs)) {
            if (currentSessionRowId <= 0) {
                commitOrUpdateSession(ctx, endWallMs);
            }
            Log.i(TAG, "chargesession: grace period expired, session finalized id=" + currentSessionRowId);
        } else {
            Log.i(TAG, String.format(Locale.US,
                "Charge discarded (below 0.05 kWh / 60s threshold): kwh=%.3f, duration=%.1fs",
                kwh, durationMs / 1000.0));
            ProgressListener pl = progressListener;
            if (pl != null) pl.onIdle();
        }

        if (ctx != null) clearOpenSession(ctx);
        resetSessionState();
    }

    public static boolean finalizeSession(Context ctx) {
        CarActor.assertCarThread();
        finalizeGracePeriod(ctx);
        return true;
    }

    public static boolean finalizeSession(Context ctx, long now) {
        CarActor.assertCarThread();
        finalizeGracePeriod(ctx);
        return true;
    }

    public static boolean isQualified(double kwh, long durationMs) {
        return (kwh >= MIN_CHARGE_KWH) || (durationMs >= MIN_CHARGE_DURATION_MS);
    }

    private static void resetSessionState() {
        sessionActive = false;
        wasCharging = false;
        chargeGraceRunnable = null;
        startWallMs = 0;
        pauseWallMs = 0;
        lastSampleMonoMs = 0;
        startSampleId = -1;
        socStart = -1;
        socEnd = -1;
        whAccum = 0;
        maxChargeV = Double.NaN;
        sampleCount = 0;
        odoStart = -1;
        currentSessionRowId = -1;
        currentCost = null;
    }

    public static void setSessionStateForTesting(boolean active, boolean flowing, long startMs,
                                                 int socS, int socE, double wh, int samples) {
        sessionActive = active;
        wasCharging = flowing;
        startWallMs = startMs;
        socStart = socS;
        socEnd = socE;
        whAccum = wh;
        sampleCount = samples;
    }

    public static void resetForTesting() {
        resetSessionState();
    }

    // Periodic sampling — from the regular 15s telemetry tick, only while
    // a session is active per the fast edge above. Self-corrects against
    // state drift by cross-checking CarActor's current cached is_charging.
    public static void onTelemetryTick(Map<String, Object> data) {
        onTelemetryTick(null, data);
    }

    public static void onTelemetryTick(Context ctx, Map<String, Object> data) {
        CarActor.assertCarThread();
        CarActor actor = (ctx != null) ? CarActor.get(ctx) : CarActor.get();
        if (actor != null) {
            Boolean cachedCharging = CarActor.chargingFrom(actor.get("car.is_charging"));
            if (cachedCharging != null && cachedCharging != wasCharging) {
                onChargingEdge(ctx, cachedCharging);
            }
        }
        if (!wasCharging) return;
        Integer soc = asInt(data.get("battery"));
        Float a = asFloat(data.get("charge_a"));
        Float v = asFloat(data.get("charge_v"));
        Float odo = asFloat(data.get("odometer"));
        long nowMono = SystemClock.elapsedRealtime();

        if (soc != null) socEnd = soc;
        if (odoStart < 0 && odo != null) odoStart = odo;
        if (v != null && v > 0 && (Double.isNaN(maxChargeV) || v > maxChargeV)) maxChargeV = v;
        // Rectangular integration at the tick's own cadence (whatever it
        // actually was, measured via nowMono - lastSampleMonoMs — not
        // assumed): this tick's power held for the time since the last
        // one. Good enough for a session lasting tens of minutes+; not
        // trying to be a lab instrument.
        if (a != null && v != null && lastSampleMonoMs != 0) {
            double hours = (nowMono - lastSampleMonoMs) / 3_600_000.0;
            whAccum += a * v * hours;
            sampleCount++;
        }
        lastSampleMonoMs = nowMono;
        if (ctx != null) persistOpenSession(ctx);

        ProgressListener pl = progressListener;
        if (pl != null) pl.onProgress(socStart, socEnd, startWallMs, System.currentTimeMillis());
    }

    private static Integer asInt(Object o) {
        if (o instanceof Integer) return (Integer) o;
        if (o instanceof Float) return Math.round((Float) o);
        return null;
    }

    private static Float asFloat(Object o) {
        if (o instanceof Float) return (Float) o;
        if (o instanceof Integer) return ((Integer) o).floatValue();
        return null;
    }

    // Kept only so DbMigration can find and parse the old flat file exactly
    // once, on upgrade. Nothing live writes here any more.
    public static File file(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        return new File(dir, NAME);
    }

    public static File rotatedFile(Context ctx) {
        File f = file(ctx);
        return new File(f.getParentFile(), NAME + ".1");
    }

    /** Parses legacy flat-file format into Summaries (migration only).
     * Malformed lines are silently skipped. */
    public static void readOneFile(File f, java.util.List<Summary> out) {
        if (!f.exists()) return;
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) { first = false; continue; }   // header
                Summary s = parseLine(line);
                if (s != null) out.add(s);
            }
        } catch (Throwable t) {
            Log.w(TAG, "chargesession: readOneFile " + f + ": " + t);
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
    }

    private static Summary parseLine(String line) {
        try {
            String[] c = line.split("\t", -1);
            if (c.length < 8) return null;
            long start = FMT.parse(c[0]).getTime();
            long end = FMT.parse(c[1]).getTime();
            int socStart = Integer.parseInt(c[3]);
            int socEnd = Integer.parseInt(c[4]);
            double kwh = Double.parseDouble(c[5]);   // the ctor stores kwh as-is, no unit conversion
            double avgPowerW = Double.parseDouble(c[6]);
            int samples = Integer.parseInt(c[7]);
            // Older log lines (before odo_start existed) have no 9th column —
            // fall back to -1 (unknown) rather than fail the whole row.
            double odoStart = (c.length > 8 && !c[8].isEmpty()) ? Double.parseDouble(c[8]) : -1;
            return new Summary(start, end, socStart, socEnd, kwh, avgPowerW, samples, odoStart);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reads all charging sessions from the database (oldest first). */
    public static java.util.List<Summary> readLog(Context ctx) {
        java.util.List<Summary> out = new java.util.ArrayList<>();
        android.database.Cursor c = CarDb.get(ctx).db().rawQuery(
            "SELECT id, start_ms, end_ms, soc_start, soc_end, kwh, avg_power_w, max_charge_v, samples, odo_start_km, cost, dismissed "
          + "FROM charge_session ORDER BY start_ms ASC", null);
        try {
            while (c.moveToNext()) {
                double maxV = c.isNull(7) ? Double.NaN : c.getDouble(7);
                Double cost = c.isNull(10) ? null : c.getDouble(10);
                boolean dismissed = c.getInt(11) != 0;
                out.add(new Summary(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getInt(4),
                    c.getDouble(5), c.getDouble(6), maxV, c.getInt(8), c.getDouble(9), cost, dismissed));
            }
        } finally { c.close(); }
        return out;
    }

    /** Returns the most recent completed charge that has not been dismissed yet. */
    public static Summary getLatestUndismissed(Context ctx) {
        android.database.Cursor c = CarDb.get(ctx).db().rawQuery(
            "SELECT id, start_ms, end_ms, soc_start, soc_end, kwh, avg_power_w, max_charge_v, samples, odo_start_km, cost, dismissed "
          + "FROM charge_session WHERE dismissed = 0 ORDER BY end_ms DESC LIMIT 1", null);
        try {
            if (c.moveToFirst()) {
                double maxV = c.isNull(7) ? Double.NaN : c.getDouble(7);
                Double cost = c.isNull(10) ? null : c.getDouble(10);
                boolean dismissed = c.getInt(11) != 0;
                return new Summary(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getInt(4),
                    c.getDouble(5), c.getDouble(6), maxV, c.getInt(8), c.getDouble(9), cost, dismissed);
            }
        } finally { c.close(); }
        return null;
    }

    /** Updates the recorded monetary cost of a completed charge session. */
    public static void updateCost(Context ctx, long id, double cost) {
        if (id == currentSessionRowId) {
            currentCost = cost;
            if (ctx != null) persistOpenSession(ctx);
        }
        if (ctx == null) return;
        CarDb.get(ctx).write(() -> {
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put("cost", cost);
                CarDb.get(ctx).db().update("charge_session", v, "id = ?", new String[]{String.valueOf(id)});
                CarDb.get(ctx).db().execSQL(
                    "UPDATE daily_stat SET charge_cost = ("
                  + "  SELECT COALESCE(SUM(cost), 0) FROM charge_session "
                  + "  WHERE date(charge_session.start_ms/1000,'unixepoch','localtime') = daily_stat.date"
                  + ") WHERE date IN ("
                  + "  SELECT date(start_ms/1000,'unixepoch','localtime') FROM charge_session WHERE id = ?"
                  + ")", new Object[]{id});
                CarActor actor = CarActor.get(ctx);
                if (actor != null) {
                    actor.runOnCarThread(() -> EntityBus.publish("charge.cost_updated", CarActor.Reading.ok(id)));
                } else {
                    EntityBus.publish("charge.cost_updated", CarActor.Reading.ok(id));
                }
            } catch (Throwable t) {
                Log.w(TAG, "chargesession updateCost: " + t);
            }
        });
    }

    /** Dismisses a completed charge session from remaining on the main dashboard card. */
    public static void dismissSession(Context ctx, long id) {
        if (ctx == null) return;
        CarDb.get(ctx).write(() -> {
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put("dismissed", 1);
                CarDb.get(ctx).db().update("charge_session", v, "id = ?", new String[]{String.valueOf(id)});
                CarActor actor = CarActor.get(ctx);
                if (actor != null) {
                    actor.runOnCarThread(() -> EntityBus.publish("charge.dismissed", CarActor.Reading.ok(id)));
                } else {
                    EntityBus.publish("charge.dismissed", CarActor.Reading.ok(id));
                }
            } catch (Throwable t) {
                Log.w(TAG, "chargesession dismissSession: " + t);
            }
        });
    }

    public static void recoverOpenSession(Context ctx) {
        if (ctx == null) return;
        try {
            recoverOpenSessionUnsafe(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "chargesession recovery: failed, continuing without it: " + t);
        }
    }

    private static void recoverOpenSessionUnsafe(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); // pii: allow (17-char identifier, not a VIN)
        recoverFromPreferences(p, ctx);
    }

    public static boolean recoverFromPreferences(SharedPreferences p, Context ctx) {
        if (p == null) return false;
        long persistedStartMs = p.getLong(PREF_CHARGE_OPEN_START_MS, -1);
        if (persistedStartMs <= 0) return false;

        int persistedSocStart = p.getInt(PREF_CHARGE_OPEN_START_SOC, -1);
        int persistedSocEnd = p.getInt(PREF_CHARGE_OPEN_SOC_END, persistedSocStart);
        long persistedStartSampleId = p.getLong(PREF_CHARGE_OPEN_START_SAMPLE_ID, -1);
        double persistedWhAccum = Double.longBitsToDouble(p.getLong(PREF_CHARGE_OPEN_WH_ACCUM, 0L));
        long persistedRowId = p.getLong(PREF_CHARGE_OPEN_ROW_ID, -1);
        double persistedOdoStart = Double.longBitsToDouble(p.getLong(PREF_CHARGE_OPEN_ODO_START, Double.doubleToRawLongBits(-1.0)));
        double persistedMaxV = Double.longBitsToDouble(p.getLong(PREF_CHARGE_OPEN_MAX_V, Double.doubleToRawLongBits(Double.NaN)));
        int persistedSampleCount = p.getInt(PREF_CHARGE_OPEN_SAMPLE_COUNT, 0);
        float persistedCost = p.getFloat(PREF_CHARGE_OPEN_COST, -1f);

        sessionActive = true;
        startWallMs = persistedStartMs;
        socStart = persistedSocStart;
        socEnd = persistedSocEnd;
        startSampleId = persistedStartSampleId;
        whAccum = persistedWhAccum;
        currentSessionRowId = persistedRowId;
        odoStart = persistedOdoStart;
        maxChargeV = persistedMaxV;
        sampleCount = persistedSampleCount;
        currentCost = (persistedCost >= 0) ? (double) persistedCost : null;
        lastSampleMonoMs = SystemClock.elapsedRealtime();

        CarActor actor = (ctx != null) ? CarActor.get(ctx) : CarActor.get();
        Boolean cachedCharging = (actor != null) ? CarActor.chargingFrom(actor.get("car.is_charging")) : null;
        if (Boolean.TRUE.equals(cachedCharging)) {
            wasCharging = true;
            pauseWallMs = 0;
            chargeGraceRunnable = null;
        } else {
            wasCharging = false;
            pauseWallMs = System.currentTimeMillis();
            if (chargeGraceRunnable != null && ctx != null && actor != null) {
                actor.cancelOnCarThread(chargeGraceRunnable);
            }
            chargeGraceRunnable = () -> finalizeGracePeriod(ctx);
            if (ctx != null && actor != null) {
                actor.runOnCarThreadDelayed(chargeGraceRunnable, CHARGE_GRACE_PERIOD_MS);
            }
        }

        if (ctx != null && persistedStartSampleId >= 0) {
            try {
                replaySamplesFromDb(ctx, persistedStartSampleId);
            } catch (Throwable t) {
                Log.w(TAG, "charge recovery: replay telemetry failed: " + t);
            }
        }

        Log.i(TAG, String.format(Locale.US,
            "charge recovery: restored open session from %d (wh=%.1f, socStart=%d, socEnd=%d, rowId=%d)",
            startWallMs, whAccum, socStart, socEnd, currentSessionRowId));

        ProgressListener pl = progressListener;
        if (pl != null) pl.onProgress(socStart, socEnd, startWallMs, System.currentTimeMillis());
        return true;
    }

    public static double[] replayTelemetry(Object[][] rows, double prevWh, int prevCount, double prevMaxV, int prevSoc) {
        double rederivedWh = 0;
        int rederivedCount = 0;
        double rederivedMaxV = prevMaxV;
        int latestSoc = prevSoc;
        long lastTs = -1;
        for (Object[] r : rows) {
            long ts = ((Number) r[0]).longValue();
            Float a = r[1] != null ? ((Number) r[1]).floatValue() : null;
            Float v = r[2] != null ? ((Number) r[2]).floatValue() : null;
            if (r[3] != null) latestSoc = ((Number) r[3]).intValue();
            if (v != null && v > 0 && (Double.isNaN(rederivedMaxV) || v > rederivedMaxV)) {
                rederivedMaxV = v;
            }
            if (a != null && v != null && a > 0 && lastTs > 0) {
                double hours = (ts - lastTs) / 3_600_000.0;
                if (hours > 0 && hours < 1.0) {
                    rederivedWh += a * v * hours;
                    rederivedCount++;
                }
            }
            lastTs = ts;
        }
        return new double[]{
            Math.max(prevWh, rederivedWh),
            Math.max(prevCount, rederivedCount),
            rederivedMaxV,
            latestSoc
        };
    }

    private static void replaySamplesFromDb(Context ctx, long fromSampleId) {
        long endSampleId = CarDb.get(ctx).latestSampleId();
        if (endSampleId < fromSampleId) return;
        Cursor c = CarDb.get(ctx).db().rawQuery(
            "SELECT ts_ms, charge_a, charge_v, battery_pct FROM telemetry_sample "
          + "WHERE id BETWEEN ? AND ? ORDER BY id ASC",
            new String[]{String.valueOf(fromSampleId), String.valueOf(endSampleId)});
        try {
            java.util.List<Object[]> rows = new java.util.ArrayList<>();
            while (c.moveToNext()) {
                long ts = c.getLong(0);
                Float a = c.isNull(1) ? null : c.getFloat(1);
                Float v = c.isNull(2) ? null : c.getFloat(2);
                Integer soc = c.isNull(3) ? null : c.getInt(3);
                rows.add(new Object[]{ts, a, v, soc});
            }
            double[] res = replayTelemetry(rows.toArray(new Object[0][]), whAccum, sampleCount, maxChargeV, socEnd);
            whAccum = res[0];
            sampleCount = (int) res[1];
            maxChargeV = res[2];
            socEnd = (int) res[3];
        } finally {
            c.close();
        }
    }

    public static void persistOpenSession(Context ctx) {
        if (ctx == null) return;
        persistOpenSession(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)); // pii: allow (17-char identifier, not a VIN)
    }

    public static void persistOpenSession(SharedPreferences p) {
        if (p == null) return;
        p.edit()
            .putLong(PREF_CHARGE_OPEN_START_MS, startWallMs)
            .putInt(PREF_CHARGE_OPEN_START_SOC, socStart)
            .putInt(PREF_CHARGE_OPEN_SOC_END, socEnd)
            .putLong(PREF_CHARGE_OPEN_START_SAMPLE_ID, startSampleId)
            .putLong(PREF_CHARGE_OPEN_WH_ACCUM, Double.doubleToRawLongBits(whAccum))
            .putLong(PREF_CHARGE_OPEN_ROW_ID, currentSessionRowId)
            .putLong(PREF_CHARGE_OPEN_ODO_START, Double.doubleToRawLongBits(odoStart))
            .putLong(PREF_CHARGE_OPEN_MAX_V, Double.doubleToRawLongBits(maxChargeV))
            .putInt(PREF_CHARGE_OPEN_SAMPLE_COUNT, sampleCount)
            .putFloat(PREF_CHARGE_OPEN_COST, currentCost != null ? currentCost.floatValue() : -1f)
            .apply();
    }

    public static void clearOpenSession(Context ctx) {
        if (ctx == null) return;
        clearOpenSession(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)); // pii: allow (17-char identifier, not a VIN)
    }

    public static void clearOpenSession(SharedPreferences p) {
        if (p == null) return;
        p.edit()
            .remove(PREF_CHARGE_OPEN_START_MS)
            .remove(PREF_CHARGE_OPEN_START_SOC)
            .remove(PREF_CHARGE_OPEN_SOC_END)
            .remove(PREF_CHARGE_OPEN_START_SAMPLE_ID)
            .remove(PREF_CHARGE_OPEN_WH_ACCUM)
            .remove(PREF_CHARGE_OPEN_ROW_ID)
            .remove(PREF_CHARGE_OPEN_ODO_START)
            .remove(PREF_CHARGE_OPEN_MAX_V)
            .remove(PREF_CHARGE_OPEN_SAMPLE_COUNT)
            .remove(PREF_CHARGE_OPEN_COST)
            .apply();
    }
}
