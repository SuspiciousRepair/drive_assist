package com.geely.drivemem.car;

import com.geely.drivemem.SqliteTestDb;

import org.junit.Test;

import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.Assert.*;

/** A charge's place is the last route point before it started, within a day. */
public class ChargePlaceSqlTest {
    private static final long DAY = 86_400_000L;

    @Test public void backfillTakesTheLastPointBeforeTheCharge() throws Exception {
        SqliteTestDb db = new SqliteTestDb();
        db.createCarDbSchema();
        try (Statement s = db.getConnection().createStatement()) {
            // Neutral places, no real location in the repo.
            s.execute("INSERT INTO trip_point (trip_id, ts_ms, lat, lon) VALUES"
                + " (1, 1000, 10.0, 20.0), (1, 2000, 10.5, 20.5),"   // trip ends at 2000
                + " (2, 9000, 11.0, 21.0)");                          // a later trip
            s.execute("INSERT INTO charge_session (start_ms, end_ms) VALUES"
                + " (5000, 8000),"                                   // after trip 1: its end
                + " (" + (2 * DAY + 9000) + ", " + (2 * DAY + 10000) + ")");  // nothing in the day before
            s.execute("INSERT INTO charge_session (start_ms, end_ms, lat, lon) VALUES (9500, 9600, 1.0, 2.0)");
            s.execute(CarDb.CHARGE_PLACE_BACKFILL_SQL);
            ResultSet r = s.executeQuery("SELECT lat, lon FROM charge_session ORDER BY id");
            r.next(); assertEquals(10.5, r.getDouble(1), 0); assertEquals(20.5, r.getDouble(2), 0);
            r.next(); r.getDouble(1); assertTrue("too old: no place", r.wasNull());
            r.next(); assertEquals("an existing place is kept", 1.0, r.getDouble(1), 0);
        }
    }

    @Test public void placeQueryMatchesTheBackfill() throws Exception {
        SqliteTestDb db = new SqliteTestDb();
        db.createCarDbSchema();
        try (java.sql.PreparedStatement p = db.getConnection().prepareStatement(CarDb.CHARGE_PLACE_SQL);
             Statement s = db.getConnection().createStatement()) {
            s.execute("INSERT INTO trip_point (trip_id, ts_ms, lat, lon) VALUES (1, 1000, 10.0, 20.0), (1, 2000, 10.5, 20.5)");
            p.setLong(1, 5000); p.setLong(2, 5000);
            ResultSet r = p.executeQuery();
            assertTrue(r.next());
            assertEquals(10.5, r.getDouble(1), 0);
        }
    }
}
