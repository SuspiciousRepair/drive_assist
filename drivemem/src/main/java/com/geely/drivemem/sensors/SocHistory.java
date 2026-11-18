package com.geely.drivemem.sensors;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.geely.drivemem.car.CarDb;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How low the battery went, per calendar day, for the charge page's "Bateria
 * baixa" card: one min..max per day from daily_stat (kept for good), with the
 * days not frozen yet read from telemetry_sample. Daily lows count every dip,
 * drives included, which the per-charge bars (SoC at plug-in) cannot.
 *
 * The counting (days below a floor, the lowest point) is pure and tested.
 */
public final class SocHistory {

    private SocHistory() {}

    /** The line on the per-charge bars. LFP has no hard floor: 20 % is the usual
     * daily-use comfort line, but going under it now and then does no harm;
     * what does is sitting near empty. 10 % marks that (owner, 2026-11-18). */
    public static final int LOW_PCT = 10;

    /** A plausible battery_pct: 0 and above-100 rows are sensor noise. */
    private static final String VALID = "battery_pct BETWEEN 1 AND 100";

    public static final class DayRange {
        public final LocalDate date;
        public final int min, max;
        public DayRange(LocalDate date, int min, int max) {
            this.date = date; this.min = min; this.max = max;
        }
    }

    /** One min..max per calendar day from `from` to today, oldest first; a
     * day with no record is left out. */
    public static List<DayRange> dailyRanges(Context ctx, LocalDate from, LocalDate today) {
        SQLiteDatabase db = CarDb.get(ctx).db();
        Map<LocalDate, int[]> days = new TreeMap<>();
        Cursor c = db.rawQuery(
            "SELECT date, min_battery_pct, max_battery_pct FROM daily_stat "
          + "WHERE date >= ? AND min_battery_pct BETWEEN 1 AND 100 "
          + "AND max_battery_pct BETWEEN 1 AND 100",
            new String[]{from.toString()});
        try {
            while (c.moveToNext()) {
                days.put(LocalDate.parse(c.getString(0)), new int[]{c.getInt(1), c.getInt(2)});
            }
        } finally { c.close(); }

        // Days not frozen into daily_stat yet (today, maybe yesterday): from
        // the samples. Grouped on the local calendar day in Java, since the
        // index needs a plain ts_ms range (see DailyStatsProvider.dayBoundsMs).
        ZoneId zone = ZoneId.systemDefault();
        LocalDate recent = today.minusDays(2).isBefore(from) ? from : today.minusDays(2);
        long fromMs = recent.atStartOfDay(zone).toInstant().toEpochMilli();
        Map<LocalDate, int[]> live = new TreeMap<>();
        Cursor s = db.rawQuery(
            "SELECT ts_ms, battery_pct FROM telemetry_sample WHERE ts_ms >= ? AND " + VALID,
            new String[]{String.valueOf(fromMs)});
        try {
            while (s.moveToNext()) {
                LocalDate d = java.time.Instant.ofEpochMilli(s.getLong(0)).atZone(zone).toLocalDate();
                int pct = s.getInt(1);
                int[] r = live.get(d);
                if (r == null) live.put(d, new int[]{pct, pct});
                else { r[0] = Math.min(r[0], pct); r[1] = Math.max(r[1], pct); }
            }
        } finally { s.close(); }
        for (Map.Entry<LocalDate, int[]> e : live.entrySet()) {
            if (!days.containsKey(e.getKey())) days.put(e.getKey(), e.getValue());
        }

        List<DayRange> out = new ArrayList<>(days.size());
        for (Map.Entry<LocalDate, int[]> e : days.entrySet()) {
            if (e.getKey().isAfter(today)) continue;
            out.add(new DayRange(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        return out;
    }

    /** Days whose lowest SoC went under `floor`. */
    public static int daysBelow(List<DayRange> days, int floor) {
        int n = 0;
        for (DayRange d : days) if (d.min < floor) n++;
        return n;
    }

    /** The day with the lowest SoC (the most recent one on a tie), or null. */
    public static DayRange lowest(List<DayRange> days) {
        DayRange low = null;
        for (DayRange d : days) if (low == null || d.min <= low.min) low = d;
        return low;
    }
}
