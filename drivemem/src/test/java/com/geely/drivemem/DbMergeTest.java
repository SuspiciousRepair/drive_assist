package com.geely.drivemem;

import com.geely.drivemem.util.DbMerge;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** DbMerge on real SQLite: a backup (old) merged into the live database. */
public class DbMergeTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Connection c;
    private DbMerge.Sql sql;

    // The live schema, then the same tables as an older install has them
    // after its ALTER TABLE migrations: same columns, another order.
    private static final String[] LIVE = {
        "CREATE TABLE %s.telemetry_sample (id INTEGER PRIMARY KEY AUTOINCREMENT, ts_ms INTEGER NOT NULL, odo_km REAL, battery_pct INTEGER)",
        "CREATE TABLE %s.trip (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER, start_sample_id INTEGER, end_sample_id INTEGER, net_kwh REAL)",
        "CREATE TABLE %s.charge_session (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, start_sample_id INTEGER, end_sample_id INTEGER, kwh REAL)",
        "CREATE TABLE %s.charge_stop_event (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, occurred_ms INTEGER NOT NULL, UNIQUE(session_id, occurred_ms))",
        "CREATE TABLE %s.valet_session (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER, start_odo_km REAL, end_odo_km REAL)",
        "CREATE TABLE %s.trip_point (id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER, ts_ms INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL)",
        "CREATE TABLE %s.diary_event (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE, ts_ms INTEGER NOT NULL, kind TEXT NOT NULL, category TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'DONE', parent_id INTEGER)",
        "CREATE TABLE %s.aux_12v_reading (id INTEGER PRIMARY KEY AUTOINCREMENT, ts_ms INTEGER NOT NULL, voltage_v REAL, phase TEXT NOT NULL, source TEXT NOT NULL)",
        "CREATE TABLE %s.daily_stat (date TEXT PRIMARY KEY, first_odo_km REAL NOT NULL, last_odo_km REAL NOT NULL)",
    };
    private static final String[] OLD = {
        "CREATE TABLE %s.telemetry_sample (id INTEGER PRIMARY KEY AUTOINCREMENT, ts_ms INTEGER NOT NULL, battery_pct INTEGER, odo_km REAL)",
        "CREATE TABLE %s.trip (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER, start_sample_id INTEGER, end_sample_id INTEGER, net_kwh REAL)",
        "CREATE TABLE %s.charge_session (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, start_sample_id INTEGER, end_sample_id INTEGER, kwh REAL)",
        "CREATE TABLE %s.charge_stop_event (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, occurred_ms INTEGER NOT NULL, UNIQUE(session_id, occurred_ms))",
        "CREATE TABLE %s.valet_session (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER, start_odo_km REAL, end_odo_km REAL)",
        "CREATE TABLE %s.trip_point (id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER, ts_ms INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL)",
        "CREATE TABLE %s.diary_event (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE, ts_ms INTEGER NOT NULL, kind TEXT NOT NULL, category TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'DONE', parent_id INTEGER)",
        "CREATE TABLE %s.aux_12v_reading (id INTEGER PRIMARY KEY AUTOINCREMENT, ts_ms INTEGER NOT NULL, voltage_v REAL, phase TEXT NOT NULL, source TEXT NOT NULL)",
        "CREATE TABLE %s.daily_stat (date TEXT PRIMARY KEY, last_odo_km REAL NOT NULL, first_odo_km REAL NOT NULL)",
    };

    @Before public void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        c = DriverManager.getConnection("jdbc:sqlite:" + tmp.newFile("live.db").getPath());
        x("ATTACH DATABASE '" + tmp.newFile("old.db").getPath() + "' AS old");
        for (String s : LIVE) x(String.format(s, "main"));
        for (String s : OLD) x(String.format(s, "old"));
        // Uuid tables, from the app's own DDL, on both sides.
        for (String ddl : new String[]{com.geely.drivemem.car.CarDb.TOLL_BOOTH_DDL,
                com.geely.drivemem.car.CarDb.TOLL_PASSAGE_DDL, com.geely.drivemem.car.CarDb.PARKING_COST_DDL}) {
            for (String schema : new String[]{"main", "old"}) {
                x(ddl.replace("CREATE TABLE IF NOT EXISTS ", "CREATE TABLE IF NOT EXISTS " + schema + "."));
            }
        }
        sql = new DbMerge.Sql() {
            @Override public void exec(String s, Object... a) throws Exception {
                try (PreparedStatement p = c.prepareStatement(s)) { bind(p, a); p.executeUpdate(); }
            }
            @Override public long queryLong(String s, Object... a) throws Exception {
                try (PreparedStatement p = c.prepareStatement(s)) {
                    bind(p, a);
                    try (ResultSet r = p.executeQuery()) { return r.next() ? r.getLong(1) : 0; }
                }
            }
            @Override public List<String> columns(String schema, String t) throws Exception {
                List<String> out = new ArrayList<>();
                try (Statement st = c.createStatement();
                     ResultSet r = st.executeQuery("PRAGMA " + schema + ".table_info(" + t + ")")) {
                    while (r.next()) out.add(r.getString(2));
                }
                return out;
            }
        };
    }

    @After public void tearDown() throws Exception { c.close(); }

    private void x(String s) throws Exception { try (Statement st = c.createStatement()) { st.execute(s); } }
    private static void bind(PreparedStatement p, Object[] a) throws Exception {
        for (int i = 0; i < a.length; i++) p.setObject(i + 1, a[i]);
    }
    private long q(String s) throws Exception { return sql.queryLong(s); }

    /** Backup: Sep, ids 1..3. Live (after the wipe): Nov, ids 1..2. */
    private void fill() throws Exception {
        x("INSERT INTO old.telemetry_sample (id, ts_ms, odo_km, battery_pct) VALUES (1,100,10,80),(2,200,20,70),(3,300,30,60)");
        x("INSERT INTO old.trip (id, start_ms, end_ms, start_sample_id, end_sample_id, net_kwh) VALUES (1,100,300,1,3,2.5)");
        x("INSERT INTO old.charge_session (id, start_ms, end_ms, start_sample_id, end_sample_id, kwh) VALUES (1,250,290,2,3,9)");
        x("INSERT INTO old.charge_stop_event (session_id, occurred_ms) VALUES (1,290)");
        x("INSERT INTO old.daily_stat (date, first_odo_km, last_odo_km) VALUES ('2026-09-10',10,30),('2026-11-13',1,2)");

        x("INSERT INTO main.telemetry_sample (id, ts_ms, odo_km, battery_pct) VALUES (1,1000,500,90),(2,1100,510,85)");
        x("INSERT INTO main.trip (id, start_ms, end_ms, start_sample_id, end_sample_id, net_kwh) VALUES (1,1000,1100,1,2,1.0)");
        x("INSERT INTO main.charge_session (id, start_ms, end_ms, start_sample_id, end_sample_id, kwh) VALUES (1,1050,1090,1,2,4)");
        x("INSERT INTO main.charge_stop_event (session_id, occurred_ms) VALUES (1,1090)");
        x("INSERT INTO main.daily_stat (date, first_odo_km, last_odo_km) VALUES ('2026-11-13',500,510)");
    }

    @Test public void mergeKeepsBackupIdsAndMovesLiveRowsWithTheirLinks() throws Exception {
        fill();
        assertFalse(DbMerge.overlaps(sql));
        DbMerge.Result r = DbMerge.merge(sql);

        assertEquals(3L, (long) r.offsets.get("telemetry_sample"));
        assertEquals(1L, (long) r.added.get("trip"));
        assertEquals(5, q("SELECT COUNT(*) FROM main.telemetry_sample"));
        // backup trip: ids unchanged
        assertEquals(1, q("SELECT start_sample_id FROM main.trip WHERE start_ms = 100"));
        assertEquals(3, q("SELECT end_sample_id FROM main.trip WHERE start_ms = 100"));
        // live trip: moved up, links follow to the SAME samples
        assertEquals(2, q("SELECT id FROM main.trip WHERE start_ms = 1000"));
        assertEquals(1000, q("SELECT s.ts_ms FROM main.trip t JOIN main.telemetry_sample s ON s.id = t.start_sample_id WHERE t.start_ms = 1000"));
        assertEquals(1100, q("SELECT s.ts_ms FROM main.trip t JOIN main.telemetry_sample s ON s.id = t.end_sample_id WHERE t.start_ms = 1000"));
        // the column that moved position landed in the right place
        assertEquals(10, q("SELECT odo_km FROM main.telemetry_sample WHERE ts_ms = 100"));
        assertEquals(80, q("SELECT battery_pct FROM main.telemetry_sample WHERE ts_ms = 100"));
        // charge stop events still point at their own session
        assertEquals(1050, q("SELECT s.start_ms FROM main.charge_stop_event e JOIN main.charge_session s ON s.id = e.session_id WHERE e.occurred_ms = 1090"));
        assertEquals(250, q("SELECT s.start_ms FROM main.charge_stop_event e JOIN main.charge_session s ON s.id = e.session_id WHERE e.occurred_ms = 290"));
        // same date in both: the live day wins; backup-only day comes in
        assertEquals(500, q("SELECT first_odo_km FROM main.daily_stat WHERE date = '2026-11-13'"));
        assertEquals(30, q("SELECT last_odo_km FROM main.daily_stat WHERE date = '2026-09-10'"));
        // new rows get ids above everything
        x("INSERT INTO main.trip (start_ms) VALUES (2000)");
        assertEquals(3, q("SELECT id FROM main.trip WHERE start_ms = 2000"));
    }

    /** Route points follow their trip to its new id. Points of the trip that
     * is still open have no trip yet (NULL) and must stay that way. */
    @Test public void routePointsFollowTheirTripAndOpenPointsStayOpen() throws Exception {
        fill();
        x("INSERT INTO old.trip_point (id, trip_id, ts_ms, lat, lon) VALUES (1,1,110,1.5,2.5),(2,1,120,1.6,2.6)");
        x("INSERT INTO main.trip_point (id, trip_id, ts_ms, lat, lon) VALUES (1,1,1010,7.5,8.5),(2,NULL,1200,7.6,8.6)");

        DbMerge.Result r = DbMerge.merge(sql);

        assertEquals(2L, (long) r.offsets.get("trip_point"));
        assertEquals(4, q("SELECT COUNT(*) FROM main.trip_point"));
        // backup points: ids and trip unchanged
        assertEquals(100, q("SELECT t.start_ms FROM main.trip_point p JOIN main.trip t ON t.id = p.trip_id WHERE p.ts_ms = 110"));
        assertEquals(1, q("SELECT id FROM main.trip_point WHERE ts_ms = 110"));
        // live point: moved up, and still on the SAME (moved) trip
        assertEquals(3, q("SELECT id FROM main.trip_point WHERE ts_ms = 1010"));
        assertEquals(1000, q("SELECT t.start_ms FROM main.trip_point p JOIN main.trip t ON t.id = p.trip_id WHERE p.ts_ms = 1010"));
        // the open trip's point: moved up, still no trip
        assertEquals(4, q("SELECT id FROM main.trip_point WHERE ts_ms = 1200"));
        assertEquals(1, q("SELECT COUNT(*) FROM main.trip_point WHERE ts_ms = 1200 AND trip_id IS NULL"));
        // new points get ids above everything
        x("INSERT INTO main.trip_point (ts_ms, lat, lon) VALUES (2000,0,0)");
        assertEquals(5, q("SELECT id FROM main.trip_point WHERE ts_ms = 2000"));
    }

    /** The app keeps the open valet row's id in its prefs (valet_row_id):
     * the offset reported here is what moves that pref. */
    @Test public void valetSessionsMoveByTheReportedOffset() throws Exception {
        fill();
        x("INSERT INTO old.valet_session (id, start_ms, end_ms, start_odo_km, end_odo_km) VALUES (2,150,160,10,11),(5,170,180,11,12)");
        x("INSERT INTO main.valet_session (id, start_ms, end_ms, start_odo_km, end_odo_km) VALUES (1,1020,1030,500,501),(2,1040,NULL,501,NULL)");

        DbMerge.Result r = DbMerge.merge(sql);

        assertEquals(5L, (long) r.offsets.get("valet_session"));
        assertEquals(2L, (long) r.added.get("valet_session"));
        assertEquals(4, q("SELECT COUNT(*) FROM main.valet_session"));
        assertEquals(2, q("SELECT id FROM main.valet_session WHERE start_ms = 150"));
        assertEquals(5, q("SELECT id FROM main.valet_session WHERE start_ms = 170"));
        assertEquals(6, q("SELECT id FROM main.valet_session WHERE start_ms = 1020"));
        // the open valet session: old id 2 + offset 5
        assertEquals(7, q("SELECT id FROM main.valet_session WHERE start_ms = 1040"));
        assertEquals(1, q("SELECT COUNT(*) FROM main.valet_session WHERE id = 7 AND end_ms IS NULL"));
        x("INSERT INTO main.valet_session (start_ms) VALUES (2000)");
        assertEquals(8, q("SELECT id FROM main.valet_session WHERE start_ms = 2000"));
    }

    @Test public void backupOfTheSameHistoryOverlaps() throws Exception {
        fill();
        x("INSERT INTO old.telemetry_sample (id, ts_ms) VALUES (4, 1000)");
        assertTrue(DbMerge.overlaps(sql));
    }

    @Test public void emptyLiveDatabaseTakesTheBackupAsIs() throws Exception {
        x("INSERT INTO old.telemetry_sample (id, ts_ms) VALUES (7,100),(9,200)");
        x("INSERT INTO old.trip (id, start_ms, start_sample_id, end_sample_id) VALUES (4,100,7,9)");
        assertFalse(DbMerge.overlaps(sql));
        DbMerge.merge(sql);
        assertEquals(7, q("SELECT start_sample_id FROM main.trip WHERE id = 4"));
        x("INSERT INTO main.telemetry_sample (ts_ms) VALUES (300)");
        assertEquals(10, q("SELECT id FROM main.telemetry_sample WHERE ts_ms = 300"));
    }

    /** Diary recurrence children follow their parent_id across a backup merge,
     * and aux_12v_reading rows merge without losing either side. */
    @Test public void diaryEventsAndSelfReferencesFollowOnMerge() throws Exception {
        fill();
        x("INSERT INTO old.diary_event (id, uuid, ts_ms, kind, category, status, parent_id) VALUES "
            + "(1,'u-old-1',120,'NOISE','NOISE','OPEN',NULL),"
            + "(2,'u-old-2',150,'NOISE','NOISE','DONE',1)");
        x("INSERT INTO main.diary_event (id, uuid, ts_ms, kind, category, status, parent_id) VALUES "
            + "(1,'u-live-1',1020,'NOISE','NOISE','OPEN',NULL),"
            + "(2,'u-live-2',1050,'NOISE','NOISE','DONE',1)");
        x("INSERT INTO old.aux_12v_reading (id, ts_ms, voltage_v, phase, source) VALUES (1,120,12.6,'RESTING','vhal')");
        x("INSERT INTO main.aux_12v_reading (id, ts_ms, voltage_v, phase, source) VALUES (1,1020,12.2,'RESTING','vhal')");

        DbMerge.Result r = DbMerge.merge(sql);

        assertEquals(2L, (long) r.offsets.get("diary_event"));
        assertEquals(4, q("SELECT COUNT(*) FROM main.diary_event"));
        assertEquals(1, q("SELECT parent_id FROM main.diary_event WHERE uuid = 'u-old-2'"));
        assertEquals(3, q("SELECT id FROM main.diary_event WHERE uuid = 'u-live-1'"));
        assertEquals(3, q("SELECT parent_id FROM main.diary_event WHERE uuid = 'u-live-2'"));
        assertEquals(2, q("SELECT COUNT(*) FROM main.aux_12v_reading"));
    }

    /** The same diary entry on both sides (a backup restored onto a car that
     * still has the entry): the restore must not fail on diary_event.uuid. */
    @Test public void sameDiaryUuidOnBothSidesDoesNotFailTheRestore() throws Exception {
        fill();
        x("INSERT INTO old.diary_event (id, uuid, ts_ms, kind, category, status, parent_id) VALUES "
            + "(1,'u-same',120,'CARE','SERVICE','DONE',NULL),"
            + "(2,'u-old-only',150,'CARE','WIPERS','DONE',NULL)");
        x("INSERT INTO main.diary_event (id, uuid, ts_ms, kind, category, status, parent_id) VALUES "
            + "(1,'u-same',120,'CARE','SERVICE','DONE',NULL),"
            + "(2,'u-live-only',1050,'CARE','TIRES','DONE',NULL)");

        DbMerge.merge(sql);

        assertEquals(1, q("SELECT COUNT(*) FROM main.diary_event WHERE uuid = 'u-same'"));
        assertEquals(3, q("SELECT COUNT(*) FROM main.diary_event"));
    }

    /** Live toll prices, dismissed passages and parking edits survive a
     * restore of an older backup, and a live parking row keeps pointing at
     * its own Valet stay after the Valet ids move. */
    @Test public void tollAndParkingOverridesSurviveAndValetLinksFollow() throws Exception {
        fill();
        x("INSERT INTO old.valet_session (id, start_ms, end_ms) VALUES (1,120,180)");
        x("INSERT INTO main.valet_session (id, start_ms, end_ms) VALUES (1,1020,1080)");
        // The same booth and passage on both sides: the backup has no price,
        // the live row has the owner's price and a dismissed passage.
        x("INSERT INTO old.toll_booth (id, uuid, source, external_id, name, lat, lon, updated_ms) "
            + "VALUES (1,'b-1','osm','node/1','Plaza',1,1,100)");
        x("INSERT INTO main.toll_booth (id, uuid, source, external_id, name, lat, lon, user_price, updated_ms) "
            + "VALUES (1,'b-1','osm','node/1','Plaza',1,1,12.5,1000)");
        x("INSERT INTO old.toll_passage (id, uuid, booth_id, ts_ms, lat, lon, booth_name, dismissed, source) "
            + "VALUES (1,'p-1',1,150,1,1,'Plaza',0,'auto')");
        x("INSERT INTO main.toll_passage (id, uuid, booth_id, ts_ms, lat, lon, booth_name, dismissed, source) "
            + "VALUES (1,'p-1',1,150,1,1,'Plaza',1,'auto')");
        // Parking: one stop on both sides (edited on the car), one Valet
        // stay in each, and one stop only in the backup.
        x("INSERT INTO old.parking_cost (id, uuid, start_ms, cost, kind, updated_ms) VALUES (1,'k-same',140,20,'lot',140)");
        x("INSERT INTO old.parking_cost (id, uuid, start_ms, valet_id, cost, kind, updated_ms) VALUES (2,'k-old-valet',120,1,40,'lot',120)");
        x("INSERT INTO old.parking_cost (id, uuid, start_ms, cost, kind, updated_ms) VALUES (3,'k-old',160,5,'street',160)");
        x("INSERT INTO main.parking_cost (id, uuid, start_ms, cost, place_name, kind, updated_ms) VALUES (1,'k-same',140,30,'Mall','lot',1000)");
        x("INSERT INTO main.parking_cost (id, uuid, start_ms, valet_id, cost, kind, updated_ms) VALUES (2,'k-live-valet',1020,1,50,'lot',1020)");

        DbMerge.merge(sql);

        assertEquals(1, q("SELECT COUNT(*) FROM main.toll_booth"));
        assertEquals(1250, q("SELECT CAST(user_price * 100 AS INTEGER) FROM main.toll_booth WHERE uuid = 'b-1'"));
        assertEquals(1, q("SELECT dismissed FROM main.toll_passage WHERE uuid = 'p-1'"));
        assertEquals(4, q("SELECT COUNT(*) FROM main.parking_cost"));
        assertEquals(30, q("SELECT cost FROM main.parking_cost WHERE uuid = 'k-same'"));
        // Live Valet stay moved from id 1 to 2; its parking row moved with it.
        assertEquals(1020, q("SELECT v.start_ms FROM main.parking_cost p JOIN main.valet_session v "
            + "ON v.id = p.valet_id WHERE p.uuid = 'k-live-valet'"));
        assertEquals(120, q("SELECT v.start_ms FROM main.parking_cost p JOIN main.valet_session v "
            + "ON v.id = p.valet_id WHERE p.uuid = 'k-old-valet'"));
    }
}
