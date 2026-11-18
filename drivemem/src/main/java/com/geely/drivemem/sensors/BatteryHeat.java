package com.geely.drivemem.sensors;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.geely.drivemem.car.CarDb;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The charge page's battery temperature card: how warm the pack got, and how
 * long it sat at 100 % and at what temperature. For LFP, heat and a full
 * pack together are what age it.
 *
 * battery_temp_c lives only in telemetry_sample (OBD2 dongle), which
 * TelemetryRollup prunes after ~90 days. So each day's numbers are kept in
 * daily_stat (v32 columns, {@link #storeDays}), and a period reads those,
 * with the days not stored yet (today, yesterday) read from the samples.
 *
 * A stay at 100 % runs from the first sample at 100 % to the last one before
 * the SoC drops; a gap while the car was off counts (the pack showed 100 % on
 * both sides of it) but has no temperature of its own. A stay belongs to the
 * day it STARTED on, whole, so a period's count, total and longest agree.
 */
public final class BatteryHeat {

    private BatteryHeat() {}

    /** Shorter stays are a charge ending and a drive starting, not a stay. */
    public static final long MIN_STAY_MS = 30 * 60_000L;
    /** A stay that started the evening before can run into this day, and one
     * starting late runs into the next: the samples read around a day. */
    static final long AROUND_MS = 36 * 3600_000L;
    /** Days still recomputed on each rollup: a stay that started on them may
     * have been open when they were last stored. */
    static final int REDO_DAYS = 3;
    /** Under TelemetryRollup's 90-day retention, so a stored day never comes
     * from a half-pruned set of samples. */
    static final int STORE_DAYS = 88;

    /** One day: its temperature readings and the stays at 100 % that began on it. */
    public static final class Day {
        public final LocalDate date;
        public double tMin = Double.NaN, tMax = Double.NaN, tSum;
        public int tN;
        public long tMaxMs = -1;
        public int fullN;
        public long fullMs, fullLongestMs;
        public double fullTMax = Double.NaN, fullTSum;
        public int fullTN;
        public Day(LocalDate date) { this.date = date; }
    }

    /** A period: the days added up. */
    public static final class Stats {
        public double minTemp = Double.NaN, maxTemp = Double.NaN, avgTemp = Double.NaN;
        public long maxTempMs = -1;
        public LocalDate firstTempDay;            // first day with a reading
        public int fullCount;
        public long fullTotalMs, fullLongestMs;
        public double fullTMax = Double.NaN, fullTAvg = Double.NaN;

        public boolean hasTemp() { return !Double.isNaN(maxTemp); }
        public long avgFullMs() { return fullCount == 0 ? 0 : fullTotalMs / fullCount; }
    }

    // ---- pure ----

    /** One day from the samples around it (time order, temperature null when
     * not read): readings inside [dayStartMs, dayEndMs), stays starting there. */
    public static Day computeDay(LocalDate date, long dayStartMs, long dayEndMs,
                                 long[] ts, int[] pct, List<Double> temp) {
        Day d = new Day(date);
        long runFrom = -1, runTo = -1; double runMax = Double.NaN, runSum = 0; int runN = 0;
        for (int i = 0; i < ts.length; i++) {
            Double t = temp.get(i);
            if (t != null && ts[i] >= dayStartMs && ts[i] < dayEndMs) {
                if (d.tN == 0 || t < d.tMin) d.tMin = t;
                if (d.tN == 0 || t >= d.tMax) { d.tMax = t; d.tMaxMs = ts[i]; }
                d.tSum += t; d.tN++;
            }
            if (pct[i] >= 100) {
                if (runFrom < 0) { runFrom = ts[i]; runMax = Double.NaN; runSum = 0; runN = 0; }
                runTo = ts[i];
                if (t != null) { if (!(t <= runMax)) runMax = t; runSum += t; runN++; }
            } else if (runFrom >= 0) {
                addStay(d, dayStartMs, dayEndMs, runFrom, runTo, runMax, runSum, runN);
                runFrom = -1;
            }
        }
        if (runFrom >= 0) addStay(d, dayStartMs, dayEndMs, runFrom, runTo, runMax, runSum, runN);
        return d;
    }

    private static void addStay(Day d, long dayStartMs, long dayEndMs, long from, long to,
                                double tMax, double tSum, int tN) {
        if (from < dayStartMs || from >= dayEndMs || to - from < MIN_STAY_MS) return;
        d.fullN++;
        d.fullMs += to - from;
        d.fullLongestMs = Math.max(d.fullLongestMs, to - from);
        if (tN > 0) {
            if (!(tMax <= d.fullTMax)) d.fullTMax = tMax;
            d.fullTSum += tSum; d.fullTN += tN;
        }
    }

    public static Stats aggregate(List<Day> days) {
        Stats s = new Stats();
        double tSum = 0, fSum = 0; int tN = 0, fN = 0;
        for (Day d : days) {
            if (d.tN > 0) {
                if (s.firstTempDay == null || d.date.isBefore(s.firstTempDay)) s.firstTempDay = d.date;
                if (tN == 0 || d.tMin < s.minTemp) s.minTemp = d.tMin;
                if (tN == 0 || d.tMax >= s.maxTemp) { s.maxTemp = d.tMax; s.maxTempMs = d.tMaxMs; }
                tSum += d.tSum; tN += d.tN;
            }
            s.fullCount += d.fullN;
            s.fullTotalMs += d.fullMs;
            s.fullLongestMs = Math.max(s.fullLongestMs, d.fullLongestMs);
            if (d.fullTN > 0) {
                if (!(d.fullTMax <= s.fullTMax)) s.fullTMax = d.fullTMax;
                fSum += d.fullTSum; fN += d.fullTN;
            }
        }
        if (tN > 0) s.avgTemp = tSum / tN;
        if (fN > 0) s.fullTAvg = fSum / fN;
        return s;
    }

    // ---- database ----

    private static long startMs(LocalDate d) {
        return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static Day fromSamples(SQLiteDatabase db, LocalDate date) {
        long s = startMs(date), e = startMs(date.plusDays(1));
        List<Long> tsL = new ArrayList<>();
        List<Integer> pctL = new ArrayList<>();
        List<Double> temp = new ArrayList<>();
        Cursor c = db.rawQuery(
            "SELECT ts_ms, battery_pct, battery_temp_c FROM telemetry_sample "
          + "WHERE ts_ms >= ? AND ts_ms < ? AND battery_pct BETWEEN 1 AND 100 ORDER BY ts_ms",
            new String[]{String.valueOf(s - AROUND_MS), String.valueOf(e + AROUND_MS)});
        try {
            while (c.moveToNext()) {
                tsL.add(c.getLong(0));
                pctL.add(c.getInt(1));
                temp.add(c.isNull(2) ? null : c.getDouble(2));
            }
        } finally { c.close(); }
        long[] ts = new long[tsL.size()];
        int[] pct = new int[tsL.size()];
        for (int i = 0; i < ts.length; i++) { ts[i] = tsL.get(i); pct[i] = pctL.get(i); }
        return computeDay(date, s, e, ts, pct, temp);
    }

    private static final String COLS = "date, batt_t_min, batt_t_max, batt_t_max_ms, batt_t_sum, batt_t_n, "
        + "full_n, full_ms, full_longest_ms, full_t_max, full_t_sum, full_t_n";

    private static Day fromRow(Cursor c) {
        Day d = new Day(LocalDate.parse(c.getString(0)));
        if (!c.isNull(1)) d.tMin = c.getDouble(1);
        if (!c.isNull(2)) d.tMax = c.getDouble(2);
        d.tMaxMs = c.isNull(3) ? -1 : c.getLong(3);
        d.tSum = c.getDouble(4);
        d.tN = c.getInt(5);
        d.fullN = c.getInt(6);
        d.fullMs = c.getLong(7);
        d.fullLongestMs = c.getLong(8);
        if (!c.isNull(9)) d.fullTMax = c.getDouble(9);
        d.fullTSum = c.getDouble(10);
        d.fullTN = c.getInt(11);
        return d;
    }

    /** Stores each day of the last ~88 that has a daily_stat row and has not
     * been stored yet, plus the last few again. Run by TelemetryRollup,
     * inside its write, after the day's freeze. The first run fills the
     * whole window the samples still cover. */
    public static void storeDays(SQLiteDatabase db, LocalDate today) {
        LocalDate redoFrom = today.minusDays(REDO_DAYS);
        Cursor c = db.rawQuery(
            "SELECT date FROM daily_stat WHERE date >= ? AND date < ? AND (heat_done IS NULL OR date >= ?)",
            new String[]{today.minusDays(STORE_DAYS).toString(), today.toString(), redoFrom.toString()});
        List<LocalDate> todo = new ArrayList<>();
        try { while (c.moveToNext()) todo.add(LocalDate.parse(c.getString(0))); } finally { c.close(); }
        for (LocalDate date : todo) {
            Day d = fromSamples(db, date);
            ContentValues v = new ContentValues();
            v.put("batt_t_min", d.tN > 0 ? d.tMin : null);
            v.put("batt_t_max", d.tN > 0 ? d.tMax : null);
            v.put("batt_t_max_ms", d.tN > 0 ? d.tMaxMs : null);
            v.put("batt_t_sum", d.tSum);
            v.put("batt_t_n", d.tN);
            v.put("full_n", d.fullN);
            v.put("full_ms", d.fullMs);
            v.put("full_longest_ms", d.fullLongestMs);
            v.put("full_t_max", d.fullTN > 0 ? d.fullTMax : null);
            v.put("full_t_sum", d.fullTSum);
            v.put("full_t_n", d.fullTN);
            v.put("heat_done", 1);
            db.update("daily_stat", v, "date = ?", new String[]{date.toString()});
        }
    }

    /** The period [from, toExcl): stored days, plus the samples for the days
     * not stored yet that the samples still hold. Off the UI thread. */
    public static Stats read(Context ctx, LocalDate from, LocalDate toExcl) {
        SQLiteDatabase db = CarDb.get(ctx).db();
        LocalDate today = LocalDate.now();
        List<Day> days = new ArrayList<>();
        Set<LocalDate> stored = new HashSet<>();
        Cursor c = db.rawQuery("SELECT " + COLS + " FROM daily_stat WHERE date >= ? AND date < ? "
            + "AND heat_done = 1", new String[]{from.toString(), toExcl.toString()});
        try {
            while (c.moveToNext()) { Day d = fromRow(c); days.add(d); stored.add(d.date); }
        } finally { c.close(); }
        LocalDate rawFrom = today.minusDays(STORE_DAYS);
        if (from.isAfter(rawFrom)) rawFrom = from;
        for (LocalDate d = rawFrom; d.isBefore(toExcl) && !d.isAfter(today); d = d.plusDays(1)) {
            if (!stored.contains(d)) days.add(fromSamples(db, d));
        }
        return aggregate(days);
    }
}
