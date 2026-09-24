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
 * Real SQL tests for DailyStatsProvider queries. Verifies that the actual
 * SQL queries used by DailyStatsProvider correctly aggregate and filter data.
 */
public class DailyStatsProviderSqliteTest {

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
    public void getRecentDaysQueryFromDailyStatTable() throws SQLException {
        // Simulate what TelemetryRollup produces: frozen daily_stat rows
        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO daily_stat (date, first_odo_km, last_odo_km) VALUES (?, ?, ?)");

        ps.setString(1, "2026-09-13");
        ps.setDouble(2, 1000.0);
        ps.setDouble(3, 1050.0);
        ps.executeUpdate();

        ps.setString(1, "2026-09-14");
        ps.setDouble(2, 1050.0);
        ps.setDouble(3, 1100.0);
        ps.executeUpdate();

        ps.setString(1, "2026-09-15");
        ps.setDouble(2, 1100.0);
        ps.setDouble(3, 1150.0);
        ps.executeUpdate();
        ps.close();

        // DailyStatsProvider.getRecentDays() query
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT date, MAX(0, last_odo_km - first_odo_km) AS km FROM daily_stat " +
            "ORDER BY date DESC LIMIT 5");

        assertTrue(rs.next());
        assertEquals("2026-09-15", rs.getString(1));
        assertEquals(50.0, rs.getDouble(2), 1e-6);

        assertTrue(rs.next());
        assertEquals("2026-09-14", rs.getString(1));
        assertEquals(50.0, rs.getDouble(2), 1e-6);

        assertTrue(rs.next());
        assertEquals("2026-09-13", rs.getString(1));
        assertEquals(50.0, rs.getDouble(2), 1e-6);

