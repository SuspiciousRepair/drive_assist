package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TempRangeViewTest {
    private static int red(int c) { return (c >> 16) & 0xFF; }
    private static int blue(int c) { return c & 0xFF; }

    @Test public void stopsMapToTheirOwnColours() {
        for (int i = 0; i < TempRangeView.STOPS_C.length; i++) {
            assertEquals(TempRangeView.STOP_COLORS[i], TempRangeView.colorFor(TempRangeView.STOPS_C[i]));
        }
    }

    @Test public void scaleIsClampedAtBothEnds() {
        assertEquals(TempRangeView.STOP_COLORS[0], TempRangeView.colorFor(-15f));
        assertEquals(TempRangeView.STOP_COLORS[TempRangeView.STOP_COLORS.length - 1], TempRangeView.colorFor(48f));
    }

    @Test public void hotSwingStaysWarmAndColdStaysCool() {
        // A 30-40 °C day: every colour is warm (strong red, little blue).
        for (float c = 30; c <= 40; c += 1) {
            int col = TempRangeView.colorFor(c);
            assertTrue(c + "°C should be warm", red(col) >= 190 && blue(col) < 60);
        }
        // A 5-12 °C night: blue outweighs red.
        for (float c = 5; c <= 12; c += 1) {
            int col = TempRangeView.colorFor(c);
            assertTrue(c + "°C should be cool", blue(col) > red(col));
        }
    }
}
