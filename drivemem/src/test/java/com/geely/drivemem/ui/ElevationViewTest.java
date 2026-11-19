package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ElevationViewTest {

    // The case that looked like a roller coaster: 716..762 m (46 m) now
    // sits in a 200 m chart, around its middle.
    @Test public void smallClimbGetsTheMinimumSpan() {
        double[] r = ElevationView.range(716, 762);
        assertEquals(ElevationView.MIN_SPAN_M, r[1] - r[0], 1e-9);
        assertEquals(739, (r[0] + r[1]) / 2, 1e-9);
    }

    // A big climb keeps its own range: nothing is cut off.
    @Test public void bigClimbKeepsItsRange() {
        double[] r = ElevationView.range(100, 900);
        assertEquals(100, r[0], 1e-9);
        assertEquals(900, r[1], 1e-9);
    }

    @Test public void flatTripIsCentred() {
        double[] r = ElevationView.range(500, 500);
        assertEquals(400, r[0], 1e-9);
        assertEquals(600, r[1], 1e-9);
    }
}
