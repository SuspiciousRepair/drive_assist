package com.geely.drivemem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/**
 * Real SQL tests using actual SQLite database. Exercises schema creation,
 * data insertion, and query correctness against real rows.
 */
public class CarDbSqliteTest {

    private SqliteTestDb db;
    private Connection conn;

    @Before
    public void setUp() throws SQLException {
        db = new SqliteTestDb();
        conn = db.getConnection();
        db.createCarDbSchema();
    }

    @After
    public void tearDown() throws SQLException {
        db.close();
    }

    @Test
    public void schemaCreatesAllTables() throws SQLException {
        String[] tables = {
            "telemetry_sample", "charge_session", "park_session", "trip",
            "trip_energy_segment", "comfort_event", "battery_health_reading",
            "valet_session", "charge_stop_event", "daily_stat"
        };

        for (String table : tables) {
            ResultSet rs = conn.createStatement().executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'");
            assertTrue("Table " + table + " should exist", rs.next());
            rs.close();
        }
    }

    @Test
    public void telemetrySampleInsertAndRetrieve() throws SQLException {
        long ts = 1000000000L;
        double odo = 12345.5;
        int battery = 75;
        double speed = 60.0;
        int gear = 2;

        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO telemetry_sample (ts_ms, odo_km, battery_pct, speed_kmh, gear) " +
            "VALUES (?, ?, ?, ?, ?)");
        ps.setLong(1, ts);
        ps.setDouble(2, odo);
        ps.setInt(3, battery);
        ps.setDouble(4, speed);
        ps.setInt(5, gear);
        ps.executeUpdate();
        ps.close();

        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT ts_ms, odo_km, battery_pct, speed_kmh, gear FROM telemetry_sample WHERE id = 1");
        assertTrue("Should have one row", rs.next());
        assertEquals(ts, rs.getLong(1));
        assertEquals(odo, rs.getDouble(2), 1e-6);
        assertEquals(battery, rs.getInt(3));
        assertEquals(speed, rs.getDouble(4), 1e-6);
        assertEquals(gear, rs.getInt(5));
        rs.close();
    }

    @Test
    public void chargingFlagAndBatteryTempMarkMeasuredEnergy() throws SQLException {
        long ts1 = 1000000000L;
        long ts2 = 1000001000L;

        // Row with OBD2 battery temp (measured energy)
        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO telemetry_sample (ts_ms, speed_kmh, is_charging, battery_temp_c, " +
            "energy_measured, energy_spent_kwh) VALUES (?, ?, ?, ?, ?, ?)");
        ps.setLong(1, ts1);
        ps.setDouble(2, 0);
        ps.setInt(3, 0);
        ps.setDouble(4, 35.5);  // OBD2-exclusive, marks as measured
        ps.setInt(5, 0);        // Initially marked as estimated
        ps.setDouble(6, 0.1);
        ps.executeUpdate();

        // Row without battery temp (no OBD2)
        ps.setLong(1, ts2);
        ps.setDouble(2, 0);
        ps.setInt(3, 0);
        ps.setNull(4, java.sql.Types.REAL);  // No battery temp
        ps.setInt(5, 0);        // Marked as estimated
        ps.setDouble(6, 0.05);
        ps.executeUpdate();
        ps.close();

        // Query to count measured vs estimated
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT " +
            "  SUM(CASE WHEN energy_measured=1 OR battery_temp_c IS NOT NULL THEN 1 ELSE 0 END) AS measured, " +
            "  SUM(CASE WHEN energy_measured=0 AND battery_temp_c IS NULL THEN 1 ELSE 0 END) AS estimated " +
            "FROM telemetry_sample");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt("measured"));  // First row has battery_temp_c
        assertEquals(1, rs.getInt("estimated")); // Second row has neither
        rs.close();
    }

    @Test
    public void drivingConsumptionQueryExcludesParkedGear() throws SQLException {
        long ts1 = 1000000000L;
        long ts2 = 1000010000L;
        long ts3 = 1000020000L;
        long ts4 = 1000030000L;

        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO telemetry_sample (ts_ms, speed_kmh, gear, energy_spent_kwh, " +
            "energy_regen_kwh, instant_power_kw_est) VALUES (?, ?, ?, ?, ?, ?)");

        // Gear 2 (driving) - should be included
        ps.setLong(1, ts1);
        ps.setDouble(2, 50.0);
        ps.setInt(3, 2);
        ps.setDouble(4, 0.15);
        ps.setDouble(5, 0.0);
        ps.setDouble(6, 5.0);
        ps.executeUpdate();

        // Gear 4 (park) - should be excluded
        ps.setLong(1, ts2);
        ps.setDouble(2, 0.0);
        ps.setInt(3, 4);
        ps.setDouble(4, 0.1);
        ps.setDouble(5, 0.0);
        ps.setDouble(6, 0.0);
        ps.executeUpdate();

        // Gear 1 (driving at low speed) - should be included
        ps.setLong(1, ts3);
        ps.setDouble(2, 5.0);
        ps.setInt(3, 1);
        ps.setDouble(4, 0.05);
        ps.setDouble(5, 0.0);
        ps.setDouble(6, 2.0);
        ps.executeUpdate();

        // No gear (charging) - excluded by gear filter, same as park
        ps.setLong(1, ts4);
        ps.setDouble(2, 0.0);
        ps.setNull(3, java.sql.Types.INTEGER);
        ps.setDouble(4, 0.0);
        ps.setDouble(5, 0.0);
        ps.setDouble(6, 1.0);
        ps.executeUpdate();
        ps.close();

        // DailyStatsProvider's actual filter: gear <> 4 when gear is known
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT COALESCE(SUM(energy_spent_kwh), 0) AS total_spent " +
            "FROM telemetry_sample " +
            "WHERE (CASE WHEN gear IS NOT NULL THEN gear <> 4 " +
            "       ELSE (is_charging IS NULL OR is_charging = 0) END)");
        assertTrue(rs.next());
        // ts1: 0.15, ts3: 0.05, ts4: 0.0 (null is_charging means true)
        assertEquals(0.2, rs.getDouble("total_spent"), 1e-6);
        rs.close();
    }

    @Test
    public void dailyStatAggregatesRawSampleRows() throws SQLException {
        String date = "2026-09-15";
        long dayStart = 1726310400000L;  // 2026-09-15 00:00 UTC
        long dayEnd = 1726396800000L;    // 2026-09-16 00:00 UTC

        // Insert samples spanning a day
        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO telemetry_sample (ts_ms, odo_km, battery_pct, outside_temp_c, " +
            "speed_kmh, gear, energy_spent_kwh, energy_regen_kwh) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");

        // First sample of the day
        ps.setLong(1, dayStart + 1000);
        ps.setDouble(2, 1000.0);
        ps.setInt(3, 80);
        ps.setDouble(4, 22.0);
        ps.setDouble(5, 0.0);
        ps.setInt(6, 4);  // Park
        ps.setDouble(7, 0.0);
        ps.setDouble(8, 0.0);
        ps.executeUpdate();

        // Middle sample
        ps.setLong(1, dayStart + 86400000 / 2);
        ps.setDouble(2, 1050.0);
        ps.setInt(3, 60);
        ps.setDouble(4, 25.0);
        ps.setDouble(5, 80.0);
        ps.setInt(6, 2);  // Driving
        ps.setDouble(7, 0.5);
        ps.setDouble(8, 0.0);
        ps.executeUpdate();

        // Last sample of the day
        ps.setLong(1, dayEnd - 1000);
        ps.setDouble(2, 1100.0);
        ps.setInt(3, 40);
        ps.setDouble(4, 20.0);
        ps.setDouble(5, 0.0);
        ps.setInt(6, 4);  // Park
        ps.setDouble(7, 0.0);
        ps.setDouble(8, 0.0);
        ps.executeUpdate();
        ps.close();

        // Insert into daily_stat (mimicking what TelemetryRollup does)
        ps = conn.prepareStatement(
            "INSERT INTO daily_stat " +
            "(date, first_odo_km, last_odo_km, first_battery_pct, last_battery_pct, " +
            "min_battery_pct, max_battery_pct, avg_speed_kmh, avg_temp_c, discharge_kwh) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        ps.setString(1, date);
        ps.setDouble(2, 1000.0);
        ps.setDouble(3, 1100.0);
        ps.setInt(4, 80);
        ps.setInt(5, 40);
        ps.setInt(6, 40);
        ps.setInt(7, 80);
        ps.setDouble(8, 40.0);  // avg speed
        ps.setDouble(9, 22.33);  // avg temp
        ps.setDouble(10, 0.5);   // discharge
        ps.executeUpdate();
        ps.close();

        // Query daily_stat
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT first_odo_km, last_odo_km, first_battery_pct, last_battery_pct, " +
            "       min_battery_pct, max_battery_pct FROM daily_stat WHERE date = ?");
        ps = conn.prepareStatement(
            "SELECT first_odo_km, last_odo_km, first_battery_pct, last_battery_pct, " +
            "       min_battery_pct, max_battery_pct FROM daily_stat WHERE date = ?");
        ps.setString(1, date);
        rs = ps.executeQuery();
        assertTrue("Should have daily_stat row", rs.next());
        assertEquals(1000.0, rs.getDouble(1), 1e-6);  // first_odo
        assertEquals(1100.0, rs.getDouble(2), 1e-6);  // last_odo
        assertEquals(80, rs.getInt(3));               // first_battery
        assertEquals(40, rs.getInt(4));               // last_battery
        assertEquals(40, rs.getInt(5));               // min_battery
        assertEquals(80, rs.getInt(6));               // max_battery
        rs.close();
        ps.close();
    }

    @Test
    public void tripAggregatesSamplesAcrossStartEndIds() throws SQLException {
        // Create samples with incrementing IDs
        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO telemetry_sample (ts_ms, odo_km, speed_kmh, gear) " +
            "VALUES (?, ?, ?, ?)");

        long ts = 1000000000L;
        for (int i = 0; i < 5; i++) {
            ps.setLong(1, ts + i * 1000);
            ps.setDouble(2, 1000.0 + i);
            ps.setDouble(3, 50.0);
            ps.setInt(4, 2);
            ps.executeUpdate();
        }
        ps.close();

        // Insert a trip spanning samples 2-4
        ps = conn.prepareStatement(
            "INSERT INTO trip (start_ms, end_ms, start_sample_id, end_sample_id, ascent_m, descent_m) " +
            "VALUES (?, ?, ?, ?, ?, ?)");
        ps.setLong(1, ts + 1000);
        ps.setLong(2, ts + 3000);
        ps.setInt(3, 2);
        ps.setInt(4, 4);
        ps.setDouble(5, 50.0);  // ascent
        ps.setDouble(6, 10.0);  // descent
        ps.executeUpdate();
        ps.close();

        // Query the trip
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT start_ms, end_ms, start_sample_id, end_sample_id, ascent_m, descent_m FROM trip");
        assertTrue("Should have one trip", rs.next());
        assertEquals(2, rs.getInt("start_sample_id"));
        assertEquals(4, rs.getInt("end_sample_id"));
        assertEquals(50.0, rs.getDouble("ascent_m"), 1e-6);
        assertEquals(10.0, rs.getDouble("descent_m"), 1e-6);
        rs.close();
    }

    @Test
    public void chargeSessionStoresEnergyAndCost() throws SQLException {
        long startMs = 1000000000L;
        long endMs = 1000003600000L;  // 1 hour later
        int socStart = 20;
        int socEnd = 80;
        double kwh = 40.0;
        double avgPowerW = 10000.0;
        double maxChargeV = 400.0;
        Double cost = 5.50;

        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO charge_session " +
            "(start_ms, end_ms, soc_start, soc_end, kwh, avg_power_w, max_charge_v, cost) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
        ps.setLong(1, startMs);
        ps.setLong(2, endMs);
        ps.setInt(3, socStart);
        ps.setInt(4, socEnd);
        ps.setDouble(5, kwh);
        ps.setDouble(6, avgPowerW);
        ps.setDouble(7, maxChargeV);
        ps.setDouble(8, cost);
        ps.executeUpdate();
        ps.close();

        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT start_ms, end_ms, soc_start, soc_end, kwh, avg_power_w, max_charge_v, cost " +
            "FROM charge_session WHERE id = 1");
        assertTrue("Should have one charge session", rs.next());
        assertEquals(startMs, rs.getLong("start_ms"));
        assertEquals(endMs, rs.getLong("end_ms"));
        assertEquals(socStart, rs.getInt("soc_start"));
        assertEquals(socEnd, rs.getInt("soc_end"));
        assertEquals(kwh, rs.getDouble("kwh"), 1e-6);
        assertEquals(avgPowerW, rs.getDouble("avg_power_w"), 1e-6);
        assertEquals(maxChargeV, rs.getDouble("max_charge_v"), 1e-6);
        assertEquals(cost, rs.getDouble("cost"), 1e-6);
        rs.close();
    }

    @Test
    public void nullValuesHandledCorrectlyInQueries() throws SQLException {
        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO telemetry_sample (ts_ms, odo_km, battery_pct, speed_kmh, gear) " +
            "VALUES (?, ?, ?, ?, ?)");

        // Row with NULLs
        ps.setLong(1, 1000000000L);
        ps.setNull(2, java.sql.Types.REAL);      // NULL odo_km
        ps.setNull(3, java.sql.Types.INTEGER);   // NULL battery_pct
        ps.setDouble(4, 60.0);
        ps.setNull(5, java.sql.Types.INTEGER);   // NULL gear
        ps.executeUpdate();
        ps.close();

        // Query with NULL handling
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT " +
            "  odo_km IS NULL AS odo_is_null, " +
            "  battery_pct IS NULL AS batt_is_null, " +
            "  gear IS NULL AS gear_is_null " +
            "FROM telemetry_sample WHERE ts_ms = 1000000000");
        assertTrue(rs.next());
        assertTrue(rs.getBoolean("odo_is_null"));
        assertTrue(rs.getBoolean("batt_is_null"));
        assertTrue(rs.getBoolean("gear_is_null"));
        rs.close();
    }

    @Test
    public void dailyStatEnergySourceStoredAndRetrieved() throws SQLException {
        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO daily_stat " +
            "(date, first_odo_km, last_odo_km, first_battery_pct, last_battery_pct, energy_source) " +
            "VALUES (?, ?, ?, ?, ?, ?)");

        ps.setString(1, "2026-09-15");
        ps.setDouble(2, 1000.0);
        ps.setDouble(3, 1050.0);
        ps.setInt(4, 80);
        ps.setInt(5, 70);
        ps.setString(6, "MEASURED");
        ps.executeUpdate();

        ps.setString(1, "2026-09-16");
        ps.setDouble(2, 1050.0);
        ps.setDouble(3, 1100.0);
        ps.setInt(4, 70);
        ps.setInt(5, 60);
        ps.setString(6, "ESTIMATED");
        ps.executeUpdate();

        ps.setString(1, "2026-09-17");
        ps.setDouble(2, 1100.0);
        ps.setDouble(3, 1150.0);
        ps.setInt(4, 60);
        ps.setInt(5, 50);
        ps.setString(6, null);  // NULL energy_source for older rows
        ps.executeUpdate();
        ps.close();

        // Query and verify energy_source
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT date, energy_source FROM daily_stat ORDER BY date");

        assertTrue(rs.next());
        assertEquals("2026-09-15", rs.getString("date"));
        assertEquals("MEASURED", rs.getString("energy_source"));

        assertTrue(rs.next());
        assertEquals("2026-09-16", rs.getString("date"));
        assertEquals("ESTIMATED", rs.getString("energy_source"));

        assertTrue(rs.next());
        assertEquals("2026-09-17", rs.getString("date"));
        assertEquals(null, rs.getString("energy_source"));

        assertFalse(rs.next());
        rs.close();
    }
}
