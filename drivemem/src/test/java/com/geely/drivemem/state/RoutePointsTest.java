package com.geely.drivemem.state;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RoutePointsTest {
    // 0.0005 deg of latitude is about 55 m; 0.0001 is about 11 m.

    @Test public void firstFixOfATripIsAlwaysKept() {
        assertTrue(RoutePoints.keep(-1, 0, 0, 1_000, 30.0, -40.0));
    }

    @Test public void lessThanFiveSecondsIsSkipped() {
        assertFalse(RoutePoints.keep(10_000, 30.0, -40.0, 14_999, 30.0005, -40.0));
    }

    @Test public void lessThanTwentyFiveMetresIsSkipped() {
        assertFalse(RoutePoints.keep(10_000, 30.0, -40.0, 60_000, 30.0001, -40.0));
    }

    @Test public void fiveSecondsAndTwentyFiveMetresIsKept() {
        assertTrue(RoutePoints.keep(10_000, 30.0, -40.0, 15_000, 30.0005, -40.0));
    }
}
