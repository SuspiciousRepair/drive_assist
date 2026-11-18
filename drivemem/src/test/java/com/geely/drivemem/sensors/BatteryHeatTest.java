package com.geely.drivemem.sensors;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BatteryHeatTest {

    private static final long MIN = 60_000L;

    @Test
    public void temperatureRangeSkipsMissingReadings() {
        long[] ts = {0, MIN, 2 * MIN, 3 * MIN};
        int[] pct = {50, 49, 48, 47};
        List<Double> t = Arrays.asList(null, 24.0, 31.0, 20.0);
        BatteryHeat.Stats s = BatteryHeat.compute(ts, pct, t);
        assertEquals(20.0, s.minTemp, 1e-9);
        assertEquals(31.0, s.maxTemp, 1e-9);
        assertEquals(25.0, s.avgTemp, 1e-9);
        assertEquals(2 * MIN, s.maxTempMs);
        assertEquals(MIN, s.firstMs);
        assertTrue(s.stays.isEmpty());
    }

    @Test
    public void aStayAtFullSpansTheCarBeingOffAndShortOnesDoNotCount() {
        // 100 % for 10 min (too short), then 100 % from 60 to 240 min with
        // the car off in between (no samples), then the SoC drops.
        long[] ts = {0, 10 * MIN, 20 * MIN, 60 * MIN, 70 * MIN, 240 * MIN, 250 * MIN};
        int[] pct = {100, 100, 98, 100, 100, 100, 99};
        List<Double> t = Arrays.asList(26.0, 26.0, 25.0, 28.0, null, 30.0, 29.0);
        BatteryHeat.Stats s = BatteryHeat.compute(ts, pct, t);
        assertEquals(1, s.stays.size());
        assertEquals(180 * MIN, s.stays.get(0).durationMs());
        assertEquals(30.0, s.maxTempAtFull(), 1e-9);
        assertEquals(29.0, s.avgTempAtFull(), 1e-9);
        assertEquals(180 * MIN, s.longestStay().durationMs());
    }

    @Test
    public void aStayStillOpenAtTheEndCounts() {
        long[] ts = {0, 45 * MIN};
        int[] pct = {100, 100};
        BatteryHeat.Stats s = BatteryHeat.compute(ts, pct, Arrays.asList(null, null));
        assertEquals(1, s.stays.size());
        assertTrue(Double.isNaN(s.maxTempAtFull()));
    }
}
