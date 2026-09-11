package com.geely.drivemem;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarDataHub;
import com.geely.drivemem.util.Modes;

import org.junit.Test;
import static org.junit.Assert.*;

// Exercises CarDataHub.apply()/read() end to end against a fake CarAccess —
// the actual dispatch a car write travels through, not just the Range/
// ValueSet helpers in isolation (see CarDataHubTest).
public class CarDataHubApplyTest {

    // Records what was actually sent to the "car" and lets a test dictate
    // what a write reports back, without touching android.car at all.
    static class FakeCarAccess extends CarAccess {
        Integer lastWriteDrive, lastWriteRegen, lastSetParkMode, lastSetAmbientColor,
                lastSetAmbientBrightness, lastSetCharging, lastSetChargeLimit;
        boolean writeShouldSucceed = true;
        boolean ambientColorWriteShouldReportSuccess = false; // the racy read-back — see CarDataHub

        @Override public boolean writeDrive(int val) { lastWriteDrive = val; return writeShouldSucceed; }
        @Override public boolean writeRegen(int val) { lastWriteRegen = val; return writeShouldSucceed; }
        @Override public boolean setParkMode(int value) { lastSetParkMode = value; return writeShouldSucceed; }
        @Override public boolean setAmbientColor(int rgb) {
            lastSetAmbientColor = rgb;
            return ambientColorWriteShouldReportSuccess;
        }
        @Override public boolean setAmbientBrightness(int level) { lastSetAmbientBrightness = level; return writeShouldSucceed; }
        @Override public boolean setCharging(int adaptedValue) { lastSetCharging = adaptedValue; return writeShouldSucceed; }
        @Override public int setChargeCurrentLimit(int amps) { lastSetChargeLimit = amps; return writeShouldSucceed ? amps : 0; }
    }

    @Test public void unknownEntityIsRejected() {
        CarDataHub.WriteResult r = CarDataHub.apply(new FakeCarAccess(), "not_a_real_entity", 1);
        assertFalse(r.applied);
        assertTrue(r.error.contains("unknown entity"));
    }

    @Test public void driveModeResolvesNameToRawAndWrites() {
        FakeCarAccess car = new FakeCarAccess();
        CarDataHub.WriteResult r = CarDataHub.apply(car, "drive_mode", "sport");
        assertTrue(r.applied);
        assertEquals(Modes.DRIVE_SPORT, car.lastWriteDrive.intValue());
        assertEquals(Modes.DRIVE_SPORT, r.value);
    }

    @Test public void driveModeRejectsNameOutsideClosedSet() {
        FakeCarAccess car = new FakeCarAccess();
        CarDataHub.WriteResult r = CarDataHub.apply(car, "drive_mode", "turbo");
        assertFalse(r.applied);
        assertNull(car.lastWriteDrive); // never reached the car at all
    }

    @Test public void parkMode3hResolvesToTheLiveMeasuredCode() {
        FakeCarAccess car = new FakeCarAccess();
        CarDataHub.apply(car, "park_mode", "3h");
        assertEquals(CarAccess.PARK_ON_BASE | 0x08, car.lastSetParkMode.intValue());
    }

    @Test public void ambientBrightnessClampsAboveMax() {
        FakeCarAccess car = new FakeCarAccess();
        CarDataHub.WriteResult r = CarDataHub.apply(car, "ambient_brightness", 999);
        assertEquals(CarAccess.AMBIENT_BRIGHT_MAX, car.lastSetAmbientBrightness.intValue());
        assertEquals((double) CarAccess.AMBIENT_BRIGHT_MAX, r.value);
    }

    @Test public void ambientBrightnessClampsBelowMin() {
        FakeCarAccess car = new FakeCarAccess();
        CarDataHub.apply(car, "ambient_brightness", -5);
        assertEquals(0, car.lastSetAmbientBrightness.intValue());
    }

    @Test public void chargeCurrentLimitClampsToDeviceRange() {
        FakeCarAccess car = new FakeCarAccess();
        CarDataHub.apply(car, "charge_current_limit", 999);
        assertEquals(CarAccess.CHARGE_MAX_A, car.lastSetChargeLimit.intValue());

        car = new FakeCarAccess();
        CarDataHub.apply(car, "charge_current_limit", 1);
        assertEquals(CarAccess.CHARGE_MIN_A, car.lastSetChargeLimit.intValue());
    }

    // The actual regression this entity exists to fix (see CarDataHub's own
    // comment on "ambient_color", and git commit 6fb3ad6): the ECU write
    // race made an immediate read-back report failure on a write that had
    // actually landed. This asserts the fix — apply() trusts the send and
    // reports success even when the underlying (racy) read-back says no.
    @Test public void ambientColorTrustsTheSendEvenWhenReadbackWouldSayFailed() {
        FakeCarAccess car = new FakeCarAccess();
        car.ambientColorWriteShouldReportSuccess = false; // simulates the race
        CarDataHub.WriteResult r = CarDataHub.apply(car, "ambient_color", 0xFF0000);
        assertTrue("a cast is trusted as sent, not verified", r.applied);
        assertEquals(0xFF0000, car.lastSetAmbientColor.intValue());
    }

    @Test public void writeFailurePropagatesAsRejected() {
        FakeCarAccess car = new FakeCarAccess();
        car.writeShouldSucceed = false;
        CarDataHub.WriteResult r = CarDataHub.apply(car, "drive_mode", "eco");
        assertFalse(r.applied);
        assertEquals("write failed", r.error);
    }

    @Test public void readDispatchesToTheRightEntity() {
        FakeCarAccess car = new FakeCarAccess() {
            @Override public Integer readDrive() { return Modes.DRIVE_COMFORT; }
        };
        assertEquals(Modes.DRIVE_COMFORT, CarDataHub.read(car, "drive_mode"));
    }

    @Test public void readOfUnknownEntityIsNull() {
        assertNull(CarDataHub.read(new FakeCarAccess(), "not_a_real_entity"));
    }
}
