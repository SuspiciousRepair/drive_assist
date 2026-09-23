package com.geely.drivemem.state;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.car.Telemetry;
import com.geely.drivemem.sensors.EnergyIntegrator;
import com.geely.drivemem.sensors.EnergySource;
import com.geely.drivemem.sensors.GpsReader;
import com.geely.drivemem.util.Modes;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences; // pii: allow (17-char identifier, not a VIN)
import android.database.Cursor;
import android.util.Log;

import java.util.Locale;
import java.util.Map;

/** Records each driving trip (park to park). Bounded by gear changes,
 * accumulates telemetry in memory, writes one row at trip end. Stores only
 * sample ID pointers; telemetry data is joined from telemetry_sample at
 * read time. Includes ascent/descent meters from GPS altitude (filtered for noise).
 * Implements a 75-second park debounce grace period and 100m / 45s qualification filter
 * to avoid micro-trip fragmentation. */
public final class TripSession {
    static final String TAG = CarAccess.TAG;

    // GPS altitude is noisy (vertical fix accuracy is typically worse than
    // horizontal) — deltas smaller than this are treated as jitter, not
    // real elevation change, so a stationary-ish GPS wobble doesn't
    // inflate both ascent and descent over a long drive. A rough estimate,
    // not a precise one; said plainly rather than pretending otherwise.
    static final double ALTITUDE_NOISE_M = 3.0;

    public static final long PARK_GRACE_PERIOD_MS = 75_000L;
    public static final double MIN_TRIP_DISTANCE_KM = 0.1;
    public static final long MIN_TRIP_DRIVE_DURATION_MS = 45_000L;

    // A trip in progress lives ONLY in the static fields below — nothing about
    // it touches disk until finalizeTrip() writes the summary row. That's fine
    // for a normal park-to-park drive, but a process restart mid-trip (an app
    // reinstall during a brief Park is what actually happened, on 2026-09-12:
    // ~34 minutes and 12km of driving before the install were never written,
    // because the whole in-progress trip lived only in these fields) wipes
    // every one of them with nothing to show for the drive already underway.
    // These prefs are the fix: just enough of the trip's IDENTITY (not its
    // accumulators) to find it again in telemetry_sample after a restart.
    // See persistOpenTrip/recoverOpenTrip below.
    private static final String PREFS = "drivemem";
    private static final String PREF_OPEN_START_MS = "trip_open_start_ms";
    private static final String PREF_OPEN_START_SAMPLE_ID = "trip_open_start_sample_id";
    private static final String PREF_OPEN_START_ODO_KM = "trip_open_start_odo_km";
    private static final String PREF_OPEN_START_SOC = "trip_open_start_soc";

    private static volatile boolean subscribed = false;

