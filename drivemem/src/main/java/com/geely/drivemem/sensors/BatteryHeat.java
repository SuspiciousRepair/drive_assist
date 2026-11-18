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
 * The same numbers are kept for the outside air (outside_temp_c, the car's
 * own sensor, v34 columns): with no dongle in a period, the card shows those
 * instead and says they are not the battery's.
 *
 * A stay at 100 % runs from the first sample at 100 % to the last one before
 * the SoC drops; a gap while the car was off counts (the pack showed 100 % on
 * both sides of it) but has no temperature of its own. A stay belongs to the
 * day it STARTED on, whole, so a period's count, total and longest agree.
 *
 * Also the time spent low (under 10 % and under 20 %), for the "Saúde da
 * bateria" card: what ages LFP is sitting near empty, so hours count, not
 * days that dipped (owner, 2026-11-18). The time between two samples counts
 * when both are under the floor, so a night parked low counts whole; it is
 * cut at midnight, each day keeping its own share.
 */
public final class BatteryHeat {

    private BatteryHeat() {}

    /** The two floors of the low-battery hours. */
    public static final int LOW_PCT = SocHistory.LOW_PCT, LOW2_PCT = 20;

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

    /** One temperature series, battery or outside air, over a day or a
     * period: readings (min, max and when, sum and count for the mean) and
     * the readings taken while the pack sat at 100 %. */
    public static final class Temps {
        public double min = Double.NaN, max = Double.NaN;
        public long maxMs = -1;
        public double sum;
        public int n;
        public double fullMax = Double.NaN, fullSum;
        public int fullN;
        /** A period only: the first day with a reading. */
        public LocalDate firstDay;

        public boolean has() { return n > 0 || fullN > 0; }
        public double avg() { return n == 0 ? Double.NaN : sum / n; }
        public double fullAvg() { return fullN == 0 ? Double.NaN : fullSum / fullN; }

        void add(double t, long ms) {
            if (n == 0 || t < min) min = t;
            if (n == 0 || t >= max) { max = t; maxMs = ms; }
            sum += t; n++;
        }

        /** A stay's readings (in {@code run}'s min/max/sum/n) as "at 100 %". */
        void addFull(Temps run) {
            if (run.n == 0) return;
            if (!(run.max <= fullMax)) fullMax = run.max;
            fullSum += run.sum; fullN += run.n;
        }

        /** Adds one day's numbers to this period's. */
        void addDay(Temps d, LocalDate date) {
            if (d.n > 0) {
                if (firstDay == null || date.isBefore(firstDay)) firstDay = date;
                if (n == 0 || d.min < min) min = d.min;
                if (n == 0 || d.max >= max) { max = d.max; maxMs = d.maxMs; }
                sum += d.sum; n += d.n;
            }
            if (d.fullN > 0) {
                if (!(d.fullMax <= fullMax)) fullMax = d.fullMax;
                fullSum += d.fullSum; fullN += d.fullN;
            }
        }
    }

    /** One day: its temperature readings and the stays at 100 % that began on it. */
    public static final class Day {
        public final LocalDate date;
        /** Battery (OBD2 dongle) and outside air (the car's own sensor). */
        public final Temps batt = new Temps(), out = new Temps();
        public int fullN;
        public long fullMs, fullLongestMs;
        public long below10Ms, below20Ms;
        public Day(LocalDate date) { this.date = date; }
    }

    /** A period: the days added up. */
    public static final class Stats {
        public final Temps batt = new Temps(), out = new Temps();
        public int fullCount;
        public long fullTotalMs, fullLongestMs;
        public long below10Ms, below20Ms;
        public long avgFullMs() { return fullCount == 0 ? 0 : fullTotalMs / fullCount; }
        /** The card shows the battery when the dongle read it at all in the
         * period (or when nothing read the air either), else the outside air. */
        public boolean showsBattery() { return batt.has() || !out.has(); }
        public Temps shown() { return showsBattery() ? batt : out; }
    }

    // ---- pure ----

    /** {@link #computeDay(LocalDate, long, long, long[], int[], List, List)}
     * with no outside readings. */
    public static Day computeDay(LocalDate date, long dayStartMs, long dayEndMs,
                                 long[] ts, int[] pct, List<Double> batt) {
        return computeDay(date, dayStartMs, dayEndMs, ts, pct, batt, null);
    }

    /** One day from the samples around it (time order, a temperature null
     * when not read, {@code out} null when there are none): readings inside
     * [dayStartMs, dayEndMs), stays starting there. */
    public static Day computeDay(LocalDate date, long dayStartMs, long dayEndMs,
                                 long[] ts, int[] pct, List<Double> batt, List<Double> out) {
        Day d = new Day(date);
        long runFrom = -1, runTo = -1;
        Temps runB = new Temps(), runO = new Temps();
        for (int i = 0; i < ts.length; i++) {
            Double b = batt.get(i), o = out == null ? null : out.get(i);
            if (ts[i] >= dayStartMs && ts[i] < dayEndMs) {
                if (b != null) d.batt.add(b, ts[i]);
                if (o != null) d.out.add(o, ts[i]);
            }
            if (pct[i] >= 100) {
                if (runFrom < 0) { runFrom = ts[i]; runB = new Temps(); runO = new Temps(); }
                runTo = ts[i];
                if (b != null) runB.add(b, ts[i]);
                if (o != null) runO.add(o, ts[i]);
            } else if (runFrom >= 0) {
                addStay(d, dayStartMs, dayEndMs, runFrom, runTo, runB, runO);
                runFrom = -1;
            }
        }
        if (runFrom >= 0) addStay(d, dayStartMs, dayEndMs, runFrom, runTo, runB, runO);
        for (int i = 0; i + 1 < ts.length; i++) {
            long from = Math.max(ts[i], dayStartMs), to = Math.min(ts[i + 1], dayEndMs);
            if (to <= from) continue;
            if (pct[i] < LOW_PCT && pct[i + 1] < LOW_PCT) d.below10Ms += to - from;
            if (pct[i] < LOW2_PCT && pct[i + 1] < LOW2_PCT) d.below20Ms += to - from;
        }
        return d;
    }

