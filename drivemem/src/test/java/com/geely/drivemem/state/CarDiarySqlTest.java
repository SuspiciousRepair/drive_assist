package com.geely.drivemem.state;

import com.geely.drivemem.SqliteTestDb;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The Car Diary's queries and TCO window on real SQLite: the period bounds,
 * the tracked km, the open tire entry check and the recurrence read-back. */
public class CarDiarySqlTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long DAY = 86_400_000L;

    private SqliteTestDb db;
    private Connection conn;

    @Before public void setUp() throws Exception {
        db = new SqliteTestDb();
        conn = db.getConnection();
        db.createCarDbSchema();
    }

    @After public void tearDown() throws Exception { db.close(); }

    private void x(String sql) throws Exception {
        try (Statement st = conn.createStatement()) { st.execute(sql); }
    }

    /** First row of a query, as doubles (NaN for NULL) and as strings. */
    private Object[][] row(String sql, String... args) throws Exception {
        try (PreparedStatement p = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) p.setString(i + 1, args[i]);
            try (ResultSet r = p.executeQuery()) {
                assertTrue(r.next());
                int n = r.getMetaData().getColumnCount();
                Object[][] out = new Object[n][2];
                for (int i = 0; i < n; i++) {
                    out[i][1] = r.getString(i + 1);
                    out[i][0] = out[i][1] == null ? Double.NaN : r.getDouble(i + 1);
                }
                return out;
            }
        }
    }

    private static long at(int y, int m, int d, int h) {
        return LocalDate.of(y, m, d).atTime(h, 0).atZone(UTC).toInstant().toEpochMilli();
    }

    private static CarDiary.Entry cost(long id, CarDiary.Category cat, long tsMs, double cost) {
        return new CarDiary.Entry(id, "u-" + id, tsMs, cat.kind, cat, CarDiary.STATUS_DONE,
            null, null, null, null, cost, null, null, null, null, null, null, 1);
    }

    // 3a: the first tracked day moves only the proration window.
    @Test public void oneOffCostBeforeTheFirstTrackedDayStaysInThePeriod() {
        long now = at(2026, 11, 24, 12);
        long[] bounds = CarDiary.PeriodFilter.DAYS_30.boundsMs(now, UTC);
        long endIncl = CarDiary.inclusiveEndMs(bounds, now);
        // History starts 10 days ago; the period started 29 days ago.
        String firstDay = LocalDate.of(2026, 11, 14).toString();
        long prorateStart = CarDiary.prorationStartMs(bounds[0], bounds[1], firstDay, false, UTC);
        assertEquals(at(2026, 11, 14, 0), prorateStart);

        CarDiary.Entry ticket = cost(1, CarDiary.Category.TRAFFIC_TICKET, now - 20 * DAY, 200.0);
        CarDiary.Entry wash = cost(2, CarDiary.Category.WASH_DETAIL, now - 25 * DAY, 80.0);
        CarDiary.Entry before = cost(3, CarDiary.Category.WASH_DETAIL, now - 40 * DAY, 999.0);
        CarDiary.TcoSummary tco = CarDiary.computeTco(0, Arrays.asList(ticket, wash, before), 500.0,
            bounds[0], endIncl, prorateStart, bounds[1], false);
        assertEquals(200.0, tco.roadCost, 0.01);
        assertEquals(80.0, tco.maintenanceCost, 0.01);

        // A fixed cost is still prorated over the tracked part only: 10.5 of 365 days.
        CarDiary.Entry insurance = cost(4, CarDiary.Category.INSURANCE, now - 20 * DAY, 3650.0);
        CarDiary.TcoSummary fixed = CarDiary.computeTco(0, Collections.singletonList(insurance), 500.0,
            bounds[0], endIncl, prorateStart, bounds[1], false);
        assertEquals(105.0, fixed.bureaucracyCost, 0.01);
        assertTrue(fixed.prorated);
    }

    @Test public void prorationStartFallsBackToThePeriodOrTheLast30Days() {
        long now = at(2026, 11, 24, 12);
        long start = now - 29 * DAY;
        // First tracked day before the period: the period start stays.
        assertEquals(start, CarDiary.prorationStartMs(start, now, "2026-01-01", false, UTC));
        // ALL-time: anchored to the first tracked day; without history, the last 30 days.
        assertEquals(at(2026, 1, 1, 0), CarDiary.prorationStartMs(0, now, "2026-01-01", true, UTC));
        assertEquals(now - 30 * DAY, CarDiary.prorationStartMs(0, now, null, true, UTC));
    }

    // 3b: a past year ends on Dec 31, not on Jan 1 of the next year.
    @Test public void pastYearDoesNotTakeInJanuaryFirstOfTheNextYear() throws Exception {
        long now = at(2026, 11, 24, 12);
        long[] bounds = CarDiary.PeriodFilter.ofYear(2025).boundsMs(now, UTC);
        long newYear = at(2026, 1, 1, 0);
        assertEquals(newYear, bounds[1]);
        long endIncl = CarDiary.inclusiveEndMs(bounds, now);
        assertEquals(newYear - 1, endIncl);
        assertArrayEq(new String[]{"2025-01-01", "2025-12-31"}, CarDiary.tcoDayRange(bounds, now, UTC));

        x("INSERT INTO daily_stat (date, first_odo_km, last_odo_km) VALUES "
            + "('2025-12-30', 100, 150), ('2025-12-31', 150, 200), ('2026-01-01', 200, 900)");
        Object[][] odo = row(CarDiary.TCO_ODO_PERIOD_SQL, CarDiary.tcoDayRange(bounds, now, UTC));
        assertEquals(100.0, (Double) odo[0][0], 0.001);
        assertEquals(200.0, (Double) odo[1][0], 0.001);
        assertEquals("2025-12-30", odo[2][1]);

        x("INSERT INTO charge_session (start_ms, end_ms, cost) VALUES "
            + "(" + (newYear - 1000) + ", " + newYear + ", 40), "
            + "(" + newYear + ", " + (newYear + 1000) + ", 70)");
        Object[][] charge = row(CarDiary.TCO_CHARGE_COST_SQL,
            String.valueOf(bounds[0]), String.valueOf(endIncl));
        assertEquals(40.0, (Double) charge[0][0], 0.001);

        // The list and the one-off costs use the same end.
        CarDiary.Entry dec31 = cost(1, CarDiary.Category.WASH_DETAIL, newYear - 1000, 50.0);
        CarDiary.Entry jan1 = cost(2, CarDiary.Category.WASH_DETAIL, newYear, 60.0);
        assertEquals(1, CarDiary.filterEntries(Arrays.asList(dec31, jan1), null,
            CarDiary.PeriodFilter.ofYear(2025), now, UTC).size());
        assertEquals(1, CarDiary.filterEntries(Arrays.asList(dec31, jan1), null,
            CarDiary.PeriodFilter.ofYear(2026), now, UTC).size());
        CarDiary.TcoSummary tco = CarDiary.computeTco(0, Arrays.asList(dec31, jan1), 100.0,
            bounds[0], endIncl, bounds[0], bounds[1], false);
        assertEquals(50.0, tco.maintenanceCost, 0.01);
    }

    // Road TCO: toll passages (not dismissed) and parking costs in the
    // period, plus the diary's own road entries.
    @Test public void roadCostAddsTollsAndParkingInThePeriod() throws Exception {
        long now = at(2026, 11, 24, 12);
        long[] bounds = CarDiary.PeriodFilter.DAYS_30.boundsMs(now, UTC);
        long endIncl = CarDiary.inclusiveEndMs(bounds, now);
        long in = now - 2 * DAY, before = bounds[0] - DAY;
        x("INSERT INTO toll_passage (uuid, ts_ms, lat, lon, booth_name, cost, dismissed, source) VALUES "
            + "('t1', " + in + ", 0, 0, 'A', 12.5, 0, 'auto'), "
            + "('t2', " + in + ", 0, 0, 'B', 99, 1, 'auto'), "
            + "('t3', " + before + ", 0, 0, 'C', 7, 0, 'auto')");
        x("INSERT INTO parking_cost (uuid, start_ms, cost, kind, updated_ms) VALUES "
            + "('k1', " + in + ", 25, 'lot', " + in + "), "
            + "('k2', " + in + ", 40, 'lot', " + in + "), "
            + "('k3', " + before + ", 15, 'street', " + before + ")");
        String[] win = {String.valueOf(bounds[0]), String.valueOf(endIncl)};
        double tolls = (Double) row(CarDiary.TCO_TOLL_COST_SQL, win)[0][0];
        double parking = (Double) row(CarDiary.TCO_PARKING_COST_SQL, win)[0][0];
        assertEquals(12.5, tolls, 0.001);
        assertEquals(65.0, parking, 0.001);

        CarDiary.Entry ticket = cost(1, CarDiary.Category.TRAFFIC_TICKET, in, 100.0);
        CarDiary.TcoSummary tco = CarDiary.computeTco(50.0, tolls + parking, Collections.singletonList(ticket),
            500.0, bounds[0], endIncl, bounds[0], bounds[1], false);
        assertEquals(177.5, tco.roadCost, 0.01);
        assertEquals(227.5, tco.totalCost, 0.01);
    }

    @Test public void periodThatEndsNowKeepsNowAndToday() {
        long now = at(2026, 11, 24, 12);
        long[] bounds = CarDiary.PeriodFilter.DAYS_30.boundsMs(now, UTC);
        assertEquals(now, CarDiary.inclusiveEndMs(bounds, now));
        assertArrayEq(new String[]{"2026-10-26", "2026-11-24"}, CarDiary.tcoDayRange(bounds, now, UTC));
    }

    // 3c: no history, or no distance in it, gives no km (and so no cost/km).
    @Test public void trackedKmIsZeroWithoutHistory() throws Exception {
        Object[][] empty = row(CarDiary.TCO_ODO_SQL);
        assertTrue(Double.isNaN((Double) empty[0][0]));
        assertEquals(0.0, CarDiary.trackedKmFrom(-1, -1, 7_077.0), 0.001);
        assertEquals(0.0, CarDiary.trackedKmFrom(-1, -1, null), 0.001);
        assertEquals(0.0, CarDiary.trackedKmFrom(500, 500, null), 0.001);
        assertEquals(100.0, CarDiary.trackedKmFrom(500, 600, null), 0.001);
        assertEquals(150.0, CarDiary.trackedKmFrom(500, 600, 650.0), 0.001);

        CarDiary.TcoSummary tco = CarDiary.TcoSummary.compute(300.0, 100.0, 0.0, 0.0, 0.0, false);
        assertTrue(tco.hasCosts());
        assertEquals(-1.0, tco.costPerKm, 0.001);
    }

    // The tire warning is logged once while an auto entry is still open.
    @Test public void openTireEntryIsFoundUntilItIsResolved() throws Exception {
        assertEquals(0.0, (Double) row(CarDiary.OPEN_TIRE_AUTO_SQL)[0][0], 0.001);
        x("INSERT INTO diary_event (uuid, ts_ms, kind, category, status) VALUES "
            + "('n1', 100, 'NOISE', 'NOISE', 'OPEN'), "
            + "('t0', 50, 'AUTO', 'TPMS_AUTO', 'RESOLVED')");
        assertEquals(0.0, (Double) row(CarDiary.OPEN_TIRE_AUTO_SQL)[0][0], 0.001);
        x("INSERT INTO diary_event (uuid, ts_ms, kind, category, status) VALUES "
            + "('t1', 200, 'AUTO', 'TPMS_AUTO', 'OPEN')");
        assertEquals(1.0, (Double) row(CarDiary.OPEN_TIRE_AUTO_SQL)[0][0], 0.001);
        x("UPDATE diary_event SET status = 'RESOLVED' WHERE uuid = 't1'");
        assertEquals(0.0, (Double) row(CarDiary.OPEN_TIRE_AUTO_SQL)[0][0], 0.001);
    }

    // A recurrence is a child row; the parent keeps its date and the last one is read back.
    @Test public void recurrenceChildrenGiveCountAndLastOccurrence() throws Exception {
        x("INSERT INTO diary_event (id, uuid, ts_ms, kind, category, status, odo_km, cost) VALUES "
            + "(1, 'p', 1000, 'NOISE', 'NOISE', 'OPEN', 5000, 120)");
        x("INSERT INTO diary_event (uuid, ts_ms, kind, category, status, odo_km, parent_id) VALUES "
            + "('c1', 5000, 'NOISE', 'NOISE', 'DONE', 5300, 1), "
            + "('c2', 9000, 'NOISE', 'NOISE', 'DONE', 5600, 1)");
        Object[][] stats = row(CarDiary.CHILD_STATS_SQL);
        assertEquals(1.0, (Double) stats[0][0], 0.001);
        assertEquals(2.0, (Double) stats[1][0], 0.001);
        assertEquals(9000.0, (Double) stats[2][0], 0.001);
        Object[][] parent = row("SELECT ts_ms, odo_km FROM diary_event WHERE id = 1");
        assertEquals(1000.0, (Double) parent[0][0], 0.001);
        assertEquals(5000.0, (Double) parent[1][0], 0.001);

        CarDiary.Entry e = new CarDiary.Entry(1, "p", 1000, CarDiary.KIND_NOISE,
            CarDiary.Category.NOISE, CarDiary.STATUS_OPEN, 5000.0, null, null, null, 120.0,
            null, null, null, null, null, null, 3, 9000, null);
        assertEquals(1000, e.tsMs);
        assertEquals(9000, e.lastOccurrenceMs);
        assertEquals(3, e.occurrences);
        // Without children the last occurrence is the entry itself.
        assertEquals(1000, cost(2, CarDiary.Category.NOISE, 1000, 1.0).lastOccurrenceMs);
        assertFalse(e.lastOccurrenceMs < e.tsMs);
    }

    private static void assertArrayEq(String[] want, String[] got) {
        assertEquals(Arrays.asList(want), Arrays.asList(got));
    }
}
