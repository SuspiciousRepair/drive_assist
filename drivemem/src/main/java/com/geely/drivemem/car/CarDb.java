package com.geely.drivemem.car;

import com.geely.drivemem.hvac.ComfortEvents;
import com.geely.drivemem.sensors.DrivingConsumption;
import com.geely.drivemem.sensors.EnergySource;
import com.geely.drivemem.sensors.OdoStats;
import com.geely.drivemem.sensors.TelemetryRollup;
import com.geely.drivemem.sensors.TelemetrySampler;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.state.ParkSession;
import com.geely.drivemem.state.TripSession;
import com.geely.drivemem.util.DbMigration;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.HandlerThread;

/** Unified database for all car telemetry, charging sessions, parking events,
 * and comfort events. Uses a single writer thread to serialize all writes
 * (SQLite constraint), while reads are synchronous and thread-safe. */
// One writer thread for the whole app — SQLite tolerates only one writer
// at a time. Reads are synchronous and callable from any thread.
public final class CarDb extends SQLiteOpenHelper {
    private static final String NAME = "car.db";
    // A branch that requested VERSION 10 got deployed once, on 2026-09-12,
    // against a car.db that in-progress adb-installed testing had already
    // pushed to user_version 14 outside build.sh (so no commit recorded that
    // as "deployed" for the publish guard to catch). SQLiteOpenHelper refuses
    // to OPEN a db newer than requested (onDowngrade, not onUpgrade), and
    // every write failed until this was bumped past 14. See the v15 entry in
    // onUpgrade below for what v15 itself actually does.
    private static final int VERSION = 22;

    private static volatile CarDb instance;

    /** Returns the singleton CarDb instance, creating it if necessary. */
    public static CarDb get(Context ctx) {
        CarDb i = instance;
        if (i == null) {
            synchronized (CarDb.class) {
                if (instance == null) instance = new CarDb(ctx.getApplicationContext());
                i = instance;
            }
        }
        return i;
    }

    private CarDb(Context ctx) { super(ctx, NAME, null, VERSION); }

    /** Returns the database file path for external access (e.g., USB export). */
    public static java.io.File file(Context ctx) { return ctx.getDatabasePath(NAME); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE telemetry_sample ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "ts_ms INTEGER NOT NULL,"
            + "odo_km REAL,"
            + "battery_pct INTEGER, battery_raw_pct REAL,"
            + "speed_kmh REAL,"
            + "gear INTEGER,"
            + "is_charging INTEGER,"
            + "charge_a REAL, charge_v REAL,"
            + "park_mode INTEGER,"
            + "outside_temp_c REAL,"
            + "altitude_m REAL,"
            + "instant_power_kw_est REAL,"
            + "drive_energy_pct REAL,"
            + "battery_energy_pct REAL,"
            + "other_energy_pct REAL,"
            + "power_spent_kw REAL,"
            + "power_regen_kw REAL,"
            + "energy_spent_kwh REAL,"
            + "energy_regen_kwh REAL,"
            + "energy_net_kwh REAL,"
            + "battery_temp_c REAL,"
            + "energy_measured INTEGER,"
            + "energy_spent_est_kwh REAL, energy_regen_est_kwh REAL)");
        db.execSQL("CREATE INDEX idx_sample_ts ON telemetry_sample(ts_ms)");
        db.execSQL("CREATE INDEX idx_sample_odo ON telemetry_sample(odo_km)");

        db.execSQL("CREATE TABLE charge_session ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,"
            + "start_sample_id INTEGER, end_sample_id INTEGER,"
            + "soc_start INTEGER, soc_end INTEGER,"
            + "kwh REAL, avg_power_w REAL,"
            + "max_charge_v REAL,"
            + "samples INTEGER,"
            + "odo_start_km REAL,"
            + "cost REAL,"
            + "dismissed INTEGER NOT NULL DEFAULT 0)");

        // No daily_odometer table: "odometer for day X" is just the earliest
        // telemetry_sample row that day (see OdoStats.kmSince()/readLog()),
        // so it's a query, not a table. Legacy odo.log readings are folded
        // into telemetry_sample as sparse rows (ts_ms + odo_km only) instead
        // — see DbMigration.

        // No start/end battery_pct or odo_km columns: unlike charge_session
        // (which migrated from legacy flat files), park_session and trip are
        // newly added; every row has valid start/end_sample_id pointers, so
        // snapshot columns would duplicate telemetry_sample. Values are queried
        // at read time via joins against the sample pointers.
        db.execSQL("CREATE TABLE park_session ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "start_ms INTEGER NOT NULL, end_ms INTEGER,"
            + "start_sample_id INTEGER, end_sample_id INTEGER)");

