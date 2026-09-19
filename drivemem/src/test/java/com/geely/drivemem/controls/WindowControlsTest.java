package com.geely.drivemem.controls;

import com.geely.drivemem.car.CarAccess;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.geely.drivemem.controls.WindowControls.FRONT_LEFT;
import static com.geely.drivemem.controls.WindowControls.FRONT_RIGHT;
import static com.geely.drivemem.controls.WindowControls.REAR_LEFT;
import static com.geely.drivemem.controls.WindowControls.REAR_RIGHT;
import static com.geely.drivemem.controls.WindowControls.Result;
import static org.junit.Assert.*;

public class WindowControlsTest {
    /** Records reads/writes without connecting to Android services or changing readings. */
    private static final class WindowCar extends CarAccess {
        final Map<Integer, Integer> positions = new HashMap<>();
        final Set<Integer> rejected = new HashSet<>();
        final List<String> operations = new ArrayList<>();
        boolean connected = true;
        boolean disconnectAfterRead;
        Runnable duringRead;

        WindowCar() {
            for (int area : WindowControls.areas()) positions.put(area, 0);
        }

        @Override public boolean isReady() { return connected; }

        @Override public Integer readIntRaw(int property, int area) {
            operations.add("read:" + property + ":" + area);
            if (duringRead != null) duringRead.run();
            if (disconnectAfterRead) connected = false;
            return positions.get(area);
        }

        @Override public boolean setIntRaw(int property, int area, int target) {
            operations.add("write:" + property + ":" + area + ":" + target);
            return !rejected.contains(area);
        }
    }

    @Test public void areaListUsesPhysicalPositionsAndCannotBeMutatedByCallers() {
        int[] areas = WindowControls.areas();
        assertArrayEquals(new int[]{16, 64, 256, 1024}, areas);
        areas[0] = 999;
        assertArrayEquals(new int[]{16, 64, 256, 1024}, WindowControls.areas());
        for (int area : WindowControls.areas()) assertTrue(WindowControls.isArea(area));
        assertFalse(WindowControls.isArea(0));
        assertFalse(WindowControls.isArea(FRONT_LEFT | FRONT_RIGHT));
    }

    @Test public void positionValidationAcceptsOnlyMeasuredPercentageRange() {
        assertFalse(WindowControls.isPosition(null));
        assertFalse(WindowControls.isPosition(-1));
        assertFalse(WindowControls.isPosition(101));
        assertTrue(WindowControls.isPosition(0));
        assertTrue(WindowControls.isPosition(37));
        assertTrue(WindowControls.isPosition(100));
    }

    @Test public void eachPresetWritesOnlyTheRequestedPaneAfterReadingIt() {
        for (int area : WindowControls.areas()) {
            for (int target : new int[]{0, 50, 100}) {
                WindowCar car = new WindowCar();
                car.positions.put(area, 25);
                assertEquals(Result.REQUESTED, WindowControls.request(car, area, target));
                assertEquals(Arrays.asList(readOp(area), writeOp(area, target)), car.operations);
            }
        }
    }

    @Test public void invalidAreasAreRejectedBeforeVehicleAccess() {
        WindowCar car = new WindowCar();
        for (int area : new int[]{-1, 0, 1, FRONT_LEFT | FRONT_RIGHT, 2048}) {
            assertThrows(IllegalArgumentException.class, () -> WindowControls.read(car, area));
            assertThrows(IllegalArgumentException.class, () -> WindowControls.request(car, area, 50));
        }
        assertTrue(car.operations.isEmpty());
    }

    @Test public void unsupportedTargetsAreRejectedRatherThanClamped() {
        WindowCar car = new WindowCar();
        for (int target : new int[]{-1, 1, 25, 49, 51, 99, 101}) {
            assertThrows(IllegalArgumentException.class,
                () -> WindowControls.request(car, FRONT_LEFT, target));
        }
        assertTrue(car.operations.isEmpty());
    }

    @Test public void absentOrDisconnectedCarDoesNotReadOrWrite() {
        assertNull(WindowControls.read(null, FRONT_LEFT));
        assertEquals(Result.UNAVAILABLE, WindowControls.request(null, FRONT_LEFT, 100));
        WindowCar car = new WindowCar();
        car.connected = false;
        assertNull(WindowControls.read(car, FRONT_LEFT));
        assertEquals(Result.UNAVAILABLE, WindowControls.request(car, FRONT_LEFT, 100));
        assertTrue(car.operations.isEmpty());
    }

    @Test public void unreadableOrInvalidReadingsNeverCauseWrites() {
        for (Integer position : new Integer[]{null, -1, 101, Integer.MAX_VALUE}) {
            WindowCar car = new WindowCar();
            car.positions.put(FRONT_LEFT, position);
            assertNull(WindowControls.read(car, FRONT_LEFT));
            assertEquals(Result.UNAVAILABLE, WindowControls.request(car, FRONT_LEFT, 100));
            assertEquals(Arrays.asList(readOp(FRONT_LEFT), readOp(FRONT_LEFT)), car.operations);
        }
    }

    @Test public void disconnectDuringReadPreventsWriteEvenWithAValidReturnedPosition() {
        WindowCar car = new WindowCar();
        car.disconnectAfterRead = true;
        assertEquals(Result.UNAVAILABLE, WindowControls.request(car, FRONT_LEFT, 100));
        assertEquals(Arrays.asList(readOp(FRONT_LEFT)), car.operations);
    }

