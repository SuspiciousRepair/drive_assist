package com.geely.drivemem;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * In-memory SQLite database for unit tests. Uses sqlite-jdbc to provide
 * real SQL execution without Android dependencies.
 */
public final class SqliteTestDb {

    private Connection connection;

    public SqliteTestDb() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    public Connection getConnection() {
        return connection;
    }

    public void createCarDbSchema() throws SQLException {
        String[] createStatements = {
            "CREATE TABLE telemetry_sample (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts_ms INTEGER NOT NULL," +
                "odo_km REAL," +
                "battery_pct INTEGER, battery_raw_pct REAL," +
                "speed_kmh REAL," +
                "gear INTEGER," +
                "is_charging INTEGER," +
                "charge_a REAL, charge_v REAL," +
                "park_mode INTEGER," +
                "outside_temp_c REAL," +
                "altitude_m REAL," +
                "instant_power_kw_est REAL," +
                "drive_energy_pct REAL," +
                "battery_energy_pct REAL," +
                "other_energy_pct REAL," +
                "power_spent_kw REAL," +
                "power_regen_kw REAL," +
                "energy_spent_kwh REAL," +
                "energy_regen_kwh REAL," +
                "energy_net_kwh REAL," +
                "battery_temp_c REAL," +
                "energy_measured INTEGER," +
                "energy_spent_est_kwh REAL, energy_regen_est_kwh REAL)",

            "CREATE INDEX idx_sample_ts ON telemetry_sample(ts_ms)",
            "CREATE INDEX idx_sample_odo ON telemetry_sample(odo_km)",

            "CREATE TABLE charge_session (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL," +
                "start_sample_id INTEGER, end_sample_id INTEGER," +
                "soc_start INTEGER, soc_end INTEGER," +
                "kwh REAL, avg_power_w REAL," +
                "max_charge_v REAL," +
                "samples INTEGER," +
                "odo_start_km REAL," +
                "cost REAL," +
                "dismissed INTEGER NOT NULL DEFAULT 0)",

            "CREATE TABLE park_session (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "start_ms INTEGER NOT NULL, end_ms INTEGER," +
                "start_sample_id INTEGER, end_sample_id INTEGER)",

            "CREATE TABLE trip (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "start_ms INTEGER NOT NULL, end_ms INTEGER," +
                "start_sample_id INTEGER, end_sample_id INTEGER," +
                "ascent_m REAL, descent_m REAL," +
                "regen_kwh REAL," +
                "spent_kwh REAL," +
                "net_kwh REAL," +
                "energy_source TEXT, estimated_net_kwh REAL)",

            "CREATE TABLE trip_energy_segment (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER," +
                "start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL," +
                "source TEXT NOT NULL, net_kwh REAL NOT NULL)",
            "CREATE INDEX idx_trip_energy_segment_trip ON trip_energy_segment(trip_id)",

            "CREATE TABLE comfort_event (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts_ms INTEGER NOT NULL," +
                "event TEXT NOT NULL," +
                "pointer INTEGER, out_c REAL, setpoint REAL, fan INTEGER," +
                "ac INTEGER, recirc INTEGER, power INTEGER," +
                "detail TEXT)",

            "CREATE TABLE battery_health_reading (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts_ms INTEGER NOT NULL," +
                "odo_km REAL," +
                "soh_pct REAL NOT NULL," +
                "source TEXT NOT NULL)",

            "CREATE TABLE valet_session (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "start_ms INTEGER NOT NULL, end_ms INTEGER," +
                "start_odo_km REAL, end_odo_km REAL," +
                "start_soc INTEGER, end_soc INTEGER," +
                "max_speed_kmh REAL, max_power_kw REAL)",

            "CREATE TABLE charge_stop_event (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL," +
                "occurred_ms INTEGER NOT NULL, soc INTEGER, kwh REAL, published INTEGER NOT NULL DEFAULT 0," +
                "UNIQUE(session_id, occurred_ms))",

            "CREATE TABLE daily_stat (" +
                "date TEXT PRIMARY KEY," +
                "first_odo_km REAL NOT NULL, last_odo_km REAL NOT NULL," +
                "first_battery_pct INTEGER, last_battery_pct INTEGER," +
                "min_battery_pct INTEGER, max_battery_pct INTEGER," +
                "avg_speed_kmh REAL, max_speed_kmh REAL," +
                "min_temp_c REAL, avg_temp_c REAL, max_temp_c REAL," +
                "ascent_m REAL NOT NULL DEFAULT 0, descent_m REAL NOT NULL DEFAULT 0," +
                "trip_count INTEGER NOT NULL DEFAULT 0, driving_minutes REAL NOT NULL DEFAULT 0," +
                "charge_count INTEGER NOT NULL DEFAULT 0, charge_kwh REAL NOT NULL DEFAULT 0," +
                "charge_cost REAL NOT NULL DEFAULT 0," +
                "discharge_kwh REAL NOT NULL DEFAULT 0," +
                "regen_kwh REAL NOT NULL DEFAULT 0," +
                "net_kwh REAL NOT NULL DEFAULT 0," +
                "energy_source TEXT)"
        };

        for (String sql : createStatements) {
            connection.createStatement().execute(sql);
        }
    }

    public void close() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }
}
