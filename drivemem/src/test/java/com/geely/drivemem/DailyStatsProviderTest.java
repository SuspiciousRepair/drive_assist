package com.geely.drivemem;

import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.sensors.DrivingConsumption;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;

// Covers DailyStatsProvider.aggregate() and the week/month date-range
// helpers added for plan/active/STATS-PERIOD-VIEWS-ROADMAP.md. getDayOverview
// / recentWeeks / recentMonths themselves are DB-backed and not unit-tested
// here, same reasoning OdoStatsTest gives for its own DB-touching methods --
// aggregate() is the pure, testable half of that split.
public class DailyStatsProviderTest {

    // Only the fields aggregate() actually reads carry real values; the
    // rest (efficiency/netKwh/netElevation/avgSpeed -- all recomputed by
    // aggregate() from the other fields, never copied through) are 0.
    private static DailyStatsProvider.DayOverview day(String date, double distanceKm,
            double dischargeKwh, double regenKwh, double ascentM, double descentM,
            double drivingMinutes, int chargeCount, double chargeKwh,
            int minBatt, int maxBatt, int firstBatt, int lastBatt, double avgTempC) {
        return new DailyStatsProvider.DayOverview(
            date, date, distanceKm, dischargeKwh, regenKwh, 0,
            0, ascentM, descentM, 0, firstBatt, lastBatt,
            minBatt, maxBatt, drivingMinutes, 0, avgTempC, chargeCount, chargeKwh,
            new ArrayList<>());
    }

    @Test public void sumsDistanceEnergyElevationAndCharging() {
        DailyStatsProvider.DayOverview a = day("2026-09-01", 10, 2.0, 0.5, 50, 40, 20, 1, 5.0, 60, 80, 80, 70, 20.0);
        DailyStatsProvider.DayOverview b = day("2026-09-02", 20, 3.0, 1.0, 30, 60, 30, 0, 0.0, 55, 75, 75, 65, 22.0);

        DailyStatsProvider.DayOverview total = DailyStatsProvider.aggregate(asList(a, b), "label");

        assertEquals(30, total.distanceKm, 1e-9);
        assertEquals(5.0, total.dischargeKwh, 1e-9);
        assertEquals(1.5, total.regenKwh, 1e-9);
        assertEquals(3.5, total.netKwh, 1e-9);
        assertEquals(80, total.ascentDPlusM, 1e-9);
        assertEquals(100, total.descentDMinusM, 1e-9);
        assertEquals(-20, total.netElevationM, 1e-9);
        assertEquals(50, total.drivingMinutes, 1e-9);
        assertEquals(1, total.chargeCount);
        assertEquals(5.0, total.chargeKwh, 1e-9);
    }

    @Test public void efficiencyMatchesTheSharedFormulaOnSummedTotals() {
        DailyStatsProvider.DayOverview a = day("2026-09-01", 10, 2.0, 0.5, 0, 0, 20, 0, 0, -1, -1, -1, -1, 0);
        DailyStatsProvider.DayOverview b = day("2026-09-02", 20, 3.0, 1.0, 0, 0, 30, 0, 0, -1, -1, -1, -1, 0);

        DailyStatsProvider.DayOverview total = DailyStatsProvider.aggregate(asList(a, b), "label");

        // Not a sixth copy of the formula -- calling the same shared method
        // directly on the summed totals must give the exact same number.
        double expected = DrivingConsumption.efficiencyKwh100km(30, 5.0, 1.5);
        assertEquals(expected, total.efficiencyKwh100km, 1e-9);
    }