    @Test public void cancellationBeforeReadDoesNotAccessVehicle() {
        WindowCar car = new WindowCar();
        assertEquals(Result.CANCELLED, WindowControls.request(car, FRONT_LEFT, 100, () -> false));
        assertTrue(car.operations.isEmpty());
    }

    @Test public void cancellationDuringReadPreventsWrite() {
        WindowCar car = new WindowCar();
        AtomicBoolean active = new AtomicBoolean(true);
        car.duringRead = () -> active.set(false);
        assertEquals(Result.CANCELLED, WindowControls.request(car, FRONT_LEFT, 100, active::get));
        assertEquals(Arrays.asList(readOp(FRONT_LEFT)), car.operations);
    }

    @Test public void alreadyAtTargetDoesNotWrite() {
        for (int target : new int[]{0, 50, 100}) {
            WindowCar car = new WindowCar();
            car.positions.put(FRONT_RIGHT, target);
            assertEquals(Result.UNCHANGED, WindowControls.request(car, FRONT_RIGHT, target));
            assertEquals(Arrays.asList(readOp(FRONT_RIGHT)), car.operations);
        }
    }

    @Test public void refusedWriteIsReportedOnceWithoutRetry() {
        WindowCar car = new WindowCar();
        car.rejected.add(REAR_LEFT);
        assertEquals(Result.REJECTED, WindowControls.request(car, REAR_LEFT, 50));
        assertEquals(Arrays.asList(readOp(REAR_LEFT), writeOp(REAR_LEFT, 50)), car.operations);
    }

    @Test public void acceptedWriteDoesNotInventAnArrivedPosition() {
        WindowCar car = new WindowCar();
        car.positions.put(REAR_RIGHT, 12);
        assertEquals(Result.REQUESTED, WindowControls.request(car, REAR_RIGHT, 100));
        assertEquals(Integer.valueOf(12), WindowControls.read(car, REAR_RIGHT));
        car.positions.put(REAR_RIGHT, 64);
        assertEquals(Integer.valueOf(64), WindowControls.read(car, REAR_RIGHT));
        assertEquals(Arrays.asList(readOp(REAR_RIGHT), writeOp(REAR_RIGHT, 100),
            readOp(REAR_RIGHT), readOp(REAR_RIGHT)), car.operations);
    }

    @Test public void subsequentRequestUsesFreshPositionInsteadOfPreviousTarget() {
        WindowCar car = new WindowCar();
        assertEquals(Result.REQUESTED, WindowControls.request(car, FRONT_LEFT, 100));
        // The first request was accepted but the glass has not moved.
        assertEquals(Result.UNCHANGED, WindowControls.request(car, FRONT_LEFT, 0));
        assertEquals(Arrays.asList(readOp(FRONT_LEFT), writeOp(FRONT_LEFT, 100),
            readOp(FRONT_LEFT)), car.operations);
    }

    @Test public void frontPairSequenceDoesNotReadOrWriteRearPanes() {
        WindowCar car = new WindowCar();
        assertEquals(Result.REQUESTED, WindowControls.request(car, FRONT_LEFT, 50));
        assertEquals(Result.REQUESTED, WindowControls.request(car, FRONT_RIGHT, 50));
        assertEquals(Arrays.asList(readOp(FRONT_LEFT), writeOp(FRONT_LEFT, 50),
            readOp(FRONT_RIGHT), writeOp(FRONT_RIGHT, 50)), car.operations);
    }

    @Test public void rearPairSequenceDoesNotReadOrWriteFrontPanes() {
        WindowCar car = new WindowCar();
        car.positions.put(REAR_LEFT, 100);
        car.positions.put(REAR_RIGHT, 50);
        assertEquals(Result.REQUESTED, WindowControls.request(car, REAR_LEFT, 0));
        assertEquals(Result.REQUESTED, WindowControls.request(car, REAR_RIGHT, 0));
        assertEquals(Arrays.asList(readOp(REAR_LEFT), writeOp(REAR_LEFT, 0),
            readOp(REAR_RIGHT), writeOp(REAR_RIGHT, 0)), car.operations);
    }

    @Test public void allPanesSequenceKeepsIndividualOutcomesForPartialFailure() {
        WindowCar car = new WindowCar();
        car.positions.remove(FRONT_RIGHT);
        car.rejected.add(REAR_LEFT);
        car.positions.put(REAR_RIGHT, 50);
        List<Result> results = new ArrayList<>();
        for (int area : WindowControls.areas()) results.add(WindowControls.request(car, area, 50));
        assertEquals(Arrays.asList(Result.REQUESTED, Result.UNAVAILABLE, Result.REJECTED,
            Result.UNCHANGED), results);
        assertEquals(Arrays.asList(readOp(FRONT_LEFT), writeOp(FRONT_LEFT, 50),
            readOp(FRONT_RIGHT), readOp(REAR_LEFT), writeOp(REAR_LEFT, 50),
            readOp(REAR_RIGHT)), car.operations);
    }

    private static String readOp(int area) {
        return "read:" + CarAccess.WINDOW_POS + ":" + area;
    }

    private static String writeOp(int area, int target) {
        return "write:" + CarAccess.WINDOW_POS + ":" + area + ":" + target;
    }
}
