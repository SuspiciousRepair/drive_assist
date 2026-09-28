package com.geely.drivemem.sensors;

import com.geely.drivemem.SqliteTestDb;

import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** DailyStatsProvider.TRIP_IN_VALET_SQL, run on a real SQLite database. */
public class ValetTripFilterSqlTest {

    // 2026-09-28: Valet turned off mid-drive splits the trip, and the rest of
    // the drive starts at exactly valet end_ms. It must stay in the day list.
    @Test public void driveSplitAtValetEndIsNotHidden() throws Exception {
        SqliteTestDb db = new SqliteTestDb();
        db.createCarDbSchema();
        Connection c = db.getConnection();
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO valet_session (start_ms, end_ms) VALUES (1000, 5000)");
            s.execute("INSERT INTO valet_session (start_ms, end_ms) VALUES (9000, NULL)");
            s.execute("INSERT INTO trip (id, start_ms, end_ms) VALUES"
                + " (1, 500, 900),"      // before Valet: shown
                + " (2, 1000, 3000),"    // starts as Valet starts: hidden
                + " (3, 3000, 5000),"    // under Valet: hidden
                + " (4, 5000, 8000),"    // split off at Valet end: shown
                + " (5, 9500, NULL)");   // under a Valet still on: hidden
            ResultSet r = s.executeQuery("SELECT t.id FROM trip t WHERE NOT "
                + DailyStatsProvider.TRIP_IN_VALET_SQL + " ORDER BY t.id");
            List<Integer> shown = new ArrayList<>();
            while (r.next()) shown.add(r.getInt(1));
            assertEquals(List.of(1, 4), shown);
        } finally {
            db.close();
        }
    }
}
