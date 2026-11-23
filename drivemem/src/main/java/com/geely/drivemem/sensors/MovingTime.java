package com.geely.drivemem.sensors;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The time the car was moving in a day, for the stats page's moving speed
 * (distance over moving time): a day's driving time counts the stops too
 * (lights, a trip left open in Park), so distance over it reads far too low
 * in town.
 *
 * From telemetry_sample: the time between two samples counts when the later
 * one has a speed above zero. Samples are ~30 s apart, so one trip is rough
 * and a day evens out. TelemetryRollup prunes the samples after ~90 days, so
 * each day's number is kept in daily_stat.moving_ms (v35, {@link #storeDays}).
 */
public final class MovingTime {

    private MovingTime() {}

    /** A longer gap is the app not sampling (car off, process dead), not driving. */
    static final long MAX_GAP_MS = 90_000L;

    /** Under TelemetryRollup's 90-day retention, as in BatteryHeat. */
    static final int STORE_DAYS = 88;

    /** Moving time over samples in time order; NaN = no speed on that sample.
     * -1 when no sample has a speed at all: not known, which is not zero. */
    public static long movingMs(long[] ts, double[] speedKmh) {
        long sum = 0;
        boolean any = false;
        for (int i = 0; i < ts.length; i++) {
            if (Double.isNaN(speedKmh[i])) continue;
            any = true;
            if (i == 0 || !(speedKmh[i] > 0)) continue;
            long gap = ts[i] - ts[i - 1];
            if (gap > 0 && gap <= MAX_GAP_MS) sum += gap;
        }
        return any ? sum : -1;
    }

    /** km/h over the moving time; 0 when there is under a minute of it. */
    public static double kmh(double distanceKm, double movingMinutes) {
        return movingMinutes >= 1 && distanceKm > 0 ? distanceKm / (movingMinutes / 60.0) : 0;
    }

    /** Moving time of the samples in [startMs, endMs); -1 when not known. */
    public static long ofRange(SQLiteDatabase db, long startMs, long endMs) {
        List<Long> tsL = new ArrayList<>();
        List<Double> spL = new ArrayList<>();
        Cursor c = db.rawQuery(
            "SELECT ts_ms, speed_kmh FROM telemetry_sample WHERE ts_ms >= ? AND ts_ms < ? ORDER BY ts_ms",
            new String[]{String.valueOf(startMs), String.valueOf(endMs)});
        try {
            while (c.moveToNext()) {
                tsL.add(c.getLong(0));
                spL.add(c.isNull(1) ? Double.NaN : c.getDouble(1));
            }
        } finally { c.close(); }
        long[] ts = new long[tsL.size()];
        double[] sp = new double[ts.length];
        for (int i = 0; i < ts.length; i++) { ts[i] = tsL.get(i); sp[i] = spL.get(i); }
        return movingMs(ts, sp);
    }

    private static long startMs(LocalDate d) {
        return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** Moving time of one calendar day from the samples; -1 when not known. */
    public static long ofDay(SQLiteDatabase db, LocalDate date) {
        return ofRange(db, startMs(date), startMs(date.plusDays(1)));
    }

    /** Stores the moving time of every frozen day that has none yet and whose
     * samples are still there. Run by TelemetryRollup after the day's freeze,
     * before the prune. */
    public static void storeDays(SQLiteDatabase db, LocalDate today) {
        Cursor c = db.rawQuery(
            "SELECT date FROM daily_stat WHERE moving_ms IS NULL AND date >= ? AND date < ?",
            new String[]{today.minusDays(STORE_DAYS).toString(), today.toString()});
        List<LocalDate> todo = new ArrayList<>();
        try { while (c.moveToNext()) todo.add(LocalDate.parse(c.getString(0))); } finally { c.close(); }
        for (LocalDate date : todo) {
            long ms = ofDay(db, date);
            if (ms < 0) continue;
            ContentValues v = new ContentValues();
            v.put("moving_ms", ms);
            db.update("daily_stat", v, "date = ?", new String[]{date.toString()});
        }
    }
}
