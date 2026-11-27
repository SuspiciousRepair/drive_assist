package com.geely.drivemem.controls;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.support.FakeCarAccess;

import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Covers the per-pane target feature: a single raw WINDOW_POS value
 * doesn't open every pane the same real amount (different regulator/gearing
 * per pane), so open() now takes a target per area instead of one shared
 * value for all four. */
public class PurgeTest {
    private FakeCarAccess car;
    private Purge purge;

    @Before public void setUp() {
        car = new FakeCarAccess();
        purge = new Purge();
        // All four panes start fully closed.
        for (int area : Purge.AREAS) car.seedIntRaw(CarAccess.WINDOW_POS, area, 0);
    }

    @Test public void opensEachPaneToItsOwnConfiguredTarget() {
        Map<Integer, Integer> targets = new LinkedHashMap<>();
        targets.put(Purge.AREAS[0], 30);  // FL
        targets.put(Purge.AREAS[1], 50);  // FR
        targets.put(Purge.AREAS[2], 70);  // RL
        targets.put(Purge.AREAS[3], 90);  // RR

        int moved = purge.open(car, targets);

        assertEquals(4, moved);
        assertEquals(Integer.valueOf(30), car.readIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[0]));
        assertEquals(Integer.valueOf(50), car.readIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[1]));
        assertEquals(Integer.valueOf(70), car.readIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[2]));
        assertEquals(Integer.valueOf(90), car.readIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[3]));
    }

    @Test public void paneAlreadyPastItsOwnTargetIsLeftAlone() {
        // FL already lower (more open) than its configured target -- someone
        // put it there on purpose, not ours to move.
        car.seedIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[0], 80);
        Map<Integer, Integer> targets = new LinkedHashMap<>();
        for (int area : Purge.AREAS) targets.put(area, 50);

        int moved = purge.open(car, targets);

        assertEquals(3, moved);  // FL untouched, the other three moved
        assertEquals(Integer.valueOf(80), car.readIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[0]));
        assertEquals(Integer.valueOf(50), car.readIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[1]));
    }

    @Test public void unconfiguredPaneFallsBackToOPEN() {
        Map<Integer, Integer> targets = new LinkedHashMap<>();
        targets.put(Purge.AREAS[0], 30);  // only FL configured

        purge.open(car, targets);

        assertEquals(Integer.valueOf(30), car.readIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[0]));
        assertEquals(Integer.valueOf(Purge.OPEN), car.readIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[1]));
    }

    @Test public void closeRestoresEachPaneToItsOwnRecordedBeforePosition() {
        car.seedIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[0], 5);
        car.seedIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[1], 15);
        Map<Integer, Integer> targets = new LinkedHashMap<>();
        targets.put(Purge.AREAS[0], 40);
        targets.put(Purge.AREAS[1], 60);
        targets.put(Purge.AREAS[2], 40);
        targets.put(Purge.AREAS[3], 60);
        purge.open(car, targets);

        int moved = purge.close(car);

        assertEquals(4, moved);
        assertEquals(Integer.valueOf(5), car.readIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[0]));
        assertEquals(Integer.valueOf(15), car.readIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[1]));
        assertEquals(Integer.valueOf(0), car.readIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[2]));
        assertEquals(Integer.valueOf(0), car.readIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[3]));
    }

    @Test public void unreadablePaneIsSkippedNotCrashed() {
        // No seed for AREAS[2] at all -- readIntRaw returns null, same as a
        // pane the car can't currently report.
        Map<Integer, Integer> targets = new LinkedHashMap<>();
        for (int area : Purge.AREAS) targets.put(area, 50);
        car = new FakeCarAccess();
        car.seedIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[0], 0);
        car.seedIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[1], 0);
        car.seedIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[3], 0);
        // AREAS[2] deliberately left unseeded.

        int moved = purge.open(car, targets);

        assertEquals(3, moved);
        assertNull(car.readIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[2]));
    }

    private static Map<Integer, Integer> allAt(int target) {
        Map<Integer, Integer> targets = new LinkedHashMap<>();
        for (int area : Purge.AREAS) targets.put(area, target);
        return targets;
    }

    // #208: with every target at 10 (below the door-crack margin of 14) the
    // button read "all shut" after opening, tried to open again and never
    // closed the windows.
    @Test public void lowTargetsCountAsOpenSoTheButtonCanCloseThem() {
        Map<Integer, Integer> targets = allAt(10);
        assertEquals(4, purge.open(car, targets));

        assertEquals(Boolean.TRUE, purge.anyOpen(car, targets));
        assertEquals(4, purge.close(car));
        for (int area : Purge.AREAS)
            assertEquals(Integer.valueOf(0), car.readIntRaw(CarAccess.WINDOW_POS, area));
        assertEquals(Boolean.FALSE, purge.anyOpen(car, targets));
    }

    @Test public void doorCrackStillDoesNotCountWithALargerTarget() {
        // The door feature cracks a pane to 10; with the default target (50)
        // that is not "open" for this button, same as before.
        car.seedIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[0], DoorWindow.CRACK);
        assertEquals(Boolean.FALSE, purge.anyOpen(car, allAt(Purge.OPEN)));
        car.seedIntRaw(CarAccess.WINDOW_POS, Purge.AREAS[0], DoorWindow.MORE_THAN_CRACK);
        assertEquals(Boolean.TRUE, purge.anyOpen(car, allAt(Purge.OPEN)));
    }

    @Test public void aShutPaneIsNeverOpenEvenWithATargetOfZero() {
        assertFalse(Purge.isOpen(Purge.AREAS[0], 0, allAt(0)));
        assertTrue(Purge.isOpen(Purge.AREAS[0], 1, allAt(0)));
        assertEquals(1, Purge.openAt(0));
        assertEquals(10, Purge.openAt(10));
        assertEquals(Purge.OPEN_AT_LEAST, Purge.openAt(100));
    }

    @Test public void paneWithoutATargetUsesTheDefault() {
        Map<Integer, Integer> none = new LinkedHashMap<>();
        assertFalse(Purge.isOpen(Purge.AREAS[0], 10, none));
        assertTrue(Purge.isOpen(Purge.AREAS[0], 14, none));
    }

    @Test public void unreadableWindowsGiveNull() {
        assertNull(purge.anyOpen(new FakeCarAccess(), allAt(10)));
    }

    @Test public void targetsSnapToStepsOfTen() {
        // The glass shows no movement below 10 (#208): a typed 5 opens to 10.
        assertEquals(10, Purge.snapTarget(5));
        assertEquals(0, Purge.snapTarget(4));
        assertEquals(0, Purge.snapTarget(0));
        assertEquals(20, Purge.snapTarget(15));
        assertEquals(40, Purge.snapTarget(37));
        assertEquals(50, Purge.snapTarget(50));
        assertEquals(100, Purge.snapTarget(250));
        assertEquals(0, Purge.snapTarget(-3));
    }
}
