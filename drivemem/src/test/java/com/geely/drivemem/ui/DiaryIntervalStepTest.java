package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.*;

/** Settings -> Maintenance: the arrows of an interval row. */
public class DiaryIntervalStepTest {
    private static final int[][] DAYS = {{0, 0}, {7, 0}, {15, 0}, {30, 0}, {60, 0}};
    private static final int[][] SERVICE = {{0, 0}, {10_000, 12}, {20_000, 12}, {30_000, 24}};

    @Test public void arrowsWalkThePresets() {
        assertEquals(3, TelemetryDiarySection.neighbour(DAYS, 15, 0, false, 1));
        assertEquals(1, TelemetryDiarySection.neighbour(DAYS, 15, 0, false, -1));
    }

    @Test public void noStepPastTheEnds() {
        assertEquals(-1, TelemetryDiarySection.neighbour(DAYS, 0, 0, false, -1));
        assertEquals(-1, TelemetryDiarySection.neighbour(DAYS, 60, 0, false, 1));
    }

    @Test public void offGoesToTheFirstPreset() {
        assertEquals(1, TelemetryDiarySection.neighbour(SERVICE, 0, 0, true, 1));
    }

    // 25,000 km / 18 months is no preset: the arrows go to the nearest ones.
    @Test public void aCustomValueGoesToTheNearestPreset() {
        assertEquals(3, TelemetryDiarySection.neighbour(SERVICE, 25_000, 18, true, 1));
        assertEquals(2, TelemetryDiarySection.neighbour(SERVICE, 25_000, 18, true, -1));
        assertEquals(-1, TelemetryDiarySection.neighbour(DAYS, 90, 0, false, 1));
    }

    // Same km as a preset but other months is a custom value too.
    @Test public void sameKmOtherMonthsIsCustom() {
        assertEquals(3, TelemetryDiarySection.neighbour(SERVICE, 20_000, 24, true, 1));
        assertEquals(1, TelemetryDiarySection.neighbour(SERVICE, 20_000, 24, true, -1));
    }
}