        // max_speed_kmh also dropped, for the same reason — MAX(speed_kmh)
        // over [start_sample_id, end_sample_id] is a trivial query, not
        // worth precomputing. ascent_m/descent_m stay as real columns
        // though: unlike a MAX(), they need a noise-filtered running sum
        // of consecutive altitude deltas (GPS altitude jitters), which is
        // simplest done incrementally as ticks arrive (TripSession), the same
        // "needs real computation, not a point lookup" argument kwh
        // already earns on charge_session. regen_kwh is the same case,
        // once a property for it is found.
        db.execSQL("CREATE TABLE trip ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "start_ms INTEGER NOT NULL, end_ms INTEGER,"
            + "start_sample_id INTEGER, end_sample_id INTEGER,"
            + "ascent_m REAL, descent_m REAL,"
            + "regen_kwh REAL,"
            + "spent_kwh REAL,"
            + "net_kwh REAL,"
            + "energy_source TEXT, estimated_net_kwh REAL)");
        createTripEnergySegment(db);

        db.execSQL("CREATE TABLE comfort_event ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "ts_ms INTEGER NOT NULL,"
            + "event TEXT NOT NULL,"
            + "pointer INTEGER, out_c REAL, setpoint REAL, fan INTEGER,"
            + "ac INTEGER, recirc INTEGER, power INTEGER,"
            + "detail TEXT)");

        db.execSQL("CREATE TABLE battery_health_reading ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "ts_ms INTEGER NOT NULL,"
            + "odo_km REAL,"
            + "soh_pct REAL NOT NULL,"
            + "source TEXT NOT NULL)");

        createValetSession(db);
        createChargeStopEvent(db);

