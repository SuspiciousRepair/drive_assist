package com.geely.drivemem.sensors;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The calendar behind the stats navigator: every real day, the period a day
 * belongs to, how the arrows step, and the colour level of a day.
 *
 * WHY EVERY DAY: the old 14-bar chart took the last 14 rows that existed, so
 * a day the car did not wake up had no row and simply vanished — a weekend,
 * or the 37 days in the shop (2026-10-08 .. 11-12), closed up with no mark.
 * Here a day with no row is a real day with 0 km.
 *
 * Pure (java.time only) so the rules are unit-tested without Android.
 */
public final class StatsCalendar {

    private StatsCalendar() {}

    public enum Grain { DAY, WEEK, MONTH }

    /** One calendar day of the navigator. */
    public static final class Cell {
        public final LocalDate date;
        public final double km;
        public final int charges;

        public Cell(LocalDate date, double km, int charges) {
            this.date = date;
            this.km = km;
            this.charges = charges;
        }
    }

    /** One entry per calendar day from..to (both inclusive), oldest first.
     * `rows` maps 'YYYY-MM-DD' to {km, charges}; a missing day is 0 km. */
    public static List<Cell> fill(Map<String, double[]> rows, LocalDate from, LocalDate to) {
        List<Cell> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            double[] r = rows.get(d.toString());
            out.add(r == null ? new Cell(d, 0, 0) : new Cell(d, Math.max(0, r[0]), (int) r[1]));
        }
        return out;
    }

    /** Monday of the week containing d (Monday-start, same as weekDates()). */
    public static LocalDate weekStart(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** {first, last} day of the period of `grain` that contains d. */
    public static LocalDate[] period(LocalDate d, Grain grain) {
        switch (grain) {
            case WEEK: {
                LocalDate s = weekStart(d);
                return new LocalDate[]{s, s.plusDays(6)};
            }
            case MONTH:
                return new LocalDate[]{d.withDayOfMonth(1), d.with(TemporalAdjusters.lastDayOfMonth())};
            default:
                return new LocalDate[]{d, d};
        }
    }

    /** One arrow press: a day, a week or a month. Month keeps the day of
     * month where it can (Jan 31 + 1 month = Feb 28, java.time clamps). */
    public static LocalDate step(LocalDate d, Grain grain, int dir) {
        switch (grain) {
            case WEEK: return d.plusWeeks(dir);
            case MONTH: return d.plusMonths(dir);
            default: return d.plusDays(dir);
        }
    }

    /** Can the arrow move from d in `dir` and still land on a period that
     * overlaps [min, max]? */
    public static boolean canStep(LocalDate d, Grain grain, int dir, LocalDate min, LocalDate max) {
        LocalDate[] p = period(step(d, grain, dir), grain);
        return !p[0].isAfter(max) && !p[1].isBefore(min);
    }

    /** Keeps d inside [min, max]. */
    public static LocalDate clamp(LocalDate d, LocalDate min, LocalDate max) {
        if (d.isBefore(min)) return min;
        if (d.isAfter(max)) return max;
        return d;
    }

    /** The colour scale adapts to the driver: four cut points that split
     * THIS driver's own driving days (km > 0) into five equal groups. A 20 km
     * commuter and a 300 km traveller each get their whole scale used, instead
     * of one fixed km ladder that is all-dark for one and all-bright for the
     * other. Empty array when there are no driving days. */
    public static double[] levelCuts(List<Cell> cells) {
        double[] kms = cells.stream().mapToDouble(c -> c.km).filter(k -> k > 0).sorted().toArray();
        if (kms.length == 0) return new double[0];
        double[] cuts = new double[4];
        for (int i = 0; i < 4; i++) {
            cuts[i] = kms[Math.min(kms.length - 1, (i + 1) * kms.length / 5)];
        }
        return cuts;
    }

    /** 0 = no driving; 1..5 = the driver's own quintiles (see levelCuts). */
    public static int level(double km, double[] cuts) {
        if (km <= 0) return 0;
        int lv = 1;
        for (double c : cuts) if (km >= c) lv++;
        return Math.min(5, lv);
    }
}
