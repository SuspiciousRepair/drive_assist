package com.geely.drivemem.state;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** ParkingCost's matching: gaps, Valet stays, parking on arrival, and the
 * place memory (spots that grow by 30 m per stop, street prices by zone). */
public class ParkingCostTest {
    // Neutral place (middle of the ocean): no real location in the repo.
    private static final double LAT = 30.0, LON = -40.0;
    /** Metres per degree of latitude (the haversine Earth radius). */
    private static final double M_PER_DEG = 6371000.0 * Math.PI / 180.0;
    private static final long MIN = 60_000L, H = 60 * MIN;

    private long nextId = 1;

    /** A stop metres north of the base point. */
    private ParkingCost.Row at(double northM, String name, String kind, double cost, long startMs) {
        return new ParkingCost.Row(nextId++, startMs, startMs + H, null, cost, name, kind,
            LAT + northM / M_PER_DEG, LON);
    }

    private static double lat(double northM) { return LAT + northM / M_PER_DEG; }

    private ParkingCost.Row gapRow(long startMs, Long valetId) {
        return new ParkingCost.Row(nextId++, startMs, startMs + H, valetId, 10, null, null, null, null);
    }

    // ---- gaps, Valet, arrival ----

    @Test public void gapMatchesARowStartingInsideOrJustBeforeIt() {
        long gs = 10 * H, ge = 12 * H;
        ParkingCost.Row inside = gapRow(gs + 5 * MIN, null);
        assertEquals(inside, ParkingCost.matchGap(Collections.singletonList(inside), gs, ge));
        ParkingCost.Row early = gapRow(gs - 30_000L, null);
        assertEquals(early, ParkingCost.matchGap(Collections.singletonList(early), gs, ge));
        // Typed on the home card at the shift to P: the trip row closes 75 s later.
        ParkingCost.Row atShiftToP = gapRow(gs - 75_000L, null);
        assertEquals(atShiftToP, ParkingCost.matchGap(Collections.singletonList(atShiftToP), gs, ge));
        ParkingCost.Row tooEarly = gapRow(gs - 3 * MIN, null);
        assertNull(ParkingCost.matchGap(Collections.singletonList(tooEarly), gs, ge));
        ParkingCost.Row after = gapRow(ge + MIN, null);
        assertNull(ParkingCost.matchGap(Collections.singletonList(after), gs, ge));
    }

    @Test public void gapNeverTakesAValetRow() {
        long gs = 10 * H, ge = 12 * H;
        ParkingCost.Row valet = gapRow(gs, 7L);
        assertNull(ParkingCost.matchGap(Collections.singletonList(valet), gs, ge));
    }

    @Test public void valetLookupFindsItsOwnRowOnly() {
        ParkingCost.Row a = gapRow(H, 3L), b = gapRow(2 * H, 4L), plain = gapRow(3 * H, null);
        List<ParkingCost.Row> rows = Arrays.asList(a, b, plain);
        assertEquals(b, ParkingCost.matchValet(rows, 4));
        assertNull(ParkingCost.matchValet(rows, 9));
        assertNull(ParkingCost.matchValet(rows, 0));
    }

    @Test public void arrivalWindowStopsAtTheNextTripOrCharge() {
        long end = 10 * H;
        assertEquals(end + ParkingCost.ARRIVAL_WINDOW_MS, ParkingCost.arrivalLimitMs(end, 0, 0));
        assertEquals(end + 5 * MIN, ParkingCost.arrivalLimitMs(end, end + 5 * MIN, end + 20 * MIN));
        assertEquals(end + 3 * MIN, ParkingCost.arrivalLimitMs(end, end + 2 * H, end + 3 * MIN));
    }

    @Test public void parkingOnArrivalNeverLeaksFromALaterStop() {
        long end = 10 * H;
        ParkingCost.Row now = gapRow(end, null);
        ParkingCost.Row later = gapRow(end + 2 * H, null);
        long limit = ParkingCost.arrivalLimitMs(end, end + H, 0);
        assertEquals(now, ParkingCost.matchAfterTrip(Arrays.asList(now, later), end, limit));
        assertNull(ParkingCost.matchAfterTrip(Collections.singletonList(later), end, limit));
        // A charge right after the trip closes the window before the stop.
        long chargeFirst = ParkingCost.arrivalLimitMs(end, 0, end + MIN);
        assertNull(ParkingCost.matchAfterTrip(Collections.singletonList(gapRow(end + 10 * MIN, null)),
            end, chargeFirst));
    }

    @Test public void sumCountsOnlyPricedStopsInTheWindow() {
        List<ParkingCost.Row> rows = Arrays.asList(gapRow(H, null), gapRow(5 * H, 2L), gapRow(9 * H, null));
        assertEquals(20.0, ParkingCost.sum(rows, 0, 6 * H), 1e-9);
    }

    // ---- place memory ----

    @Test public void aStop25mFromOneEarlierStopJoinsItsSpot() {
        List<ParkingCost.Row> h = Collections.singletonList(at(0, "Shopping Iguatemi", "lot", 25, H));
        ParkingCost.Suggestion s = ParkingCost.suggest(h, lat(25), LON, null, -1);
        assertEquals("Shopping Iguatemi", s.placeName);
        assertEquals(Collections.singletonList(25.0), s.prices);
    }

