package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarDb;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides aggregated daily statistics, altimetry (D+/D-),
 * and ABRP-style chronological sessions (trips and charges) for any calendar day.
 */
public final class DailyStatsProvider {

    private static final SimpleDateFormat DAY_FMT = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat SHORT_DAY_FMT = new SimpleDateFormat("d/M", Locale.US);
    private static final SimpleDateFormat DISPLAY_DAY_FMT = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("pt", "BR"));
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm", Locale.US);

    // getDayOverview() for a past (non-today) date never changes once
    // computed -- that day is frozen history. Week/Month (recentWeeks/
    // recentMonths) call it for every day of every period in their window,
    // and the SAME days recur across overlapping/adjacent windows (paging,
    // switching Week<->Month<->Day), so this alone turns most repeat window
    // loads from a full DB pass back into a map lookup. Unbounded is fine:
    // one small DayOverview per calendar day the app has ever displayed, for
    // as long as the process lives -- not a real growth risk in practice.
    private static final Map<String, DayOverview> DAY_CACHE = new ConcurrentHashMap<>();

    /** Bar chart day item representing one column of daily km. */
    public static final class DayItem {
        public final String date;       // 'YYYY-MM-DD'
        public final String shortLabel; // 'd/M'
        public final double km;

        public DayItem(String date, String shortLabel, double km) {
            this.date = date;
            this.shortLabel = shortLabel;
            this.km = km;
        }
    }

    /** Base session item for the ABRP-style daily chronological log. */
    public static abstract class DaySession {
        public final long startMs, endMs;
        public final String timeLabel;
        public final String durationLabel;

        protected DaySession(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.timeLabel = TIME_FMT.format(new Date(startMs)) + " – "
                    + (endMs > 0 ? TIME_FMT.format(new Date(endMs)) : "em andamento");
            this.durationLabel = formatDuration(startMs, endMs > 0 ? endMs : System.currentTimeMillis());
        }

        public abstract boolean isTrip();
        public boolean isValet() { return false; }
    }

    /** Driving trip session item. */
    public static final class DriveSession extends DaySession {
        public final long tripId;
        public final double distanceKm;
        public final int socStart, socEnd;
        public final double ascentDPlusM;   // D+
        public final double descentDMinusM; // D-
        public final double regenKwh;       // 3. Regen (kWh recovered >= 0)
        public final double spentKwh;       // 2. Power spent (gross kWh consumed >= 0)
        public final double energyKwh;      // 1. Power as a whole (net kWh = spent - regen)
        public final double efficiencyKwh100km;

        public DriveSession(long tripId, long startMs, long endMs, double distanceKm,
                            int socStart, int socEnd, double ascentDPlusM, double descentDMinusM,
                            double regenKwh, double spentKwh, double energyKwh, double efficiencyKwh100km) {
            super(startMs, endMs);
            this.tripId = tripId;
            this.distanceKm = distanceKm;
            this.socStart = socStart;
            this.socEnd = socEnd;
            this.ascentDPlusM = ascentDPlusM;
            this.descentDMinusM = descentDMinusM;
            this.regenKwh = regenKwh;
            this.spentKwh = spentKwh;
            this.energyKwh = energyKwh;
            this.efficiencyKwh100km = efficiencyKwh100km;
        }

        public DriveSession(long tripId, long startMs, long endMs, double distanceKm,
                            int socStart, int socEnd, double ascentDPlusM, double descentDMinusM,
                            double regenKwh, double energyKwh, double efficiencyKwh100km) {
            this(tripId, startMs, endMs, distanceKm, socStart, socEnd, ascentDPlusM, descentDMinusM,
                 regenKwh, energyKwh + regenKwh, energyKwh, efficiencyKwh100km);
        }

        @Override public boolean isTrip() { return true; }
    }

    /** Charging session item. */
    public static final class ChargeSessionItem extends DaySession {
        public final long chargeId;
        public final int socStart, socEnd;
        public final double kwh;
        public final double avgPowerKw;
        public final boolean isDcfc;
        public final Double cost;

        public ChargeSessionItem(long chargeId, long startMs, long endMs, int socStart, int socEnd,
                                 double kwh, double avgPowerKw, boolean isDcfc) {
            this(chargeId, startMs, endMs, socStart, socEnd, kwh, avgPowerKw, isDcfc, null);
        }

        public ChargeSessionItem(long chargeId, long startMs, long endMs, int socStart, int socEnd,
                                 double kwh, double avgPowerKw, boolean isDcfc, Double cost) {
            super(startMs, endMs);
            this.chargeId = chargeId;
            this.socStart = socStart;
            this.socEnd = socEnd;
            this.kwh = kwh;
            this.avgPowerKw = avgPowerKw;
            this.isDcfc = isDcfc;
            this.cost = cost;
        }

        @Override public boolean isTrip() { return false; }
    }

    /** One explicit Valet interval; underlying trip rows remain stored but are grouped here. */
    public static final class ValetSessionItem extends DaySession {
        public final long valetId;
        public final double distanceKm, maxSpeedKmh;
        public final Double maxPowerKw;
        public final int startSoc, endSoc;
        public ValetSessionItem(long valetId, long startMs, long endMs, double distanceKm,
                                double maxSpeedKmh, Double maxPowerKw, int startSoc, int endSoc) {
            super(startMs, endMs);
            this.valetId = valetId; this.distanceKm = distanceKm; this.maxSpeedKmh = maxSpeedKmh;
            this.maxPowerKw = maxPowerKw; this.startSoc = startSoc; this.endSoc = endSoc;
        }
        @Override public boolean isTrip() { return false; }
        @Override public boolean isValet() { return true; }
    }

    /** Complete daily overview for a selected date. */
    public static final class DayOverview {
        public final String date;           // 'YYYY-MM-DD'
        public final String displayDate;    // 'Quarta-feira, 10 de Setembro'
        public final double distanceKm;
        public final double dischargeKwh;   // 2. Power spent (gross kWh consumed >= 0)
        public final double regenKwh;       // 3. Regen (kWh recovered >= 0)
        public final double netKwh;         // 1. Power (net kWh = spent - regen)
        public final double efficiencyKwh100km;
        public final double ascentDPlusM;   // D+ Ganho Acumulado
        public final double descentDMinusM; // D- Perda Acumulada
        public final double netElevationM;  // Saldo líquido
        public final int firstBatteryPct;
        public final int lastBatteryPct;
        public final int minBatteryPct;
        public final int maxBatteryPct;
        public final double drivingMinutes;
        public final double avgSpeedKmh;
        public final double avgTempC;
        public final int chargeCount;
        public final double chargeKwh;
        public final List<DaySession> sessions;
        // Trivial point lookups against telemetry_sample, same as max_speed_kmh
        // (see CarDb's comment on why that one isn't a precomputed column
        // either) -- filled in by getDayOverview() after construction rather
        // than threaded through both constructors below, so neither existing
        // caller has to change.
        public double maxAltitudeM;
        // daily_stat.charge_cost already existed and was already populated by
        // TelemetryRollup on freeze -- this was just never read back out
        // until the 2026-09-13 period-views work. Same after-construction
        // pattern as maxAltitudeM, for the same reason.
        public double chargeCost;

        public DayOverview(String date, String displayDate, double distanceKm, double dischargeKwh,
                           double regenKwh, double netKwh,
                           double efficiencyKwh100km, double ascentDPlusM, double descentDMinusM,
                           double netElevationM, int firstBatteryPct, int lastBatteryPct,
                           int minBatteryPct, int maxBatteryPct, double drivingMinutes,
                           double avgSpeedKmh, double avgTempC, int chargeCount, double chargeKwh,
                           List<DaySession> sessions) {
            this.date = date;
            this.displayDate = displayDate;
            this.distanceKm = distanceKm;
            this.dischargeKwh = dischargeKwh;
            this.regenKwh = regenKwh;
            this.netKwh = netKwh;
            this.efficiencyKwh100km = efficiencyKwh100km;
            this.ascentDPlusM = ascentDPlusM;
            this.descentDMinusM = descentDMinusM;
            this.netElevationM = netElevationM;
            this.firstBatteryPct = firstBatteryPct;
            this.lastBatteryPct = lastBatteryPct;
            this.minBatteryPct = minBatteryPct;
            this.maxBatteryPct = maxBatteryPct;
            this.drivingMinutes = drivingMinutes;
            this.avgSpeedKmh = avgSpeedKmh;
            this.avgTempC = avgTempC;
            this.chargeCount = chargeCount;
            this.chargeKwh = chargeKwh;
            this.sessions = sessions;
        }

        public DayOverview(String date, String displayDate, double distanceKm, double dischargeKwh,
                           double efficiencyKwh100km, double ascentDPlusM, double descentDMinusM,
                           double netElevationM, int firstBatteryPct, int lastBatteryPct,
                           int minBatteryPct, int maxBatteryPct, double drivingMinutes,
                           double avgSpeedKmh, double avgTempC, int chargeCount, double chargeKwh,
                           List<DaySession> sessions) {
            this(date, displayDate, distanceKm, dischargeKwh, 0.0, dischargeKwh,
                 efficiencyKwh100km, ascentDPlusM, descentDMinusM, netElevationM,
                 firstBatteryPct, lastBatteryPct, minBatteryPct, maxBatteryPct,
                 drivingMinutes, avgSpeedKmh, avgTempC, chargeCount, chargeKwh, sessions);
        }
    }

    /** A Week or Month view: `totals` is a DayOverview-shaped aggregate across
     * the period (see aggregate()), so any rendering code written for a
     * single day already knows how to draw the period's headline numbers.
     * `days` is the underlying daily breakdown, for a day-grouped session
     * log -- see plan/active/STATS-PERIOD-VIEWS-ROADMAP.md. */
    public static final class PeriodOverview {
        public final DayOverview totals;
        public final List<DayOverview> days;
        public final String periodLabel;

        public PeriodOverview(DayOverview totals, List<DayOverview> days, String periodLabel) {
            this.totals = totals;
            this.days = days;
            this.periodLabel = periodLabel;
        }
    }

    /** Sums a list of daily overviews into one DayOverview-shaped total.
     * Pure and testable -- no DB access, matching the "query then aggregate"
     * split OdoStats already uses for its own multi-day rollups.
     *
     * avgSpeedKmh is recomputed from summed distance/driving-time rather
     * than averaged day-to-day (a short slow day and a long fast day don't
     * weigh the same). avgTempC is a plain mean of the daily averages that
     * actually have a reading -- a documented approximation, not weighted
     * by sample count. efficiencyKwh100km goes through the same shared
     * DrivingConsumption.efficiencyKwh100km formula every other screen
     * already uses, fed the summed totals -- not a new formula. */
    public static DayOverview aggregate(List<DayOverview> days, String label) {
        double distanceKm = 0, dischargeKwh = 0, regenKwh = 0;
        double ascentDPlus = 0, descentDMinus = 0, drivingMin = 0;
        int chargeCount = 0; double chargeKwh = 0, chargeCost = 0;
        int minBatt = -1, maxBatt = -1, firstBatt = -1, lastBatt = -1;
        double tempSum = 0; int tempDays = 0;
        double maxAlt = 0;
        List<DaySession> allSessions = new ArrayList<>();

        for (DayOverview d : days) {
            distanceKm += d.distanceKm;
            dischargeKwh += d.dischargeKwh;
            regenKwh += d.regenKwh;
            ascentDPlus += d.ascentDPlusM;
            descentDMinus += d.descentDMinusM;
            drivingMin += d.drivingMinutes;
            chargeCount += d.chargeCount;
            chargeKwh += d.chargeKwh;
            chargeCost += d.chargeCost;
            if (d.minBatteryPct >= 0 && (minBatt < 0 || d.minBatteryPct < minBatt)) minBatt = d.minBatteryPct;
            if (d.maxBatteryPct >= 0 && d.maxBatteryPct > maxBatt) maxBatt = d.maxBatteryPct;
            if (firstBatt < 0 && d.firstBatteryPct >= 0) firstBatt = d.firstBatteryPct;
            if (d.lastBatteryPct >= 0) lastBatt = d.lastBatteryPct;
            if (d.avgTempC != 0) { tempSum += d.avgTempC; tempDays++; }
            if (d.maxAltitudeM > maxAlt) maxAlt = d.maxAltitudeM;
            allSessions.addAll(d.sessions);
        }

        double netKwh = dischargeKwh - regenKwh;
        double avgSpeed = drivingMin > 0 ? distanceKm / (drivingMin / 60.0) : 0;
        double avgTemp = tempDays > 0 ? tempSum / tempDays : 0;
        double netElevation = ascentDPlus - descentDMinus;
        double efficiency = DrivingConsumption.efficiencyKwh100km(distanceKm, dischargeKwh, regenKwh);

        DayOverview total = new DayOverview(label, label, distanceKm, dischargeKwh, regenKwh, netKwh,
            efficiency, ascentDPlus, descentDMinus, netElevation, firstBatt, lastBatt,
            minBatt, maxBatt, drivingMin, avgSpeed, avgTemp, chargeCount, chargeKwh, allSessions);
        total.chargeCost = chargeCost;
        total.maxAltitudeM = maxAlt;
        return total;
    }

    /** Sunday-start calendar week containing dateStr, oldest first. */
    public static List<String> weekDates(String dateStr) {
        Calendar cal = dayCalendar(dateStr);
        cal.setFirstDayOfWeek(Calendar.SUNDAY);
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
        List<String> out = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            out.add(DAY_FMT.format(cal.getTime()));
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    /** Every date in the calendar month containing dateStr, oldest first. */
    public static List<String> monthDates(String dateStr) {
        Calendar cal = dayCalendar(dateStr);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        List<String> out = new ArrayList<>(daysInMonth);
        for (int i = 0; i < daysInMonth; i++) {
            out.add(DAY_FMT.format(cal.getTime()));
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    private static Calendar dayCalendar(String dateStr) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        try { cal.setTime(DAY_FMT.parse(dateStr)); } catch (Exception ignored) { /* keep today */ }
        return cal;
    }

    /** [start, end) epoch-ms bounds of the local calendar day `dateStr` --
     * the index-friendly equivalent of filtering with
     * date(col/1000,'unixepoch','localtime') = dateStr. SQLite can use
     * idx_sample_ts for `col >= ? AND col < ?`; it can't for a date()
     * expression wrapped around the column, which forces a full table scan
     * every time (measured live: ~1.2s on a 90-day/500k-row table, vs.
     * ~0.0004s for the range form -- see the 2026-09-13 session notes).
     * Every per-day query in this file should use this, not date(). */
    private static long[] dayBoundsMs(String dateStr) {
        Calendar cal = dayCalendar(dateStr);
        long startMs = cal.getTimeInMillis();
        cal.add(Calendar.DAY_OF_MONTH, 1);
        return new long[]{startMs, cal.getTimeInMillis()};
    }

    /** Cheap existence check for a calendar day, using the plain ts_ms range
     * idx_sample_ts already indexes -- unlike this class's usual
     * date(ts_ms/1000,'unixepoch','localtime') = ? filter, which SQLite can't
     * satisfy from that index and has to full-scan telemetry_sample for. */
    private static boolean hasAnyTelemetry(SQLiteDatabase db, String dateStr) {
        long[] bounds = dayBoundsMs(dateStr);
        Cursor c = db.rawQuery(
            "SELECT EXISTS(SELECT 1 FROM telemetry_sample WHERE ts_ms >= ? AND ts_ms < ?)",
            new String[]{String.valueOf(bounds[0]), String.valueOf(bounds[1])});
        try { return c.moveToFirst() && c.getInt(0) != 0; } finally { c.close(); }
    }

    private static final SimpleDateFormat WEEK_BOUND_FMT = new SimpleDateFormat("d 'de' MMM", new Locale("pt", "BR"));
    private static final SimpleDateFormat MONTH_LABEL_FMT = new SimpleDateFormat("MMMM 'de' yyyy", new Locale("pt", "BR"));

    private static String weekLabel(List<String> weekDates) {
        try {
            String first = WEEK_BOUND_FMT.format(DAY_FMT.parse(weekDates.get(0)));
            String last = WEEK_BOUND_FMT.format(DAY_FMT.parse(weekDates.get(weekDates.size() - 1)));
            return first + " - " + last;
        } catch (Exception e) {
            return weekDates.get(0) + " - " + weekDates.get(weekDates.size() - 1);
        }
    }

    private static String monthLabel(String anyDateInMonth) {
        String s = MONTH_LABEL_FMT.format(dayCalendar(anyDateInMonth).getTime());
        return s.substring(0, 1).toUpperCase(new Locale("pt", "BR")) + s.substring(1);
    }

    /** The most recent `count` calendar weeks (Sunday-start), oldest first,
     * the last one containing anchorDate -- one bar per week, not one bar per
     * day of a single week (2026-09-13: "aggregate of the months: Aug, Sep,
     * Oct..." -- Week mode is the same idea one grain finer). Each entry
     * aggregates its own 7 days -- see aggregate()'s own header for why
     * that's preferred over a dedicated aggregate SQL path (daily data stays
     * the single source of truth). */
    public static List<PeriodOverview> recentWeeks(Context ctx, String anchorDate, int count) {
        List<PeriodOverview> out = new ArrayList<>(count);
        Calendar cal = dayCalendar(anchorDate);
        for (int i = count - 1; i >= 0; i--) {
            Calendar c2 = (Calendar) cal.clone();
            c2.add(Calendar.DAY_OF_MONTH, -7 * i);
            List<String> dates = weekDates(DAY_FMT.format(c2.getTime()));
            out.add(buildPeriodOverview(ctx, dates, weekLabel(dates)));
        }
        return out;
    }

    /** The most recent `count` calendar months, oldest first, the last one
     * containing anchorDate -- see recentWeeks()'s own comment. */
    public static List<PeriodOverview> recentMonths(Context ctx, String anchorDate, int count) {
        List<PeriodOverview> out = new ArrayList<>(count);
        Calendar cal = dayCalendar(anchorDate);
        cal.set(Calendar.DAY_OF_MONTH, 1); // avoid month-length rollover surprises (e.g. Jan 31 -> Mar 3)
        for (int i = count - 1; i >= 0; i--) {
            Calendar c2 = (Calendar) cal.clone();
            c2.add(Calendar.MONTH, -i);
            String d = DAY_FMT.format(c2.getTime());
            out.add(buildPeriodOverview(ctx, monthDates(d), monthLabel(d)));
        }
        return out;
    }

    private static PeriodOverview buildPeriodOverview(Context ctx, List<String> dates, String label) {
        List<DayOverview> days = new ArrayList<>(dates.size());
        for (String d : dates) days.add(getDayOverview(ctx, d));
        return new PeriodOverview(aggregate(days, label), days, label);
    }

    /** Average efficiency (kWh/100km) grouped by driving speed for a day. */
    public static final class SpeedBucket {
        public final double kwh100km;   // 0 when there's no qualifying distance in this bucket
        public final double distanceKm;
        SpeedBucket(double kwh100km, double distanceKm) { this.kwh100km = kwh100km; this.distanceKm = distanceKm; }
    }

    // Upper bound of each bucket in km/h; the last one is open-ended (120+).
    private static final double[] SPEED_BUCKET_MAX = {40, 80, 120, Double.MAX_VALUE};

    private static int speedBucketFor(double speedKmh) {
        for (int i = 0; i < SPEED_BUCKET_MAX.length; i++) {
            if (speedKmh < SPEED_BUCKET_MAX[i]) return i;
        }
        return SPEED_BUCKET_MAX.length - 1;
    }

    /** Distance per hour, split into the same four speed buckets used by efficiency. */
    public static final class HourlySpeedData {
        public final double[][] km = new double[24][SPEED_BUCKET_MAX.length];
        public double maxHourlyTotalKm;
    }

    public static HourlySpeedData getHourlySpeedData(Context ctx, String dateStr) {
        HourlySpeedData out = new HourlySpeedData();
        long[] bounds = dayBoundsMs(dateStr);
        // Gear wins whenever it's known — same reasoning as DrivingConsumption
        // .isDriving(): is_charging is derived from a car property observed
        // to latch for hours, including through a real drive (2026-09-14),
        // and excluding on it alone emptied this whole chart for the day.
        // is_charging is only the decider when gear itself is missing.
        Cursor c = CarDb.get(ctx).db().rawQuery(
                "SELECT odo_km, speed_kmh, ts_ms FROM telemetry_sample "
              + "WHERE ts_ms >= ? AND ts_ms < ? "
              + "AND (CASE WHEN gear IS NOT NULL THEN gear <> 4 "
              + "     ELSE (is_charging IS NULL OR is_charging = 0) END) "
              + "ORDER BY id ASC",
                new String[]{String.valueOf(bounds[0]), String.valueOf(bounds[1])});
        try {
            double previousOdo = -1;
            Calendar calendar = Calendar.getInstance();
            while (c.moveToNext()) {
                if (c.isNull(0) || c.isNull(1)) continue;
                double odo = c.getDouble(0);
                double speed = c.getDouble(1);
                if (odo <= 0 || speed < 0) continue;
                if (previousOdo >= 0 && odo >= previousOdo && odo - previousOdo < 50) {
                    calendar.setTimeInMillis(c.getLong(2));
                    int hour = calendar.get(Calendar.HOUR_OF_DAY);
                    int bucket = speedBucketFor(speed);
                    out.km[hour][bucket] += odo - previousOdo;
                }
                previousOdo = odo;
            }
        } finally { c.close(); }
        for (int h = 0; h < 24; h++) {
            double total = 0;
            for (double value : out.km[h]) total += value;
            if (total > out.maxHourlyTotalKm) out.maxHourlyTotalKm = total;
        }
        return out;
    }

    /**
     * Buckets telemetry_sample by each row's own speed_kmh (0-40 / 40-80 /
     * 80-120 / 120+), same as the day's overall efficiency: energy_spent_kwh/
     * energy_regen_kwh are already per-sample increments (see the day-total
     * energy query below), so they sum directly per bucket with no diffing.
     * Distance is NOT a per-sample increment (odo_km is cumulative), so each
     * bucket's distance is the odometer delta since the previous sample,
     * attributed to whichever bucket the CURRENT sample's speed falls into --
     * SQLite here has no window functions (see TelemetryRollup's note on
     * LAG()), so this is a sequential Java-side pass, same idea as the
     * legacy-sample piecewise integration in getDayOverview() below.
     *
     * energy_spent_kwh/energy_regen_kwh can be NULL for a stretch after the
     * telemetry service restarts (observed ~53 minutes on 2026-09-10, id
     * 2067-2269) even though odo_km/speed_kmh are already reporting fine --
     * instant_power_kw_est is populated during that stretch, though, so it is
     * integrated the same way getDayOverview() does for legacy rows. Treating
     * a NULL energy pair as zero (instead of falling back) would keep
     * crediting that distance to a bucket while starving it of energy,
     * understating that bucket's kWh/100km.
     */
    public static SpeedBucket[] getSpeedBucketEfficiency(Context ctx, String dateStr) {
        SQLiteDatabase db = CarDb.get(ctx).db();
        long[] bounds = dayBoundsMs(dateStr);
        DrivingConsumption totals = queryDrivingConsumption(db,
            "ts_ms >= ? AND ts_ms < ?",
            new String[]{String.valueOf(bounds[0]), String.valueOf(bounds[1])});

        SpeedBucket[] out = new SpeedBucket[SPEED_BUCKET_MAX.length];
        for (int i = 0; i < SPEED_BUCKET_MAX.length; i++) {
            double kwh100 = DrivingConsumption.per100km(
                totals.spent[i], totals.regen[i], totals.distance[i]);
            out[i] = new SpeedBucket(kwh100, totals.distance[i]);
        }
        return out;
    }

    /** Reads every row in the interval so a Park or charging row can reset legacy
     * integration. Filtering those rows in SQL would incorrectly bridge energy
     * across short parked gaps. Zero-speed rows in a driving gear remain included. */
    static DrivingConsumption queryDrivingConsumption(SQLiteDatabase db, String where,
                                                       String[] args) {
        DrivingConsumption totals = new DrivingConsumption();
        Cursor c = db.rawQuery(
            "SELECT ts_ms, odo_km, speed_kmh, gear, is_charging, "
          + "       energy_spent_kwh, energy_regen_kwh, instant_power_kw_est "
          + "FROM telemetry_sample WHERE " + where + " ORDER BY ts_ms ASC, id ASC", args);
        try {
            while (c.moveToNext()) {
                long ts = c.getLong(0);
                double odo = c.isNull(1) ? Double.NaN : c.getDouble(1);
                double speed = c.isNull(2) ? Double.NaN : c.getDouble(2);
                Integer gear = c.isNull(3) ? null : c.getInt(3);
                boolean charging = !c.isNull(4) && c.getInt(4) != 0;
                double spent = c.isNull(5) ? Double.NaN : c.getDouble(5);
                double regen = c.isNull(6) ? Double.NaN : c.getDouble(6);
                double power = c.isNull(7) ? Double.NaN : c.getDouble(7);
                totals.add(ts, odo, speed, gear, charging, spent, regen, power);
            }
        } finally { c.close(); }
        return totals;
    }

    public static String todayDateStr() {
        return DAY_FMT.format(new Date());
    }

    /** Returns the most recent N days for the bar chart. */
    public static List<DayItem> getRecentDays(Context ctx, int limitDays) {
        List<DayItem> list = new ArrayList<>();
        SQLiteDatabase db = CarDb.get(ctx).db();

        // 1. Fetch days from daily_stat
        Cursor c1 = db.rawQuery(
            "SELECT date, MAX(0, last_odo_km - first_odo_km) AS km FROM daily_stat "
          + "ORDER BY date DESC LIMIT ?", new String[]{String.valueOf(limitDays)});
        java.util.Map<String, Double> dayKms = new java.util.TreeMap<>();
        try {
            while (c1.moveToNext()) {
                dayKms.put(c1.getString(0), c1.getDouble(1));
            }
        } finally { c1.close(); }

        // 2. Fetch days currently in telemetry_sample not in daily_stat (e.g.
        // today). In practice that's only ever the last day or two -- older
        // days are already frozen into daily_stat by TelemetryRollup -- so
        // bound the GROUP BY to a generous recent window instead of grouping
        // the whole (up to 90-day) table on every chart load. ts_ms >= ? uses
        // idx_sample_ts to skip the rest before GROUP BY ever sees it.
        long recentCutoffMs = dayBoundsMs(todayDateStr())[0] - 7L * 24 * 3600 * 1000;
        Cursor c2 = db.rawQuery(
            "SELECT grp.day, MAX(0, last.odo_km - first.odo_km) AS km FROM "
          + "(SELECT date(ts_ms/1000,'unixepoch','localtime') AS day, "
          + "        MIN(id) AS first_id, MAX(id) AS last_id "
          + " FROM telemetry_sample WHERE odo_km IS NOT NULL AND ts_ms >= ? GROUP BY day) grp "
          + "JOIN telemetry_sample first ON first.id = grp.first_id "
          + "JOIN telemetry_sample last ON last.id = grp.last_id "
          + "WHERE grp.day NOT IN (SELECT date FROM daily_stat) "
          + "ORDER BY grp.day DESC LIMIT ?",
            new String[]{String.valueOf(recentCutoffMs), String.valueOf(limitDays)});
        try {
            while (c2.moveToNext()) {
                dayKms.put(c2.getString(0), c2.getDouble(1));
            }
        } finally { c2.close(); }

        String today = todayDateStr();
        if (!dayKms.containsKey(today)) {
            dayKms.put(today, 0.0);
        }

        // Live refine today's km with live CarActor odometer if available
        long[] todayBounds = dayBoundsMs(today);
        Cursor todayFirstC = db.rawQuery(
            "SELECT odo_km FROM telemetry_sample WHERE ts_ms >= ? AND ts_ms < ? "
          + "  AND odo_km IS NOT NULL ORDER BY id ASC LIMIT 1",
            new String[]{String.valueOf(todayBounds[0]), String.valueOf(todayBounds[1])});
        try {
            if (todayFirstC.moveToFirst()) {
                double firstOdo = todayFirstC.getDouble(0);
                com.geely.drivemem.car.CarActor.Reading odoR = com.geely.drivemem.car.CarActor.get(ctx).get("telemetry.odometer");
                if (odoR.status == com.geely.drivemem.car.CarActor.Reading.Status.OK && odoR.value instanceof Number) {
                    double liveOdo = ((Number) odoR.value).doubleValue();
                    if (liveOdo >= firstOdo) {
                        dayKms.put(today, liveOdo - firstOdo);
                    }
                }
            }
        } finally { todayFirstC.close(); }

        // Limit to latest limitDays and format
        List<String> sortedDays = new ArrayList<>(dayKms.keySet());
        int startIdx = Math.max(0, sortedDays.size() - limitDays);
        for (int i = startIdx; i < sortedDays.size(); i++) {
            String d = sortedDays.get(i);
            String label = shortLabel(d);
            list.add(new DayItem(d, label, dayKms.get(d)));
        }
        return list;
    }

    /** Returns the comprehensive overview and session log for a selected day. */
    public static DayOverview getDayOverview(Context ctx, String dateStr) {
        SQLiteDatabase db = CarDb.get(ctx).db();
        String displayDate = formatDisplayDate(dateStr);

        double distKm = 0;
        double dischargeKwh = 0;
        double regenKwh = 0;
        double netKwh = 0;
        double ascentDPlus = 0, descentDMinus = 0;
        int firstBatt = -1, lastBatt = -1, minBatt = -1, maxBatt = -1;
        double drivingMin = 0, avgSpeed = 0, avgTemp = 0;
        int chargeCount = 0; double chargeKwh = 0; double chargeCost = 0;

        String today = todayDateStr();
        boolean isToday = dateStr.equals(today);

        if (!isToday) {
            DayOverview cached = DAY_CACHE.get(dateStr);
            if (cached != null) return cached;
        }

        // Check if precomputed in daily_stat (only for past frozen days)
        boolean foundInStat = false;
        if (!isToday) {
            Cursor c = db.rawQuery(
                "SELECT first_odo_km, last_odo_km, first_battery_pct, last_battery_pct, "
              + "       min_battery_pct, max_battery_pct, avg_speed_kmh, avg_temp_c, "
              + "       ascent_m, descent_m, driving_minutes, charge_count, charge_kwh, "
              + "       discharge_kwh, regen_kwh, net_kwh, charge_cost "
              + "FROM daily_stat WHERE date = ?", new String[]{dateStr});
            try {
                if (c.moveToFirst()) {
                    foundInStat = true;
                    distKm = Math.max(0, c.getDouble(1) - c.getDouble(0));
                    firstBatt = c.isNull(2) ? -1 : c.getInt(2);
                    lastBatt = c.isNull(3) ? -1 : c.getInt(3);
                    minBatt = c.isNull(4) ? -1 : c.getInt(4);
                    maxBatt = c.isNull(5) ? -1 : c.getInt(5);
                    avgSpeed = c.getDouble(6);
                    avgTemp = c.getDouble(7);
                    ascentDPlus = c.getDouble(8);
                    descentDMinus = c.getDouble(9);
                    drivingMin = c.getDouble(10);
                    chargeCount = c.getInt(11);
                    chargeKwh = c.getDouble(12);
                    dischargeKwh = c.getDouble(13);
                    regenKwh = c.isNull(14) ? 0 : c.getDouble(14);
                    netKwh = c.isNull(15) ? (dischargeKwh - regenKwh) : c.getDouble(15);
                    chargeCost = c.getDouble(16);
                }
            } finally { c.close(); }
        }

        // A day with zero telemetry rows can't have any distance, battery
        // reading, trip, or charge either (every one of those is sampled
        // into telemetry_sample too, including while charging) -- so once
        // this cheap, INDEXED (idx_sample_ts) check comes back empty, skip
        // straight past the ~8 date()-filtered queries below entirely. Those
        // filter on date(ts_ms/1000,'unixepoch','localtime'), an expression
        // SQLite can't use idx_sample_ts for, so each one is a full table
        // scan -- fine once for Day mode's single date, ruinous once
        // Week/Month (recentWeeks/recentMonths) call this for every day of
        // an 8-week or 12-month window, most of which predate the car
        // having any data at all (found live: this was "basically halting
        // the app" on switching to Week/Month, 2026-09-13).
        if (!foundInStat && !isToday && !hasAnyTelemetry(db, dateStr)) {
            DayOverview empty = new DayOverview(dateStr, displayDate, 0, 0, 0, 0,
                0, 0, 0, 0, -1, -1, -1, -1, 0, 0, 0, 0, 0, new ArrayList<>());
            DAY_CACHE.put(dateStr, empty);
            return empty;
        }

        // If today or unfrozen, compute on the fly
        if (!foundInStat) {
            long[] bounds = dayBoundsMs(dateStr);
            String boundStart = String.valueOf(bounds[0]), boundEnd = String.valueOf(bounds[1]);

            // Distance & battery
            double firstOdo = -1;
            Cursor odoC = db.rawQuery(
                "SELECT first.odo_km, last.odo_km, first.battery_pct, last.battery_pct FROM "
              + "(SELECT MIN(id) as first_id, MAX(id) as last_id FROM telemetry_sample "
              + " WHERE ts_ms >= ? AND ts_ms < ? AND odo_km IS NOT NULL) grp "
              + "JOIN telemetry_sample first ON first.id = grp.first_id "
              + "JOIN telemetry_sample last ON last.id = grp.last_id",
                new String[]{boundStart, boundEnd});
            try {
                if (odoC.moveToFirst()) {
                    firstOdo = odoC.isNull(0) ? -1 : odoC.getDouble(0);
                    distKm = Math.max(0, odoC.getDouble(1) - odoC.getDouble(0));
                    firstBatt = odoC.isNull(2) ? -1 : odoC.getInt(2);
                    lastBatt = odoC.isNull(3) ? -1 : odoC.getInt(3);
                }
            } finally { odoC.close(); }

            if (isToday) {
                com.geely.drivemem.car.CarActor.Reading odoR = com.geely.drivemem.car.CarActor.get(ctx).get("telemetry.odometer");
                if (odoR.status == com.geely.drivemem.car.CarActor.Reading.Status.OK && odoR.value instanceof Number) {
                    double liveOdo = ((Number) odoR.value).doubleValue();
                    if (firstOdo >= 0 && liveOdo >= firstOdo) {
                        distKm = liveOdo - firstOdo;
                    }
                }
                com.geely.drivemem.car.CarActor.Reading battR = com.geely.drivemem.car.CarActor.get(ctx).get("telemetry.battery");
                if (battR.status == com.geely.drivemem.car.CarActor.Reading.Status.OK && battR.value instanceof Integer) {
                    int liveBatt = (Integer) battR.value;
                    if (firstBatt < 0) firstBatt = liveBatt;
                    lastBatt = liveBatt;
                }
            }

            Cursor minMaxC = db.rawQuery(
                "SELECT MIN(battery_pct), MAX(battery_pct), AVG(outside_temp_c) "
              + "FROM telemetry_sample WHERE ts_ms >= ? AND ts_ms < ?",
                new String[]{boundStart, boundEnd});
            try {
                if (minMaxC.moveToFirst()) {
                    minBatt = minMaxC.isNull(0) ? -1 : minMaxC.getInt(0);
                    maxBatt = minMaxC.isNull(1) ? -1 : minMaxC.getInt(1);
                    avgTemp = minMaxC.isNull(2) ? 0 : minMaxC.getDouble(2);
                }
            } finally { minMaxC.close(); }

            if (isToday) {
                if (lastBatt >= 0) {
                    if (minBatt < 0 || lastBatt < minBatt) minBatt = lastBatt;
                    if (maxBatt < 0 || lastBatt > maxBatt) maxBatt = lastBatt;
                }
                com.geely.drivemem.car.CarActor.Reading tempR = com.geely.drivemem.car.CarActor.get(ctx).get("telemetry.outside_temp");
                if (tempR.status == com.geely.drivemem.car.CarActor.Reading.Status.OK && tempR.value instanceof Number) {
                    double liveTemp = ((Number) tempR.value).doubleValue();
                    if (avgTemp == 0) avgTemp = liveTemp;
                }
            }

            Cursor tripC = db.rawQuery(
                "SELECT COALESCE(SUM(ascent_m),0), COALESCE(SUM(descent_m),0), "
              + "       COALESCE(SUM(end_ms - start_ms),0) FROM trip "
              + "WHERE start_ms >= ? AND start_ms < ? AND end_ms IS NOT NULL",
                new String[]{boundStart, boundEnd});
            try {
                if (tripC.moveToFirst()) {
                    ascentDPlus = tripC.getDouble(0);
                    descentDMinus = tripC.getDouble(1);
                    drivingMin = tripC.getDouble(2) / 60000.0;
                }
            } finally { tripC.close(); }

            if (isToday && com.geely.drivemem.state.TripSession.isTripActive()) {
                ascentDPlus += com.geely.drivemem.state.TripSession.getActiveTripAscentM();
                descentDMinus += com.geely.drivemem.state.TripSession.getActiveTripDescentM();
                long activeTripMs = System.currentTimeMillis() - com.geely.drivemem.state.TripSession.getActiveTripStartMs();
                if (activeTripMs > 0) {
                    drivingMin += (activeTripMs / 60000.0);
                }
            }

            // Avg speed = distance over driving duration, not a per-sample
            // average -- sampling gaps and idle jitter don't skew it.
            avgSpeed = drivingMin > 0 ? distKm / (drivingMin / 60.0) : 0;

            long activeId = com.geely.drivemem.state.ChargeSession.activeRowId();
            boolean excludeActive = activeId > 0 && com.geely.drivemem.state.ChargeSession.isCharging();
            String activeFilter = excludeActive ? " AND id != ?" : "";
            String[] chArgs = excludeActive
                ? new String[]{boundStart, boundEnd, String.valueOf(activeId)}
                : new String[]{boundStart, boundEnd};
            Cursor chC = db.rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(kwh),0), COALESCE(SUM(cost),0) FROM charge_session "
              + "WHERE start_ms >= ? AND start_ms < ?" + activeFilter, chArgs);
            try {
                if (chC.moveToFirst()) {
                    chargeCount = chC.getInt(0);
                    chargeKwh = chC.getDouble(1);
                    chargeCost = chC.getDouble(2);
                }
            } finally { chC.close(); }

            if (isToday && com.geely.drivemem.state.ChargeSession.isCharging()) {
                chargeCount += 1;
                chargeKwh += com.geely.drivemem.state.ChargeSession.currentKwh();
            }

            // Read all rows so Park and charging samples reset legacy integration.
            // Driving-gear samples at 0 km/h remain included for stopped traffic.
            DrivingConsumption energy = queryDrivingConsumption(db,
                "ts_ms >= ? AND ts_ms < ?", new String[]{boundStart, boundEnd});
            dischargeKwh = energy.totalSpent;
            regenKwh = energy.totalRegen;
            netKwh = dischargeKwh - regenKwh;
        } // end if (!foundInStat)

        double efficiencyKwh100km = DrivingConsumption.efficiencyKwh100km(distKm, dischargeKwh, regenKwh);
        double netElevation = ascentDPlus - descentDMinus;

        // Fetch ABRP-style sessions (trips & charges)
        List<DaySession> sessions = queryDaySessions(ctx, db, dateStr);

        DayOverview ov = new DayOverview(dateStr, displayDate, distKm, dischargeKwh, regenKwh, netKwh,
                efficiencyKwh100km, ascentDPlus, descentDMinus, netElevation, firstBatt, lastBatt,
                minBatt, maxBatt, drivingMin, avgSpeed, avgTemp, chargeCount, chargeKwh, sessions);
        ov.chargeCost = chargeCost;

        // Trivial point lookup against raw telemetry_sample -- works for both
        // today and already-frozen days, since freezing only summarizes into
        // daily_stat, it never deletes the raw rows (pruning is 90 days out,
        // see TelemetryRollup, far past the 14-day window this screen shows).
        // Runs even for an already-frozen day (unlike everything above,
        // which foundInStat skips), so this was the one full-scan Week/Month
        // paid on every single day of its window even after that skip.
        long[] altBounds = dayBoundsMs(dateStr);
        Cursor altC = db.rawQuery(
            "SELECT MAX(altitude_m) FROM telemetry_sample WHERE ts_ms >= ? AND ts_ms < ?",
            new String[]{String.valueOf(altBounds[0]), String.valueOf(altBounds[1])});
        try {
            if (altC.moveToFirst() && !altC.isNull(0)) ov.maxAltitudeM = altC.getDouble(0);
        } finally { altC.close(); }

        if (!isToday) DAY_CACHE.put(dateStr, ov);
        return ov;
    }

    private static List<DaySession> queryDaySessions(Context ctx, SQLiteDatabase db, String dateStr) {
        List<DaySession> out = new ArrayList<>();
        long[] bounds = dayBoundsMs(dateStr);
        String boundStart = String.valueOf(bounds[0]), boundEnd = String.valueOf(bounds[1]);

        // 1. Driving trips
        Cursor tc = db.rawQuery(
            "SELECT t.id, t.start_ms, t.end_ms, t.ascent_m, t.descent_m, t.regen_kwh, "
          + "       s1.odo_km, s2.odo_km, s1.battery_pct, s2.battery_pct, "
          + "       t.spent_kwh, t.net_kwh FROM trip t "
          + "LEFT JOIN telemetry_sample s1 ON t.start_sample_id = s1.id "
          + "LEFT JOIN telemetry_sample s2 ON t.end_sample_id = s2.id "
          + "WHERE t.start_ms >= ? AND t.start_ms < ? "
          + "AND NOT EXISTS (SELECT 1 FROM valet_session v WHERE t.start_ms >= v.start_ms "
          + "AND t.start_ms <= COALESCE(v.end_ms, 9223372036854775807)) "
          + "ORDER BY t.start_ms ASC", new String[]{boundStart, boundEnd});
        try {
            while (tc.moveToNext()) {
                long id = tc.getLong(0);
                long startMs = tc.getLong(1);
                long endMs = tc.isNull(2) ? 0 : tc.getLong(2);
                double ascent = tc.getDouble(3);
                double descent = tc.getDouble(4);
                double regen = tc.isNull(5) ? 0 : tc.getDouble(5);
                double odoStart = tc.isNull(6) ? -1 : tc.getDouble(6);
                double odoEnd = tc.isNull(7) ? -1 : tc.getDouble(7);
                int socStart = tc.isNull(8) ? -1 : tc.getInt(8);
                int socEnd = tc.isNull(9) ? -1 : tc.getInt(9);
                // trip.spent_kwh is NOT used for display — always recompute from
                // telemetry_sample so every trip is consistent regardless of era:
                //   • Legacy trips (pre-v8): spent_kwh = NULL, energy only in instant_power_kw_est
                //   • Modern trips: spent_kwh populated, but may be a partial sum if trips were
                //     manually merged (one leg NULL + one leg non-NULL → only partial kwh stored)
                // Recomputing from raw samples handles all cases uniformly.
                // Gear=P (4) excluded: parked standby during a trip window is not driving energy.
                double tripSpent = 0, tripNet = 0;
                double distanceKm = (odoStart > 0 && odoEnd >= odoStart) ? (odoEnd - odoStart) : 0;

                if (endMs > startMs) {
                    DrivingConsumption energy = queryDrivingConsumption(db,
                        "ts_ms BETWEEN ? AND ?",
                        new String[]{String.valueOf(startMs), String.valueOf(endMs)});
                    tripSpent = energy.totalSpent;
                    regen = energy.totalRegen;
                    tripNet = tripSpent - regen;
                }

                double eff = DrivingConsumption.efficiencyKwh100km(distanceKm, tripSpent, regen);
                out.add(new DriveSession(id, startMs, endMs, distanceKm, socStart, socEnd,
                        ascent, descent, regen, tripSpent, tripNet, eff));
            }
        } finally { tc.close(); }

        // 2. Active in-progress trip (if driving today)
        String today = todayDateStr();
        if (dateStr.equals(today) && com.geely.drivemem.state.TripSession.isTripActive()
                && !com.geely.drivemem.state.ValetSession.isActive(ctx)) {
            long startMs = com.geely.drivemem.state.TripSession.getActiveTripStartMs();
            long startSampleId = com.geely.drivemem.state.TripSession.getActiveTripStartSampleId();
            double odoStart = com.geely.drivemem.state.TripSession.getActiveTripStartOdoKm();
            int socStart = com.geely.drivemem.state.TripSession.getActiveTripStartSoc();

            if (odoStart < 0 || socStart < 0) {
                if (startSampleId > 0) {
                    Cursor sc = db.rawQuery(
                        "SELECT odo_km, battery_pct FROM telemetry_sample WHERE id = ?",
                        new String[]{String.valueOf(startSampleId)});
                    try {
                        if (sc.moveToFirst()) {
                            if (odoStart < 0 && !sc.isNull(0)) odoStart = sc.getDouble(0);
                            if (socStart < 0 && !sc.isNull(1)) socStart = sc.getInt(1);
                        }
                    } finally { sc.close(); }
                }
                if ((odoStart < 0 || socStart < 0) && startMs > 0) {
                    Cursor sc = db.rawQuery(
                        "SELECT odo_km, battery_pct FROM telemetry_sample WHERE ts_ms >= ? ORDER BY id ASC LIMIT 1",
                        new String[]{String.valueOf(startMs)});
                    try {
                        if (sc.moveToFirst()) {
                            if (odoStart < 0 && !sc.isNull(0)) odoStart = sc.getDouble(0);
                            if (socStart < 0 && !sc.isNull(1)) socStart = sc.getInt(1);
                        }
                    } finally { sc.close(); }
                }
            }

            double odoEnd = -1;
            int socEnd = -1;
            com.geely.drivemem.car.CarActor.Reading odoR = com.geely.drivemem.car.CarActor.get(ctx).get("telemetry.odometer");
            if (odoR.status == com.geely.drivemem.car.CarActor.Reading.Status.OK && odoR.value instanceof Number) {
                odoEnd = ((Number) odoR.value).doubleValue();
            }
            com.geely.drivemem.car.CarActor.Reading battR = com.geely.drivemem.car.CarActor.get(ctx).get("telemetry.battery");
            if (battR.status == com.geely.drivemem.car.CarActor.Reading.Status.OK && battR.value instanceof Integer) {
                socEnd = (Integer) battR.value;
            }

            if (odoEnd < 0 || socEnd < 0) {
                Cursor lastC = db.rawQuery(
                    "SELECT odo_km, battery_pct FROM telemetry_sample ORDER BY id DESC LIMIT 1", null);
                try {
                    if (lastC.moveToFirst()) {
                        if (odoEnd < 0 && !lastC.isNull(0)) odoEnd = lastC.getDouble(0);
                        if (socEnd < 0 && !lastC.isNull(1)) socEnd = lastC.getInt(1);
                    }
                } finally { lastC.close(); }
            }

            double distanceKm = (odoStart > 0 && odoEnd >= odoStart) ? (odoEnd - odoStart) : 0;
            double ascent = com.geely.drivemem.state.TripSession.getActiveTripAscentM();
            double descent = com.geely.drivemem.state.TripSession.getActiveTripDescentM();
            // Recompute from timestamped rows instead of EnergyIntegrator's live
            // trip accumulator. The trip remains open during the Park grace period,
            // while its displayed driving energy must stop immediately in Park.
            DrivingConsumption activeEnergy = queryDrivingConsumption(db,
                "ts_ms >= ?", new String[]{String.valueOf(startMs)});
            double spent = activeEnergy.totalSpent;
            double regen = activeEnergy.totalRegen;
            double net = spent - regen;
            double eff = DrivingConsumption.efficiencyKwh100km(distanceKm, spent, regen);

            // endMs = 0 indicates active session ("em andamento")
            out.add(new DriveSession(0, startMs, 0, distanceKm, socStart, socEnd,
                    ascent, descent, regen, spent, net, eff));
        }

        // 3. Explicit Valet intervals. Their original trips remain in the DB,
        // but the daily timeline presents the interval as one session.
        // "Started on or before this day AND (still open OR ended on or
        // after this day)" -- date(start_ms)<=dateStr is start_ms before
        // this day's exclusive end; date(end_ms)>=dateStr is end_ms at/after
        // this day's start. Same overlap test, index-friendly form.
        Cursor vc = db.rawQuery(
            "SELECT id, start_ms, end_ms, start_odo_km, end_odo_km, max_speed_kmh, max_power_kw, start_soc, end_soc "
          + "FROM valet_session WHERE start_ms < ? "
          + "AND (end_ms IS NULL OR end_ms >= ?) ORDER BY start_ms ASC",
            new String[]{boundEnd, boundStart});
        try {
            while (vc.moveToNext()) {
                double startOdo = vc.isNull(3) ? -1 : vc.getDouble(3);
                double endOdo = vc.isNull(4) ? -1 : vc.getDouble(4);
                double distance = startOdo >= 0 && endOdo >= startOdo ? endOdo - startOdo : 0;
                out.add(new ValetSessionItem(vc.getLong(0), vc.getLong(1), vc.isNull(2) ? 0 : vc.getLong(2), distance,
                    vc.isNull(5) ? 0 : vc.getDouble(5), vc.isNull(6) ? null : vc.getDouble(6),
                    vc.isNull(7) ? -1 : vc.getInt(7), vc.isNull(8) ? -1 : vc.getInt(8)));
            }
        } finally { vc.close(); }

        // 4. Charges
        long activeId = com.geely.drivemem.state.ChargeSession.activeRowId();
        boolean excludeActive = activeId > 0 && com.geely.drivemem.state.ChargeSession.isCharging();
        String activeFilter = excludeActive ? " AND c.id != ? " : "";
        String[] ccArgs = excludeActive
            ? new String[]{boundStart, boundEnd, String.valueOf(activeId)}
            : new String[]{boundStart, boundEnd};
        Cursor cc = db.rawQuery(
            "SELECT c.id, c.start_ms, c.end_ms, c.soc_start, c.soc_end, c.kwh, c.avg_power_w, "
          + "       c.max_charge_v, c.cost FROM charge_session c "
          + "LEFT JOIN telemetry_sample s1 ON c.start_sample_id = s1.id "
          + "WHERE c.start_ms >= ? AND c.start_ms < ?" + activeFilter
          + "ORDER BY c.start_ms ASC", ccArgs);
        try {
            while (cc.moveToNext()) {
                long id = cc.getLong(0);
                long startMs = cc.getLong(1);
                long endMs = cc.getLong(2);
                int socStart = cc.getInt(3);
                int socEnd = cc.getInt(4);
                double kwh = cc.getDouble(5);
                double avgPowerKw = cc.getDouble(6) / 1000.0;
                Float v = cc.isNull(7) ? null : cc.getFloat(7);
                boolean isDcfc = v != null && v >= 250f;
                Double cost = cc.isNull(8) ? null : cc.getDouble(8);
                out.add(new ChargeSessionItem(id, startMs, endMs, socStart, socEnd, kwh, avgPowerKw, isDcfc, cost));
            }
        } finally { cc.close(); }

        // 5. Active in-progress charge (if charging today)
        if (dateStr.equals(today) && com.geely.drivemem.state.ChargeSession.isCharging()) {
            long startMs = com.geely.drivemem.state.ChargeSession.currentStartWallMs();
            int socStart = com.geely.drivemem.state.ChargeSession.currentSocStart();
            int socEnd = com.geely.drivemem.state.ChargeSession.currentSocEnd();
            double kwh = com.geely.drivemem.state.ChargeSession.currentKwh();
            long durMs = System.currentTimeMillis() - startMs;
            double avgPowerKw = (durMs > 0) ? (kwh / (durMs / 3_600_000.0)) : 0.0;
            Float v = null;
            com.geely.drivemem.car.CarActor.Reading vR = com.geely.drivemem.car.CarActor.get(ctx).get("telemetry.charge_v");
            if (vR.status == com.geely.drivemem.car.CarActor.Reading.Status.OK && vR.value instanceof Float) {
                v = (Float) vR.value;
            }
            boolean isDcfc = v != null && v >= 250f;
            out.add(new ChargeSessionItem(0, startMs, 0, socStart, socEnd, kwh, avgPowerKw, isDcfc));
        }

        // Sort interleaved sessions chronologically
        out.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        return out;
    }

    private static String shortLabel(String dateStr) {
        try { return SHORT_DAY_FMT.format(DAY_FMT.parse(dateStr)); }
        catch (Exception e) { return dateStr; }
    }

    private static String formatDisplayDate(String dateStr) {
        try {
            String s = DISPLAY_DAY_FMT.format(DAY_FMT.parse(dateStr));
            if (s.length() > 0) return Character.toUpperCase(s.charAt(0)) + s.substring(1);
            return s;
        } catch (Exception e) { return dateStr; }
    }

    private static String formatDuration(long startMs, long endMs) {
        long s = Math.max(0, (endMs - startMs) / 1000);
        long h = s / 3600, m = (s % 3600) / 60;
        if (h > 0) return h + "h " + (m < 10 ? "0" : "") + m + "m";
        return m + "m";
    }

    private DailyStatsProvider() {}
}
