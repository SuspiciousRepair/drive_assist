package com.geely.drivemem.sensors;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Keeps the battery's state of health (hub key {@link ObdHub#SOH}, the
 * dongle's BMS DID 4B37) in battery_health_reading, so the charge page can
 * show it with the dongle off and a past month can show its own.
 *
 * SoH moves over months, so a row is written only when the value changes or
 * on the first reading of a day: one row a day at most while it holds.
 */
public final class BatteryHealth {

    private BatteryHealth() {}

    static final String SOURCE_OBD = "obd2";

    private static volatile boolean subscribed;
    private static double lastPct = Double.NaN;
    private static long lastMs = -1;

    /** Pure: a new reading is stored when it differs from the last stored
     * one, or the last one is from another day (or there is none). */
    static boolean shouldStore(double lastPct, long lastMs, double pct, long nowMs, ZoneId zone) {
        if (lastMs < 0 || Double.isNaN(lastPct)) return true;
        if (Math.abs(pct - lastPct) >= 0.05) return true;
        LocalDate a = Instant.ofEpochMilli(lastMs).atZone(zone).toLocalDate();
        LocalDate b = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        return !a.equals(b);
    }

    /** Idempotent. */
    public static synchronized void ensureSubscribed(Context ctx) {
        if (subscribed) return;
        subscribed = true;
        Context app = ctx.getApplicationContext();
        EntityBus.subscribe(ObdHub.SOH, (key, r) -> {
            if (r.status != CarActor.Reading.Status.OK || !(r.value instanceof Number)) return;
            store(app, ((Number) r.value).doubleValue(), System.currentTimeMillis());
        });
    }

    private static synchronized void store(Context ctx, double pct, long now) {
        try {
            SQLiteDatabase db = CarDb.get(ctx).db();
            if (lastMs < 0) {
                try (Cursor c = db.rawQuery(
                        "SELECT soh_pct, ts_ms FROM battery_health_reading ORDER BY ts_ms DESC LIMIT 1", null)) {
                    if (c.moveToFirst()) { lastPct = c.getDouble(0); lastMs = c.getLong(1); }
                }
            }
            if (!shouldStore(lastPct, lastMs, pct, now, ZoneId.systemDefault())) return;
            ContentValues v = new ContentValues();
            v.put("ts_ms", now);
            try (Cursor c = db.rawQuery(
                    "SELECT odo_km FROM telemetry_sample WHERE odo_km IS NOT NULL ORDER BY id DESC LIMIT 1", null)) {
                if (c.moveToFirst()) v.put("odo_km", c.getDouble(0));
            }
            v.put("soh_pct", pct);
            v.put("source", SOURCE_OBD);
            db.insert("battery_health_reading", null, v);
            lastPct = pct;
            lastMs = now;
        } catch (Exception ignored) { }
    }

    /** The newest SoH stored before `beforeMs`, or null. Off the UI thread. */
    public static Double latestBefore(Context ctx, long beforeMs) {
        try (Cursor c = CarDb.get(ctx).db().rawQuery(
                "SELECT soh_pct FROM battery_health_reading WHERE ts_ms < ? ORDER BY ts_ms DESC LIMIT 1",
                new String[]{String.valueOf(beforeMs)})) {
            return c.moveToFirst() ? c.getDouble(0) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
