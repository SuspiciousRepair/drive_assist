package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EfficiencyBadgeViewTest {
    private static int red(int c) { return (c >> 16) & 0xFF; }
    private static int green(int c) { return (c >> 8) & 0xFF; }

    @Test public void stopsMapToTheirOwnColours() {
        for (int i = 0; i < EfficiencyBadgeView.STOPS.length; i++) {
            assertEquals(EfficiencyBadgeView.STOP_COLORS[i],
                EfficiencyBadgeView.colorFor(EfficiencyBadgeView.STOPS[i]));
        }
    }

    @Test public void twelveAndBelowIsGreenTwentyAndAboveIsRed() {
        int green = EfficiencyBadgeView.STOP_COLORS[0];
        int red = EfficiencyBadgeView.STOP_COLORS[EfficiencyBadgeView.STOP_COLORS.length - 1];
        assertEquals(green, EfficiencyBadgeView.colorFor(8.7));
        assertEquals(green, EfficiencyBadgeView.colorFor(12));
        assertEquals(red, EfficiencyBadgeView.colorFor(20));
        assertEquals(red, EfficiencyBadgeView.colorFor(31));
    }

    @Test public void pastFourteenHigherConsumptionLosesGreen() {
        // Yellow -> orange -> red: the green channel only falls.
        int prev = 256;
        for (double v = 14; v <= 20; v += 0.5) {
            int g = green(EfficiencyBadgeView.colorFor(v));
            assertTrue("green falls at " + v, g <= prev);
            prev = g;
        }
        assertTrue(red(EfficiencyBadgeView.colorFor(20)) > red(EfficiencyBadgeView.colorFor(12)));
    }
}
