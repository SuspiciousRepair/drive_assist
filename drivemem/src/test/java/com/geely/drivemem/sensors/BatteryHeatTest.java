package com.geely.drivemem.sensors;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BatteryHeatTest {

    private static final long MIN = 60_000L, H = 60 * MIN, DAY = 24 * H;
    private static final LocalDate D = LocalDate.parse("2026-10-04");

    @Test
    public void readingsCountOnlyInsideTheDay() {
        // day = [DAY, 2 DAY); one reading before it, one after
        long[] ts = {DAY - H, DAY + H, DAY + 2 * H, DAY + 3 * H, 2 * DAY + H};
        int[] pct = {50, 49, 48, 47, 46};
        List<Double> t = Arrays.asList(40.0, 24.0, 31.0, 20.0, 45.0);
        BatteryHeat.Day d = BatteryHeat.computeDay(D, DAY, 2 * DAY, ts, pct, t);
        assertEquals(20.0, d.tMin, 1e-9);
        assertEquals(31.0, d.tMax, 1e-9);
        assertEquals(DAY + 2 * H, d.tMaxMs);
        assertEquals(3, d.tN);
        assertEquals(0, d.fullN);
    }

    @Test
    public void aStayBelongsWholeToTheDayItStarted() {
        // starts 20:00 on the day, the car is off overnight, ends 07:00 next day
        long[] ts = {DAY + 20 * H, DAY + 20 * H + 10 * MIN, 2 * DAY + 7 * H, 2 * DAY + 7 * H + 5 * MIN};
        int[] pct = {100, 100, 100, 99};
        List<Double> t = Arrays.asList(21.0, 22.0, null, 20.0);
        BatteryHeat.Day day = BatteryHeat.computeDay(D, DAY, 2 * DAY, ts, pct, t);
        assertEquals(1, day.fullN);
        assertEquals(11 * H, day.fullMs);
        assertEquals(22.0, day.fullTMax, 1e-9);
        // the next day sees the same samples but the stay is not its own
        BatteryHeat.Day next = BatteryHeat.computeDay(D.plusDays(1), 2 * DAY, 3 * DAY, ts, pct, t);
        assertEquals(0, next.fullN);
    }

    @Test
    public void shortStaysDoNotCount() {
        long[] ts = {DAY + H, DAY + H + 10 * MIN, DAY + H + 20 * MIN};
        int[] pct = {100, 100, 98};
        BatteryHeat.Day d = BatteryHeat.computeDay(D, DAY, 2 * DAY, ts, pct, Arrays.asList(null, null, null));
        assertEquals(0, d.fullN);
    }

    @Test
    public void aggregateAddsDaysUp() {
        BatteryHeat.Day a = new BatteryHeat.Day(D);
        a.tMin = 18; a.tMax = 28; a.tMaxMs = 1; a.tSum = 230; a.tN = 10;
        a.fullN = 1; a.fullMs = 10 * H; a.fullLongestMs = 10 * H; a.fullTMax = 21; a.fullTSum = 42; a.fullTN = 2;
        BatteryHeat.Day b = new BatteryHeat.Day(D.plusDays(40));
        b.tMin = 21; b.tMax = 32; b.tMaxMs = 2; b.tSum = 270; b.tN = 10;
        b.fullN = 2; b.fullMs = 4 * H; b.fullLongestMs = 2 * H; b.fullTMax = 30; b.fullTSum = 58; b.fullTN = 2;
        BatteryHeat.Day none = new BatteryHeat.Day(D.minusDays(5));   // no readings, no stays
        BatteryHeat.Stats s = BatteryHeat.aggregate(Arrays.asList(none, a, b));
        assertEquals(18, s.minTemp, 1e-9);
        assertEquals(32, s.maxTemp, 1e-9);
        assertEquals(2, s.maxTempMs);
        assertEquals(25, s.avgTemp, 1e-9);
        assertEquals(D, s.firstTempDay);
        assertEquals(3, s.fullCount);
        assertEquals(14 * H, s.fullTotalMs);
        assertEquals(10 * H, s.fullLongestMs);
        assertEquals(30, s.fullTMax, 1e-9);
        assertEquals(25, s.fullTAvg, 1e-9);
        assertTrue(s.hasTemp());
    }
}
