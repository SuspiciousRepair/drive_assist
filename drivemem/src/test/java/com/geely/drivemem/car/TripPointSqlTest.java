package com.geely.drivemem.car;

import com.geely.drivemem.SqliteTestDb;

import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;

/** trip_point's claim and clean-up SQL, run on a real SQLite database. */
public class TripPointSqlTest {

    @Test public void claimGivesTheTripItsPointsAndDropsOlderLooseOnes() throws Exception {
        SqliteTestDb db = new SqliteTestDb();
        Connection c = db.getConnection();
        try (Statement s = c.createStatement()) {
            s.execute(CarDb.TRIP_POINT_DDL);
            // 10: left by a crash before this trip. 100..400: this trip.
            // 500: the next trip, already open. 50: an older trip's point.
            s.execute("INSERT INTO trip_point (trip_id, ts_ms, lat, lon) VALUES"
                + " (NULL, 10, 0, 0), (3, 50, 0, 0), (NULL, 100, 0, 0), (NULL, 250, 0, 0),"
                + " (NULL, 400, 0, 0), (NULL, 500, 0, 0)");
        }
        try (PreparedStatement p = c.prepareStatement(CarDb.CLAIM_TRIP_POINTS_SQL)) {
            p.setLong(1, 7); p.setLong(2, 100); p.setLong(3, 400); p.executeUpdate();
        }
        try (PreparedStatement p = c.prepareStatement(CarDb.DROP_LOOSE_TRIP_POINTS_SQL)) {
            p.setLong(1, 400); p.executeUpdate();
        }
        try (Statement s = c.createStatement()) {
            ResultSet r = s.executeQuery("SELECT ts_ms, COALESCE(trip_id, -1) FROM trip_point ORDER BY ts_ms");
            long[][] want = {{50, 3}, {100, 7}, {250, 7}, {400, 7}, {500, -1}};
            for (long[] w : want) {
                r.next();
                assertEquals(w[0], r.getLong(1));
                assertEquals(w[1], r.getLong(2));
            }
            assertEquals(false, r.next());
        }
        db.close();
    }
}
