package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ElevationViewTest {

    private static void assertRange(double bottom, double top, double[] r) {
        assertEquals(bottom, r[0], 1e-9);
        assertEquals(top, r[1], 1e-9);
    }

    // The case that looked like a roller coaster: 716..762 m (46 m) now
    // sits in a 200 m chart, around its middle, between round numbers
    // (it was 639..839).
    @Test public void smallClimbGetsTheMinimumSpanBetweenRoundNumbers() {
        assertRange(600, 800, ElevationView.range(716, 762));
    }

    // A big climb keeps its own range: nothing is cut off.
    @Test public void bigClimbKeepsItsRange() {
        assertRange(100, 900, ElevationView.range(100, 900));
    }

    @Test public void bigClimbMovesOutToRoundNumbers() {
        assertRange(100, 1000, ElevationView.range(123, 987));
    }

    @Test public void flatTripIsCentred() {
        assertRange(400, 600, ElevationView.range(500, 500));
    }

    // Rounding the bottom down must not push the highest point off the top.
    @Test public void theHighestPointStaysInsideAfterRounding() {
        double[] r = ElevationView.range(649, 849);
        assertRange(600, 850, r);
    }

    // By the coast the widening used to start the chart at -78 m.
    @Test public void aLowTripStartsAtZeroNotBelowIt() {
        assertRange(0, 200, ElevationView.range(5, 40));
    }

    // A height really measured under sea level is shown: the bottom is the
    // round number just under it.
    @Test public void aMeasuredNegativeHeightTakesTheBottomBelowZero() {
        assertRange(-50, 150, ElevationView.range(-12, 30));
    }

    @Test public void aBigClimbFromBelowSeaLevelKeepsItsRange() {
        assertRange(-50, 400, ElevationView.range(-20, 400));
    }
}
