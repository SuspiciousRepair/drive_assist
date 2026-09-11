package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;

import android.content.ContentValues;
import android.content.Context;
import android.util.Log;

import java.util.Map;

/** Persists telemetry snapshots at regular intervals (~15s) to the database.
 *
 * Subscribes to telemetry.tick events and writes each tick to telemetry_sample.
 * This granular time-series data supports detailed analysis that summary-only
 * tables (trip/charge/park) cannot provide.
 */
public final class TelemetrySampler {
    static final String TAG = CarAccess.TAG;

    private static volatile boolean subscribed = false;

    /** Subscribes to telemetry updates and enables sampling (idempotent). */
    public static synchronized void ensureSubscribed(Context ctx) {
        if (subscribed) return;
        subscribed = true;
        Context app = ctx.getApplicationContext();
        EntityBus.subscribe("telemetry.tick", (key, reading) -> {
            if (reading.status == CarActor.Reading.Status.OK) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) reading.value;
                observe(app, data);
            }
        });
    }

    private static void observe(Context ctx, Map<String, Object> data) {
        final long tsMs = System.currentTimeMillis();
        final ContentValues v = new ContentValues();
        v.put("ts_ms", tsMs);
        putIfPresent(v, "odo_km", data.get("odometer"));
        putIfPresent(v, "battery_pct", data.get("battery"));
        putIfPresent(v, "speed_kmh", data.get("speed"));
        putIfPresent(v, "gear", data.get("gear"));
        putIfPresent(v, "is_charging", data.get("is_charging"));
        putIfPresent(v, "charge_a", data.get("charge_a"));
        putIfPresent(v, "charge_v", data.get("charge_v"));
        putIfPresent(v, "park_mode", data.get("park_mode"));
        // Not part of the telemetry.tick map itself — a separate CarActor
        // poll (see CarActor's constructor) — but already cached, so this
        // is a cache read, not a new car access.
        CarActor.Reading out = CarActor.get(ctx).get("telemetry.outside_temp");
        if (out.status == CarActor.Reading.Status.OK) putIfPresent(v, "outside_temp_c", out.value);
        // Altitude: GpsReader's own LocationManager fix, not a new car
        // access — already used for the HA location tracker.
        double[] loc = GpsReader.read(ctx);
        if (loc != null) v.put("altitude_m", loc[2]);
        putIfPresent(v, "instant_power_kw_est", data.get("instant_power_kw_est"));
        putIfPresent(v, "power_spent_kw", data.get("power_spent_kw"));
        putIfPresent(v, "power_regen_kw", data.get("power_regen_kw"));
        putIfPresent(v, "energy_spent_kwh", data.get("energy_spent_kwh"));
        putIfPresent(v, "energy_regen_kwh", data.get("energy_regen_kwh"));
        putIfPresent(v, "energy_net_kwh", data.get("energy_net_kwh"));
        // drive/battery/other_energy_pct: left NULL for now — not yet a
        // tracked telemetry field (see field-catalog.md's open question on
        // ITripData.TRIP_ED_* liveness). Columns exist so filling them in
        // later needs no migration.

        CarDb.get(ctx).write(() -> {
            try {
                CarDb.get(ctx).db().insert("telemetry_sample", null, v);
            } catch (Throwable t) {
                Log.w(TAG, "telemetrysampler: " + t);
            }
        });
    }

    private static void putIfPresent(ContentValues v, String col, Object o) {
        if (o instanceof Integer) v.put(col, (Integer) o);
        else if (o instanceof Float) v.put(col, (Float) o);
        else if (o instanceof Double) v.put(col, (Double) o);
        // else: absent this tick — column stays NULL, not zero.
    }

    private TelemetrySampler() {}
}
