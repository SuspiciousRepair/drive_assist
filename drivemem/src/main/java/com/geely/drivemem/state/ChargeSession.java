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
        EntityBus.subscribe("car.is_charging", (key, reading) -> {
            if (reading.status == CarActor.Reading.Status.OK && reading.value instanceof Integer)
                onChargingEdge(app, (Integer) reading.value == 1);
        });
        EntityBus.subscribe("telemetry.tick", (key, reading) -> {
            if (reading.status == CarActor.Reading.Status.OK) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) reading.value;
                onTelemetryTick(data);
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
        public final double kwh, avgPowerW;
        public final int samples;
        public final double odoStart;   // km, -1 if unknown — for a future "km driven since" stat
        public final Double cost;       // nullable: user-inputted total cost in currency units
        public final boolean dismissed; // whether the user dismissed the dashboard card

        public Summary(long startWallMs, long endWallMs, int socStart, int socEnd,
                double kwh, double avgPowerW, int samples, double odoStart) {
            this(-1, startWallMs, endWallMs, socStart, socEnd, kwh, avgPowerW, samples, odoStart, null, false);
        }

        public Summary(long id, long startWallMs, long endWallMs, int socStart, int socEnd,
                double kwh, double avgPowerW, int samples, double odoStart, Double cost, boolean dismissed) {
            this.id = id;
            this.startWallMs = startWallMs; this.endWallMs = endWallMs;
            this.socStart = socStart; this.socEnd = socEnd;
            this.kwh = kwh; this.avgPowerW = avgPowerW; this.samples = samples;
            this.odoStart = odoStart;
            this.cost = cost;
            this.dismissed = dismissed;
        }

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

    public static final long CHARGE_GRACE_PERIOD_MS = 180_000L;
    public static final double MIN_CHARGE_KWH = 0.05;
    public static final long MIN_CHARGE_DURATION_MS = 60_000L;

    // Ongoing-session state. Only ever touched from TelemetryService's own
    // loop thread / CarActor's serialized HandlerThread.
    private static long startWallMs = 0, pauseWallMs = 0, lastSampleMonoMs = 0, startSampleId = -1;
    private static int socStart = -1, socEnd = -1;
    private static double whAccum = 0;   // running energy, Wh
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
                sampleCount = 0;
                odoStart = -1;
                currentSessionRowId = -1;
                currentCost = null;
                wasCharging = true;
                sessionActive = true;
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
            }
            Log.i(TAG, "chargesession: charge stopped, 180s grace period started");
        }
    }

    private static void commitOrUpdateSession(Context ctx, long endWallMs) {
        long durationMs = Math.max(0, endWallMs - startWallMs);
        double kwh = whAccum / 1000.0;
        double durationH = durationMs / 3_600_000.0;
        double avgPowerW = (durationH > 0) ? whAccum / durationH : 0;
        if (ctx == null) {
            long rowId = currentSessionRowId > 0 ? currentSessionRowId : 1L;
            currentSessionRowId = rowId;
            Summary complete = new Summary(rowId, startWallMs, endWallMs, socStart, socEnd,
                kwh, avgPowerW, sampleCount, odoStart, currentCost, false);
            Listener l = listener;
            if (l != null) l.onSession(complete);
            ProgressListener pl = progressListener;
            if (pl != null) pl.onCompleted(complete);
            return;
        }
        final long endSampleId = CarDb.get(ctx).latestSampleId();
        CarDb.get(ctx).write(() -> {
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put("start_ms", startWallMs);
                v.put("end_ms", endWallMs);
                v.put("start_sample_id", startSampleId);
                v.put("end_sample_id", endSampleId);
                v.put("soc_start", socStart);
                v.put("soc_end", socEnd);
                v.put("kwh", kwh);
                v.put("avg_power_w", avgPowerW);
                v.put("samples", sampleCount);
                if (odoStart >= 0) v.put("odo_start_km", odoStart);
                v.put("dismissed", 0);
                if (currentCost != null) v.put("cost", currentCost);

                long rowId = currentSessionRowId;
                if (rowId <= 0) {
                    rowId = CarDb.get(ctx).db().insert("charge_session", null, v);
                    currentSessionRowId = rowId;
                } else {
                    CarDb.get(ctx).db().update("charge_session", v, "id = ?", new String[]{String.valueOf(rowId)});
                }

                Summary complete = new Summary(rowId, startWallMs, endWallMs, socStart, socEnd,
                    kwh, avgPowerW, sampleCount, odoStart, currentCost, false);
                Listener l = listener;
                if (l != null) l.onSession(complete);
                ProgressListener pl = progressListener;
                if (pl != null) pl.onCompleted(complete);
                EntityBus.publish("charge.completed", CarActor.Reading.ok(complete));
            } catch (Throwable t) {
                Log.w(TAG, "chargesession commitOrUpdateSession: " + t);
            }
        });
    }

    public static void finalizeGracePeriod(Context ctx) {
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

        resetSessionState();
    }

    public static boolean finalizeSession(Context ctx) {
        finalizeGracePeriod(ctx);
        return true;
    }

    public static boolean finalizeSession(Context ctx, long now) {
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
    // a session is active per the fast edge above. No longer decides
    // whether a session is starting or ending itself; only integrates
    // energy and refreshes the live progress display.
    public static void onTelemetryTick(Map<String, Object> data) {
        if (!wasCharging) return;
        Integer soc = asInt(data.get("battery"));
        Float a = asFloat(data.get("charge_a"));
        Float v = asFloat(data.get("charge_v"));
        Float odo = asFloat(data.get("odometer"));
        long nowMono = SystemClock.elapsedRealtime();

        if (soc != null) socEnd = soc;
        if (odoStart < 0 && odo != null) odoStart = odo;
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
            "SELECT id, start_ms, end_ms, soc_start, soc_end, kwh, avg_power_w, samples, odo_start_km, cost, dismissed "
          + "FROM charge_session ORDER BY start_ms ASC", null);
        try {
            while (c.moveToNext()) {
                Double cost = c.isNull(9) ? null : c.getDouble(9);
                boolean dismissed = c.getInt(10) != 0;
                out.add(new Summary(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getInt(4),
                    c.getDouble(5), c.getDouble(6), c.getInt(7), c.getDouble(8), cost, dismissed));
            }
        } finally { c.close(); }
        return out;
    }

    /** Returns the most recent completed charge that has not been dismissed yet. */
    public static Summary getLatestUndismissed(Context ctx) {
        android.database.Cursor c = CarDb.get(ctx).db().rawQuery(
            "SELECT id, start_ms, end_ms, soc_start, soc_end, kwh, avg_power_w, samples, odo_start_km, cost, dismissed "
          + "FROM charge_session WHERE dismissed = 0 ORDER BY end_ms DESC LIMIT 1", null);
        try {
            if (c.moveToFirst()) {
                Double cost = c.isNull(9) ? null : c.getDouble(9);
                boolean dismissed = c.getInt(10) != 0;
                return new Summary(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getInt(4),
                    c.getDouble(5), c.getDouble(6), c.getInt(7), c.getDouble(8), cost, dismissed);
            }
        } finally { c.close(); }
        return null;
    }

    /** Updates the recorded monetary cost of a completed charge session. */
    public static void updateCost(Context ctx, long id, double cost) {
        if (id == currentSessionRowId) {
            currentCost = cost;
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
                EntityBus.publish("charge.cost_updated", CarActor.Reading.ok(id));
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
                EntityBus.publish("charge.dismissed", CarActor.Reading.ok(id));
            } catch (Throwable t) {
                Log.w(TAG, "chargesession dismissSession: " + t);
            }
        });
    }
}
