package com.geely.drivemem.sensors;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class LocationHubTest {

    private static LocationHub.Fix fix(double lat, double lon, double alt) {
        return new LocationHub.Fix(lat, lon, alt, 0, 0, 5);
    }

    // A parked car's fix wobbles by a metre or two: same cell, no republish.
    @Test public void wobbleInsideOneCellIsTheSameFix() {
        assertEquals(fix(-23.500010, -46.600010, 700), fix(-23.500020, -46.600020, 712));
    }

    @Test public void movingToAnotherCellIsANewFix() {
        assertNotEquals(fix(-23.50001, -46.60001, 700), fix(-23.50011, -46.60001, 700));
        assertNotEquals(fix(-23.50001, -46.60001, 700), fix(-23.50001, -46.60011, 700));
    }

    @Test public void arrayKeepsGpsReaderLayout() {
        double[] in = {-23.5, -46.6, 700, 90, 10, 4};   // speed 10 m/s
        LocationHub.Fix f = LocationHub.Fix.of(in);
        assertEquals(36.0, f.speedKmh, 1e-9);
        assertArrayEquals(in, f.toArray(), 1e-9);
    }

    @Test public void distanceOfOneDegreeOfLatitude() {
        assertEquals(111_195, LocationHub.distanceM(0, 0, 1, 0), 10);
        assertEquals(0, LocationHub.distanceM(-23.5, -46.6, -23.5, -46.6), 1e-6);
    }
}
