package com.geely.drivemem.car;

import com.geely.drivemem.SqliteTestDb;

import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;

/** The shared row predicates, run on a real SQLite database. */
public class CarDbRowSqlTest {

    // An idle row (no OBD2, no energy moved) is not an estimate; counting it
    // made a mostly idle, fully measured day read as MIXED (2026-09-24).
    @Test public void idleRowsAreNotEstimated() throws Exception {
        SqliteTestDb db = new SqliteTestDb();
        db.createCarDbSchema();
        Connection c = db.getConnection();
        try (Statement s = c.createStatement()) {
            // measured (OBD2 flag), measured (battery temp only), estimated
            // with energy, estimated regen only, idle unmeasured, idle null.
            s.execute("INSERT INTO telemetry_sample (ts_ms, energy_measured, battery_temp_c, energy_spent_kwh, energy_regen_kwh) VALUES"
                + " (1, 1, NULL, 0.1, 0),"
                + " (2, 0, 25.0, 0.1, 0),"
                + " (3, 0, NULL, 0.1, 0),"
                + " (4, 0, NULL, 0, 0.05),"
                + " (5, 0, NULL, 0, 0),"
                + " (6, 0, NULL, NULL, NULL)");
            ResultSet r = s.executeQuery("SELECT"
                + " SUM(CASE WHEN " + CarDb.MEASURED_ROW_SQL + " THEN 1 ELSE 0 END),"
                + " SUM(CASE WHEN " + CarDb.ESTIMATED_ROW_SQL + " THEN 1 ELSE 0 END),"
                + " SUM(CASE WHEN " + CarDb.ESTIMATED_ENERGY_ROW_SQL + " THEN 1 ELSE 0 END)"
                + " FROM telemetry_sample");
            r.next();
            assertEquals(2, r.getInt(1));
            assertEquals(4, r.getInt(2));   // estimated source, moved energy or not
            assertEquals(2, r.getInt(3));   // only the two that moved energy
        }
    }

    @Test public void drivingRowFollowsGearThenCharging() throws Exception {
        SqliteTestDb db = new SqliteTestDb();
        db.createCarDbSchema();
        try (Statement s = db.getConnection().createStatement()) {
            // D, P, P while charging, no gear not charging, no gear charging, no gear unknown
            s.execute("INSERT INTO telemetry_sample (ts_ms, gear, is_charging) VALUES"
                + " (1, 8, 0), (2, 4, 0), (3, 4, 1), (4, NULL, 0), (5, NULL, 1), (6, NULL, NULL)");
            ResultSet r = s.executeQuery("SELECT group_concat(ts_ms) FROM (SELECT ts_ms FROM telemetry_sample WHERE "
                + CarDb.DRIVING_ROW_SQL + " ORDER BY ts_ms)");
            r.next();
            assertEquals("1,4,6", r.getString(1));
        }
    }
}
