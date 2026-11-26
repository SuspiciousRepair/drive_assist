package com.geely.drivemem.sensors;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The join and split rules of issue #210, on plain values. */
public class TripEditsTest {

    private static DailyStatsProvider.DriveSession trip(long id, long s, long e, double km, double net) {
        DailyStatsProvider.DriveSession t = new DailyStatsProvider.DriveSession(id, s, e, km, 90, 80,
            10, 5, 0.5, net + 0.5, net, net * 100 / km, EnergySource.MEASURED);
        t.odoStartKm = 1000 + id * 100;
        t.odoEndKm = t.odoStartKm + km;
        return t;
    }

    @Test public void noCutIsOneWindow() {
        List<long[]> w = TripEdits.windows(100, 200, null);
        assertEquals(1, w.size());
        assertArrayEquals(new long[]{100, 200}, w.get(0));
    }

    @Test public void cutsMakePartsAndAStopIsLeftBetweenThem() {
        List<long[]> w = TripEdits.windows(100, 200,
            Arrays.asList(new long[]{170, 170}, new long[]{130, 140}));
        assertEquals(3, w.size());
        assertArrayEquals(new long[]{100, 130}, w.get(0));
        assertArrayEquals(new long[]{140, 170}, w.get(1));
        assertArrayEquals(new long[]{170, 200}, w.get(2));
    }

    @Test public void cutsAtTheEdgesOrOutsideAreSkipped() {
        List<long[]> w = TripEdits.windows(100, 200,
            Arrays.asList(new long[]{100, 100}, new long[]{200, 200}, new long[]{50, 60}, new long[]{150, 250}));
        assertEquals(1, w.size());
    }

    @Test public void joinTakesTheTripsInsideItsWindow() {
        long[] s = {100, 300, 500, 700}, e = {200, 400, 600, 800};
        List<long[]> g = TripEdits.groups(s, e, Collections.singletonList(new TripEdits.Join(9, 300, 600)));
        assertEquals(1, g.size());
        assertArrayEquals(new long[]{9, 1, 2}, g.get(0));
    }

    @Test public void joinOfOneTripIsNoJoin() {
        long[] s = {100, 300}, e = {200, 400};
        assertTrue(TripEdits.groups(s, e, Collections.singletonList(new TripEdits.Join(1, 100, 250))).isEmpty());
    }

    @Test public void theWiderJoinWins() {
        long[] s = {100, 300, 500}, e = {200, 400, 600};
        List<long[]> g = TripEdits.groups(s, e, Arrays.asList(
            new TripEdits.Join(1, 300, 600), new TripEdits.Join(2, 100, 600)));
        assertEquals(1, g.size());
        assertArrayEquals(new long[]{2, 0, 2}, g.get(0));
    }

    @Test public void anOpenTripIsNeverJoined() {
        long[] s = {100, 300}, e = {200, 0};
        assertTrue(TripEdits.groups(s, e, Collections.singletonList(new TripEdits.Join(1, 100, 900))).isEmpty());
    }

    @Test public void joinedTripAddsUpWhatTheCardsSaid() {
        DailyStatsProvider.DriveSession a = trip(1, 100, 200, 88.4, 14.2), b = trip(2, 300, 400, 41.2, 6.1);
        a.tollCount = 1; a.tollCost = 5;
        b.tollCount = 2; b.tollCost = 7; b.tollUnpriced = 1;
        DailyStatsProvider.DriveSession j = TripEdits.join(Arrays.asList(a, b), 7);
        assertEquals(1, j.tripId);
        assertEquals(100, j.startMs);
        assertEquals(400, j.endMs);
        assertEquals(129.6, j.distanceKm, 1e-9);
        assertEquals(20.3, j.energyKwh, 1e-9);
        assertEquals(20, j.ascentDPlusM, 1e-9);
        assertEquals(90, j.socStart);
        assertEquals(80, j.socEnd);
        assertEquals(a.odoStartKm, j.odoStartKm, 1e-9);
        assertEquals(b.odoEndKm, j.odoEndKm, 1e-9);
        assertEquals(3, j.tollCount);
        assertEquals(12, j.tollCost, 1e-9);
        assertEquals(1, j.tollUnpriced);
        assertEquals(7, j.joinId);
        assertTrue(j.isJoined());
        assertEquals(2, j.members.size());
        assertEquals(EnergySource.MEASURED, j.energySource);
        assertEquals(DrivingConsumption.efficiencyKwh100km(129.6, j.spentKwh, j.regenKwh), j.efficiencyKwh100km, 1e-9);
    }

