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

    // The detail window: slider units and limits.
    @Test public void sliderCoversTheUsualRangeAndASavedValueAboveIt() {
        assertEquals(100, TelemetryDiarySection.sliderMax(2, 40_000));    // km, in thousands
        assertEquals(150, TelemetryDiarySection.sliderMax(2, 150_000));
        assertEquals(90, TelemetryDiarySection.sliderMax(0, 30));         // days
        assertEquals(60, TelemetryDiarySection.sliderMax(1, 48));         // months
    }

    @Test public void aNudgeGoesInRoundSteps() {
        assertEquals(45_000, TelemetryDiarySection.step(2, 40_000, 1, 100));
        assertEquals(35_000, TelemetryDiarySection.step(2, 40_000, -1, 100));
        assertEquals(35, TelemetryDiarySection.step(0, 30, 1, 90));
        assertEquals(54, TelemetryDiarySection.step(1, 48, 1, 60));
        assertEquals(42, TelemetryDiarySection.step(1, 48, -1, 60));
    }

    // A typed value off the grid goes to the grid, not past it.
    @Test public void offTheGridGoesToTheNextRoundValue() {
        assertEquals(10, TelemetryDiarySection.step(0, 7, 1, 90));
        assertEquals(5, TelemetryDiarySection.step(0, 7, -1, 90));
        assertEquals(42_000 / 42 * 45, TelemetryDiarySection.step(2, 42_000, 1, 100));
    }

    @Test public void aNudgeStaysInside() {
        assertEquals(1, TelemetryDiarySection.step(0, 5, -1, 90));
        assertEquals(1, TelemetryDiarySection.step(0, 1, -1, 90));
        assertEquals(5, TelemetryDiarySection.step(0, 1, 1, 90));
        assertEquals(90, TelemetryDiarySection.step(0, 90, 1, 90));
        // A typed 150,000 km is above the slider: plus does not pull it down.
        assertEquals(150_000, TelemetryDiarySection.step(2, 150_000, 1, 100));
    }

    @Test public void typedTextIsANumberOrNothing() {
        assertEquals(25, TelemetryDiarySection.typed("25"));
        assertEquals(-1, TelemetryDiarySection.typed(""));
    }

    @org.junit.Test
    public void addMenuOpensAboveWhenTheRoomBelowIsSmall() {
        // Room below: opens under the link.
        org.junit.Assert.assertArrayEquals(new int[]{460, 300},
            TelemetryDiarySection.menuPlace(400, 60, 300, 8, 960));
        // Link near the bottom: opens above it, bottom edge on the link.
        org.junit.Assert.assertArrayEquals(new int[]{570, 300},
            TelemetryDiarySection.menuPlace(870, 50, 300, 8, 960));
        // Longer than the room above: cut to that room, it scrolls.
        org.junit.Assert.assertArrayEquals(new int[]{8, 862},
            TelemetryDiarySection.menuPlace(870, 50, 2000, 8, 960));
    }
}
