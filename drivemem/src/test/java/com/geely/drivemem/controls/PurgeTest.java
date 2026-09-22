package com.geely.drivemem.controls;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.support.FakeCarAccess;

import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
