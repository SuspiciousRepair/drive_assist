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
    public void lowestIsTheMinimumAndTheLatestOnATie() {
        List<SocHistory.DayRange> days = Arrays.asList(
            d("2026-09-10", 18, 90), d("2026-09-20", 12, 80), d("2026-10-01", 12, 100));
        SocHistory.DayRange low = SocHistory.lowest(days);
        assertEquals(12, low.min);
        assertEquals(LocalDate.parse("2026-10-01"), low.date);
        assertNull(SocHistory.lowest(Collections.emptyList()));
    }
}
