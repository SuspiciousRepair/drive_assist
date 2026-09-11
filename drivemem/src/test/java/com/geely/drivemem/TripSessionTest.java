package com.geely.drivemem;

import com.geely.drivemem.state.TripSession;
import com.geely.drivemem.util.Modes;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class TripSessionTest {

    @Before
    public void setUp() {
        TripSession.resetForTesting();
    }

    @Test
    public void testQualificationFilterBoundaries() {
        // Distance threshold: MIN_TRIP_DISTANCE_KM = 0.1 km (100 meters)
        // Duration threshold: MIN_TRIP_DRIVE_DURATION_MS = 45_000 ms (45 seconds)

        // Neither threshold met: micro-trip discarded
        assertFalse(TripSession.isQualified(0.0, 0L));
        assertFalse(TripSession.isQualified(0.05, 20_000L));
        assertFalse(TripSession.isQualified(0.099, 44_999L));
        assertFalse(TripSession.isQualified(-1.0, 30_000L));

        // Distance threshold met alone (e.g. short, fast drive)
        assertTrue(TripSession.isQualified(0.1, 0L));
        assertTrue(TripSession.isQualified(0.1, 10_000L));
        assertTrue(TripSession.isQualified(0.5, 30_000L));
        assertTrue(TripSession.isQualified(12.5, 5_000L));

        // Duration threshold met alone (e.g. slow crawling/traffic)
        assertTrue(TripSession.isQualified(0.0, 45_000L));
        assertTrue(TripSession.isQualified(0.05, 45_000L));
        assertTrue(TripSession.isQualified(0.02, 60_000L));
        assertTrue(TripSession.isQualified(0.0, 120_000L));

        // Both thresholds met
        assertTrue(TripSession.isQualified(0.1, 45_000L));
        assertTrue(TripSession.isQualified(1.5, 180_000L));
    }

    @Test
    public void testDrivingDurationAccumulationAcrossSegments() {
        long t0 = 100_000L;
        // Shift Park -> Drive: initial trip start
        TripSession.onGear(null, Modes.DRIVE_COMFORT, t0);
        assertTrue(TripSession.isTripActive());
        assertEquals(t0, TripSession.getActiveTripStartMs());
        assertFalse(TripSession.isParkGraceScheduled());

        // Drive for 25 seconds
        long t1 = t0 + 25_000L;
        assertEquals(25_000L, TripSession.getDrivingDurationMs(t1));

        // Shift Drive -> Park: enters 75s debounce grace period
        TripSession.onGear(null, Modes.GEAR_PARK_ADAPTED, t1);
        assertTrue("Trip must remain active during park grace period", TripSession.isTripActive());
        assertTrue("Park grace timer must be scheduled", TripSession.isParkGraceScheduled());
        assertEquals(25_000L, TripSession.getDrivingDurationMs());

        // 15 seconds spent in Park (at gate / driveway)
        long t2 = t1 + 15_000L;
        // Driving duration must NOT accumulate while parked
        assertEquals(25_000L, TripSession.getDrivingDurationMs(t2));

        // Shift Park -> Drive within grace period (e.g. gate opened)
        TripSession.onGear(null, Modes.DRIVE_SPORT, t2);
        assertTrue(TripSession.isTripActive());
        assertFalse("Grace timer must be cancelled when shifting back to Drive", TripSession.isParkGraceScheduled());
        assertEquals("Trip start time must be preserved when stitching segments", t0, TripSession.getActiveTripStartMs());

        // Drive for another 25 seconds
        long t3 = t2 + 25_000L;
        assertEquals(50_000L, TripSession.getDrivingDurationMs(t3));

        // Shift Drive -> Park again
        TripSession.onGear(null, Modes.GEAR_PARK_ADAPTED, t3);
        assertTrue(TripSession.isTripActive());
        assertTrue(TripSession.isParkGraceScheduled());
        // Total driving duration = 25s + 25s = 50s
        assertEquals(50_000L, TripSession.getDrivingDurationMs());

        // Park grace period expires after 75s (t3 + 75_000)
        long t4 = t3 + TripSession.PARK_GRACE_PERIOD_MS;
        boolean qualified = TripSession.finalizeTrip(null, t4);
        assertTrue("50s cumulative driving duration must qualify trip", qualified);
        assertFalse("Trip must be inactive after finalization", TripSession.isTripActive());
        assertFalse(TripSession.isParkGraceScheduled());
    }

    @Test
    public void testMicroTripDiscardedBelowThresholds() {
        long t0 = 50_000L;
        // Park -> Drive
        TripSession.onGear(null, Modes.DRIVE_ECO, t0);
        assertTrue(TripSession.isTripActive());

        // Move vehicle for only 8 seconds (e.g. reparking 5 meters)
        long t1 = t0 + 8_000L;
        TripSession.onGear(null, Modes.GEAR_PARK_ADAPTED, t1);
        assertTrue(TripSession.isTripActive());
        assertTrue(TripSession.isParkGraceScheduled());
        assertEquals(8_000L, TripSession.getDrivingDurationMs());

        // Simulate odometer showing 0 distance
        TripSession.setTestCurrentOdoKm(1234.0);
        TripSession.setTripStateForTesting(true, t0, 1234.0, 8_000L, 1L);

        // 75s grace period expires
        boolean qualified = TripSession.finalizeTrip(null, t1 + TripSession.PARK_GRACE_PERIOD_MS);
        assertFalse("8s driving and 0 distance must be discarded", qualified);
        assertFalse("Trip must be reset and inactive", TripSession.isTripActive());
        assertEquals(0L, TripSession.getActiveTripStartMs());
    }

    @Test
    public void testShortDriveQualifiesByDistance() {
        long t0 = 10_000L;
        TripSession.onGear(null, Modes.DRIVE_COMFORT, t0);

        // Drive for only 20 seconds, but 300 meters (0.3 km)
        long t1 = t0 + 20_000L;
        TripSession.onGear(null, Modes.GEAR_PARK_ADAPTED, t1);
        assertEquals(20_000L, TripSession.getDrivingDurationMs());

        TripSession.setTestCurrentOdoKm(500.3);
        TripSession.setTripStateForTesting(true, t0, 500.0, 20_000L, 1L);

        boolean qualified = TripSession.finalizeTrip(null, t1 + TripSession.PARK_GRACE_PERIOD_MS);
        assertTrue("0.3 km drive must qualify even if duration < 45s", qualified);
        assertFalse(TripSession.isTripActive());
    }

    @Test
    public void testManeuveringGearTogglesDoNotTriggerParkEdge() {
        long t0 = 1_000L;
        // Park -> Drive
        TripSession.onGear(null, Modes.DRIVE_COMFORT, t0);
        assertTrue(TripSession.isTripActive());

        // Shift D -> R (gear 3 or anything != Modes.GEAR_PARK_ADAPTED)
        int reverseGear = 3;
        TripSession.onGear(null, reverseGear, t0 + 5_000L);
        assertFalse("D -> R is not a park edge; grace timer must not be scheduled",
            TripSession.isParkGraceScheduled());
        assertTrue(TripSession.isTripActive());

        // Shift R -> D
        TripSession.onGear(null, Modes.DRIVE_SPORT, t0 + 10_000L);
        assertFalse(TripSession.isParkGraceScheduled());
        assertTrue(TripSession.isTripActive());
        assertEquals(10_000L, TripSession.getDrivingDurationMs(t0 + 10_000L));
    }

    @Test
    public void testNegativeClockJumpClampedToZero() {
        long t0 = 100_000L;
        TripSession.onGear(null, Modes.DRIVE_COMFORT, t0);

        // Clock goes backwards (e.g. NTP sync)
        long tEarlier = t0 - 5_000L;
        TripSession.onGear(null, Modes.GEAR_PARK_ADAPTED, tEarlier);
        assertEquals("Negative interval must clamp to 0", 0L, TripSession.getDrivingDurationMs());
    }

    @Test
    public void testDuplicateParkEventsIgnored() {
        TripSession.onGear(null, Modes.GEAR_PARK_ADAPTED, 1000L);
        assertFalse(TripSession.isTripActive());
        assertFalse(TripSession.isParkGraceScheduled());

        TripSession.onGear(null, Modes.GEAR_PARK_ADAPTED, 2000L);
        assertFalse(TripSession.isTripActive());
        assertFalse(TripSession.isParkGraceScheduled());
    }
}
