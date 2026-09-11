package com.geely.drivemem;

import com.geely.drivemem.util.Modes;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModesTest {

    @Test public void driveNameEco() {
        assertEquals("Eco", Modes.driveName(Modes.DRIVE_ECO));
    }

    @Test public void driveNameComfort() {
        assertEquals("Comfort", Modes.driveName(Modes.DRIVE_COMFORT));
    }

    @Test public void driveNameSport() {
        assertEquals("Sport", Modes.driveName(Modes.DRIVE_SPORT));
    }

    @Test public void driveNameUnknownFallsBackToRawValue() {
        assertEquals("?(999)", Modes.driveName(999));
    }

    @Test public void regenNameLow() {
        assertEquals("Low", Modes.regenName(Modes.REGEN_LOW));
    }

    @Test public void regenNameMid() {
        assertEquals("Mid", Modes.regenName(Modes.REGEN_MID));
    }

    @Test public void regenNameHigh() {
        assertEquals("High", Modes.regenName(Modes.REGEN_HIGH));
    }

    @Test public void regenNameUnknownFallsBackToRawValue() {
        assertEquals("?(-1)", Modes.regenName(-1));
    }
}
