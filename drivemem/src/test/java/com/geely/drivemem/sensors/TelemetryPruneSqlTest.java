package com.geely.drivemem.sensors;

import com.geely.drivemem.SqliteTestDb;

import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** TelemetryRollup.PRUNE_SQL on a real SQLite database: old parked samples
 * go, the samples a trip, a charge or a Valet stay reads stay. */
public class TelemetryPruneSqlTest {

    private static final long MIN = 60_000L;

    @Test public void keepsTripChargeAndValetSamplesDropsParkedOnes() throws Exception {
        SqliteTestDb db = new SqliteTestDb();
        db.createCarDbSchema();
        Connection c = db.getConnection();
        long day = 1_700_000_000_000L;   // any old day
        try (Statement s = c.createStatement()) {
            // One sample a minute for 100 minutes.
            for (int i = 0; i < 100; i++) {
                s.execute("INSERT INTO telemetry_sample (id, ts_ms, gear) VALUES (" + (i + 1) + ", " + (day + i * MIN) + ", 4)");
            }
            s.execute("INSERT INTO daily_stat (date, first_odo_km, last_odo_km) "
                + "SELECT DISTINCT date(ts_ms/1000,'unixepoch','localtime'), 0, 0 FROM telemetry_sample");
            // Trip minutes 10..20 (start sample read 1 min before it: id 10),
            // charge 40..50, Valet 70..75.
            s.execute("INSERT INTO trip (id, start_ms, end_ms, start_sample_id, end_sample_id) VALUES (1, "
                + (day + 10 * MIN) + ", " + (day + 20 * MIN) + ", 9, 21)");
            s.execute("INSERT INTO charge_session (id, start_ms, end_ms, soc_start, soc_end, kwh, avg_power_w) VALUES (1, "
                + (day + 40 * MIN) + ", " + (day + 50 * MIN) + ", 20, 80, 10, 3000)");
            s.execute("INSERT INTO valet_session (id, start_ms, end_ms) VALUES (1, "
                + (day + 70 * MIN) + ", " + (day + 75 * MIN) + ")");
        }
        long cutoff = day + 200 * MIN;
        try (PreparedStatement p = c.prepareStatement(TelemetryRollup.PRUNE_SQL)) {
            String[] a = TelemetryRollup.pruneArgs(cutoff);
            for (int i = 0; i < a.length; i++) p.setLong(i + 1, Long.parseLong(a[i]));
            p.executeUpdate();
        }
        List<Long> kept = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT (ts_ms - " + day + ") / " + MIN + " FROM telemetry_sample ORDER BY ts_ms")) {
            while (r.next()) kept.add(r.getLong(1));
        } finally {
            db.close();
        }
        List<Long> want = new ArrayList<>();
        for (long m = 8; m <= 22; m++) want.add(m);    // trip 10..20, 2 min each side
        for (long m = 38; m <= 52; m++) want.add(m);   // charge
        for (long m = 68; m <= 77; m++) want.add(m);   // Valet
        assertEquals(want, kept);
    }

    @Test public void aDayNotYetFrozenIsNotPruned() throws Exception {
        SqliteTestDb db = new SqliteTestDb();
        db.createCarDbSchema();
        Connection c = db.getConnection();
        long day = 1_700_000_000_000L;
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO telemetry_sample (ts_ms, gear) VALUES (" + day + ", 4)");
        }
        try (PreparedStatement p = c.prepareStatement(TelemetryRollup.PRUNE_SQL)) {
            String[] a = TelemetryRollup.pruneArgs(day + 10 * MIN);
            for (int i = 0; i < a.length; i++) p.setLong(i + 1, Long.parseLong(a[i]));
            p.executeUpdate();
        }
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM telemetry_sample")) {
            r.next();
            assertEquals(1, r.getInt(1));
        } finally {
            db.close();
        }
    }
}
