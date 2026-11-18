package com.geely.drivemem.sensors;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StatsCalendarTest {

    private static LocalDate d(String s) { return LocalDate.parse(s); }

    @Test
    public void fillListsEveryDayAndZeroesTheGap() {
        // The real case: last drive 2026-10-07, back from the shop 11-13.
        Map<String, double[]> rows = new HashMap<>();
        rows.put("2026-10-06", new double[]{6.3, 0});
        rows.put("2026-11-13", new double[]{28.3, 0});
        rows.put("2026-11-14", new double[]{23.4, 1});
        List<StatsCalendar.Cell> cells = StatsCalendar.fill(rows, d("2026-10-06"), d("2026-11-14"));

        assertEquals(40, cells.size());                       // every calendar day
        assertEquals(d("2026-10-06"), cells.get(0).date);
        assertEquals(d("2026-11-14"), cells.get(39).date);
        assertEquals(6.3, cells.get(0).km, 1e-9);
        assertEquals(0, cells.get(1).km, 1e-9);               // a day with no row is a real 0
        assertEquals(1, cells.get(39).charges);
        for (int i = 1; i < cells.size(); i++) {
            assertEquals(cells.get(i - 1).date.plusDays(1), cells.get(i).date);
        }
    }

    @Test
    public void periodIsMondayStartWeekAndCalendarMonth() {
        LocalDate sun = d("2026-11-15");
        LocalDate[] w = StatsCalendar.period(sun, StatsCalendar.Grain.WEEK);
        assertEquals(d("2026-11-09"), w[0]);
        assertEquals(d("2026-11-15"), w[1]);
        LocalDate[] m = StatsCalendar.period(sun, StatsCalendar.Grain.MONTH);
        assertEquals(d("2026-11-01"), m[0]);
        assertEquals(d("2026-11-30"), m[1]);
        LocalDate[] day = StatsCalendar.period(sun, StatsCalendar.Grain.DAY);
        assertEquals(sun, day[0]);
        assertEquals(sun, day[1]);
    }

    @Test
    public void stepMovesOneDayWeekOrMonth() {
        LocalDate x = d("2026-01-31");
        assertEquals(d("2026-02-01"), StatsCalendar.step(x, StatsCalendar.Grain.DAY, 1));
        assertEquals(d("2026-01-24"), StatsCalendar.step(x, StatsCalendar.Grain.WEEK, -1));
        assertEquals(d("2026-02-28"), StatsCalendar.step(x, StatsCalendar.Grain.MONTH, 1));
    }

    @Test
    public void canStepStopsAtTodayAndAtTheFirstRecord() {
        LocalDate min = d("2026-09-09"), today = d("2026-11-17");
        // Day: no step past today, none before the first record.
        assertFalse(StatsCalendar.canStep(today, StatsCalendar.Grain.DAY, 1, min, today));
        assertFalse(StatsCalendar.canStep(min, StatsCalendar.Grain.DAY, -1, min, today));
        // Week of 9-15 Nov -> the current week (16-22) still overlaps today.
        assertTrue(StatsCalendar.canStep(d("2026-11-15"), StatsCalendar.Grain.WEEK, 1, min, today));
        assertFalse(StatsCalendar.canStep(today, StatsCalendar.Grain.WEEK, 1, min, today));
        // Week of the first record: the week before ends 09-06, before the data.
        assertFalse(StatsCalendar.canStep(min, StatsCalendar.Grain.WEEK, -1, min, today));
        // Month: October back to September overlaps (data starts 09-09).
        assertTrue(StatsCalendar.canStep(d("2026-10-15"), StatsCalendar.Grain.MONTH, -1, min, today));
        assertFalse(StatsCalendar.canStep(d("2026-09-15"), StatsCalendar.Grain.MONTH, -1, min, today));
    }

    @Test
    public void levelsAreTheDriversOwnQuintiles() {
        List<StatsCalendar.Cell> cells = new ArrayList<>();
        LocalDate x = d("2026-09-01");
        for (int i = 1; i <= 10; i++) cells.add(new StatsCalendar.Cell(x.plusDays(i), i * 10, 0));
        cells.add(new StatsCalendar.Cell(x, 0, 0));          // idle days do not move the scale
        double[] cuts = StatsCalendar.levelCuts(cells);
        assertEquals(4, cuts.length);
        assertEquals(30, cuts[0], 1e-9);
        assertEquals(90, cuts[3], 1e-9);

        assertEquals(0, StatsCalendar.level(0, cuts));
        assertEquals(1, StatsCalendar.level(10, cuts));
        assertEquals(5, StatsCalendar.level(100, cuts));

        // A heavier driver: the same 50 km day sits lower on their scale.
        List<StatsCalendar.Cell> heavy = new ArrayList<>();
        for (int i = 1; i <= 10; i++) heavy.add(new StatsCalendar.Cell(x.plusDays(i), i * 50, 0));
        assertTrue(StatsCalendar.level(50, StatsCalendar.levelCuts(heavy))
                 < StatsCalendar.level(50, cuts));
    }

    @Test
    public void noDrivingDaysMeansEverySquareIsLevelZero() {
        List<StatsCalendar.Cell> cells = new ArrayList<>();
        cells.add(new StatsCalendar.Cell(d("2026-11-17"), 0, 0));
        double[] cuts = StatsCalendar.levelCuts(cells);
        assertEquals(0, cuts.length);
        assertEquals(0, StatsCalendar.level(0, cuts));
        assertEquals(1, StatsCalendar.level(5, cuts));
    }
}
