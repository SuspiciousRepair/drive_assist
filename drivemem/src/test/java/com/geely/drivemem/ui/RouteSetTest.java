package com.geely.drivemem.ui;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class RouteSetTest {
    private static final double N = Double.NaN;

    // 0.01 deg of latitude is about 1.112 km.
    private static RouteSet.Trip trip(double lat0, double... alt) {
        int n = alt.length;
        double[] lat = new double[n], lon = new double[n], speed = new double[n];
        for (int i = 0; i < n; i++) { lat[i] = lat0 + i * 0.01; lon[i] = -40.0; speed[i] = 10 * i; }
        return new RouteSet.Trip(lat, lon, speed, alt);
    }

    @Test public void pointsFollowEachOtherAndStartsMarkEachTrip() {
        RouteSet s = RouteSet.of(Arrays.asList(trip(30.0, 700, 710), trip(-23.0, 800, 810, 820)), 1);
        assertEquals(5, s.lat.length);
        assertArrayEquals(new int[]{0, 2}, s.starts);
        assertEquals(-23.0, s.lat[2], 1e-9);
        assertEquals(20, s.speed[4], 1e-9);
    }

    @Test public void theDistanceRunsOnFromTripToTrip() {
        RouteSet s = RouteSet.of(Arrays.asList(trip(30.0, 700, 710), trip(-23.0, 800, 810, 820)), 1);
        assertEquals(5, s.profKm.length);
        assertEquals(0, s.profKm[0], 1e-9);
        assertEquals(1.112, s.profKm[1], 0.01);
        assertEquals(s.profKm[1], s.profKm[2], 1e-9);   // the next trip starts where the last one ended
        assertEquals(1.112 * 3, s.profKm[4], 0.02);
        assertArrayEquals(new double[]{700, 710, 800, 810, 820}, s.profAlt, 1e-9);
        assertArrayEquals(new int[]{0, 1, 2, 3, 4}, s.point);
    }

    @Test public void aTripWithNoHeightIsLeftOutOfTheProfileOnly() {
        RouteSet s = RouteSet.of(Arrays.asList(trip(30.0, N, N), trip(-23.0, 800, N)), 1);
        assertEquals(4, s.lat.length);                 // still in the picture
        assertArrayEquals(new int[]{2, 3}, s.point);   // the profile points to the second trip
        assertArrayEquals(new double[]{800, 800}, s.profAlt, 1e-9);
        assertEquals(0, s.profKm[0], 1e-9);
    }

    @Test public void noTrips() {
        RouteSet s = RouteSet.of(Arrays.<RouteSet.Trip>asList(), 5);
        assertEquals(0, s.lat.length);
        assertEquals(0, s.starts.length);
        assertEquals(0, s.profKm.length);
    }
}
