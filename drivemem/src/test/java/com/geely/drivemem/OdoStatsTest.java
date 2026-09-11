package com.geely.drivemem;

import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.sensors.OdoStats;
import com.geely.drivemem.util.DbMigration;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
import static java.util.Arrays.asList;

public class OdoStatsTest {

    private static OdoStats.Reading r(String date, double km) {
        return new OdoStats.Reading(date, km);
    }

    private static long ms(String isoDate) throws Exception {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(isoDate).getTime();
    }

    // parseLine()/readOneFile() no longer exist on OdoStats — it's read-only
    // now, querying telemetry_sample instead of its own flat file (see
    // OdoStats's own header comment). The old file-format parsing this used
    // to test moved into DbMigration's one-time odo.log import instead,
    // which isn't unit-tested in isolation (tightly coupled to CarDb/
    // SQLite) — kmSince()'s own math, below, is what's left to verify here.

    @Test public void fewerThanTwoReadingsGivesZero() throws Exception {
        assertEquals(0, OdoStats.kmSince(asList(), ms("2026-09-03"), 30), 0.0);
        assertEquals(0, OdoStats.kmSince(asList(r("2026-09-01", 4090)), ms("2026-09-03"), 30), 0.0);
    }

    @Test public void usesTheReadingJustOutsideTheWindow() throws Exception {
        // 40 days of history, asking for the last 30 — base should be the
        // reading just before the 30-day cutoff, not the very first one.
        List<OdoStats.Reading> log = asList(
            r("2026-07-25", 4000),   // 40 days back — outside the window
            r("2026-08-10", 4050),   // 24 days back — inside the window, but not the base
            r("2026-09-03", 4090)    // today
        );
        double km = OdoStats.kmSince(log, ms("2026-09-03"), 30);
        assertEquals(90.0, km, 0.01);   // 4090 - 4000, not 4090 - 4050
    }

    @Test public void fallsBackToOldestReadingWhenLessHistoryThanRequested() throws Exception {
        // Only 5 days of history on file, but a 30-day window is asked for —
        // "since logging began" (5 days), not zero and not a crash.
        List<OdoStats.Reading> log = asList(
            r("2026-08-29", 4070),
            r("2026-09-03", 4090)
        );
        double km = OdoStats.kmSince(log, ms("2026-09-03"), 30);
        assertEquals(20.0, km, 0.01);
    }

    @Test public void neverGoesNegative() throws Exception {
        // Shouldn't happen (odometer only increases), but a clamp here beats
        // a negative "km driven" if a reading is ever out of order.
        List<OdoStats.Reading> log = asList(r("2026-09-01", 4100), r("2026-09-02", 4090));
        assertEquals(0.0, OdoStats.kmSince(log, ms("2026-09-03"), 30), 0.0);
    }

    private static OdoStats.DayRange range(String date, double first, double last) {
        return new OdoStats.DayRange(date, first, last);
    }

    // The bug this replaced: a day's bar used to be firstOdo[next day] -
    // firstOdo[this day] -- so a whole day's driving landed under the NEXT
    // day's label, and the most recent (still-open) day had nothing to diff
    // against at all. Each bar now stands on its own day's first-to-last.
    @Test public void recentDailyUsesEachDaysOwnRangeNotTheNextDaysFirstSample() throws Exception {
        List<OdoStats.DayRange> ranges = asList(
            range("2026-09-01", 4000, 4080),   // 80km, all its own
            range("2026-09-02", 4080, 4080),   // car never moved: a real 0
            range("2026-09-03", 4080, 4125)    // today so far, 45km -- a real partial total
        );
        OdoStats.DailySeries s = OdoStats.recentDaily(ranges, 3);
        assertEquals(80.0, s.km[0], 0.01);
        assertEquals(0.0, s.km[1], 0.01);
        assertEquals(45.0, s.km[2], 0.01);
        assertEquals("3/9", s.labels[2]);
    }

    @Test public void recentDailyPadsMissingDaysAtZero() throws Exception {
        List<OdoStats.DayRange> ranges = asList(range("2026-09-03", 4080, 4100));
        OdoStats.DailySeries s = OdoStats.recentDaily(ranges, 3);
        assertEquals(0.0, s.km[0], 0.01);
        assertEquals("", s.labels[0]);
        assertEquals(20.0, s.km[2], 0.01);
    }
}
