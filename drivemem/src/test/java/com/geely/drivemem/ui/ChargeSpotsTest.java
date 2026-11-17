package com.geely.drivemem.ui;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Charge spots on the route maps: one per place, DC wins, and a trip shows
 * only the charge right before or after it. */
public class ChargeSpotsTest {
    // About 0.0009 degrees of latitude = 100 m.
    private static final double LAT = -23.5, LON = -46.6;
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
}