    @Test public void avgSpeedIsDistanceOverTotalDrivingTimeNotAnAverageOfAverages() {
        DailyStatsProvider.DayOverview a = day("2026-09-01", 10, 0, 0, 0, 0, 60, 0, 0, -1, -1, -1, -1, 0);  // 10 km/h
        DailyStatsProvider.DayOverview b = day("2026-09-02", 90, 0, 0, 0, 0, 30, 0, 0, -1, -1, -1, -1, 0);  // 180 km/h

        DailyStatsProvider.DayOverview total = DailyStatsProvider.aggregate(asList(a, b), "label");

        // Correct: 100km / 90min. A wrong "average the two days' speeds"
        // formula would give (10+180)/2 = 95 instead.
        assertEquals(100.0 / (90.0 / 60.0), total.avgSpeedKmh, 1e-9);
    }

    @Test public void batteryMinMaxAndFirstLastIgnoreDaysWithNoReading() {
        DailyStatsProvider.DayOverview a = day("2026-09-01", 1, 0, 0, 0, 0, 1, 0, 0, -1, -1, -1, -1, 0); // no battery data
        DailyStatsProvider.DayOverview b = day("2026-09-02", 1, 0, 0, 0, 0, 1, 0, 0, 60, 90, 90, 60, 0);
        DailyStatsProvider.DayOverview c = day("2026-09-03", 1, 0, 0, 0, 0, 1, 0, 0, 50, 70, 70, 55, 0);

        DailyStatsProvider.DayOverview total = DailyStatsProvider.aggregate(asList(a, b, c), "label");

        assertEquals(50, total.minBatteryPct);
        assertEquals(90, total.maxBatteryPct);
        assertEquals(90, total.firstBatteryPct);  // first day WITH data, not day a's -1
        assertEquals(55, total.lastBatteryPct);   // last day's value
    }

    @Test public void avgTempIgnoresDaysWithNoReading() {
        DailyStatsProvider.DayOverview a = day("2026-09-01", 1, 0, 0, 0, 0, 1, 0, 0, -1, -1, -1, -1, 0);   // no reading
        DailyStatsProvider.DayOverview b = day("2026-09-02", 1, 0, 0, 0, 0, 1, 0, 0, -1, -1, -1, -1, 20);
        DailyStatsProvider.DayOverview c = day("2026-09-03", 1, 0, 0, 0, 0, 1, 0, 0, -1, -1, -1, -1, 30);

        DailyStatsProvider.DayOverview total = DailyStatsProvider.aggregate(asList(a, b, c), "label");

        assertEquals(25.0, total.avgTempC, 1e-9);
    }

    @Test public void chargeCostAndMaxAltitudeCarryThroughAsPostConstructionFields() {
        DailyStatsProvider.DayOverview a = day("2026-09-01", 1, 0, 0, 0, 0, 1, 1, 5.0, -1, -1, -1, -1, 0);
        a.chargeCost = 12.5;
        a.maxAltitudeM = 300;
        DailyStatsProvider.DayOverview b = day("2026-09-02", 1, 0, 0, 0, 0, 1, 1, 3.0, -1, -1, -1, -1, 0);
        b.chargeCost = 7.5;
        b.maxAltitudeM = 450;

        DailyStatsProvider.DayOverview total = DailyStatsProvider.aggregate(asList(a, b), "label");

        assertEquals(20.0, total.chargeCost, 1e-9);
        assertEquals(450, total.maxAltitudeM, 1e-9);
    }

    @Test public void weekDatesReturnsSevenConsecutiveDaysStartingSunday() {
        // 2026-09-13 is a Sunday; 2026-09-16 (a Wednesday) falls in that week.
        List<String> week = DailyStatsProvider.weekDates("2026-09-16");
        assertEquals(7, week.size());
        assertEquals("2026-09-13", week.get(0));
        assertEquals("2026-09-19", week.get(6));
    }

    @Test public void monthDatesReturnsEveryDayOfTheCalendarMonth() {
        List<String> month = DailyStatsProvider.monthDates("2026-09-16");
        assertEquals(30, month.size()); // September has 30 days
        assertEquals("2026-09-01", month.get(0));
        assertEquals("2026-09-30", month.get(29));
    }
}
