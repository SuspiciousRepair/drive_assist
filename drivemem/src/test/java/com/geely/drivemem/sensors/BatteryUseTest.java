package com.geely.drivemem.sensors;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class BatteryUseTest {

    private static final long H = 3600_000L;
    /** One day, [DAY, DAY + 24 h). */
    private static final long DAY = 1_000 * 24 * H, END = DAY + 24 * H;

    private static BatteryUse.Charge charge(long startMs, long endMs, int from, int to) {
        return new BatteryUse.Charge(startMs, endMs, from, to);
    }

    private static double used(int prevLast, int first, int last, BatteryUse.Charge... charges) {
        List<BatteryUse.Charge> l = Arrays.asList(charges);
        return BatteryUse.usedPct(prevLast, first, last, l, DAY, END);
    }

    @Test
    public void aDayWithNoChargeIsTheDrop() {
        assertEquals(15, used(80, 80, 65), 1e-9);
    }

    @Test
    public void theDayStartsAtTheLastReadingBeforeItSoTheOvernightLossCounts() {
        // 80 % last night, 79 % at the first reading today, 70 % tonight.
        assertEquals(10, used(80, 79, 70), 1e-9);
    }

    @Test
    public void withNoEarlierReadingTheDaysOwnFirstOneIsUsed() {
        assertEquals(9, used(-1, 79, 70), 1e-9);
    }

    @Test
    public void aChargeInsideTheDayAddsWhatItPutIn() {
        // Real day, 2026-11-20: 79 -> 82 with a 56 -> 89 charge = 30 % used.
        assertEquals(30, used(79, 79, 82, charge(DAY + 13 * H, DAY + 14 * H, 56, 89)), 1e-9);
    }

    @Test
    public void severalChargesAddUp() {
        // Real day, 2026-11-19: 74 -> 79 with three charges = 74 % used.
        assertEquals(74, used(74, 74, 79,
            charge(DAY + 13 * H, DAY + 14 * H, 67, 99),
            charge(DAY + 16 * H, DAY + 17 * H, 91, 99),
            charge(DAY + 22 * H, DAY + 23 * H, 57, 96)), 1e-9);
    }

    @Test
    public void aChargeAcrossMidnightGivesEachDayItsShare() {
        // Real night, 2026-11-16 21:04 -> next morning, 26 -> 100.
        BatteryUse.Charge night = charge(DAY + 21 * H, END + 5 * H, 26, 100);
        // Day one: 45 % at its start, 54 % at midnight (28 % already in).
        assertEquals(19, used(45, 45, 54, night), 1e-9);
        // Day two: 54 % at midnight, 88 % at its end, the other 46 % went in.
        double dayTwo = BatteryUse.usedPct(54, 54, 88, Collections.singletonList(night), END, END + 24 * H);
        assertEquals(12, dayTwo, 1e-9);
    }

    @Test
    public void aChargeStillRunningCountsUpToTheLastReading() {
        // No end SoC yet: the day's last reading stands in for it.
        assertEquals(10, used(60, 60, 70, charge(DAY + 20 * H, END + 4 * H, 50, -1)), 1e-9);
    }

    @Test
    public void aChargeOutsideTheDayIsLeftOut() {
        assertEquals(5, used(80, 80, 75, charge(DAY - 5 * H, DAY - 2 * H, 20, 80),
            charge(END + H, END + 2 * H, 30, 90)), 1e-9);
    }

    @Test
    public void aChargeTheAppMissedReadsAsNoUseNotNegativeUse() {
        assertEquals(0, used(40, 40, 90), 1e-9);
    }

    @Test
    public void aDayWithNoReadingIsNotKnown() {
        assertEquals(-1, used(80, -1, -1), 1e-9);
        assertEquals(-1, used(-1, -1, 70), 1e-9);
    }

    @Test
    public void aZeroReadingIsNoiseNotAnEmptyBattery() {
        assertEquals(-1, used(80, 80, 0), 1e-9);
        // A noisy "last before" falls back to the day's own first reading.
        assertEquals(10, used(0, 80, 70), 1e-9);
    }
}