    @Test public void joinedSplitIsTheSumOrNone() {
        DailyStatsProvider.DriveSession a = trip(1, 100, 200, 10, 2), b = trip(2, 300, 400, 10, 2);
        a.split = EnergySplit.of(2, 2, 2, 0.4, 0.3, 60, 0.1);
        b.split = EnergySplit.of(2, 2, 2, 0.4, 0.3, 60, 0.1);
        EnergySplit sum = TripEdits.join(Arrays.asList(a, b), 1).split;
        assertEquals(a.split.driveKwh * 2, sum.driveKwh, 1e-9);
        assertEquals(a.split.motorRegenKwh * 2, sum.motorRegenKwh, 1e-9);
        b.split = null;
        assertNull(TripEdits.join(Arrays.asList(a, b), 1).split);
    }

    @Test public void applyJoinsKeepsTheOtherTripsInOrder() {
        List<DailyStatsProvider.DriveSession> trips = new ArrayList<>(Arrays.asList(
            trip(1, 100, 200, 5, 1), trip(2, 300, 400, 5, 1), trip(3, 500, 600, 5, 1), trip(4, 700, 800, 5, 1)));
        List<DailyStatsProvider.DriveSession> out = TripEdits.applyJoins(trips,
            Collections.singletonList(new TripEdits.Join(1, 300, 600)));
        assertEquals(3, out.size());
        assertSame(trips.get(0), out.get(0));
        assertEquals(300, out.get(1).startMs);
        assertEquals(600, out.get(1).endMs);
        assertSame(trips.get(3), out.get(2));
    }

    @Test public void aNewJoinGrowsOverAJoinItTouches() {
        long[] w = TripEdits.joinWindow(500, 900, Collections.singletonList(new TripEdits.Join(1, 300, 600)));
        assertArrayEquals(new long[]{300, 900}, w);
        assertArrayEquals(new long[]{500, 900}, TripEdits.joinWindow(500, 900,
            Collections.singletonList(new TripEdits.Join(1, 100, 200))));
    }

    @Test public void stopsAreRunsOfNoSpeedInsideTheTrip() {
        double[] speed = {0, 0, 30, 0, 0, 0, 40, 0};
        long[] ts = {0, 60_000, 120_000, 180_000, 240_000, 330_000, 400_000, 500_000};
        List<TripEdits.Stop> st = TripEdits.stops(speed, ts, 120_000);
        // The runs at the very start and end are not stops to split at.
        assertEquals(1, st.size());
        assertEquals(3, st.get(0).from);
        assertEquals(5, st.get(0).to);
    }

    @Test public void snapGoesToANearStopElseStaysPut() {
        double[] km = {0, 1, 2, 2, 2, 3, 4, 5};
        List<TripEdits.Stop> st = Collections.singletonList(new TripEdits.Stop(2, 4));
        assertArrayEquals(new int[]{2, 4}, TripEdits.snap(5, km, st, 1.5));
        assertArrayEquals(new int[]{6, 6}, TripEdits.snap(6, km, st, 1.5));
        assertNull(TripEdits.snap(0, km, st, 1.5));
        assertNull(TripEdits.snap(7, km, st, 1.5));
    }
}
