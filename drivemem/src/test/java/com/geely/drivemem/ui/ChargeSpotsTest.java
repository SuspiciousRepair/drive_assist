package com.geely.drivemem.ui;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Charge spots on the route maps: one per place, DC wins, and a trip shows
 * only the charge right before or after it. */
public class ChargeSpotsTest {
    // About 0.0009 degrees of latitude = 100 m.
    // Neutral place (middle of the ocean): no real location in the repo.
    private static final double LAT = 30.0, LON = -40.0;
    private static final long H = 60 * 60_000L;

    private static ChargeSpots.Charge ch(double dLatM, long start, long end, boolean dc) {
        return new ChargeSpots.Charge(LAT + dLatM / 111_195.0, LON, start, end, dc);
    }

    @Test public void distanceIsAboutRight() {
        assertEquals(100, ChargeSpots.distanceM(LAT, LON, LAT + 100 / 111_195.0, LON), 0.5);
    }

    @Test public void chargesAtTheSamePlaceAreOneSpotAndDcWins() {
        List<ChargeSpots.Spot> s = ChargeSpots.cluster(Arrays.asList(
            ch(0, 0, 1, false), ch(40, 2, 3, true), ch(5000, 4, 5, false)));
        assertEquals(2, s.size());
        assertTrue("home had one DC charge", s.get(0).dc);
        assertFalse(s.get(1).dc);
    }

    @Test public void aTripShowsOnlyTheChargeRightBeforeOrAfterIt() {
        long tripStart = 10 * H, tripEnd = 11 * H;
        double far = 20_000;   // the trip ends 20 km away
        List<ChargeSpots.Charge> all = Arrays.asList(
            ch(0, 8 * H, tripStart - 10 * 60_000L, false),      // home, just before: yes
            ch(0, 2 * H, 3 * H, false),                         // home, hours before: no
            ch(far, tripEnd + 5 * 60_000L, tripEnd + H / 2, true), // at the end, just after: yes
            ch(far + 2_000, tripEnd + 60_000L, tripEnd + H, true)); // 2 km from the end: no
        List<ChargeSpots.Spot> s = ChargeSpots.forTrip(all, tripStart, tripEnd,
            LAT, LON, LAT + far / 111_195.0, LON);
        assertEquals(2, s.size());
        assertFalse(s.get(0).dc);
        assertTrue(s.get(1).dc);
    }

    @Test public void aTripStillDrivingHasNoAfterCharge() {
        List<ChargeSpots.Spot> s = ChargeSpots.forTrip(
            Arrays.asList(ch(0, 11 * H, 12 * H, false)), 10 * H, 0, LAT, LON, LAT, LON);
        assertTrue(s.isEmpty());
    }

    @Test public void oneDayKeepsOnlyTheSpotsNearItsRoutes() {
        double m = 1 / 111_195.0;   // one metre of latitude
        double[] lat = {LAT, LAT + 3000 * m}, lon = {LON, LON};
        ChargeSpots.Spot near = new ChargeSpots.Spot(LAT + 3500 * m, LON, false);   // 500 m past the end
        ChargeSpots.Spot far = new ChargeSpots.Spot(LAT + 400_000 * m, LON, true);  // 400 km away
        List<ChargeSpots.Spot> kept = ChargeSpots.inBox(Arrays.asList(near, far), lat, lon, 1000);
        assertEquals(1, kept.size());
        assertSame(near, kept.get(0));
        assertTrue(ChargeSpots.inBox(Arrays.asList(near), new double[0], new double[0], 1000).isEmpty());
    }

    private static ChargeSpots.Placed at(long id, double dLatM, boolean dc) {
        return new ChargeSpots.Placed(id, LAT + dLatM / 111_195.0, LON, dc);
    }

    // The charging page's map: charges at one place are one pin with their
    // count and ids; DC wins; a charge 5 km away is its own place.
    @Test public void placesCountTheirChargesAndKeepTheirIds() {
        List<ChargeSpots.Place> p = ChargeSpots.places(Arrays.asList(
            at(1, 0, false), at(2, 5000, true), at(3, 60, false), at(4, 30, true)));
        assertEquals(2, p.size());
        assertEquals(3, p.get(0).count());
        assertEquals(Arrays.asList(1L, 3L, 4L), p.get(0).ids);
        assertTrue(p.get(0).dc);
        assertEquals(1, p.get(1).count());
        assertEquals(1, ChargeSpots.placeOf(p, 2));
        assertEquals(0, ChargeSpots.placeOf(p, 4));
        assertEquals(-1, ChargeSpots.placeOf(p, 99));
        assertTrue(ChargeSpots.places(java.util.Collections.emptyList()).isEmpty());
    }
}