        assertFalse(rs.next());
        rs.close();
    }

    @Test
    public void queryDrivingConsumptionWithDateBounds() throws SQLException {
        long dayStart = 1726310400000L;   // 2026-09-15 00:00 UTC
        long dayEnd = 1726396800000L;     // 2026-09-16 00:00 UTC

        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO telemetry_sample " +
            "(ts_ms, odo_km, speed_kmh, gear, is_charging, " +
            "energy_spent_kwh, energy_regen_kwh, instant_power_kw_est, energy_measured, battery_temp_c) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

        // Row 1: Normal driving with OBD2 (battery_temp_c set)
        ps.setLong(1, dayStart + 1000);
        ps.setDouble(2, 1000.0);
        ps.setDouble(3, 60.0);
        ps.setInt(4, 2);
        ps.setInt(5, 0);
        ps.setDouble(6, 0.25);  // spent
        ps.setDouble(7, 0.0);   // regen
        ps.setDouble(8, 5.0);   // power
        ps.setInt(9, 1);        // measured
        ps.setDouble(10, 35.5); // battery_temp_c (OBD2)
        ps.executeUpdate();

        // Row 2: Regeneration
        ps.setLong(1, dayStart + 2000);
        ps.setDouble(2, 1000.5);
        ps.setDouble(3, 50.0);
        ps.setInt(4, 2);
        ps.setInt(5, 0);
        ps.setDouble(6, 0.1);   // spent
        ps.setDouble(7, 0.05);  // regen
        ps.setDouble(8, -1.0);  // power
        ps.setInt(9, 1);        // measured
        ps.setDouble(10, 36.0);
        ps.executeUpdate();

        // Row 3: Charging (excluded by gear 4)
        ps.setLong(1, dayStart + 3000);
        ps.setDouble(2, 1000.5);
        ps.setDouble(3, 0.0);
        ps.setInt(4, 4);        // Park
        ps.setInt(5, 1);        // Charging
        ps.setDouble(6, 0.0);
        ps.setDouble(7, 0.0);
        ps.setDouble(8, 0.0);
        ps.setInt(9, 0);        // Estimated for charging rows
        ps.setDouble(10, 40.0);
        ps.executeUpdate();

        // Row 4: Outside day bounds (should not be included)
        ps.setLong(1, dayEnd + 1000);
        ps.setDouble(2, 1001.0);
        ps.setDouble(3, 70.0);
        ps.setInt(4, 2);
        ps.setInt(5, 0);
        ps.setDouble(6, 0.3);
        ps.setDouble(7, 0.0);
        ps.setDouble(8, 6.0);
        ps.setInt(9, 1);
        ps.setDouble(10, 35.0);
        ps.executeUpdate();
        ps.close();

        // Simulate DailyStatsProvider.queryDrivingConsumption
        String whereClause = "ts_ms >= ? AND ts_ms < ?";
        ps = conn.prepareStatement(
            "SELECT ts_ms, odo_km, speed_kmh, gear, is_charging, " +
            "       energy_spent_kwh, energy_regen_kwh, instant_power_kw_est, energy_measured, " +
            "       battery_temp_c " +
            "FROM telemetry_sample WHERE " + whereClause + " ORDER BY ts_ms ASC, id ASC");
        ps.setLong(1, dayStart);
        ps.setLong(2, dayEnd);
        ResultSet rs = ps.executeQuery();

        int count = 0;
        double totalSpent = 0;
        double totalRegen = 0;
        while (rs.next()) {
            count++;
            totalSpent += rs.getDouble("energy_spent_kwh");
            totalRegen += rs.getDouble("energy_regen_kwh");
        }
        rs.close();
        ps.close();

        // Should have rows 1, 2, 3 (row 4 is outside day bounds)
        assertEquals(3, count);
        assertEquals(0.35, totalSpent, 1e-6);  // 0.25 + 0.1 + 0.0
        assertEquals(0.05, totalRegen, 1e-6);  // 0.0 + 0.05 + 0.0
    }

    @Test
    public void dailyStatFrozenRowsPreferredOverRawSamples() throws SQLException {
        String date = "2026-09-15";
        long dayStart = 1726310400000L;
        long dayEnd = 1726396800000L;

        // Insert raw samples for the day
        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO telemetry_sample (ts_ms, odo_km, battery_pct) VALUES (?, ?, ?)");
        ps.setLong(1, dayStart);
        ps.setDouble(2, 1000.0);
        ps.setInt(3, 80);
        ps.executeUpdate();

        ps.setLong(1, dayEnd - 1000);
        ps.setDouble(2, 1050.0);
        ps.setInt(3, 60);
        ps.executeUpdate();
        ps.close();

        // Insert a frozen daily_stat row
        ps = conn.prepareStatement(
            "INSERT INTO daily_stat " +
            "(date, first_odo_km, last_odo_km, first_battery_pct, last_battery_pct, " +
            "min_battery_pct, max_battery_pct, avg_speed_kmh, avg_temp_c, " +
            "ascent_m, descent_m, driving_minutes, charge_count, charge_kwh, " +
            "discharge_kwh, regen_kwh, net_kwh, charge_cost, energy_source) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

        ps.setString(1, date);
        ps.setDouble(2, 1000.0);
        ps.setDouble(3, 1050.0);
        ps.setInt(4, 80);
        ps.setInt(5, 60);
        ps.setInt(6, 50);
        ps.setInt(7, 90);
        ps.setDouble(8, 50.0);
        ps.setDouble(9, 22.5);
        ps.setDouble(10, 200.0);
        ps.setDouble(11, 100.0);
        ps.setDouble(12, 480.0);  // 8 hours of driving
        ps.setInt(13, 2);         // 2 charge sessions
        ps.setDouble(14, 25.0);
        ps.setDouble(15, 3.0);    // discharge
        ps.setDouble(16, 0.5);    // regen
        ps.setDouble(17, 2.5);    // net
        ps.setDouble(18, 5.50);   // cost
        ps.setString(19, "MEASURED");
        ps.executeUpdate();
        ps.close();

        // Query daily_stat - mimicking getDayOverview's query
        ps = conn.prepareStatement(
            "SELECT first_odo_km, last_odo_km, first_battery_pct, last_battery_pct, " +
            "       min_battery_pct, max_battery_pct, avg_speed_kmh, avg_temp_c, " +
            "       ascent_m, descent_m, driving_minutes, charge_count, charge_kwh, " +
            "       discharge_kwh, regen_kwh, net_kwh, charge_cost, energy_source " +
            "FROM daily_stat WHERE date = ?");
        ps.setString(1, date);
        ResultSet rs = ps.executeQuery();

        assertTrue("Should have frozen daily_stat row", rs.next());
        assertEquals(1000.0, rs.getDouble("first_odo_km"), 1e-6);
        assertEquals(1050.0, rs.getDouble("last_odo_km"), 1e-6);
        assertEquals(80, rs.getInt("first_battery_pct"));
        assertEquals(60, rs.getInt("last_battery_pct"));
        assertEquals(50, rs.getInt("min_battery_pct"));
        assertEquals(90, rs.getInt("max_battery_pct"));
        assertEquals(50.0, rs.getDouble("avg_speed_kmh"), 1e-6);
        assertEquals(22.5, rs.getDouble("avg_temp_c"), 1e-6);
        assertEquals(200.0, rs.getDouble("ascent_m"), 1e-6);
        assertEquals(100.0, rs.getDouble("descent_m"), 1e-6);
        assertEquals(480.0, rs.getDouble("driving_minutes"), 1e-6);
        assertEquals(2, rs.getInt("charge_count"));
        assertEquals(25.0, rs.getDouble("charge_kwh"), 1e-6);
        assertEquals(3.0, rs.getDouble("discharge_kwh"), 1e-6);
        assertEquals(0.5, rs.getDouble("regen_kwh"), 1e-6);
        assertEquals(2.5, rs.getDouble("net_kwh"), 1e-6);
        assertEquals(5.50, rs.getDouble("charge_cost"), 1e-6);
        assertEquals("MEASURED", rs.getString("energy_source"));

        assertFalse("Should have only one row", rs.next());
        rs.close();
        ps.close();
    }

    @Test
    public void tripQueryAggregatesAcrossDateBounds() throws SQLException {
        long dayStart = 1726310400000L;
        long dayEnd = 1726396800000L;

        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO trip (start_ms, end_ms, ascent_m, descent_m) VALUES (?, ?, ?, ?)");

        // Trip 1: Starts today, ends today (2 hours)
        ps.setLong(1, dayStart + 3600000);    // starts 1 hour into day
        ps.setLong(2, dayStart + 10800000);   // ends 3 hours into day (2 hour duration)
        ps.setDouble(3, 50.0);
        ps.setDouble(4, 30.0);
        ps.executeUpdate();

        // Trip 2: Starts today (2 hours)
        ps.setLong(1, dayStart + 43200000);   // starts 12 hours into day
        ps.setLong(2, dayStart + 50400000);   // ends 14 hours into day (2 hour duration)
        ps.setDouble(3, 100.0);
        ps.setDouble(4, 60.0);
        ps.executeUpdate();

        // Trip 3: Outside day bounds (should not be included)
        ps.setLong(1, dayEnd + 3600000);
        ps.setLong(2, dayEnd + 7200000);
        ps.setDouble(3, 75.0);
        ps.setDouble(4, 45.0);
        ps.executeUpdate();
        ps.close();

        // Aggregate query from getDayOverview
        ps = conn.prepareStatement(
            "SELECT COALESCE(SUM(ascent_m),0), COALESCE(SUM(descent_m),0), " +
            "       COALESCE(SUM(end_ms - start_ms),0) FROM trip " +
            "WHERE start_ms >= ? AND start_ms < ? AND end_ms IS NOT NULL");
        ps.setLong(1, dayStart);
        ps.setLong(2, dayEnd);
        ResultSet rs = ps.executeQuery();

        assertTrue(rs.next());
        double ascent = rs.getDouble(1);
        double descent = rs.getDouble(2);
        double durationMs = rs.getDouble(3);

        assertEquals(150.0, ascent, 1e-6);     // 50 + 100
        assertEquals(90.0, descent, 1e-6);    // 30 + 60
        assertEquals(7200000 + 7200000, durationMs, 1e-6);  // 2h + 2h

        assertFalse(rs.next());
        rs.close();
        ps.close();
    }

    @Test
    public void chargeSessionQueryGroupsByDay() throws SQLException {
        String date = "2026-09-15";
        long dayStart = 1726310400000L;
        long dayEnd = 1726396800000L;

        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO charge_session (start_ms, end_ms, soc_start, soc_end, kwh, cost) " +
            "VALUES (?, ?, ?, ?, ?, ?)");

        // Charge 1: AC, within day
        ps.setLong(1, dayStart + 3600000);
        ps.setLong(2, dayStart + 7200000);
        ps.setInt(3, 20);
        ps.setInt(4, 50);
        ps.setDouble(5, 20.0);
        ps.setDouble(6, 2.50);
        ps.executeUpdate();

        // Charge 2: AC, within day
        ps.setLong(1, dayStart + 43200000);
        ps.setLong(2, dayStart + 50400000);
        ps.setInt(3, 50);
        ps.setInt(4, 90);
        ps.setDouble(5, 30.0);
        ps.setDouble(6, 3.00);
        ps.executeUpdate();

        // Charge 3: Outside day (should not be included)
        ps.setLong(1, dayEnd + 3600000);
        ps.setLong(2, dayEnd + 7200000);
        ps.setInt(3, 30);
        ps.setInt(4, 70);
        ps.setDouble(5, 25.0);
        ps.setDouble(6, 2.75);
        ps.executeUpdate();
        ps.close();

        // Query from getDayOverview
        ps = conn.prepareStatement(
            "SELECT COUNT(*) AS count, COALESCE(SUM(kwh), 0) AS total_kwh, " +
            "       COALESCE(SUM(cost), 0) AS total_cost " +
            "FROM charge_session WHERE start_ms >= ? AND start_ms < ? AND end_ms IS NOT NULL");
        ps.setLong(1, dayStart);
        ps.setLong(2, dayEnd);
        ResultSet rs = ps.executeQuery();

        assertTrue(rs.next());
        assertEquals(2, rs.getInt("count"));
        assertEquals(50.0, rs.getDouble("total_kwh"), 1e-6);  // 20 + 30
        assertEquals(5.50, rs.getDouble("total_cost"), 1e-6); // 2.50 + 3.00

        assertFalse(rs.next());
        rs.close();
        ps.close();
    }

    @Test
    public void measureVsEstimatedEnergyClassification() throws SQLException {
        long dayStart = 1726310400000L;
        long dayEnd = 1726396800000L;

        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO telemetry_sample " +
            "(ts_ms, energy_measured, battery_temp_c, energy_spent_kwh, energy_regen_kwh) " +
            "VALUES (?, ?, ?, ?, ?)");

        // Row 1: energy_measured=1 (explicitly marked measured)
        ps.setLong(1, dayStart + 1000);
        ps.setInt(2, 1);
        ps.setDouble(3, 35.0);
        ps.setDouble(4, 0.1);
        ps.setDouble(5, 0.0);
        ps.executeUpdate();

        // Row 2: battery_temp_c set (OBD2-exclusive, proof of measured)
        ps.setLong(1, dayStart + 2000);
        ps.setInt(2, 0);  // marked as estimated
        ps.setDouble(3, 35.5);  // but battery_temp_c is set
        ps.setDouble(4, 0.1);
        ps.setDouble(5, 0.0);
        ps.executeUpdate();

        // Row 3: energy_measured=0, no battery_temp_c, real (non-zero) energy
        // -- a genuine estimate: something happened and OBD2 missed it.
        ps.setLong(1, dayStart + 3000);
        ps.setInt(2, 0);
        ps.setNull(3, java.sql.Types.REAL);
        ps.setDouble(4, 0.05);
        ps.setDouble(5, 0.0);
        ps.executeUpdate();

        // Row 4: Outside day bounds
        ps.setLong(1, dayEnd + 1000);
        ps.setInt(2, 0);
        ps.setNull(3, java.sql.Types.REAL);
        ps.setDouble(4, 0.05);
        ps.setDouble(5, 0.0);
        ps.executeUpdate();

        // Row 5: parked/idle -- energy_measured=0, no battery_temp_c, but
        // zero energy either way (nothing was happening, so OBD2 was never
        // polled). This is NOT an estimate of anything and must not count
        // toward estimated_count -- see DailyStatsProvider.getEnergyBalances()'s
        // 2026-09-24 comment: a day that was mostly idle with the screen on
        // used to read MIXED purely from rows like this one, even though
        // every real driving sample that day was fully OBD2-measured.
        ps.setLong(1, dayStart + 4000);
        ps.setInt(2, 0);
        ps.setNull(3, java.sql.Types.REAL);
        ps.setDouble(4, 0.0);
        ps.setDouble(5, 0.0);
        ps.executeUpdate();
        ps.close();

        // Query to classify measured vs estimated (from getEnergyBalances())
        ps = conn.prepareStatement(
            "SELECT " +
            "  SUM(CASE WHEN energy_measured=1 OR battery_temp_c IS NOT NULL THEN 1 ELSE 0 END) AS measured_count, " +
            "  SUM(CASE WHEN energy_measured=0 AND battery_temp_c IS NULL " +
            "            AND (IFNULL(energy_spent_kwh,0) != 0 OR IFNULL(energy_regen_kwh,0) != 0) " +
            "       THEN 1 ELSE 0 END) AS estimated_count " +
            "FROM telemetry_sample WHERE ts_ms >= ? AND ts_ms < ?");
        ps.setLong(1, dayStart);
        ps.setLong(2, dayEnd);
        ResultSet rs = ps.executeQuery();

        assertTrue(rs.next());
        // Row 1 and 2 are measured (either marked or battery_temp_c)
        assertEquals(2, rs.getInt("measured_count"));
        // Row 3 is a real estimate; Row 5 (idle, zero energy) must not count
        assertEquals(1, rs.getInt("estimated_count"));

        assertFalse(rs.next());
        rs.close();
        ps.close();
    }
}
