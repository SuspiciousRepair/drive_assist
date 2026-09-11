package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarDb;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Provides aggregated daily statistics, altimetry (D+/D-),
 * and ABRP-style chronological sessions (trips and charges) for any calendar day.
 */
public final class DailyStatsProvider {

    private static final SimpleDateFormat DAY_FMT = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat SHORT_DAY_FMT = new SimpleDateFormat("d/M", Locale.US);
    private static final SimpleDateFormat DISPLAY_DAY_FMT = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("pt", "BR"));
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm", Locale.US);

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

        // 2. Fetch days currently in telemetry_sample not in daily_stat (e.g. today)
        Cursor c2 = db.rawQuery(
            "SELECT grp.day, MAX(0, last.odo_km - first.odo_km) AS km FROM "
          + "(SELECT date(ts_ms/1000,'unixepoch','localtime') AS day, "
          + "        MIN(id) AS first_id, MAX(id) AS last_id "
          + " FROM telemetry_sample WHERE odo_km IS NOT NULL GROUP BY day) grp "
          + "JOIN telemetry_sample first ON first.id = grp.first_id "
          + "JOIN telemetry_sample last ON last.id = grp.last_id "
          + "WHERE grp.day NOT IN (SELECT date FROM daily_stat) "
          + "ORDER BY grp.day DESC LIMIT ?", new String[]{String.valueOf(limitDays)});
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
        Cursor todayFirstC = db.rawQuery(
            "SELECT odo_km FROM telemetry_sample WHERE date(ts_ms/1000,'unixepoch','localtime') = ? "
          + "  AND odo_km IS NOT NULL ORDER BY id ASC LIMIT 1", new String[]{today});
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
        int chargeCount = 0; double chargeKwh = 0;

        String today = todayDateStr();
        boolean isToday = dateStr.equals(today);

        // Check if precomputed in daily_stat (only for past frozen days)
        boolean foundInStat = false;
        if (!isToday) {
            Cursor c = db.rawQuery(
                "SELECT first_odo_km, last_odo_km, first_battery_pct, last_battery_pct, "
              + "       min_battery_pct, max_battery_pct, avg_speed_kmh, avg_temp_c, "
              + "       ascent_m, descent_m, driving_minutes, charge_count, charge_kwh, "
              + "       discharge_kwh, regen_kwh, net_kwh "
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
                }
            } finally { c.close(); }
        }

        // If today or unfrozen, compute on the fly
        if (!foundInStat) {
            // Distance & battery
            double firstOdo = -1;
            Cursor odoC = db.rawQuery(
                "SELECT first.odo_km, last.odo_km, first.battery_pct, last.battery_pct FROM "
              + "(SELECT MIN(id) as first_id, MAX(id) as last_id FROM telemetry_sample "
              + " WHERE date(ts_ms/1000,'unixepoch','localtime') = ? AND odo_km IS NOT NULL) grp "
              + "JOIN telemetry_sample first ON first.id = grp.first_id "
              + "JOIN telemetry_sample last ON last.id = grp.last_id", new String[]{dateStr});
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
                "SELECT MIN(battery_pct), MAX(battery_pct), AVG(speed_kmh), AVG(outside_temp_c) "
              + "FROM telemetry_sample WHERE date(ts_ms/1000,'unixepoch','localtime') = ?",
                new String[]{dateStr});
            try {
                if (minMaxC.moveToFirst()) {
                    minBatt = minMaxC.isNull(0) ? -1 : minMaxC.getInt(0);
                    maxBatt = minMaxC.isNull(1) ? -1 : minMaxC.getInt(1);
                    avgSpeed = minMaxC.isNull(2) ? 0 : minMaxC.getDouble(2);
                    avgTemp = minMaxC.isNull(3) ? 0 : minMaxC.getDouble(3);
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
              + "WHERE date(start_ms/1000,'unixepoch','localtime') = ? AND end_ms IS NOT NULL",
                new String[]{dateStr});
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

            long activeId = com.geely.drivemem.state.ChargeSession.activeRowId();
            boolean excludeActive = activeId > 0 && com.geely.drivemem.state.ChargeSession.isCharging();
            String activeFilter = excludeActive ? " AND id != ?" : "";
            String[] chArgs = excludeActive
                ? new String[]{dateStr, String.valueOf(activeId)} : new String[]{dateStr};
            Cursor chC = db.rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(kwh),0) FROM charge_session "
              + "WHERE date(start_ms/1000,'unixepoch','localtime') = ?" + activeFilter, chArgs);
            try {
                if (chC.moveToFirst()) {
                    chargeCount = chC.getInt(0);
                    chargeKwh = chC.getDouble(1);
                }
            } finally { chC.close(); }

            if (isToday && com.geely.drivemem.state.ChargeSession.isCharging()) {
                chargeCount += 1;
                chargeKwh += com.geely.drivemem.state.ChargeSession.currentKwh();
            }

            // Integrate 3 power metrics (net, power spent, regen) for today.
            // Exclude charging samples (is_charging = 1) so charging energy is not mixed with driving energy.
            Cursor pC = db.rawQuery(
                "SELECT COALESCE(SUM(energy_spent_kwh), 0), "
              + "       COALESCE(SUM(energy_regen_kwh), 0), "
              + "       COUNT(energy_spent_kwh), "
              + "       COUNT(CASE WHEN energy_spent_kwh IS NULL AND instant_power_kw_est IS NOT NULL THEN 1 END) "
              + "FROM telemetry_sample "
              + "WHERE date(ts_ms/1000,'unixepoch','localtime') = ? "
              + "  AND (is_charging IS NULL OR is_charging = 0)", new String[]{dateStr});
            try {
                if (pC.moveToFirst()) {
                    int newCount = pC.getInt(2);
                    int legCount = pC.getInt(3);
                    if (newCount > 0) {
                        dischargeKwh += pC.getDouble(0);
                        regenKwh += pC.getDouble(1);
                    }
                    if (legCount > 0) {
                        // Integrate legacy samples that do not have energy_spent_kwh
                        Cursor legC = db.rawQuery(
                            "SELECT ts_ms, instant_power_kw_est FROM telemetry_sample "
                          + "WHERE date(ts_ms/1000,'unixepoch','localtime') = ? "
                          + "  AND energy_spent_kwh IS NULL AND instant_power_kw_est IS NOT NULL "
                          + "  AND (is_charging IS NULL OR is_charging = 0) "
                          + "ORDER BY ts_ms ASC", new String[]{dateStr});
                        try {
                            long prevTs = -1;
                            while (legC.moveToNext()) {
                                long ts = legC.getLong(0);
                                double kw = legC.getDouble(1);
                                if (prevTs != -1) {
                                    double hours = (ts - prevTs) / 3_600_000.0;
                                    if (hours > 0 && hours <= (60.0 / 3600.0)) {
                                        if (kw >= 0) dischargeKwh += kw * hours;
                                        else regenKwh += -kw * hours;
                                    }
                                }
                                prevTs = ts;
                            }
                        } finally { legC.close(); }
                    }
                    netKwh = dischargeKwh - regenKwh;
                }
            } finally { pC.close(); }
        }

        double efficiencyKwh100km = (distKm > 0.2 && netKwh > 0)
                ? (netKwh / distKm) * 100.0
                : ((distKm > 0.2 && dischargeKwh > 0) ? (dischargeKwh / distKm) * 100.0 : 0.0);
        double netElevation = ascentDPlus - descentDMinus;

        // Fetch ABRP-style sessions (trips & charges)
        List<DaySession> sessions = queryDaySessions(ctx, db, dateStr);

        return new DayOverview(dateStr, displayDate, distKm, dischargeKwh, regenKwh, netKwh,
                efficiencyKwh100km, ascentDPlus, descentDMinus, netElevation, firstBatt, lastBatt,
                minBatt, maxBatt, drivingMin, avgSpeed, avgTemp, chargeCount, chargeKwh, sessions);
    }

    private static List<DaySession> queryDaySessions(Context ctx, SQLiteDatabase db, String dateStr) {
        List<DaySession> out = new ArrayList<>();

        // 1. Driving trips
        Cursor tc = db.rawQuery(
            "SELECT t.id, t.start_ms, t.end_ms, t.ascent_m, t.descent_m, t.regen_kwh, "
          + "       s1.odo_km, s2.odo_km, s1.battery_pct, s2.battery_pct, "
          + "       t.spent_kwh, t.net_kwh FROM trip t "
          + "LEFT JOIN telemetry_sample s1 ON t.start_sample_id = s1.id "
          + "LEFT JOIN telemetry_sample s2 ON t.end_sample_id = s2.id "
          + "WHERE date(t.start_ms/1000,'unixepoch','localtime') = ? "
          + "ORDER BY t.start_ms ASC", new String[]{dateStr});
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
                double tripSpent = tc.isNull(10) ? -1 : tc.getDouble(10);
                double tripNet = tc.isNull(11) ? -1 : tc.getDouble(11);

                double distanceKm = (odoStart >= 0 && odoEnd >= odoStart) ? (odoEnd - odoStart) : 0;

                // Calculate trip energy if not already recorded on trip row
                if (tripSpent < 0 || tripNet < 0) {
                    if (endMs > startMs) {
                        Cursor sc = db.rawQuery(
                            "SELECT COALESCE(SUM(energy_spent_kwh), 0), "
                          + "       COALESCE(SUM(energy_regen_kwh), 0), "
                          + "       COUNT(energy_spent_kwh), "
                          + "       COUNT(CASE WHEN energy_spent_kwh IS NULL AND instant_power_kw_est IS NOT NULL THEN 1 END) "
                          + "FROM telemetry_sample "
                          + "WHERE ts_ms BETWEEN ? AND ? "
                          + "  AND (is_charging IS NULL OR is_charging = 0)",
                            new String[]{String.valueOf(startMs), String.valueOf(endMs)});
                        try {
                            if (sc.moveToFirst()) {
                                int newCount = sc.getInt(2);
                                int legCount = sc.getInt(3);
                                double spent = 0;
                                double legRegen = 0;
                                if (newCount > 0) {
                                    spent += sc.getDouble(0);
                                    legRegen += sc.getDouble(1);
                                }
                                if (legCount > 0) {
                                    Cursor legSc = db.rawQuery(
                                        "SELECT ts_ms, instant_power_kw_est FROM telemetry_sample "
                                      + "WHERE ts_ms BETWEEN ? AND ? AND energy_spent_kwh IS NULL AND instant_power_kw_est IS NOT NULL "
                                      + "  AND (is_charging IS NULL OR is_charging = 0) "
                                      + "ORDER BY ts_ms ASC",
                                        new String[]{String.valueOf(startMs), String.valueOf(endMs)});
                                    try {
                                        long prevTs = -1;
                                        while (legSc.moveToNext()) {
                                            long ts = legSc.getLong(0);
                                            double kw = legSc.getDouble(1);
                                            if (prevTs != -1) {
                                                double hours = (ts - prevTs) / 3_600_000.0;
                                                if (hours > 0 && hours <= (60.0 / 3600.0)) {
                                                    if (kw >= 0) spent += kw * hours;
                                                    else legRegen += -kw * hours;
                                                }
                                            }
                                            prevTs = ts;
                                        }
                                    } finally { legSc.close(); }
                                }
                                tripSpent = spent;
                                if (regen <= 0) regen = legRegen;
                                tripNet = tripSpent - regen;
                            }
                        } finally { sc.close(); }
                    } else {
                        tripSpent = 0;
                        tripNet = 0;
                    }
                }

                double eff = (distanceKm > 0.2 && tripNet > 0) ? (tripNet / distanceKm) * 100.0
                        : ((distanceKm > 0.2 && tripSpent > 0) ? (tripSpent / distanceKm) * 100.0 : 0.0);
                out.add(new DriveSession(id, startMs, endMs, distanceKm, socStart, socEnd,
                        ascent, descent, regen, tripSpent, tripNet, eff));
            }
        } finally { tc.close(); }

        // 2. Active in-progress trip (if driving today)
        String today = todayDateStr();
        if (dateStr.equals(today) && com.geely.drivemem.state.TripSession.isTripActive()) {
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

            double distanceKm = (odoStart >= 0 && odoEnd >= odoStart) ? (odoEnd - odoStart) : 0;
            double ascent = com.geely.drivemem.state.TripSession.getActiveTripAscentM();
            double descent = com.geely.drivemem.state.TripSession.getActiveTripDescentM();
            EnergyIntegrator.TripSnapshot ts = EnergyIntegrator.currentTrip();
            double spent = ts.spentKwh;
            double regen = ts.regenKwh;
            double net = ts.netKwh;
            double eff = (distanceKm > 0.2 && net > 0) ? (net / distanceKm) * 100.0
                    : ((distanceKm > 0.2 && spent > 0) ? (spent / distanceKm) * 100.0 : 0.0);

            // endMs = 0 indicates active session ("em andamento")
            out.add(new DriveSession(0, startMs, 0, distanceKm, socStart, socEnd,
                    ascent, descent, regen, spent, net, eff));
        }

        // 3. Charges
        long activeId = com.geely.drivemem.state.ChargeSession.activeRowId();
        boolean excludeActive = activeId > 0 && com.geely.drivemem.state.ChargeSession.isCharging();
        String activeFilter = excludeActive ? " AND c.id != ? " : "";
        String[] ccArgs = excludeActive
            ? new String[]{dateStr, String.valueOf(activeId)} : new String[]{dateStr};
        Cursor cc = db.rawQuery(
            "SELECT c.id, c.start_ms, c.end_ms, c.soc_start, c.soc_end, c.kwh, c.avg_power_w, "
          + "       s1.charge_v, c.cost FROM charge_session c "
          + "LEFT JOIN telemetry_sample s1 ON c.start_sample_id = s1.id "
          + "WHERE date(c.start_ms/1000,'unixepoch','localtime') = ? " + activeFilter
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
                boolean isDcfc = (v != null && v >= 250f) || avgPowerKw > 22.0;
                Double cost = cc.isNull(8) ? null : cc.getDouble(8);
                out.add(new ChargeSessionItem(id, startMs, endMs, socStart, socEnd, kwh, avgPowerKw, isDcfc, cost));
            }
        } finally { cc.close(); }

        // 4. Active in-progress charge (if charging today)
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
            boolean isDcfc = (v != null && v >= 250f) || avgPowerKw > 22.0;
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
