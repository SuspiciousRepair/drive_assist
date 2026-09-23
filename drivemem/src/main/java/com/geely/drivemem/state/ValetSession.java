package com.geely.drivemem.state;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;

import java.util.Map;

/** Explicit, persistent Valet interval. It ends only when the owner requests it. */
public final class ValetSession {
    private static final String PREFS = "drivemem";
    private static final String ACTIVE = "valet_active";
    private static final String START = "valet_start_ms";
    private static final String START_ODO = "valet_start_odo";
    private static final String LAST_ODO = "valet_last_odo";
    private static final String START_SOC = "valet_start_soc";
    private static final String LAST_SOC = "valet_last_soc";
    private static final String MAX_SPEED = "valet_max_speed";
    private static final String MAX_POWER = "valet_max_power";
    private static final String ROW_ID = "valet_row_id";
    private static final String VALET_MARKER = "dashcam/valet.active";
    private static volatile boolean subscribed;

    public static final class Snapshot {
        public final boolean active;
        public final long startMs;
        public final double distanceKm, maxSpeedKmh;
        public final Double maxPowerKw;
        public final Integer startSoc, currentSoc;
        Snapshot(boolean active, long startMs, double distanceKm, double maxSpeedKmh,
                 Double maxPowerKw, Integer startSoc, Integer currentSoc) {
            this.active = active; this.startMs = startMs; this.distanceKm = distanceKm;
            this.maxSpeedKmh = maxSpeedKmh; this.maxPowerKw = maxPowerKw;
            this.startSoc = startSoc; this.currentSoc = currentSoc;
        }
    }

