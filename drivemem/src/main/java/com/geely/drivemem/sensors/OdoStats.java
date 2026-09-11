package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.util.DbMigration;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Computes odometer statistics from telemetry sample history.
 *
 * Derives daily and cumulative driving distance by querying the earliest sample
 * (or daily frozen row) for each calendar day. This avoids duplication with
 * separately-maintained daily_odometer; see DbMigration for the historical import
 * from the legacy odo.log file. Gracefully handles gaps in history.
 */
public final class OdoStats {
    private static final SimpleDateFormat DAY_FMT = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private OdoStats() {}

    /** An odometer reading for a calendar day. */
    public static final class Reading {
        public final String date;
        public final double odoKm;
        public Reading(String date, double odoKm) { this.date = date; this.odoKm = odoKm; }
    }

    /** Returns kilometers driven in the last N days, or since logging began if less history is available. */
    public static double kmSince(Context ctx, int days) {
        return kmSince(readLog(ctx), System.currentTimeMillis(), days);
    }

    /** Computes kilometers driven over a time window from a log and a reference time. */
    public static double kmSince(List<Reading> log, long nowMs, int days) {
        if (log.size() < 2) return 0;
        long cutoffMs = nowMs - days * 24L * 3600 * 1000;
        Reading base = log.get(0);
        for (Reading r : log) {
            long ms = dayMs(r.date);
            if (ms < 0 || ms >= cutoffMs) break;
            base = r;
        }
        Reading newest = log.get(log.size() - 1);
        return Math.max(0, newest.odoKm - base.odoKm);
    }

    /** The first and last odometer readings for a single calendar day. */
    public static final class DayRange {
        public final String date;
        public final double firstOdoKm, lastOdoKm;
        public DayRange(String date, double firstOdoKm, double lastOdoKm) {
            this.date = date; this.firstOdoKm = firstOdoKm; this.lastOdoKm = lastOdoKm;
        }
    }

    /** Day-over-day driving distance series. Each day's distance is its own last-minus-first,
     * so incomplete days show real partial totals rather than empty. */
    public static final class DailySeries {
        public final double[] km;
        public final String[] labels;   // "d/M", blank where there's no reading yet
        DailySeries(double[] km, String[] labels) { this.km = km; this.labels = labels; }
    }

    private static final SimpleDateFormat SHORT_DAY_FMT = new SimpleDateFormat("d/M", Locale.US);

    /** Formats day ranges into a series of distances for the last N calendar days. */
    public static DailySeries recentDaily(List<DayRange> ranges, int days) {
        double[] km = new double[days];
        String[] labels = new String[days];
        java.util.Arrays.fill(labels, "");
        int n = ranges.size();
        int count = Math.min(days, n);
        for (int i = 0; i < count; i++) {
            DayRange r = ranges.get(n - 1 - i);
            int slot = days - 1 - i;
            km[slot] = Math.max(0, r.lastOdoKm - r.firstOdoKm);
            labels[slot] = shortLabel(r.date);
        }
        return new DailySeries(km, labels);
    }

    private static String shortLabel(String isoDate) {
        try { return SHORT_DAY_FMT.format(DAY_FMT.parse(isoDate)); }
        catch (Exception e) { return ""; }
    }

    private static long dayMs(String date) {
        try { return DAY_FMT.parse(date).getTime(); } catch (Exception e) { return -1; }
    }

    /** Returns oldest-first odometer readings for each calendar day from the database.
     * Includes frozen daily_stat rows and the latest telemetry_sample for unfrozen days. */
    public static List<Reading> readLog(Context ctx) {
        List<Reading> out = new ArrayList<>();
        android.database.Cursor c = CarDb.get(ctx).db().rawQuery(
            "SELECT date, first_odo_km AS odo_km FROM daily_stat "
          + "UNION ALL "
          + "SELECT grp.day AS date, ts.odo_km FROM telemetry_sample ts "
          + "JOIN (SELECT date(ts_ms/1000,'unixepoch','localtime') AS day, MIN(id) AS first_id "
          + "      FROM telemetry_sample WHERE odo_km IS NOT NULL GROUP BY day) grp "
          + "ON ts.id = grp.first_id "
          + "WHERE grp.day > COALESCE((SELECT MAX(date) FROM daily_stat), '0000-00-00') "
          + "ORDER BY date ASC", null);
        try {
            while (c.moveToNext()) out.add(new Reading(c.getString(0), c.getDouble(1)));
        } finally { c.close(); }
        return out;
    }

    /** Returns oldest-first first/last odometer readings for each calendar day from the database. */
    public static List<DayRange> readDayRanges(Context ctx) {
        List<DayRange> out = new ArrayList<>();
        android.database.Cursor c = CarDb.get(ctx).db().rawQuery(
            "SELECT grp.day, first.odo_km, last.odo_km FROM "
          + "(SELECT date(ts_ms/1000,'unixepoch','localtime') AS day, "
          + "        MIN(id) AS first_id, MAX(id) AS last_id "
          + " FROM telemetry_sample WHERE odo_km IS NOT NULL GROUP BY day) grp "
          + "JOIN telemetry_sample first ON first.id = grp.first_id "
          + "JOIN telemetry_sample last ON last.id = grp.last_id "
          + "ORDER BY grp.day ASC", null);
        try {
            while (c.moveToNext()) out.add(new DayRange(c.getString(0), c.getDouble(1), c.getDouble(2)));
        } finally { c.close(); }
        return out;
    }
}