        createDailyStat(db);
    }

    // The permanent half of the 90-day-raw/infinite-daily split: one row per
    // calendar day, computed once after that day closes and never modified
    // (see TelemetryRollup). Designed to preserve permanent facts after
    // telemetry_sample is pruned past 90 days. Columns are either aggregates
    // over that day's telemetry_sample rows (speed/temp/battery) or frozen
    // copies of already-permanent facts from trip/charge_session tables
    // (ascent/descent, trip/charge counts), never recomputed. Ascent/descent
    // in particular use TripSession's already-filtered values to avoid
    // reprocessing raw GPS altitude, which contains noise (see TripSession
    // ALTITUDE_NOISE_M).
    private static void createDailyStat(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS daily_stat ("
            + "date TEXT PRIMARY KEY,"
            + "first_odo_km REAL NOT NULL, last_odo_km REAL NOT NULL,"
            + "first_battery_pct INTEGER, last_battery_pct INTEGER,"
            + "min_battery_pct INTEGER, max_battery_pct INTEGER,"
            + "avg_speed_kmh REAL, max_speed_kmh REAL,"
            + "min_temp_c REAL, avg_temp_c REAL, max_temp_c REAL,"
            + "ascent_m REAL NOT NULL DEFAULT 0, descent_m REAL NOT NULL DEFAULT 0,"
            + "trip_count INTEGER NOT NULL DEFAULT 0, driving_minutes REAL NOT NULL DEFAULT 0,"
            + "charge_count INTEGER NOT NULL DEFAULT 0, charge_kwh REAL NOT NULL DEFAULT 0,"
            + "charge_cost REAL NOT NULL DEFAULT 0,"
            + "discharge_kwh REAL NOT NULL DEFAULT 0,"
            + "regen_kwh REAL NOT NULL DEFAULT 0,"
            + "net_kwh REAL NOT NULL DEFAULT 0,"
            + "energy_source TEXT)");
    }

    /** Immutable source-specific portions of a trip; never merge estimated and measured energy. */
    private static void createTripEnergySegment(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS trip_energy_segment ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER,"
            + "start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,"
            + "source TEXT NOT NULL, net_kwh REAL NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trip_energy_segment_trip ON trip_energy_segment(trip_id)");
    }

    private static void createValetSession(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS valet_session ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "start_ms INTEGER NOT NULL, end_ms INTEGER,"
            + "start_odo_km REAL, end_odo_km REAL,"
            + "start_soc INTEGER, end_soc INTEGER,"
            + "max_speed_kmh REAL, max_power_kw REAL)");
    }

    private static void createChargeStopEvent(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS charge_stop_event ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL,"
            + "occurred_ms INTEGER NOT NULL, soc INTEGER, kwh REAL, published INTEGER NOT NULL DEFAULT 0,"
            + "UNIQUE(session_id, occurred_ms))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 -> v2: dropped daily_odometer (folded into telemetry_sample).
        // v2 -> v3: added telemetry_sample.altitude_m; dropped the snapshot
        // columns from park_session/trip (pure telemetry_sample duplication,
        // see their own comments in onCreate); added trip.ascent_m/descent_m.
        // v3 -> v4: added telemetry_sample.instant_power_kw_est (the SOC-delta
        // estimate — see Telemetry.java).
        // Early versions used drop-and-recreate; later versions with persistent
        // data require ALTER TABLE migrations (see v5 -> v6 below).
        if (oldVersion < 4) {
            db.execSQL("DROP TABLE IF EXISTS telemetry_sample");
            db.execSQL("DROP TABLE IF EXISTS charge_session");
            db.execSQL("DROP TABLE IF EXISTS daily_odometer");
            db.execSQL("DROP TABLE IF EXISTS park_session");
            db.execSQL("DROP TABLE IF EXISTS trip");
            db.execSQL("DROP TABLE IF EXISTS comfort_event");
            db.execSQL("DROP TABLE IF EXISTS battery_health_reading");
            onCreate(db);
            return;
        }
        // v4 -> v5: added daily_odo. Unlike earlier upgrades, this runs on a
        // database with irreplaceable driving history; drop-and-recreate would
        // destroy data. TelemetryRollup backfills daily_odo from existing
        // telemetry_sample rows on first run; migration only needs to create the table.
        if (oldVersion < 5) {
            db.execSQL("CREATE TABLE IF NOT EXISTS daily_odo ("
                + "date TEXT PRIMARY KEY,"
                + "first_odo_km REAL NOT NULL, last_odo_km REAL NOT NULL)");
        }
        // v5 -> v6: daily_odo -> daily_stat, wider (see createDailyStat's own
        // comment). Dropping daily_odo here is safe DESPITE the v4->v5
        // comment's warning about real history: daily_odo's own rows are not
        // themselves irreplaceable data, only a cache TelemetryRollup derives
        // from telemetry_sample -- and TelemetryRollup only ever prunes a raw
        // day AFTER daily_odo/daily_stat has it, so every day daily_odo has
        // frozen so far is still sitting in telemetry_sample too, untouched.
        // Dropping and letting TelemetryRollup recompute into the new,
        // richer table loses nothing.
        if (oldVersion < 6) {
            db.execSQL("DROP TABLE IF EXISTS daily_odo");
            createDailyStat(db);
        }
        // v6 -> v7: added daily_stat.discharge_kwh (energy consumed per day,
        // integrated from instant_power_kw_est — see TelemetryRollup). Simply
        // adding the column would leave already-frozen days with false "0 kWh"
        // values indistinguishable from actual zeros. Instead, clear daily_stat
        // rows to force TelemetryRollup to recompute them on next run with
        // discharge_kwh included. This is safe because telemetry_sample retains
        // the raw data for all historical days within the retention window.
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE daily_stat ADD COLUMN discharge_kwh REAL NOT NULL DEFAULT 0");
            db.execSQL("DELETE FROM daily_stat");
        }
        // v7 -> v8: added 3 distinct power metrics across telemetry_sample, trip, and daily_stat:
        // 1. Power (net: power_kw / instant_power_kw_est, energy_net_kwh, net_kwh)
        // 2. Power spent (power_spent_kw, energy_spent_kwh, discharge_kwh, spent_kwh)
        // 3. Regen (power_regen_kw, energy_regen_kwh, regen_kwh)
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE telemetry_sample ADD COLUMN power_spent_kw REAL");
            db.execSQL("ALTER TABLE telemetry_sample ADD COLUMN power_regen_kw REAL");
            db.execSQL("ALTER TABLE telemetry_sample ADD COLUMN energy_spent_kwh REAL");
            db.execSQL("ALTER TABLE telemetry_sample ADD COLUMN energy_regen_kwh REAL");
            db.execSQL("ALTER TABLE telemetry_sample ADD COLUMN energy_net_kwh REAL");

            db.execSQL("ALTER TABLE trip ADD COLUMN spent_kwh REAL");
            db.execSQL("ALTER TABLE trip ADD COLUMN net_kwh REAL");

            db.execSQL("ALTER TABLE daily_stat ADD COLUMN regen_kwh REAL NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE daily_stat ADD COLUMN net_kwh REAL NOT NULL DEFAULT 0");

            db.execSQL("DELETE FROM daily_stat");
        }
        // v8 -> v9: added cost tracking for charging sessions (cost, dismissed) and daily charge cost
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE charge_session ADD COLUMN cost REAL");
            db.execSQL("ALTER TABLE charge_session ADD COLUMN dismissed INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE daily_stat ADD COLUMN charge_cost REAL NOT NULL DEFAULT 0");
        }
        // v9 -> v10: driving energy now excludes gear=P (4), while retaining
        // zero-speed samples in a driving gear for stopped traffic. Invalidate
        // only summaries that still have raw telemetry available. Older frozen
        // days may already have had their raw rows pruned; deleting those rows
        // would permanently erase their history rather than improve it.
        if (oldVersion < 10) {
            db.execSQL("DELETE FROM daily_stat WHERE date IN ("
                + "SELECT DISTINCT date(ts_ms/1000,'unixepoch','localtime') "
                + "FROM telemetry_sample)");
        }
        // v10 -> v11: persist the session's peak charger voltage. Voltage
        // remains stable while DC current tapers, making it a more reliable
        // AC/DC discriminator than average power. Backfill sessions whose raw
        // samples are still available; older rows intentionally stay unknown.
        if (oldVersion < 11) {
            db.execSQL("ALTER TABLE charge_session ADD COLUMN max_charge_v REAL");
            db.execSQL("UPDATE charge_session SET max_charge_v = ("
                + "SELECT MAX(charge_v) FROM telemetry_sample "
                + "WHERE telemetry_sample.id BETWEEN charge_session.start_sample_id "
                + "AND charge_session.end_sample_id) "
                + "WHERE start_sample_id IS NOT NULL AND end_sample_id IS NOT NULL");
        }
        // v11 -> v12: explicit Valet intervals group their underlying trips
        // in Daily Stats while leaving the original trip rows untouched.
        if (oldVersion < 12) createValetSession(db);
        // v12 briefly required end_ms at activation time, which made durable
        // active rows impossible. Rebuild the new table with a nullable end.
        if (oldVersion < 13) {
            db.execSQL("ALTER TABLE valet_session RENAME TO valet_session_v12");
            createValetSession(db);
            db.execSQL("INSERT INTO valet_session (id,start_ms,end_ms,start_odo_km,end_odo_km,"
                + "start_soc,end_soc,max_speed_kmh,max_power_kw) SELECT id,start_ms,end_ms,"
                + "start_odo_km,end_odo_km,start_soc,end_soc,max_speed_kmh,max_power_kw FROM valet_session_v12");
            db.execSQL("DROP TABLE valet_session_v12");
        }
        if (oldVersion < 14) createChargeStopEvent(db);
        // v15 repairs the v13 migration helper, which accidentally recreated
        // end_ms as NOT NULL and therefore rejected activation-time rows.
        if (oldVersion < 15) {
            db.execSQL("ALTER TABLE valet_session RENAME TO valet_session_v14");
            createValetSession(db);
            db.execSQL("INSERT INTO valet_session (id,start_ms,end_ms,start_odo_km,end_odo_km,"
                + "start_soc,end_soc,max_speed_kmh,max_power_kw) SELECT id,start_ms,end_ms,"
                + "start_odo_km,end_odo_km,start_soc,end_soc,max_speed_kmh,max_power_kw FROM valet_session_v14");
            db.execSQL("DROP TABLE valet_session_v14");
        }
        // v16: battery temperature, from the OBD2 dongle (Obd2Reader
        // .freshBattTempC()) -- there was previously no column for it at
        // all, so nothing to backfill; it only starts recording from here.
        if (oldVersion < 16) {
            db.execSQL("ALTER TABLE telemetry_sample ADD COLUMN battery_temp_c REAL");
        }
        // v17: on at least one real device, valet_session's end_ms is STILL
        // NOT NULL even though user_version already reads 16 -- the v15 fix
        // above only runs when oldVersion < 15, but this device's
        // user_version was already bumped past 15 by an earlier out-of-band
        // build (see the VERSION-10 note near the top of this file) before
        // the v15 fix existed, so it was silently skipped forever and every
        // valet activation has been failing on the NOT NULL constraint since.
        // Re-run the identical rebuild under a version number no device has
        // seen yet, unconditionally.
        if (oldVersion < 17) {
            db.execSQL("ALTER TABLE valet_session RENAME TO valet_session_v16");
            createValetSession(db);
            db.execSQL("INSERT INTO valet_session (id,start_ms,end_ms,start_odo_km,end_odo_km,"
                + "start_soc,end_soc,max_speed_kmh,max_power_kw) SELECT id,start_ms,end_ms,"
                + "start_odo_km,end_odo_km,start_soc,end_soc,max_speed_kmh,max_power_kw FROM valet_session_v16");
            db.execSQL("DROP TABLE valet_session_v16");
        }
        // v17 -> v18: preserve the VHAL's decimal SoC beside the established
        // whole-percent display value. It supports explicitly-estimated
        // no-OBD trip totals without changing existing UI/HA semantics.
        if (oldVersion < 18) {
            db.execSQL("ALTER TABLE telemetry_sample ADD COLUMN battery_raw_pct REAL");
        }
        if (oldVersion < 19) {
            db.execSQL("ALTER TABLE trip ADD COLUMN energy_source TEXT");
            db.execSQL("ALTER TABLE trip ADD COLUMN estimated_net_kwh REAL");
        }
        if (oldVersion < 20) createTripEnergySegment(db);
        // v20 -> v21: was this sample's energy measured (OBD2) or estimated
        // (VHAL SoC-delta)? See EnergySource. Nullable, forward-only -- no
        // backfill for pre-migration rows, since the split for that history
        // was never captured (that's the bug this migration fixes, not
        // something recoverable). energy_spent_est_kwh/energy_regen_est_kwh
        // are the SoC-delta estimate, now recorded every tick regardless of
        // whether OBD2 was available that tick, so a mid-trip disconnect
        // doesn't cold-start the estimate and any period can be
        // reconstructed on a consistent all-estimated basis later.
        //
        // NOT FULLY UNRECOVERABLE, THOUGH -- for a row dated on/after
        // 2026-09-13 (when telemetry_sample.battery_temp_c was added, v16
        // below), that column is itself proof OBD2 was connected: it is the
        // one column ChargeCurrentCurveDialog documents as "genuinely
        // OBD2-exclusive" -- VHAL never fills it. So if a user reports the
        // same "really measured, shown as estimated" bug for data from that
        // window, a targeted migration can reclassify any row where
        // energy_source says estimated but battery_temp_c IS NOT NULL,
        // instead of telling them it's gone. Rows before 2026-09-13 still
        // have nothing to cross-check against -- those genuinely are gone.
        // Not written as a migration here because nobody has hit it a
        // second time yet; do this only in response to an actual report.
        if (oldVersion < 21) {
            db.execSQL("ALTER TABLE telemetry_sample ADD COLUMN energy_measured INTEGER");
            db.execSQL("ALTER TABLE telemetry_sample ADD COLUMN energy_spent_est_kwh REAL");
            db.execSQL("ALTER TABLE telemetry_sample ADD COLUMN energy_regen_est_kwh REAL");
            db.execSQL("ALTER TABLE daily_stat ADD COLUMN energy_source TEXT");
        }
        // v21 -> v22: makes good on the "NOT FULLY UNRECOVERABLE" comment
        // above -- a user reported the exact "OBD2 connected, shown as
        // estimated" bug for real, on data young enough for the
        // battery_temp_c cross-check to apply. See repairEstimatedFlag().
        if (oldVersion < 22) repairEstimatedFlag(db);
    }

    // Fixes telemetry_sample rows mislabeled "estimated" despite OBD2
    // actually being connected, and the already-frozen daily_stat.energy_source
    // values that were computed from them before the label was fixed --
    // daily_stat is otherwise permanent/never-recomputed (see its own class
    // comment), so this migration is the only chance those days get.
    //
    // Two-step, deliberately in this order:
    // 1. Fix the raw flag: any row with battery_temp_c set (OBD2-exclusive,
    //    see the v21 comment) but energy_measured=0 was really measured.
    //    Unconditional and immediately useful even where step 2 can't
    //    reach -- battery_temp_c didn't exist before 2026-09-13, and
    //    telemetry_sample itself only keeps ~90 days raw (see
    //    TelemetryRollup.RETAIN_DAYS) before a day is pruned down to just
    //    its daily_stat row, so this can only recompute days whose raw
    //    rows are still here. Rows/days it can't reach are left exactly as
    //    they were -- not touched, not guessed at.
    // 2. Recompute daily_stat.energy_source for every date that still has
    //    raw telemetry_sample rows, reusing DrivingConsumption -- the exact
    //    same driving/hasEnergy accumulation TelemetryRollup itself uses --
    //    rather than a second copy of that logic in SQL. Spent/regen/net
    //    kWh are left untouched: per the original bug report, those numbers
    //    were already correct; only the measured/estimated label was wrong.
    private static void repairEstimatedFlag(SQLiteDatabase db) {
        db.execSQL("UPDATE telemetry_sample SET energy_measured = 1 "
            + "WHERE energy_measured = 0 AND battery_temp_c IS NOT NULL");

        Cursor days = db.rawQuery(
            "SELECT DISTINCT date(ts_ms/1000,'unixepoch','localtime') FROM telemetry_sample "
            + "WHERE date(ts_ms/1000,'unixepoch','localtime') IN (SELECT date FROM daily_stat)", null);
        java.util.List<String> dates = new java.util.ArrayList<>();
        try { while (days.moveToNext()) dates.add(days.getString(0)); } finally { days.close(); }
        if (dates.isEmpty()) return;

        java.util.Map<String, DrivingConsumption> acc = new java.util.HashMap<>();
        for (String d : dates) acc.put(d, new DrivingConsumption());
        String placeholders = String.join(",", java.util.Collections.nCopies(dates.size(), "?"));
        Cursor c = db.rawQuery(
            "SELECT date(ts_ms/1000,'unixepoch','localtime') AS day, "
          + "       ts_ms, odo_km, speed_kmh, gear, is_charging, "
          + "       energy_spent_kwh, energy_regen_kwh, instant_power_kw_est, energy_measured "
          + "FROM telemetry_sample "
          + "WHERE date(ts_ms/1000,'unixepoch','localtime') IN (" + placeholders + ") "
          + "ORDER BY day ASC, ts_ms ASC, id ASC", dates.toArray(new String[0]));
        try {
            while (c.moveToNext()) {
                DrivingConsumption a = acc.get(c.getString(0));
                if (a == null) continue;
                long ts = c.getLong(1);
                double odo = c.isNull(2) ? Double.NaN : c.getDouble(2);
                double speed = c.isNull(3) ? Double.NaN : c.getDouble(3);
                Integer gear = c.isNull(4) ? null : c.getInt(4);
                boolean charging = !c.isNull(5) && c.getInt(5) != 0;
                double spent = c.isNull(6) ? Double.NaN : c.getDouble(6);
                double regen = c.isNull(7) ? Double.NaN : c.getDouble(7);
                double power = c.isNull(8) ? Double.NaN : c.getDouble(8);
                Integer measured = c.isNull(9) ? null : c.getInt(9);
                a.add(ts, odo, speed, gear, charging, spent, regen, power, measured);
            }
        } finally { c.close(); }

        for (java.util.Map.Entry<String, DrivingConsumption> e : acc.entrySet()) {
            EnergySource resolved = e.getValue().energySource();
            db.execSQL("UPDATE daily_stat SET energy_source = ? WHERE date = ?",
                new Object[]{resolved.name(), e.getKey()});
        }
    }

    private Handler io;
    private synchronized Handler handler() {
        if (io == null) {
            HandlerThread t = new HandlerThread("cardb");
            t.start();
            io = new Handler(t.getLooper());
        }
        return io;
    }

    /** Posts a write operation to the single writer thread. The runnable
     * should call getWritableDatabase() or db() itself; this method only
     * serializes access, ensuring no concurrent writes. */
    public void write(Runnable r) { handler().post(r); }

    /** Returns the writable database instance. Reads are synchronous and
     * thread-safe; calls can be made from any thread. */
    public SQLiteDatabase db() { return getWritableDatabase(); }

    /** Returns the ID of the most recently inserted telemetry_sample row,
     * or -1 if none exist. Used by TripSession/ParkSession to stamp their
     * start/end_sample_id pointers. Queries fresh on each call rather than
     * relying on cross-class ordering of operations, so the result is the
     * latest sample available at query time. */
    public long latestSampleId() {
        android.database.Cursor c = db().rawQuery(
            "SELECT id FROM telemetry_sample ORDER BY id DESC LIMIT 1", null);
        try {
            return c.moveToFirst() ? c.getLong(0) : -1;
        } finally { c.close(); }
    }
}
