package com.geely.drivemem.car;

import com.geely.drivemem.hvac.ComfortEvents;
import com.geely.drivemem.sensors.OdoStats;
import com.geely.drivemem.sensors.TelemetryRollup;
import com.geely.drivemem.sensors.TelemetrySampler;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.state.ParkSession;
import com.geely.drivemem.state.TripSession;
import com.geely.drivemem.util.DbMigration;

import android.content.Context;
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
    private static final int VERSION = 9;

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
            + "battery_pct INTEGER,"
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
            + "energy_net_kwh REAL)");
        db.execSQL("CREATE INDEX idx_sample_ts ON telemetry_sample(ts_ms)");
        db.execSQL("CREATE INDEX idx_sample_odo ON telemetry_sample(odo_km)");

        db.execSQL("CREATE TABLE charge_session ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,"
            + "start_sample_id INTEGER, end_sample_id INTEGER,"
            + "soc_start INTEGER, soc_end INTEGER,"
            + "kwh REAL, avg_power_w REAL,"
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
            + "net_kwh REAL)");

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
            + "net_kwh REAL NOT NULL DEFAULT 0)");
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
