package com.geely.drivemem.state;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RoutePointsTest {
    // 0.0005 deg of latitude is about 55 m; 0.0001 is about 11 m.

    @Test public void firstFixOfATripIsAlwaysKept() {
        assertTrue(RoutePoints.keep(-1, 0, 0, 1_000, -23.5, -46.6));
    }

    @Test public void lessThanFiveSecondsIsSkipped() {
        assertFalse(RoutePoints.keep(10_000, -23.5, -46.6, 14_999, -23.5005, -46.6));
    }

    @Test public void lessThanTwentyFiveMetresIsSkipped() {
        assertFalse(RoutePoints.keep(10_000, -23.5, -46.6, 60_000, -23.5001, -46.6));
    }

    @Test public void fiveSecondsAndTwentyFiveMetresIsKept() {
        assertTrue(RoutePoints.keep(10_000, -23.5, -46.6, 15_000, -23.5005, -46.6));
    }
}
