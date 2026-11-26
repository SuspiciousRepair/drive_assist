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

    @Test public void shortMovesToFindTheChargerStillCount() {
        long tripStart = 10 * H, tripEnd = 11 * H;
        double far = 20_000;
        // The trip ends 20 km away; two short moves (0.6 + 0.7 km), then a
        // charge 1.1 km from the trip's end: the moves widen the reach.
        List<ChargeSpots.Charge> all = Arrays.asList(
            ch(far + 1_100, tripEnd + 20 * 60_000L, tripEnd + 2 * H, true));
        List<ChargeSpots.Hop> hops = Arrays.asList(
            new ChargeSpots.Hop(tripEnd + 5 * 60_000L, 0.6),
            new ChargeSpots.Hop(tripEnd + 12 * 60_000L, 0.7));
        assertEquals(1, ChargeSpots.forTrip(all, tripStart, tripEnd,
            LAT, LON, LAT + far / 111_195.0, LON, hops).size());
        // Without the moves, 1.1 km is too far.
        assertTrue(ChargeSpots.forTrip(all, tripStart, tripEnd,
            LAT, LON, LAT + far / 111_195.0, LON).isEmpty());
    }

    @Test public void aRealTripInBetweenTakesTheCharge() {
        long tripStart = 10 * H, tripEnd = 11 * H;
        // Home, a round trip of 12 km that ends at home, then a charge at
        // home: the charge is the round trip's, not this trip's.
        List<ChargeSpots.Charge> all = Arrays.asList(ch(0, tripEnd + 50 * 60_000L, tripEnd + 3 * H, false));
        List<ChargeSpots.Hop> hops = Arrays.asList(new ChargeSpots.Hop(tripEnd + 10 * 60_000L, 12));
        assertTrue(ChargeSpots.forTrip(all, tripStart, tripEnd, LAT, LON, LAT, LON, hops).isEmpty());
        // Same before the trip: a real trip between the charge and this one.
        List<ChargeSpots.Charge> before = Arrays.asList(ch(0, 6 * H, tripStart - 50 * 60_000L, false));
        List<ChargeSpots.Hop> hops2 = Arrays.asList(new ChargeSpots.Hop(tripStart - 40 * 60_000L, 8));
        assertTrue(ChargeSpots.forTrip(before, tripStart, tripEnd, LAT, LON, LAT, LON, hops2).isEmpty());
    }

    @Test public void aMoveWithNoDistanceCountsAsATrip() {
        List<ChargeSpots.Hop> hops = Arrays.asList(new ChargeSpots.Hop(5, Double.NaN));
        assertEquals(-1, ChargeSpots.reachM(hops, 0, 10), 0);
        assertEquals(ChargeSpots.NEAR_TRIP_M, ChargeSpots.reachM(hops, 6, 10), 0);   // outside the window
    }

    @Test public void aTripStillDrivingHasNoAfterCharge() {
        List<ChargeSpots.Spot> s = ChargeSpots.forTrip(
            Arrays.asList(ch(0, 11 * H, 12 * H, false)), 10 * H, 0, LAT, LON, LAT, LON);
        assertTrue(s.isEmpty());
    }

    // A period's map: only the charges that started in it (today's map
    // must not show, and zoom out to, last weekend's charge at the coast).
    @Test public void aPeriodKeepsOnlyItsOwnCharges() {
        ChargeSpots.Charge before = ch(0, 1 * H, 2 * H, false);
        ChargeSpots.Charge inside = ch(0, 10 * H, 11 * H, true);
        ChargeSpots.Charge atEnd = ch(0, 24 * H, 25 * H, false);
        List<ChargeSpots.Charge> kept = ChargeSpots.between(Arrays.asList(before, inside, atEnd), 5 * H, 24 * H);
        assertEquals(1, kept.size());
        assertSame(inside, kept.get(0));
        assertTrue(ChargeSpots.between(null, 0, 1).isEmpty());
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
