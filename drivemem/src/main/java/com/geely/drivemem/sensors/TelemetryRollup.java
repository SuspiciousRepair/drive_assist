package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.Telemetry;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.util.DbMigration;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Aggregates telemetry samples into daily summaries and prunes old raw data.
 *
 * Runs once per calendar day (guarded by SharedPreferences). First freezes all
 * completed days into daily_stat, computing aggregates (speed, temperature, etc.)
 * from raw telemetry_sample rows. Second, prunes raw samples older than RETAIN_DAYS
 * but only for days already frozen in daily_stat, preserving unfrozen data.
 */
public final class TelemetryRollup {
    private static final String TAG = CarAccess.TAG;
    // Version the guard whenever a DB migration invalidates frozen summaries,
    // so an update installed after today's rollup still rebuilds them at once.
    private static final String PREF_KEY = "rollup_last_day_v10";
    private static final int RETAIN_DAYS = 90;
    private static final SimpleDateFormat DAY_FMT = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private TelemetryRollup() {}

    /** [start, end) epoch-ms bounds of the local calendar day `dateStr` --
     * lets a query use idx_sample_ts (ts_ms range) instead of wrapping the
     * column in date(ts_ms/1000,'unixepoch','localtime'), which forces a
     * full table scan every time (see DailyStatsProvider's own copy of this
     * helper for the measured cost: ~1.2s vs ~0.0004s on a 90-day table). */
    private static long[] dayBoundsMs(String dateStr) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0);
        try { cal.setTime(DAY_FMT.parse(dateStr)); } catch (Exception ignored) { /* keep today */ }
        long start = cal.getTimeInMillis();
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
        return new long[]{start, cal.getTimeInMillis()};
    }

    /** Freezes completed days and prunes old telemetry if not already done today. */
    public static void runIfDue(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final SharedPreferences p = app.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
        final String today = DAY_FMT.format(new Date());

        CarDb.get(app).write(() -> {
            if (today.equals(p.getString(PREF_KEY, ""))) return;
            try {
                SQLiteDatabase db = CarDb.get(app).db();
                freezeCompletedDays(db, today);
                pruneOldRawRows(db);
                p.edit().putString(PREF_KEY, today).apply();
                Log.i(TAG, "rollup: done for " + today);
            } catch (Throwable t) {
                // Not persisting PREF_KEY on failure: same reasoning as
                // DbMigration -- a broken run should retry next start, not
                // silently skip a day's freeze/prune forever.
                Log.w(TAG, "rollup: " + t);
            }
        });
    }

    // Ascent/descent/trip_count/driving_minutes come from `trip`, and
    // charge_count/charge_kwh from `charge_session` -- both already
    // permanent, already-computed facts (see CarDb's daily_stat comment on
    // why these are frozen COPIES, not recomputed from raw telemetry). Speed
    // and temperature are the only real aggregates over telemetry_sample
    // itself; odometer and battery reuse the same "first/last chronological
    // sample of the day" pair, since that's one pair of rows regardless of
    // which columns are being read off them.
    private static void freezeCompletedDays(SQLiteDatabase db, String today) {
        List<String> days = new ArrayList<>();
        Map<String, double[]> odoBatt = new HashMap<>();   // firstOdo, lastOdo, firstBatt, lastBatt
        Cursor c = db.rawQuery(
            "SELECT grp.day, first.odo_km, last.odo_km, first.battery_pct, last.battery_pct FROM "
          + "(SELECT date(ts_ms/1000,'unixepoch','localtime') AS day, "
          + "        MIN(id) AS first_id, MAX(id) AS last_id "
          + " FROM telemetry_sample WHERE odo_km IS NOT NULL GROUP BY day) grp "
          + "JOIN telemetry_sample first ON first.id = grp.first_id "
          + "JOIN telemetry_sample last ON last.id = grp.last_id "
          + "WHERE grp.day < ? AND grp.day NOT IN (SELECT date FROM daily_stat)",
            new String[]{today});
        try {
            while (c.moveToNext()) {
                String day = c.getString(0);
                days.add(day);
                // NaN, not 0, when the day's first/last sample happens to be
                // one of DbMigration's old sparse rows (odo_km only, no
                // battery_pct) -- getDouble() on a NULL column silently
                // returns 0, which would otherwise freeze a false "0%
                // battery" into a permanent row forever.
                odoBatt.put(day, new double[]{
                    c.getDouble(1), c.getDouble(2),
                    c.isNull(3) ? Double.NaN : c.getDouble(3),
                    c.isNull(4) ? Double.NaN : c.getDouble(4)});
            }
        } finally { c.close(); }
        if (days.isEmpty()) return;

        // The above must scan the whole table once, to discover which days
        // even exist -- but every query below already knows exactly which
        // days (the `days` list), so it can bound itself to that range's
        // ts_ms/start_ms via idx_sample_ts instead of re-scanning all 90
        // days of history every time this runs (this job scoped a fixed
        // handful of queries instead of one per day, but not once per run
        // either -- unbounded, it paid the same date()-forced full scan
        // this whole 2026-09-13 session's other fixes removed elsewhere).
        String minDay = days.get(0), maxDay = days.get(0);
        for (String d : days) {
            if (d.compareTo(minDay) < 0) minDay = d;
            if (d.compareTo(maxDay) > 0) maxDay = d;
        }
        String rangeStart = String.valueOf(dayBoundsMs(minDay)[0]);
        String rangeEnd = String.valueOf(dayBoundsMs(maxDay)[1]);

        // MAX(speed_kmh) only -- avg_speed_kmh below is distance/duration,
        // not a per-sample average.
        Map<String, double[]> speed = groupedByDay(db,
            "SELECT date(ts_ms/1000,'unixepoch','localtime') AS day, MAX(speed_kmh) "
          + "FROM telemetry_sample WHERE speed_kmh > 0 AND ts_ms >= ? AND ts_ms < ? GROUP BY day",
            1, rangeStart, rangeEnd);
        Map<String, double[]> temp = groupedByDay(db,
            "SELECT date(ts_ms/1000,'unixepoch','localtime') AS day, "
          + "MIN(outside_temp_c), AVG(outside_temp_c), MAX(outside_temp_c) "
          + "FROM telemetry_sample WHERE outside_temp_c IS NOT NULL AND ts_ms >= ? AND ts_ms < ? GROUP BY day",
            3, rangeStart, rangeEnd);
        Map<String, double[]> battRange = groupedByDay(db,
            "SELECT date(ts_ms/1000,'unixepoch','localtime') AS day, MIN(battery_pct), MAX(battery_pct) "
          + "FROM telemetry_sample WHERE battery_pct IS NOT NULL AND ts_ms >= ? AND ts_ms < ? GROUP BY day",
            2, rangeStart, rangeEnd);
        Map<String, double[]> trips = groupedByDay(db,
            "SELECT date(start_ms/1000,'unixepoch','localtime') AS day, "
          + "COALESCE(SUM(ascent_m),0), COALESCE(SUM(descent_m),0), COUNT(*), COALESCE(SUM(end_ms-start_ms),0) "
          + "FROM trip WHERE end_ms IS NOT NULL AND start_ms >= ? AND start_ms < ? GROUP BY day",
            4, rangeStart, rangeEnd);
        Map<String, double[]> charges = groupedByDay(db,
            "SELECT date(start_ms/1000,'unixepoch','localtime') AS day, COUNT(*), COALESCE(SUM(kwh),0), COALESCE(SUM(cost),0) "
          + "FROM charge_session WHERE start_ms >= ? AND start_ms < ? GROUP BY day",
            3, rangeStart, rangeEnd);
        Map<String, EnergyStats> energy = energyByDay(db, days, rangeStart, rangeEnd);

        for (String day : days) {
            double[] ob = odoBatt.get(day);
            double[] sp = speed.get(day);
            double[] tp = temp.get(day);
            double[] mb = battRange.get(day);
            double[] tr = trips.get(day);
            double[] ch = charges.get(day);

            ContentValues v = new ContentValues();
            v.put("date", day);
            v.put("first_odo_km", ob[0]);
            v.put("last_odo_km", ob[1]);
            if (!Double.isNaN(ob[2])) v.put("first_battery_pct", (int) ob[2]);
            if (!Double.isNaN(ob[3])) v.put("last_battery_pct", (int) ob[3]);
            if (mb != null) { v.put("min_battery_pct", (int) mb[0]); v.put("max_battery_pct", (int) mb[1]); }
            if (sp != null) v.put("max_speed_kmh", sp[0]);
            if (tp != null) { v.put("min_temp_c", tp[0]); v.put("avg_temp_c", tp[1]); v.put("max_temp_c", tp[2]); }
            v.put("ascent_m", tr != null ? tr[0] : 0);
            v.put("descent_m", tr != null ? tr[1] : 0);
            v.put("trip_count", tr != null ? (int) tr[2] : 0);
            double drivingMinutes = tr != null ? tr[3] / 60000.0 : 0;
            v.put("driving_minutes", drivingMinutes);
            // Avg speed = distance over driving duration, not a per-sample
            // average -- sampling gaps and idle jitter don't skew it.
            double dayDistKm = ob[1] - ob[0];
            v.put("avg_speed_kmh", drivingMinutes > 0 ? dayDistKm / (drivingMinutes / 60.0) : 0);
            v.put("charge_count", ch != null ? (int) ch[0] : 0);
            v.put("charge_kwh", ch != null ? ch[1] : 0);
            v.put("charge_cost", ch != null ? ch[2] : 0.0);
            EnergyStats es = energy.get(day);
            v.put("discharge_kwh", es != null ? es.spentKwh : 0.0);
            v.put("regen_kwh", es != null ? es.regenKwh : 0.0);
            v.put("net_kwh", es != null ? es.netKwh : 0.0);

            db.insertWithOnConflict("daily_stat", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        }
        Log.i(TAG, "rollup: froze " + days.size() + " day(s) into daily_stat");
    }

    // Column 0 of `sql` must be the day string; the rest are numeric and
    // land in the returned array in order. One query per metric group
    // instead of one query per day, so backfilling months of history at
    // once is still a fixed handful of queries, not N. `boundArgs` is the
    // [rangeStart, rangeEnd] pair `sql`'s own ts_ms/start_ms range filter
    // expects, in order -- omit for a query with no such filter.
    private static Map<String, double[]> groupedByDay(SQLiteDatabase db, String sql, int cols, String... boundArgs) {
        Map<String, double[]> out = new HashMap<>();
        Cursor c = db.rawQuery(sql, boundArgs.length > 0 ? boundArgs : null);
        try {
            while (c.moveToNext()) {
                double[] vals = new double[cols];
                for (int i = 0; i < cols; i++) vals[i] = c.getDouble(i + 1);
                out.put(c.getString(0), vals);
            }
        } finally { c.close(); }
        return out;
    }

    static final class EnergyStats {
        final double spentKwh;
        final double regenKwh;
        final double netKwh;
        EnergyStats(double spentKwh, double regenKwh, double netKwh) {
            this.spentKwh = spentKwh; this.regenKwh = regenKwh; this.netKwh = netKwh;
        }
    }

    // Energy metrics per day across 3 distinct dimensions:
    // 1. Power (net kWh = spent - regen)
    // 2. Power spent (gross positive consumption kWh >= 0)
    // 3. Regen (energy recovered via regenerative braking kWh >= 0)
    //
    // Uses direct sums over high-frequency integrated telemetry_sample columns
    // (energy_spent_kwh, energy_regen_kwh, energy_net_kwh) when available, and
    // falls back to rectangular integration separating positive/negative power
    // for legacy samples.
    private static Map<String, EnergyStats> energyByDay(SQLiteDatabase db, List<String> days,
            String rangeStart, String rangeEnd) {
        Map<String, EnergyStats> out = new HashMap<>();
        if (days.isEmpty()) return out;

        Map<String, DrivingConsumption> acc = new HashMap<>();
        for (String d : days) acc.put(d, new DrivingConsumption());

        // Bounded to [rangeStart, rangeEnd) via idx_sample_ts, not a
        // date() IN-list (which can't use that index and forces a full
        // scan) -- a non-contiguous `days` list can pull in a few rows for
        // an already-frozen day in between, but acc.get(day) below is null
        // for those and they're skipped, same as before this bound existed.
        //
        // Include every row. DrivingConsumption excludes Park/charging energy and
        // uses those rows to break the legacy integration chain. A zero-speed row
        // in a driving gear remains part of consumption, as it should in traffic.
        Cursor c = db.rawQuery(
            "SELECT date(ts_ms/1000,'unixepoch','localtime') AS day, "
          + "       ts_ms, odo_km, speed_kmh, gear, is_charging, "
          + "       energy_spent_kwh, energy_regen_kwh, instant_power_kw_est "
          + "FROM telemetry_sample "
          + "WHERE ts_ms >= ? AND ts_ms < ? "
          + "ORDER BY day ASC, ts_ms ASC, id ASC", new String[]{rangeStart, rangeEnd});
        try {
            while (c.moveToNext()) {
                String day = c.getString(0);
                DrivingConsumption a = acc.get(day);
                if (a == null) continue;
                long ts = c.getLong(1);
                double odo = c.isNull(2) ? Double.NaN : c.getDouble(2);
                double speed = c.isNull(3) ? Double.NaN : c.getDouble(3);
                Integer gear = c.isNull(4) ? null : c.getInt(4);
                boolean charging = !c.isNull(5) && c.getInt(5) != 0;
                double spent = c.isNull(6) ? Double.NaN : c.getDouble(6);
                double regen = c.isNull(7) ? Double.NaN : c.getDouble(7);
                double power = c.isNull(8) ? Double.NaN : c.getDouble(8);
                a.add(ts, odo, speed, gear, charging, spent, regen, power);
            }
        } finally { c.close(); }

        for (Map.Entry<String, DrivingConsumption> e : acc.entrySet()) {
            double spent = e.getValue().totalSpent;
            double regen = e.getValue().totalRegen;
            out.put(e.getKey(), new EnergyStats(spent, regen, spent - regen));
        }
        return out;
    }

    private static void pruneOldRawRows(SQLiteDatabase db) {
        long cutoffMs = System.currentTimeMillis() - RETAIN_DAYS * 24L * 3600 * 1000;
        int n = db.delete("telemetry_sample",
            "ts_ms < ? AND date(ts_ms/1000,'unixepoch','localtime') IN (SELECT date FROM daily_stat)",
            new String[]{String.valueOf(cutoffMs)});
        if (n > 0) Log.i(TAG, "rollup: pruned " + n + " raw row(s) older than " + RETAIN_DAYS + "d");
    }
}