    @Test public void aStop45mFromEveryStopMatchesNothing() {
        List<ParkingCost.Row> h = Arrays.asList(at(0, "Mall", "lot", 25, H), at(-10, "Mall", "lot", 20, 2 * H));
        ParkingCost.Suggestion s = ParkingCost.suggest(h, lat(45), LON, null, -1);
        assertNull(s.placeName);
        assertTrue(s.prices.isEmpty());
    }

    @Test public void aSpotGrownAlongA100mCarParkMatchesAnywhereOnIt() {
        // Visits at both ends and two in between: each adds its own 30 m.
        List<ParkingCost.Row> h = Arrays.asList(at(0, "Garage", "lot", 18, H), at(100, "Garage", "lot", 18, 2 * H),
            at(40, "Garage", "lot", 20, 3 * H), at(70, "Garage", "lot", 22, 4 * H));
        for (double m = 0; m <= 100; m += 5) {
            assertEquals("at " + m + " m", "Garage", ParkingCost.suggest(h, lat(m), LON, null, -1).placeName);
        }
        // Newest first, distinct.
        assertEquals(Arrays.asList(22.0, 20.0),
            ParkingCost.suggest(h, lat(50), LON, null, -1).prices);
        assertNull(ParkingCost.suggest(h, lat(135), LON, null, -1).placeName);
    }

    @Test public void aRenamedStopNeverJoinsTheOldNamesSpot() {
        // Owner renamed the second stop: two spots, never merged.
        List<ParkingCost.Row> h = Arrays.asList(at(0, "Mall X", "lot", 15, H), at(5, "Mall Y", "lot", 30, 2 * H));
        assertEquals(2, ParkingCost.spots(h, "lot").size());
        assertEquals(1, ParkingCost.spots(h, "lot").get("mall x").size());
        // Nearest stop wins: 2 m from Y's stop, 7 m from X's.
        ParkingCost.Suggestion s = ParkingCost.suggest(h, lat(7), LON, null, -1);
        assertEquals("Mall Y", s.placeName);
        assertEquals(Collections.singletonList(30.0), s.prices);
        // The same name in another case is still the same spot.
        List<ParkingCost.Row> h2 = new ArrayList<>(h);
        h2.add(at(60, "mall x", "lot", 16, 3 * H));
        assertEquals(2, ParkingCost.spots(h2, "lot").get("mall x").size());
    }

    @Test public void aStreetStopNeverMatchesACarParkSpot10mAway() {
        List<ParkingCost.Row> h = Collections.singletonList(at(0, "Shopping", "lot", 25, H));
        ParkingCost.Suggestion s = ParkingCost.suggest(h, lat(10), LON, "street", -1);
        assertEquals("street", s.kind);
        assertNull(s.placeName);
        assertTrue(s.prices.isEmpty());
        // And the car park default stays a car park.
        assertEquals("lot", ParkingCost.suggest(h, lat(10), LON, null, -1).kind);
    }

    @Test public void aStreetStopTakesThePriceOfAnotherStreetStop250mAway() {
        List<ParkingCost.Row> h = Arrays.asList(at(0, null, "street", 6.5, H), at(600, null, "street", 9, 2 * H));
        ParkingCost.Suggestion s = ParkingCost.suggest(h, lat(250), LON, "street", -1);
        assertEquals(Collections.singletonList(6.5), s.prices);
        assertNull(s.placeName);
    }

    @Test public void theNearestStopsKindIsTheDefault() {
        List<ParkingCost.Row> h = Arrays.asList(at(0, null, "street", 6, H), at(100, "Garage", "lot", 20, 2 * H));
        assertEquals("street", ParkingCost.suggest(h, lat(10), LON, null, -1).kind);
        assertEquals("lot", ParkingCost.suggest(h, lat(95), LON, null, -1).kind);
        assertEquals("lot", ParkingCost.suggest(h, lat(50), LON, null, -1).kind);
    }

    @Test public void anUnnamedCarParkStopGivesOnlyPrices() {
        List<ParkingCost.Row> h = Collections.singletonList(at(0, null, "lot", 12, H));
        ParkingCost.Suggestion s = ParkingCost.suggest(h, lat(20), LON, null, -1);
        assertNull(s.placeName);
        assertEquals(Collections.singletonList(12.0), s.prices);
        assertTrue(ParkingCost.spots(h, "lot").isEmpty());
    }

    @Test public void theRowBeingEditedIsLeftOut() {
        ParkingCost.Row self = at(0, "Mall", "lot", 25, H);
        ParkingCost.Suggestion s = ParkingCost.suggest(Collections.singletonList(self), lat(0), LON, null, self.id);
        assertNull(s.placeName);
    }

    @Test public void noPlaceMeansNoPrefill() {
        List<ParkingCost.Row> h = Collections.singletonList(at(0, "Mall", "lot", 25, H));
        ParkingCost.Suggestion s = ParkingCost.suggest(h, null, null, null, -1);
        assertNotNull(s);
        assertEquals("lot", s.kind);
        assertNull(s.placeName);
        assertTrue(s.prices.isEmpty());
    }
}