    /** Subscribes to gear and telemetry changes to track trips; idempotent. */
    public static synchronized void ensureSubscribed(Context ctx) {
        if (subscribed) return;
        subscribed = true;
        Context app = ctx.getApplicationContext();
        EnergyIntegrator.ensureSubscribed(app);
        recoverOpenTrip(app);
        EntityBus.subscribe("car.gear", (key, reading) -> {
            if (reading.status == CarActor.Reading.Status.OK && reading.value instanceof Integer) {
                onGear(app, (Integer) reading.value);
            }
        });
        EntityBus.subscribe("telemetry.tick", (key, reading) -> {
            if (reading.status == CarActor.Reading.Status.OK) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) reading.value;
                onTelemetryTick(app, data);
            }
        });
    }

    // Only ever touched from CarActor's own thread — both car.gear and
    // telemetry.tick are delivered there, one at a time, same discipline
    // ChargeSession's fields already rely on.
    private static boolean wasParked = true; // matches CarState's own default guess
    private static volatile boolean tripActive = false;
    private static volatile long startMs = 0;
    private static volatile long startSampleId = -1;
    private static volatile double startOdoKm = -1;
    private static volatile int startSoc = -1;
    // Centralized no-OBD fallback state. This is deliberately a start-to-now
    // net-energy estimate, never an invented instantaneous power reading.
    private static volatile double startSocRaw = Double.NaN;
    private static volatile double currentSocRaw = Double.NaN;
    private static volatile Double lastAltitude = null;
    private static volatile double ascentM = 0;
    private static volatile double descentM = 0;

    private static volatile long currentDriveSegmentStartMs = 0;
    private static volatile long drivingDurationMs = 0;
    private static Runnable parkGraceRunnable = null;
    private static Double testCurrentOdoKm = null;

    public static void onGear(Context ctx, int gear) {
        onGear(ctx, gear, System.currentTimeMillis());
    }

    public static void onGear(Context ctx, int gear, long now) {
        boolean nowParked = (gear == Modes.GEAR_PARK_ADAPTED);
        if (nowParked == wasParked) return; // no edge

        // The one place car.gear becomes "parked or not" — CarState relays
        // this to everyone else (ChargeSession included) via its own
        // listeners, so nothing downstream needs its own gear subscription.
        CarState.reportParked(nowParked);

        if (!nowParked) {
            // Parked -> driving: cancel pending park grace finalizer if any
            if (parkGraceRunnable != null) {
                if (ctx != null) {
                    CarActor.get(ctx).cancelOnCarThread(parkGraceRunnable);
                }
                parkGraceRunnable = null;
            }

            if (tripActive) {
                // Continue existing trip seamlessly (stitch/merge segments)
                currentDriveSegmentStartMs = now;
            } else {
                // Any open charge session already got force-closed by
                // CarState.reportParked() above — its onParkExit listener runs
                // synchronously, before this line — so there is nothing to do
                // here for that any more.
                startNewTrip(ctx, now);
            }
        } else {
            // Driving -> parked: enter park grace period
            if (tripActive) {
                drivingDurationMs += Math.max(0, now - currentDriveSegmentStartMs);
                currentDriveSegmentStartMs = 0;
                if (parkGraceRunnable != null && ctx != null) {
                    CarActor.get(ctx).cancelOnCarThread(parkGraceRunnable);
                }
                parkGraceRunnable = () -> finalizeTrip(ctx);
                if (ctx != null) {
                    CarActor.get(ctx).runOnCarThreadDelayed(parkGraceRunnable, PARK_GRACE_PERIOD_MS);
                }
            }
        }
        wasParked = nowParked;
    }

    private static void startNewTrip(Context ctx, long now) {
        tripActive = true;
        startMs = now;
        currentDriveSegmentStartMs = now;
        drivingDurationMs = 0;
        startSampleId = (ctx != null) ? CarDb.get(ctx).latestSampleId() : -1;
        lastAltitude = null;
        ascentM = 0;
        descentM = 0;
        if (ctx != null) {
            CarActor.Reading odoR = CarActor.get(ctx).get("telemetry.odometer");
            startOdoKm = (odoR != null && odoR.status == CarActor.Reading.Status.OK && odoR.value instanceof Number)
                ? ((Number) odoR.value).doubleValue() : -1;
            CarActor.Reading battR = CarActor.get(ctx).get("telemetry.battery");
            startSoc = (battR != null && battR.status == CarActor.Reading.Status.OK && battR.value instanceof Integer)
                ? (Integer) battR.value : -1;
        } else {
            startOdoKm = -1;
            startSoc = -1;
        }
        CarActor.Reading rawSocR = ctx != null ? CarActor.get(ctx).get("telemetry.battery_raw_pct") : null;
        startSocRaw = (rawSocR != null && rawSocR.status == CarActor.Reading.Status.OK
            && rawSocR.value instanceof Number) ? ((Number) rawSocR.value).doubleValue() : Double.NaN;
        currentSocRaw = startSocRaw;
        EnergyIntegrator.startTrip();
        if (ctx != null) persistOpenTrip(ctx);
    }

    /** Closes the trip open right now and, if still driving, immediately opens
     * a fresh one so the rest of THIS drive keeps being tracked. For Valet
     * being turned off mid-drive: unlike the Park-side call to finalizeTrip()
     * (parkGraceRunnable above), there is no upcoming Park->Drive edge here to
     * start the next trip -- wasParked is already false and stays false, so
     * without this, onGear() would never see an edge again until the car
     * actually parks, and everything driven between "Valet off" and that park
     * would go completely untracked, not merely mislabeled. A no-op (like
     * finalizeTrip) if no trip is open. */
    public static synchronized boolean splitTrip(Context ctx, long now) {
        if (!tripActive) return false;
        boolean wasDriving = !wasParked;
        boolean qualified = finalizeTrip(ctx, now);
        if (wasDriving) startNewTrip(ctx, now);
        return qualified;
    }

    private static void onTelemetryTick(Context ctx, Map<String, Object> data) {
        if (!tripActive) return; // only track while a trip is actually open
        boolean backfilled = false;
        if (startOdoKm < 0 && data.containsKey("odometer")) {
            Object o = data.get("odometer");
            if (o instanceof Number) { startOdoKm = ((Number) o).doubleValue(); backfilled = true; }
        }
        if (startSoc < 0 && data.containsKey("battery")) {
            Object b = data.get("battery");
            if (b instanceof Integer) { startSoc = (Integer) b; backfilled = true; }
        }
        Object raw = data.get("battery_raw_pct");
        boolean charging = data.get("is_charging") instanceof Integer && (Integer) data.get("is_charging") != 0;
        if (!wasParked && !charging && raw instanceof Number) {
            currentSocRaw = ((Number) raw).doubleValue();
            if (!Double.isFinite(startSocRaw)) startSocRaw = currentSocRaw;
        }
        // Re-persist once the identity fields are actually known — a restart
        // right after trip start but before the first odometer/SoC reading
        // arrived would otherwise recover a marker with startOdoKm still -1.
        if (backfilled && ctx != null) persistOpenTrip(ctx);
        if (wasParked) return; // stationary in park debounce: don't accumulate altitude jitter
        double[] loc = GpsReader.read(ctx);
        if (loc == null) return;
        double alt = loc[2];
        if (lastAltitude != null) {
            double delta = alt - lastAltitude;
            if (delta > ALTITUDE_NOISE_M) ascentM += delta;
            else if (delta < -ALTITUDE_NOISE_M) descentM += -delta;
            else return; // within noise band — don't move the reference point either
        }
        lastAltitude = alt;
    }

    /** Net energy since the trip began from raw dashboard SoC; NaN until both
     * endpoints exist. Used only when direct OBD integration has no samples. */
    public static double currentSocEstimatedNetKwh() {
        if (!tripActive || !Double.isFinite(startSocRaw) || !Double.isFinite(currentSocRaw)) return Double.NaN;
        return (startSocRaw - currentSocRaw) * Telemetry.BATTERY_CAPACITY_KWH / 100.0;
    }

    public static boolean finalizeTrip(Context ctx) {
        return finalizeTrip(ctx, System.currentTimeMillis());
    }

    public static boolean finalizeTrip(Context ctx, long now) {
        if (parkGraceRunnable != null) {
            if (ctx != null) {
                CarActor.get(ctx).cancelOnCarThread(parkGraceRunnable);
            }
            parkGraceRunnable = null;
        }

        if (!tripActive) return false;

        if (!wasParked && currentDriveSegmentStartMs > 0) {
            drivingDurationMs += Math.max(0, now - currentDriveSegmentStartMs);
            currentDriveSegmentStartMs = 0;
        }

        final EnergyIntegrator.TripSnapshot ts = EnergyIntegrator.endTrip();
        final double socEstimatedNetKwh = currentSocEstimatedNetKwh();

        double currentOdoKm = -1;
        if (testCurrentOdoKm != null) {
            currentOdoKm = testCurrentOdoKm;
        } else if (ctx != null) {
            CarActor.Reading odoR = CarActor.get(ctx).get("telemetry.odometer");
            if (odoR != null && odoR.status == CarActor.Reading.Status.OK && odoR.value instanceof Number) {
                currentOdoKm = ((Number) odoR.value).doubleValue();
            }
            if (currentOdoKm < 0) {
                try {
                    Cursor c = CarDb.get(ctx).db().rawQuery(
                        "SELECT odo_km FROM telemetry_sample WHERE odo_km IS NOT NULL ORDER BY id DESC LIMIT 1", null);
                    try {
                        if (c.moveToFirst()) currentOdoKm = c.getDouble(0);
                    } finally {
                        c.close();
                    }
                } catch (Throwable t) {
                    // ignore
                }
            }
        }

        double distanceKm = (currentOdoKm >= startOdoKm && startOdoKm >= 0) ? (currentOdoKm - startOdoKm) : 0;
        boolean qualified = isQualified(distanceKm, drivingDurationMs);

        if (qualified) {
            if (startSampleId >= 0 && ctx != null) {
                final long endMs = now;
                final long endSampleId = CarDb.get(ctx).latestSampleId();
                final EnergySource tripSource = classifyTripEnergySource(ctx, startMs, endMs, ts);
                final ContentValues v = new ContentValues();
                v.put("start_ms", startMs);
                v.put("end_ms", endMs);
                v.put("start_sample_id", startSampleId);
                v.put("end_sample_id", endSampleId);
                v.put("ascent_m", ascentM);
                v.put("descent_m", descentM);
                v.put("spent_kwh", ts.spentKwh);
                v.put("regen_kwh", ts.regenKwh);
                v.put("net_kwh", ts.netKwh);
                v.put("energy_source", tripSource == EnergySource.ESTIMATED ? "SOC_ESTIMATED"
                    : tripSource == EnergySource.MIXED ? "MIXED" : "OBD_MEASURED");
                if (tripSource == EnergySource.ESTIMATED && Double.isFinite(socEstimatedNetKwh)) {
                    v.put("estimated_net_kwh", socEstimatedNetKwh);
                }
                CarDb.get(ctx).write(() -> {
                    try {
                        CarDb.get(ctx).db().insert("trip", null, v);
                    } catch (Throwable t) {
                        Log.w(TAG, "triplog: " + t);
                    }
                });
                EntityBus.publish("trip.completed", CarActor.Reading.ok(v));
            }
        } else {
            Log.i(TAG, String.format(Locale.US,
                "Trip discarded (below 100m / 45s threshold): dist=%.3f km, driving=%.1fs",
                distanceKm, drivingDurationMs / 1000.0));
        }

        if (ctx != null) clearOpenTrip(ctx);
        resetTripState();
        return qualified;
    }

    // ---- Crash/restart recovery (see the PREF_OPEN_* fields' own comment) ----

    private static void persistOpenTrip(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(PREF_OPEN_START_MS, startMs)
            .putLong(PREF_OPEN_START_SAMPLE_ID, startSampleId)
            .putFloat(PREF_OPEN_START_ODO_KM, (float) startOdoKm)
            .putInt(PREF_OPEN_START_SOC, startSoc)
            .apply();
    }

    private static void clearOpenTrip(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(PREF_OPEN_START_MS)
            .remove(PREF_OPEN_START_SAMPLE_ID)
            .remove(PREF_OPEN_START_ODO_KM)
            .remove(PREF_OPEN_START_SOC)
            .apply();
    }

    // Runs once per process start (from ensureSubscribed, before any gear/tick
    // arrives). A leftover marker means the LAST process died with a trip open
    // — normal finalization always clears it first. Restores the trip's
    // identity and replays its ascent/descent and energy from telemetry_sample,
    // which kept recording the whole time regardless of what TripSession's own
    // in-memory accumulators were doing. drivingDurationMs is deliberately left
    // at 0 rather than reconstructed: the distance check (startOdoKm is exact,
    // recovered below) already qualifies the overwhelming majority of real
    // trips, and the remaining sliver — a very short, very slow drive that
    // ALSO happens to restart mid-trip — is an acceptable gap for how rare it
    // is, versus the complexity of replaying per-sample gear timings too.
    private static void recoverOpenTrip(Context ctx) {
        // Runs unconditionally on every app start, before anything else --
        // there is no safe fallback path above this in the call chain
        // (TelemetryService.onStartCommand has none either). Learned the hard
        // way on 2026-09-12: this whole method used to run bare, and a
        // completely unrelated DB problem (a schema-downgrade refusal) turned
        // into an uncaught SQLiteException here, which crashed the service,
        // which got the whole app killed and backed off for an hour. Recovery
        // is a best-effort convenience, not something worth ever bringing the
        // app down over -- any failure here should cost the recovered trip's
        // ascent/descent/energy accuracy at worst, never app startup.
        try {
            recoverOpenTripUnsafe(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "trip recovery: failed, continuing without it: " + t);
        }
    }

    private static void recoverOpenTripUnsafe(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); // pii: allow (17-char identifier, not a VIN)
        long persistedStartMs = p.getLong(PREF_OPEN_START_MS, -1);
        if (persistedStartMs < 0) return; // normal case: no trip was left open

        long persistedStartSampleId = p.getLong(PREF_OPEN_START_SAMPLE_ID, -1);
        double persistedStartOdoKm = p.getFloat(PREF_OPEN_START_ODO_KM, -1f);
        int persistedStartSoc = p.getInt(PREF_OPEN_START_SOC, -1);

        tripActive = true;
        // Recovering an open trip IS proof the car was mid-drive when the
        // process died — sync both the local latch and the shared hub to
        // match, or two things stay stuck: wasParked stays at its default
        // true, so the REAL Park edge that eventually ends this trip reads
        // as "no change" and finalizeTrip() never gets scheduled at all; and
        // CarState stays parked too, so anything hanging off it (charging
        // included) never gets the memo that a drive is already under way.
        wasParked = false;
        CarState.reportParked(false);
        startMs = persistedStartMs;
        startSampleId = persistedStartSampleId;
        startOdoKm = persistedStartOdoKm;
        startSoc = persistedStartSoc;
        currentDriveSegmentStartMs = System.currentTimeMillis();
        drivingDurationMs = 0;
        lastAltitude = null;
        ascentM = 0;
        descentM = 0;

        long endSampleId = CarDb.get(ctx).latestSampleId();
        if (persistedStartSampleId >= 0 && endSampleId >= persistedStartSampleId) {
            double[] ad = replayAscentDescent(readGearAndAltitude(ctx, persistedStartSampleId, endSampleId));
            ascentM = ad[0];
            descentM = ad[1];
        }

        EnergyIntegrator.startTrip();
        double[] energy = sumEnergySince(ctx, persistedStartMs, System.currentTimeMillis());
        EnergyIntegrator.seedTrip(energy[0], energy[1], energy[2]);

        Log.i(TAG, String.format(Locale.US,
            "trip recovery: restored open trip from %d (ascent=%.1fm descent=%.1fm)",
            persistedStartMs, ascentM, descentM));
    }

    private static Object[][] readGearAndAltitude(Context ctx, long startSampleId, long endSampleId) {
        Cursor c = CarDb.get(ctx).db().rawQuery(
            "SELECT gear, altitude_m FROM telemetry_sample WHERE id BETWEEN ? AND ? ORDER BY id",
            new String[]{String.valueOf(startSampleId), String.valueOf(endSampleId)});
        try {
            Object[][] rows = new Object[c.getCount()][2];
            int i = 0;
            while (c.moveToNext()) {
                rows[i][0] = c.isNull(0) ? null : c.getInt(0);
                rows[i][1] = c.isNull(1) ? null : c.getDouble(1);
                i++;
            }
            return rows;
        } finally { c.close(); }
    }

    /** Replays the same altitude noise filter onTelemetryTick uses, over
     * {gear, altitude} rows in sample-id order. Pure/testable — the DB read
     * is kept separate in readGearAndAltitude(). Each row is {Integer gear,
     * Double altitude}, either of which may be null. */
    public static double[] replayAscentDescent(Object[][] gearAltitudeRows) {
        double ascent = 0, descent = 0;
        Double lastAlt = null;
        for (Object[] row : gearAltitudeRows) {
            Integer gear = (Integer) row[0];
            Double alt = (Double) row[1];
            if (gear == null || gear == Modes.GEAR_PARK_ADAPTED) continue; // parked: don't accumulate jitter
            if (alt == null) continue;
            if (lastAlt != null) {
                double delta = alt - lastAlt;
                if (delta > ALTITUDE_NOISE_M) ascent += delta;
                else if (delta < -ALTITUDE_NOISE_M) descent += -delta;
                else continue; // within noise band — don't move the reference point either
            }
            lastAlt = alt;
        }
        return new double[]{ascent, descent};
    }

    // Same fallback formula DailyStatsProvider already uses when a trip row's
    // own energy columns are missing — see queryDaySessions()'s own comment.
    // Gear wins over is_charging whenever gear is known — see
    // DrivingConsumption.isDriving()'s own comment for why.
    private static double[] sumEnergySince(Context ctx, long startMs, long endMs) {
        Cursor c = CarDb.get(ctx).db().rawQuery(
            "SELECT COALESCE(SUM(energy_spent_kwh),0), COALESCE(SUM(energy_regen_kwh),0), "
          + "COALESCE(SUM(energy_net_kwh),0) FROM telemetry_sample "
          + "WHERE ts_ms BETWEEN ? AND ? "
          + "AND (CASE WHEN gear IS NOT NULL THEN gear <> 4 "
          + "     ELSE (is_charging IS NULL OR is_charging = 0) END)",
            new String[]{String.valueOf(startMs), String.valueOf(endMs)});
        try {
            if (c.moveToFirst()) return new double[]{c.getDouble(0), c.getDouble(1), c.getDouble(2)};
        } finally { c.close(); }
        return new double[]{0, 0, 0};
    }

    /** Classifies a finished trip's energy as measured/mixed/estimated by
     * counting how many of its own telemetry_sample rows were OBD2-backed
     * vs SoC-delta-estimated -- replaces the old sampleCount>0 binary check,
     * which tagged a trip fully "measured" even if only one brief window out
     * of an hour-long drive had a real OBD2 reading. Uses the same gear<>4/
     * not-charging filter as sumEnergySince() above, so the label describes
     * exactly the rows that produced this trip's own spent_kwh/net_kwh --
     * not a plain unfiltered scan, which could let a parked trailing tail
     * (Park grace period) skew the ratio away from what actually happened
     * while driving. */
    private static EnergySource classifyTripEnergySource(
            Context ctx, long startMs, long endMs, EnergyIntegrator.TripSnapshot ts) {
        try {
            Cursor c = CarDb.get(ctx).db().rawQuery(
                // battery_temp_c is OBD2-exclusive (see CarDb's v22 migration
                // comment) -- checked directly here, not just via the stored
                // energy_measured flag, so a live-path bug self-heals for every
                // trip finalized from here on, not only rows a one-time
                // migration happened to already reach.
                "SELECT SUM(CASE WHEN energy_measured=1 OR battery_temp_c IS NOT NULL THEN 1 ELSE 0 END), "
              + "       SUM(CASE WHEN energy_measured=0 AND battery_temp_c IS NULL THEN 1 ELSE 0 END) "
              + "FROM telemetry_sample WHERE ts_ms BETWEEN ? AND ? "
              + "AND (CASE WHEN gear IS NOT NULL THEN gear <> 4 "
              + "     ELSE (is_charging IS NULL OR is_charging = 0) END)",
                new String[]{String.valueOf(startMs), String.valueOf(endMs)});
            try {
                if (c.moveToFirst()) {
                    EnergySource s = EnergySource.resolve(c.getLong(0), c.getLong(1));
                    if (s != EnergySource.NO_DATA) return s;
                }
            } finally { c.close(); }
        } catch (Throwable t) {
            Log.w(TAG, "trip energy classification: " + t);
        }
        // Rollout edge case: this trip's rows predate the energy_measured
        // column. Fall back to the old any-vs-none signal.
        return ts.sampleCount > 0 ? EnergySource.MEASURED : EnergySource.ESTIMATED;
    }

    public static boolean isQualified(double distanceKm, long drivingDurationMs) {
        return (distanceKm >= MIN_TRIP_DISTANCE_KM) || (drivingDurationMs >= MIN_TRIP_DRIVE_DURATION_MS);
    }

    private static void resetTripState() {
        tripActive = false;
        parkGraceRunnable = null;
        startMs = 0;
        startSampleId = -1;
        startOdoKm = -1;
        startSoc = -1;
        currentDriveSegmentStartMs = 0;
        drivingDurationMs = 0;
        lastAltitude = null;
        ascentM = 0;
        descentM = 0;
    }

    /** Trip statistics for the last 7 days: count and total driving time. */
    public static final class WeekSummary {
        public final int trips;
        public final long drivingMs;
        WeekSummary(int trips, long drivingMs) { this.trips = trips; this.drivingMs = drivingMs; }
    }

    /** Returns trip statistics for the last 7 days. */
    public static WeekSummary weekSummary(Context ctx) {
        long cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        Cursor c = CarDb.get(ctx).db().rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(end_ms - start_ms), 0) FROM trip "
          + "WHERE start_ms >= ? AND end_ms IS NOT NULL", new String[]{String.valueOf(cutoff)});
        try {
            c.moveToFirst();
            return new WeekSummary(c.getInt(0), c.getLong(1));
        } finally { c.close(); }
    }

    public static boolean isTripActive() { return tripActive; }
    public static long getActiveTripStartMs() { return startMs; }
    public static long getActiveTripStartSampleId() { return startSampleId; }
    public static double getActiveTripAscentM() { return ascentM; }
    public static double getActiveTripDescentM() { return descentM; }
    public static double getActiveTripStartOdoKm() { return startOdoKm; }
    public static int getActiveTripStartSoc() { return startSoc; }

    public static long getDrivingDurationMs() {
        long d = drivingDurationMs;
        if (!wasParked && currentDriveSegmentStartMs > 0) {
            d += Math.max(0, System.currentTimeMillis() - currentDriveSegmentStartMs);
        }
        return d;
    }

    public static long getDrivingDurationMs(long now) {
        long d = drivingDurationMs;
        if (!wasParked && currentDriveSegmentStartMs > 0) {
            d += Math.max(0, now - currentDriveSegmentStartMs);
        }
        return d;
    }

    public static boolean isParkGraceScheduled() { return parkGraceRunnable != null; }

    public static void setTripStateForTesting(boolean active, long startMsVal, double startOdoVal,
                                              long drivingDurationVal, long startSampleIdVal) {
        tripActive = active;
        startMs = startMsVal;
        startOdoKm = startOdoVal;
        drivingDurationMs = drivingDurationVal;
        startSampleId = startSampleIdVal;
    }

    public static void setTestCurrentOdoKm(Double odo) {
        testCurrentOdoKm = odo;
    }

    /** Test-only injection for the centralized raw-SoC estimator. */
    public static void setSocEstimateForTesting(double startPct, double currentPct) {
        tripActive = true;
        startSocRaw = startPct;
        currentSocRaw = currentPct;
    }

    public static void resetForTesting() {
        resetTripState();
        wasParked = true;
        testCurrentOdoKm = null;
        EnergyIntegrator.resetForTesting();
    }

    private TripSession() {}
}