    private static void addStay(Day d, long dayStartMs, long dayEndMs, long from, long to,
                                Temps runB, Temps runO) {
        if (from < dayStartMs || from >= dayEndMs || to - from < MIN_STAY_MS) return;
        d.fullN++;
        d.fullMs += to - from;
        d.fullLongestMs = Math.max(d.fullLongestMs, to - from);
        d.batt.addFull(runB);
        d.out.addFull(runO);
    }

    public static Stats aggregate(List<Day> days) {
        Stats s = new Stats();
        for (Day d : days) {
            s.batt.addDay(d.batt, d.date);
            s.out.addDay(d.out, d.date);
            s.fullCount += d.fullN;
            s.fullTotalMs += d.fullMs;
            s.fullLongestMs = Math.max(s.fullLongestMs, d.fullLongestMs);
            s.below10Ms += d.below10Ms;
            s.below20Ms += d.below20Ms;
        }
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
        List<Double> batt = new ArrayList<>(), out = new ArrayList<>();
        Cursor c = db.rawQuery(
            "SELECT ts_ms, battery_pct, battery_temp_c, outside_temp_c FROM telemetry_sample "
          + "WHERE ts_ms >= ? AND ts_ms < ? AND battery_pct BETWEEN 1 AND 100 ORDER BY ts_ms",
            new String[]{String.valueOf(s - AROUND_MS), String.valueOf(e + AROUND_MS)});
        try {
            while (c.moveToNext()) {
                tsL.add(c.getLong(0));
                pctL.add(c.getInt(1));
                batt.add(c.isNull(2) ? null : c.getDouble(2));
                out.add(c.isNull(3) ? null : c.getDouble(3));
            }
        } finally { c.close(); }
        long[] ts = new long[tsL.size()];
        int[] pct = new int[tsL.size()];
        for (int i = 0; i < ts.length; i++) { ts[i] = tsL.get(i); pct[i] = pctL.get(i); }
        return computeDay(date, s, e, ts, pct, batt, out);
    }

    private static final String COLS = "date, batt_t_min, batt_t_max, batt_t_max_ms, batt_t_sum, batt_t_n, "
        + "full_n, full_ms, full_longest_ms, full_t_max, full_t_sum, full_t_n, below10_ms, below20_ms, "
        + "out_t_min, out_t_max, out_t_max_ms, out_t_sum, out_t_n, full_out_t_max, full_out_t_sum, full_out_t_n";

    /** Reads one series: min, max, max ms, sum, n from column {@code min}
     * on, and max, sum, n while at 100 % from column {@code fullMax} on.
     * NULL counts read 0. */
    private static void readTemps(Cursor c, Temps t, int min, int fullMax) {
        if (!c.isNull(min)) t.min = c.getDouble(min);
        if (!c.isNull(min + 1)) t.max = c.getDouble(min + 1);
        t.maxMs = c.isNull(min + 2) ? -1 : c.getLong(min + 2);
        t.sum = c.getDouble(min + 3);
        t.n = c.getInt(min + 4);
        if (!c.isNull(fullMax)) t.fullMax = c.getDouble(fullMax);
        t.fullSum = c.getDouble(fullMax + 1);
        t.fullN = c.getInt(fullMax + 2);
    }

    private static Day fromRow(Cursor c) {
        Day d = new Day(LocalDate.parse(c.getString(0)));
        readTemps(c, d.batt, 1, 9);
        d.fullN = c.getInt(6);
        d.fullMs = c.getLong(7);
        d.fullLongestMs = c.getLong(8);
        d.below10Ms = c.getLong(12);   // NULL (stored before v33, past the samples) reads 0
        d.below20Ms = c.getLong(13);
        readTemps(c, d.out, 14, 19);   // NULL before v34 reads as no readings
        return d;
    }

    private static void putTemps(ContentValues v, String p, String fullP, Temps t) {
        v.put(p + "_min", t.n > 0 ? t.min : null);
        v.put(p + "_max", t.n > 0 ? t.max : null);
        v.put(p + "_max_ms", t.n > 0 ? t.maxMs : null);
        v.put(p + "_sum", t.sum);
        v.put(p + "_n", t.n);
        v.put(fullP + "_max", t.fullN > 0 ? t.fullMax : null);
        v.put(fullP + "_sum", t.fullSum);
        v.put(fullP + "_n", t.fullN);
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
            putTemps(v, "batt_t", "full_t", d.batt);
            putTemps(v, "out_t", "full_out_t", d.out);
            v.put("full_n", d.fullN);
            v.put("full_ms", d.fullMs);
            v.put("full_longest_ms", d.fullLongestMs);
            v.put("below10_ms", d.below10Ms);
            v.put("below20_ms", d.below20Ms);
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
