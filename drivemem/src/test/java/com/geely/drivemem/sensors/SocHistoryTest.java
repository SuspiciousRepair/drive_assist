package com.geely.drivemem.sensors;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SocHistoryTest {

    private static SocHistory.DayRange d(String date, int min, int max) {
        return new SocHistory.DayRange(LocalDate.parse(date), min, max);
    }

    @Test
    public void countsOnlyDaysThatWentUnderTheFloor() {
        List<SocHistory.DayRange> days = Arrays.asList(
            d("2026-09-10", 18, 90), d("2026-09-11", 20, 100), d("2026-09-12", 35, 60),
            d("2026-09-13", 9, 70));
        // 20 itself is not "below 20"
        assertEquals(2, SocHistory.daysBelow(days, SocHistory.LOW_PCT));
        assertEquals(0, SocHistory.daysBelow(days, 5));
    }

    @Test
    public void lowestIsTheMinimumAndTheLatestOnATie() {
        List<SocHistory.DayRange> days = Arrays.asList(
            d("2026-09-10", 18, 90), d("2026-09-20", 12, 80), d("2026-10-01", 12, 100));
        SocHistory.DayRange low = SocHistory.lowest(days);
        assertEquals(12, low.min);
        assertEquals(LocalDate.parse("2026-10-01"), low.date);
        assertNull(SocHistory.lowest(Collections.emptyList()));
    }
}
