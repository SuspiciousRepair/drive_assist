package com.geely.drivemem.sensors;

import com.geely.drivemem.state.ChargeSession;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ChargePeriodSummaryTest {

    private static final long DAY = 24L * 3600 * 1000;

    /** A charge starting on day `d` (ms = d * DAY), lasting an hour. */
    private static ChargeSession.Summary ch(long id, int d, int s0, int s1, double kwh, double volts, Double cost) {
        long st = d * DAY;
        return new ChargeSession.Summary(id, st, st + 3600_000, s0, s1, kwh, kwh * 1000, volts, 10, -1, cost, false);
    }

    @Test
    public void splitsAcAndDcCountsEnergyAndCost() {
        List<ChargeSession.Summary> all = Arrays.asList(
            ch(1, 10, 30, 100, 30, 247, 3.0),     // AC, full
            ch(2, 12, 40, 90, 20, 412, 60.0),     // DC
            ch(3, 13, 50, 100, 25, Double.NaN, null)); // no voltage -> AC, no cost
        ChargePeriodSummary m = ChargePeriodSummary.of(all, 0, 20 * DAY, Collections.emptyList());
        assertEquals(3, m.count);
        assertEquals(2, m.acCount);
        assertEquals(1, m.dcCount);
        assertEquals(55, m.acKwh, 1e-9);
        assertEquals(20, m.dcKwh, 1e-9);
        // R$/kWh only over charges that have a cost
        assertEquals(0.10, m.acPerKwh(), 1e-9);
        assertEquals(3.00, m.dcPerKwh(), 1e-9);
        assertEquals(60.0 / 63.0, m.dcCostShare(), 1e-9);
        assertEquals(40, m.avgPlugPct, 1e-9);
        assertEquals(2, m.fullInPeriod);
    }

    @Test
    public void periodCountsOnlyItsChargesButGapsReachBack() {
        List<ChargeSession.Summary> all = Arrays.asList(
            ch(1, 1, 30, 100, 30, 247, 1.0),      // before the period, full
            ch(2, 40, 30, 100, 30, 247, 1.0),     // in it, full: 39-day gap ends here
            ch(3, 45, 60, 80, 10, 247, 1.0));     // in it, not full
        ChargePeriodSummary m = ChargePeriodSummary.of(all, 30 * DAY, 50 * DAY, Collections.emptyList());
        assertEquals(2, m.count);
        assertEquals(1, m.fullInPeriod);
        assertEquals(40 * DAY + 3600_000, m.lastFullMs);
        assertEquals(1 * DAY + 3600_000, m.longestGapFromMs);
        assertEquals(40 * DAY + 3600_000, m.longestGapToMs);
    }

    @Test
    public void anOpenGapRunsToNow() {
        List<ChargeSession.Summary> all = Arrays.asList(
            ch(1, 1, 30, 100, 30, 247, null),
            ch(2, 3, 30, 100, 30, 247, null));
        ChargePeriodSummary m = ChargePeriodSummary.of(all, 0, 30 * DAY, Collections.emptyList());
        assertEquals(3 * DAY + 3600_000, m.longestGapFromMs);
        assertEquals(-1, m.longestGapToMs);   // still open
        assertFalse(m.anyCost);
    }

    @Test
    public void lowsComeFromTheDailyRanges() {
        List<SocHistory.DayRange> days = Arrays.asList(
            new SocHistory.DayRange(LocalDate.parse("2026-09-30"), 18, 100),
            new SocHistory.DayRange(LocalDate.parse("2026-10-01"), 8, 60),
            new SocHistory.DayRange(LocalDate.parse("2026-10-02"), 40, 90));
        ChargePeriodSummary m = ChargePeriodSummary.of(Collections.emptyList(), 0, 0, days);
        assertEquals(8, m.lowest.min);
        assertEquals(1, m.daysBelow10);
        assertEquals(2, m.daysBelow20);
        assertEquals(-1, m.avgPlugPct, 1e-9);
    }
}