    public static synchronized void ensureSubscribed(Context context) {
        if (subscribed) return;
        subscribed = true;
        Context app = context.getApplicationContext();
        EntityBus.subscribe("telemetry.tick", (key, reading) -> {
            if (reading.status != CarActor.Reading.Status.OK || !isActive(app)) return;
            @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) reading.value;
            update(app, data);
        });
    }

    public static synchronized boolean start(Context c) {
        if (!CarState.isParked() || isActive(c)) return false;
        // Close the owner's arrival before opening the Valet interval. Without
        // this, a maneuver inside TripSession's 75 s Park grace would be merged
        // backward into that arrival and could not be grouped truthfully.
        if (TripSession.isTripActive()) TripSession.finalizeTrip(c);
        Number odo = cached(c, "telemetry.odometer");
        Number soc = cached(c, "telemetry.battery");
        long startMs = System.currentTimeMillis();
        ContentValues row = new ContentValues();
        row.put("start_ms", startMs);
        if (odo != null) row.put("start_odo_km", odo.doubleValue());
        if (soc != null) row.put("start_soc", soc.intValue());
        long rowId = CarDb.get(c).db().insert("valet_session", null, row);
        if (rowId <= 0) return false;
        SharedPreferences.Editor e = prefs(c).edit().putBoolean(ACTIVE, true)
            .putLong(ROW_ID, rowId)
            .putLong(START, startMs).putFloat(MAX_SPEED, 0).remove(MAX_POWER);
        if (odo != null) e.putFloat(START_ODO, odo.floatValue()).putFloat(LAST_ODO, odo.floatValue());
        else e.remove(START_ODO).remove(LAST_ODO);
        if (soc != null) e.putInt(START_SOC, soc.intValue()).putInt(LAST_SOC, soc.intValue());
        else e.remove(START_SOC).remove(LAST_SOC);
        e.apply();
        setDashcamMarker(c, true, rowId);
        EntityBus.publish("valet.changed", CarActor.Reading.ok(true));
        return true;
    }

    public static synchronized boolean stop(Context c) {
        Snapshot s = snapshot(c);
        if (!s.active) return false;
        final long endMs = System.currentTimeMillis();
        // Close the trip that happened under Valet before reopening the road
        // to normal ones. Without this, stopping while parked inside
        // TripSession's 75 s Park grace lets the drive that follows merge
        // backward into this one -- and since the daily view hides any trip
        // whose start_ms falls inside a valet_session interval, that merge
        // doesn't just mislabel the drive that follows, it makes it vanish
        // entirely. Mirrors the same finalize start() already does when
        // Valet begins, for the same reason in the other direction.
        // Valet can also be turned off while still driving (easy to forget it
        // was on) -- splitTrip(), not finalizeTrip(), handles that case too:
        // it immediately reopens a trip for the rest of the drive rather than
        // leaving TripSession dark until the next Park->Drive edge.
        if (TripSession.isTripActive()) TripSession.splitTrip(c, endMs);
        SharedPreferences p = prefs(c);
        final double startOdo = p.contains(START_ODO) ? p.getFloat(START_ODO, 0) : -1;
        final double endOdo = p.contains(LAST_ODO) ? p.getFloat(LAST_ODO, 0) : -1;
        final int startSoc = p.contains(START_SOC) ? p.getInt(START_SOC, 0) : -1;
        final int endSoc = p.contains(LAST_SOC) ? p.getInt(LAST_SOC, 0) : -1;
        final boolean hasPower = p.contains(MAX_POWER);
        final double maxPower = p.getFloat(MAX_POWER, 0);
        ContentValues v = new ContentValues();
        v.put("end_ms", endMs);
        if (endOdo >= 0) v.put("end_odo_km", endOdo);
        if (startSoc >= 0) v.put("start_soc", startSoc);
        if (endSoc >= 0) v.put("end_soc", endSoc);
        v.put("max_speed_kmh", s.maxSpeedKmh);
        if (hasPower) v.put("max_power_kw", maxPower);
        long rowId = p.getLong(ROW_ID, -1);
        if (rowId <= 0 || CarDb.get(c).db().update("valet_session", v, "id = ?",
                new String[]{String.valueOf(rowId)}) != 1) return false;
        p.edit().putBoolean(ACTIVE, false).remove(ROW_ID).apply();
        setDashcamMarker(c, false, rowId);
        EntityBus.publish("valet.changed", CarActor.Reading.ok(false));
        return true;
    }

    private static synchronized void update(Context c, Map<String, Object> data) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit();
        Number odo = number(data.get("odometer"));
        Number soc = number(data.get("battery"));
        Number speed = number(data.get("speed"));
        Number power = number(data.get("instant_power_kw_est"));
        if (odo != null) e.putFloat(LAST_ODO, odo.floatValue());
        if (soc != null) e.putInt(LAST_SOC, soc.intValue());
        if (speed != null && speed.floatValue() > p.getFloat(MAX_SPEED, 0)) e.putFloat(MAX_SPEED, speed.floatValue());
        if (power != null && power.floatValue() > 0) {
            float draw = power.floatValue();
            if (!p.contains(MAX_POWER) || draw > p.getFloat(MAX_POWER, 0)) e.putFloat(MAX_POWER, draw);
        }
        e.apply();
        long rowId = p.getLong(ROW_ID, -1);
        if (rowId > 0) {
            ContentValues checkpoint = new ContentValues();
            if (odo != null) checkpoint.put("end_odo_km", odo.doubleValue());
            if (soc != null) checkpoint.put("end_soc", soc.intValue());
            checkpoint.put("max_speed_kmh", Math.max(p.getFloat(MAX_SPEED, 0), speed == null ? 0 : speed.floatValue()));
            if (p.contains(MAX_POWER) || (power != null && power.floatValue() > 0))
                checkpoint.put("max_power_kw", Math.max(p.getFloat(MAX_POWER, 0), power == null ? 0 : power.floatValue()));
            CarDb.get(c).db().update("valet_session", checkpoint, "id = ?", new String[]{String.valueOf(rowId)});
        }
        EntityBus.publish("valet.progress", CarActor.Reading.ok(snapshot(c)));
    }

    public static boolean isActive(Context c) { return prefs(c).getBoolean(ACTIVE, false); }

    public static Snapshot snapshot(Context c) {
        SharedPreferences p = prefs(c);
        double distance = p.contains(START_ODO) && p.contains(LAST_ODO)
            ? Math.max(0, p.getFloat(LAST_ODO, 0) - p.getFloat(START_ODO, 0)) : 0;
        return new Snapshot(p.getBoolean(ACTIVE, false), p.getLong(START, 0), distance,
            p.getFloat(MAX_SPEED, 0), p.contains(MAX_POWER) ? (double) p.getFloat(MAX_POWER, 0) : null,
            p.contains(START_SOC) ? p.getInt(START_SOC, 0) : null,
            p.contains(LAST_SOC) ? p.getInt(LAST_SOC, 0) : null);
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static Number cached(Context c, String key) {
        CarActor.Reading r = CarActor.get(c).get(key);
        return r.status == CarActor.Reading.Status.OK ? number(r.value) : null;
    }
    private static Number number(Object o) { return o instanceof Number ? (Number) o : null; }
    private static void setDashcamMarker(Context c, boolean active, long rowId) {
        try {
            java.io.File base = c.getExternalFilesDir(null);
            if (base == null) return;
            java.io.File dir = new java.io.File(base, "dashcam");
            if (!dir.exists()) dir.mkdirs();
            java.io.File marker = new java.io.File(base, VALET_MARKER);
            if (active) {
                java.io.FileWriter writer = new java.io.FileWriter(marker, false);
                writer.write(String.valueOf(rowId)); writer.close();
            } else if (marker.exists()) marker.delete();
        } catch (Throwable ignored) { }
    }
    private ValetSession() {}
}
