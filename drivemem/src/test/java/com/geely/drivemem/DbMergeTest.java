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
        "CREATE TABLE %s.daily_stat (date TEXT PRIMARY KEY, first_odo_km REAL NOT NULL, last_odo_km REAL NOT NULL)",
    };
    private static final String[] OLD = {
        "CREATE TABLE %s.telemetry_sample (id INTEGER PRIMARY KEY AUTOINCREMENT, ts_ms INTEGER NOT NULL, battery_pct INTEGER, odo_km REAL)",
        "CREATE TABLE %s.trip (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER, start_sample_id INTEGER, end_sample_id INTEGER, net_kwh REAL)",
        "CREATE TABLE %s.charge_session (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, start_sample_id INTEGER, end_sample_id INTEGER, kwh REAL)",
        "CREATE TABLE %s.charge_stop_event (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, occurred_ms INTEGER NOT NULL, UNIQUE(session_id, occurred_ms))",
        "CREATE TABLE %s.daily_stat (date TEXT PRIMARY KEY, last_odo_km REAL NOT NULL, first_odo_km REAL NOT NULL)",
    };

    @Before public void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        c = DriverManager.getConnection("jdbc:sqlite:" + tmp.newFile("live.db").getPath());
        x("ATTACH DATABASE '" + tmp.newFile("old.db").getPath() + "' AS old");
        for (String s : LIVE) x(String.format(s, "main"));
        for (String s : OLD) x(String.format(s, "old"));
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
}
