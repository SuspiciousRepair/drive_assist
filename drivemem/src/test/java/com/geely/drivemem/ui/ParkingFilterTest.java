package com.geely.drivemem.ui;

import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.state.ParkingCost;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** The stats page's Parking filter: which stops it lists, in which order. */
public class ParkingFilterTest {
    private static final long MIN = 60_000L, H = 60 * MIN;

    private static DailyStatsProvider.ValetSessionItem valet(long id, long start, long end) {
        return new DailyStatsProvider.ValetSessionItem(id, start, end, 1.0, 20, null, 50, 49);
    }

    @Test public void longGapsAndValetStaysNewestFirst() {
        DailyStatsProvider.ValetSessionItem a = valet(1, 10 * H, 11 * H);
        DailyStatsProvider.ValetSessionItem b = valet(2, 12 * H, 12 * H + 5 * MIN);
        DailyStatsProvider.ValetSessionItem c = valet(3, 12 * H + 10 * MIN, 13 * H);
        // Given out of order: the list sorts them.
        List<Object> stops = DailyStatsView.parkingStops(Arrays.asList(c, a, b), Collections.emptyList());
        // The 1 h gap (11:00-12:00) counts; the 5 min one (12:05-12:10) does not.
        assertEquals(4, stops.size());
        assertSame(c, stops.get(0));
        assertSame(b, stops.get(1));
        assertArrayEquals(new long[]{11 * H, 12 * H}, (long[]) stops.get(2));
        assertSame(a, stops.get(3));
    }

    @Test public void aShortGapThatWasPaidIsListed() {
        DailyStatsProvider.ValetSessionItem a = valet(1, 10 * H, 11 * H);
        DailyStatsProvider.ValetSessionItem b = valet(2, 11 * H + 5 * MIN, 12 * H);
        ParkingCost.Row paid = new ParkingCost.Row(9, 11 * H, 11 * H + 5 * MIN, null, 6, null, "street", null, null);
        List<Object> stops = DailyStatsView.parkingStops(Arrays.asList(a, b), Collections.singletonList(paid));
        assertEquals(3, stops.size());
        assertArrayEquals(new long[]{11 * H, 11 * H + 5 * MIN}, (long[]) stops.get(1));
    }

    @Test public void noSessionsNoStops() {
        assertEquals(0, DailyStatsView.parkingStops(Collections.emptyList(), null).size());
    }
}
