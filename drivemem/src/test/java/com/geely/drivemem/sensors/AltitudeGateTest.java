package com.geely.drivemem.sensors;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AltitudeGateTest {

    private static final double NO = Double.NaN;

    @Test
    public void aRoadGradeIsNotAJump() {
        // 12 m over 100 m of road: steep, but a road.
        assertFalse(AltitudeGate.isJump(12, 100, 3));
        // 15 % of 100 m plus 3 m of noise is the limit.
        assertFalse(AltitudeGate.isJump(-18, 100, 3));
        assertTrue(AltitudeGate.isJump(19, 100, 3));
    }

    @Test
    public void aBigStepWhileStoppedIsAJump() {
        // 2026-11-23: +106 m with the odometer still, the car at 0 km/h.
        assertTrue(AltitudeGate.isOdoJump(106, 7069.5, 7069.5, 3));
    }

    @Test
    public void aTunnelClimbOverKilometresIsNotAJump() {
        // +197 m over 3.4 km of road, GPS lost inside the tunnel.
        assertFalse(AltitudeGate.isOdoJump(197, 100.0, 103.4, 3));
    }

    @Test
    public void anUnknownOdometerNeverMakesAJump() {
        assertFalse(AltitudeGate.isOdoJump(500, NO, 100.0, 3));
        assertFalse(AltitudeGate.isOdoJump(500, 100.0, NO, 3));
        assertFalse(AltitudeGate.isOdoJump(500, -1, 100.0, 3));
    }

    @Test
    public void theDayTopLeavesOutASpikeAndTheWayBack() {
        double[] alt = {783, 933, 820, 812, 790};
        double[] odo = {7069.5, 7069.5, 7069.6, 7069.6, 7070.5};
        // 933 is a jump; 820 is a jump back from it; 812 is the top.
        assertEquals(812, AltitudeGate.maxHeight(alt, odo, 3), 1e-9);
    }

    @Test
    public void theDayTopKeepsARealClimb() {
        double[] alt = {700, NO, 760, 790};
        double[] odo = {100.0, 100.3, 100.6, 101.0};
        assertEquals(790, AltitudeGate.maxHeight(alt, odo, 3), 1e-9);
    }

    @Test
    public void theDayTopIsNaNWithoutHeights() {
        assertTrue(Double.isNaN(AltitudeGate.maxHeight(new double[]{NO, NO}, new double[]{1, 2}, 3)));
    }

    /** Points 25 m apart, km from the start. */
    private static double[] km(int n) {
        double[] k = new double[n];
        for (int i = 0; i < n; i++) k[i] = i * 0.025;
        return k;
    }

    @Test
    public void aSpikeOnTheRouteIsDropped() {
        double[] alt = {790, 791, 789, 790, 875, 933, 885, 790, 791, 790, 789, 790};
        double[] out = AltitudeGate.dropJumps(alt, km(alt.length));
        for (int i = 4; i <= 6; i++) assertTrue("point " + i, Double.isNaN(out[i]));
        assertEquals(790, out[3], 1e-9);
        assertEquals(790, out[7], 1e-9);
    }

    @Test
    public void aBadStartIsDropped() {
        // The trip starts 75 m too high and the GPS takes a while to settle.
        double[] alt = {816, 810, 797, 774, 745, 742, 740, 741, 739, 742, 740, 741};
        double[] out = AltitudeGate.dropJumps(alt, km(alt.length));
        for (int i = 0; i <= 3; i++) assertTrue("point " + i, Double.isNaN(out[i]));
        for (int i = 4; i < alt.length; i++) assertEquals(alt[i], out[i], 1e-9);
    }

    @Test
    public void aSteadyClimbIsKept() {
        // 8 % up a hill: 2 m every 25 m.
        double[] alt = new double[30];
        for (int i = 0; i < alt.length; i++) alt[i] = 600 + 2 * i;
        double[] out = AltitudeGate.dropJumps(alt, km(alt.length));
        for (int i = 0; i < alt.length; i++) assertEquals(alt[i], out[i], 1e-9);
    }

    @Test
    public void aPointAfterATunnelWithNoNearNeighbourIsKept() {
        // Two points 3 km apart: nothing within 500 m judges either.
        double[] out = AltitudeGate.dropJumps(new double[]{50, 247}, new double[]{0, 3.0});
        assertEquals(50, out[0], 1e-9);
        assertEquals(247, out[1], 1e-9);
    }

    @Test
    public void missingHeightsStayMissing() {
        double[] alt = {700, NO, 701, 700};
        double[] out = AltitudeGate.dropJumps(alt, km(alt.length));
        assertTrue(Double.isNaN(out[1]));
        assertEquals(701, out[2], 1e-9);
    }

    @Test
    public void theFirstTwoMinutesAreNotTrusted() {
        double[] alt = {816, 797, 760, 742, 741};
        long[] ts = {0, 30_000, 90_000, 120_000, 150_000};
        double[] acc = {2, 2, 2, 2, 2};
        double[] out = AltitudeGate.dropUnsure(alt, ts, acc);
        for (int i = 0; i <= 2; i++) assertTrue("point " + i, Double.isNaN(out[i]));
        assertEquals(742, out[3], 1e-9);
        assertEquals(741, out[4], 1e-9);
    }

    @Test
    public void aVagueFixIsNotTrusted() {
        // After a tunnel: 10 m position error, and a height 146 m low.
        double[] alt = {700, 701, 384, 530, 531};
        long[] ts = {0, 130_000, 136_000, 142_000, 148_000};   // past the warm-up
        double[] acc = {2, 2, 10, 2, Double.NaN};
        double[] out = AltitudeGate.dropUnsure(alt, ts, acc);
        assertTrue(Double.isNaN(out[2]));
        assertEquals(530, out[3], 1e-9);
        assertEquals(531, out[4], 1e-9);   // unknown error: kept
    }

    @Test
    public void aTripShorterThanTheWaitKeepsItsHeights() {
        double[] alt = {740, 741, 742};
        long[] ts = {0, 30_000, 60_000};
        double[] out = AltitudeGate.dropUnsure(alt, ts, new double[]{2, 2, 2});
        assertEquals(741, out[1], 1e-9);
    }
}
