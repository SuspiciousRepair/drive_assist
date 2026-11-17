package com.geely.drivemem.sensors;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class LocationHubTest {

    private static LocationHub.Fix fix(double lat, double lon, double alt) {
        return new LocationHub.Fix(lat, lon, alt, 0, 0, 5);
    }

    // A parked car's fix wobbles by a metre or two: same cell, no republish.
    @Test public void wobbleInsideOneCellIsTheSameFix() {
        assertEquals(fix(30.000010, -40.000010, 700), fix(30.000020, -40.000020, 712));
    }

    @Test public void movingToAnotherCellIsANewFix() {
        assertNotEquals(fix(30.00001, -40.00001, 700), fix(30.00011, -40.00001, 700));
        assertNotEquals(fix(30.00001, -40.00001, 700), fix(30.00001, -40.00011, 700));
    }

    @Test public void arrayKeepsGpsReaderLayout() {
        double[] in = {30.0, -40.0, 700, 90, 10, 4};   // speed 10 m/s
        LocationHub.Fix f = LocationHub.Fix.of(in);
        assertEquals(36.0, f.speedKmh, 1e-9);
        assertArrayEquals(in, f.toArray(), 1e-9);
    }

    // GpsReader gives NaN for a fix with no altitude; the hub value keeps it.
    @Test public void aFixWithoutAltitudeSaysSo() {
        LocationHub.Fix f = LocationHub.Fix.of(new double[]{30.0, -40.0, Double.NaN, 90, 10, 4});
        assertFalse(f.hasAlt());
        assertTrue(fix(30.0, -40.0, 0).hasAlt());
    }

    @Test public void distanceOfOneDegreeOfLatitude() {
        assertEquals(111_195, LocationHub.distanceM(0, 0, 1, 0), 10);
        assertEquals(0, LocationHub.distanceM(30.0, -40.0, 30.0, -40.0), 1e-6);
    }
}
