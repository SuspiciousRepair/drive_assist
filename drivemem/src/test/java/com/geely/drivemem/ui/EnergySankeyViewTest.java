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

    // The first label never goes above the floor (Used's label is there).
    @Test public void firstLabelStaysUnderTheFloor() {
        float[] y = EnergySankeyView.labelYs(new float[]{5, 200, 300}, 40, 16);
        assertArrayEquals(new float[]{16, 200, 300}, y, 0f);
    }

    @Test public void kwhHasTwoDecimalsWhenSmall() {
        assertEquals("0.12", EnergySankeyView.kwh(0.12));
        assertEquals("6.5", EnergySankeyView.kwh(6.5));
    }

    // A tap goes kWh -> Wh/km -> % -> kWh.
    @Test public void tapCyclesTheUnits() {
        assertEquals(EnergySankeyView.UNIT_WH_KM, EnergySankeyView.nextUnit(EnergySankeyView.UNIT_KWH, 4.2));
        assertEquals(EnergySankeyView.UNIT_PCT, EnergySankeyView.nextUnit(EnergySankeyView.UNIT_WH_KM, 4.2));
        assertEquals(EnergySankeyView.UNIT_KWH, EnergySankeyView.nextUnit(EnergySankeyView.UNIT_PCT, 4.2));
    }

    // No distance: no Wh/km, a tap skips it and a saved Wh/km shows kWh.
    @Test public void noDistanceSkipsWhPerKm() {
        assertEquals(EnergySankeyView.UNIT_PCT, EnergySankeyView.nextUnit(EnergySankeyView.UNIT_KWH, 0));
        assertEquals(EnergySankeyView.UNIT_KWH, EnergySankeyView.shownUnit(EnergySankeyView.UNIT_WH_KM, 0));
        assertEquals(EnergySankeyView.UNIT_WH_KM, EnergySankeyView.shownUnit(EnergySankeyView.UNIT_WH_KM, 4.2));
    }

    // The trip from the screenshot: used 1.41, drive 0.52, 4.2 km.
    @Test public void valuesInEachUnit() {
        assertEquals("0.52", EnergySankeyView.value(0.52, EnergySankeyView.UNIT_KWH, 1.41, 4.2));
        assertEquals("124", EnergySankeyView.value(0.52, EnergySankeyView.UNIT_WH_KM, 1.41, 4.2));
        assertEquals("37", EnergySankeyView.value(0.52, EnergySankeyView.UNIT_PCT, 1.41, 4.2));
        assertEquals("100", EnergySankeyView.value(1.41, EnergySankeyView.UNIT_PCT, 1.41, 4.2));
        assertEquals("<1", EnergySankeyView.value(0.005, EnergySankeyView.UNIT_PCT, 1.41, 4.2));
        assertEquals("—", EnergySankeyView.value(0.52, EnergySankeyView.UNIT_WH_KM, 1.41, 0));
    }
}
