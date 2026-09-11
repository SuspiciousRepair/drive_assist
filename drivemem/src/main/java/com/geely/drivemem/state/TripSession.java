package com.geely.drivemem.state;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.sensors.EnergyIntegrator;
import com.geely.drivemem.sensors.GpsReader;
import com.geely.drivemem.util.Modes;

import android.content.ContentValues;
import android.content.Context;
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

    private static volatile boolean subscribed = false;

    /** Subscribes to gear and telemetry changes to track trips; idempotent. */
    public static synchronized void ensureSubscribed(Context ctx) {
        if (subscribed) return;
        subscribed = true;
        Context app = ctx.getApplicationContext();
        EnergyIntegrator.ensureSubscribed(app);
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
                // Start a brand new trip
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
                EnergyIntegrator.startTrip();
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

    private static void onTelemetryTick(Context ctx, Map<String, Object> data) {
        if (!tripActive) return; // only track while a trip is actually open
        if (startOdoKm < 0 && data.containsKey("odometer")) {
            Object o = data.get("odometer");
            if (o instanceof Number) startOdoKm = ((Number) o).doubleValue();
        }
        if (startSoc < 0 && data.containsKey("battery")) {
            Object b = data.get("battery");
            if (b instanceof Integer) startSoc = (Integer) b;
        }
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

        resetTripState();
        return qualified;
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

    public static void resetForTesting() {
        resetTripState();
        wasParked = true;
        testCurrentOdoKm = null;
        EnergyIntegrator.resetForTesting();
    }

    private TripSession() {}
}
