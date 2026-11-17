package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.*;

/** The Sankey's labels move apart; its bands never do. */
public class EnergySankeyViewTest {
    @Test public void labelsKeepTheirPlaceWhenThereIsRoom() {
        float[] y = EnergySankeyView.labelYs(new float[]{100, 200, 300}, 40);
        assertArrayEquals(new float[]{100, 200, 300}, y, 0f);
    }

    @Test public void thinPartsPushTheirLabelsDown() {
        // Climate and other are thin, right under drive.
        float[] y = EnergySankeyView.labelYs(new float[]{100, 205, 222}, 40);
        assertArrayEquals(new float[]{100, 205, 245}, y, 0f);
    }

    @Test public void kwhHasTwoDecimalsWhenSmall() {
        assertEquals("0.12", EnergySankeyView.kwh(0.12));
        assertEquals("6.5", EnergySankeyView.kwh(6.5));
    }
}
