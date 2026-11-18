package com.geely.drivemem.sensors;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
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
        assertEquals(20.0, d.batt.min, 1e-9);
        assertEquals(31.0, d.batt.max, 1e-9);
        assertEquals(DAY + 2 * H, d.batt.maxMs);
        assertEquals(3, d.batt.n);
        assertEquals(0, d.fullN);
        assertFalse(d.out.has());
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
        assertEquals(22.0, day.batt.fullMax, 1e-9);
        // the next day sees the same samples but the stay is not its own
        BatteryHeat.Day next = BatteryHeat.computeDay(D.plusDays(1), 2 * DAY, 3 * DAY, ts, pct, t);
        assertEquals(0, next.fullN);
    }

    /** No dongle: the battery has no reading, the outside air is kept the
     * same way, readings in the day and readings while at 100 %. */
    @Test
    public void outsideAirIsKeptLikeTheBattery() {
        long[] ts = {DAY + H, DAY + 2 * H, DAY + 3 * H, DAY + 4 * H, DAY + 5 * H};
        int[] pct = {90, 100, 100, 100, 97};
        List<Double> batt = Arrays.asList(null, null, null, null, null);
        List<Double> out = Arrays.asList(18.0, 24.0, 27.0, 25.0, 20.0);
        BatteryHeat.Day d = BatteryHeat.computeDay(D, DAY, 2 * DAY, ts, pct, batt, out);
        assertFalse(d.batt.has());
        assertEquals(18.0, d.out.min, 1e-9);
        assertEquals(27.0, d.out.max, 1e-9);
        assertEquals(DAY + 3 * H, d.out.maxMs);
        assertEquals(5, d.out.n);
        assertEquals(1, d.fullN);                       // 2:00-4:00 at 100 %
        assertEquals(27.0, d.out.fullMax, 1e-9);
        assertEquals(76.0 / 3, d.out.fullAvg(), 1e-9);  // 24, 27, 25
    }

    /** Hours low, not days: a night parked at 8 % counts whole, cut at
     * midnight; time at exactly 20 % is not "below 20". */
    @Test
    public void lowHoursCountTheTimeBetweenLowSamples() {
        long[] ts = {
            DAY + 20 * H,                 // 25 %
            DAY + 21 * H,                 // 18 %  (20:00-21:00 not low: 25 on one side)
            DAY + 22 * H,                 // 8 %   21-22 under 20, not under 10
            2 * DAY + 6 * H,              // 8 %   parked overnight: 22:00-06:00 under both
            2 * DAY + 7 * H,              // 20 %  charging: 06-07 not under 20 (20 is not below)
        };
        int[] pct = {25, 18, 8, 8, 20};
        List<Double> t = Arrays.asList(null, null, null, null, null);
        BatteryHeat.Day day = BatteryHeat.computeDay(D, DAY, 2 * DAY, ts, pct, t);
        assertEquals(2 * H, day.below10Ms);              // 22:00-24:00
        assertEquals(3 * H, day.below20Ms);              // 21:00-24:00
        BatteryHeat.Day next = BatteryHeat.computeDay(D.plusDays(1), 2 * DAY, 3 * DAY, ts, pct, t);
        assertEquals(6 * H, next.below10Ms);             // 00:00-06:00
        assertEquals(6 * H, next.below20Ms);
        BatteryHeat.Stats s = BatteryHeat.aggregate(Arrays.asList(day, next));
        assertEquals(8 * H, s.below10Ms);
        assertEquals(9 * H, s.below20Ms);
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
        set(a.batt, 18, 28, 1, 230, 10, 21, 42, 2);
        a.fullN = 1; a.fullMs = 10 * H; a.fullLongestMs = 10 * H;
        BatteryHeat.Day b = new BatteryHeat.Day(D.plusDays(40));
        set(b.batt, 21, 32, 2, 270, 10, 30, 58, 2);
        b.fullN = 2; b.fullMs = 4 * H; b.fullLongestMs = 2 * H;
        BatteryHeat.Day none = new BatteryHeat.Day(D.minusDays(5));   // no readings, no stays
        BatteryHeat.Stats s = BatteryHeat.aggregate(Arrays.asList(none, a, b));
        assertEquals(18, s.batt.min, 1e-9);
        assertEquals(32, s.batt.max, 1e-9);
        assertEquals(2, s.batt.maxMs);
        assertEquals(25, s.batt.avg(), 1e-9);
        assertEquals(D, s.batt.firstDay);
        assertEquals(3, s.fullCount);
        assertEquals(14 * H, s.fullTotalMs);
        assertEquals(10 * H, s.fullLongestMs);
        assertEquals(30, s.batt.fullMax, 1e-9);
        assertEquals(25, s.batt.fullAvg(), 1e-9);
        assertTrue(s.batt.has());
    }

    /** The card shows the battery whenever the dongle read it in the period,
     * the outside air only when it never did. */
    @Test
    public void theCardShowsTheBatteryWhenReadElseTheAir() {
        BatteryHeat.Day airOnly = new BatteryHeat.Day(D);
        set(airOnly.out, 15, 30, 3, 220, 10, 0, 0, 0);
        BatteryHeat.Stats noDongle = BatteryHeat.aggregate(Arrays.asList(airOnly));
        assertFalse(noDongle.showsBattery());
        assertSame(noDongle.out, noDongle.shown());
        assertEquals(D, noDongle.out.firstDay);

        BatteryHeat.Day both = new BatteryHeat.Day(D.plusDays(1));
        set(both.batt, 24, 26, 4, 50, 2, 0, 0, 0);
        set(both.out, 20, 22, 5, 42, 2, 0, 0, 0);
        BatteryHeat.Stats someDongle = BatteryHeat.aggregate(Arrays.asList(airOnly, both));
        assertTrue(someDongle.showsBattery());
        assertSame(someDongle.batt, someDongle.shown());
        assertEquals(D.plusDays(1), someDongle.batt.firstDay);

        BatteryHeat.Stats nothing = BatteryHeat.aggregate(Arrays.asList(new BatteryHeat.Day(D)));
        assertTrue(nothing.showsBattery());   // the plain "needs the dongle" card, as before
        assertFalse(nothing.shown().has());
    }

    private static void set(BatteryHeat.Temps t, double min, double max, long maxMs, double sum, int n,
                            double fullMax, double fullSum, int fullN) {
        t.min = min; t.max = max; t.maxMs = maxMs; t.sum = sum; t.n = n;
        if (fullN > 0) t.fullMax = fullMax;
        t.fullSum = fullSum; t.fullN = fullN;
    }
}
