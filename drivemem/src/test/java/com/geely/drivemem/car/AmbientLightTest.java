package com.geely.drivemem.car;

import com.geely.drivemem.util.Modes;

import org.junit.Test;
import static org.junit.Assert.*;

/** #17: in "follow drive mode" the car keeps reporting the last hand-picked
 * colour (0xC30000 on the owner's car) while the light shows the drive
 * mode's colour. Values below were read on the car 2026-11-14. */
public class AmbientLightTest {
    private static final int STALE_RED = 0xC30000;
    private static final int ORANGE = 16740097;

    @Test public void followModeUsesTheDriveModeColour() {
        int m = AmbientLight.MODE_FOLLOW_DRIVE;
        assertEquals(Integer.valueOf(AmbientLight.ECO_RGB), AmbientLight.effective(m, STALE_RED, Modes.DRIVE_ECO));
        assertEquals(Integer.valueOf(AmbientLight.COMFORT_RGB), AmbientLight.effective(m, STALE_RED, Modes.DRIVE_COMFORT));
        assertEquals(Integer.valueOf(AmbientLight.SPORT_RGB), AmbientLight.effective(m, STALE_RED, Modes.DRIVE_SPORT));
    }

    @Test public void documentedFollowValueWorksToo() {
        assertEquals(Integer.valueOf(AmbientLight.ECO_RGB),
            AmbientLight.effective(AmbientLight.MODE_FOLLOW_DRIVE_DOC, STALE_RED, Modes.DRIVE_ECO));
    }

    @Test public void pickedColourIsUsedAsIs() {
        assertEquals(Integer.valueOf(ORANGE),
            AmbientLight.effective(AmbientLight.MODE_SET_COLOR, ORANGE, Modes.DRIVE_ECO));
    }

    @Test public void unknownModeOrDriveFallsBackToReportedColour() {
        assertEquals(Integer.valueOf(STALE_RED), AmbientLight.effective(null, STALE_RED, Modes.DRIVE_ECO));
        assertEquals(Integer.valueOf(STALE_RED),
            AmbientLight.effective(AmbientLight.MODE_FOLLOW_DRIVE, STALE_RED, null));
        assertEquals(Integer.valueOf(STALE_RED),
            AmbientLight.effective(AmbientLight.MODE_FOLLOW_DRIVE, STALE_RED, 12345));
    }

    @Test public void deriveUsesHubReadings() {
        CarActor.Reading r = AmbientLight.derive(CarActor.Reading.ok(STALE_RED),
            CarActor.Reading.ok(AmbientLight.MODE_FOLLOW_DRIVE), CarActor.Reading.ok(Modes.DRIVE_COMFORT));
        assertEquals(CarActor.Reading.Status.OK, r.status);
        assertEquals(AmbientLight.COMFORT_RGB, r.value);
        // Mode not read yet: the reported colour, as before the fix.
        r = AmbientLight.derive(CarActor.Reading.ok(STALE_RED), CarActor.Reading.LOADING,
            CarActor.Reading.ok(Modes.DRIVE_COMFORT));
        assertEquals(STALE_RED, r.value);
        // No colour at all: the colour's own error passes through.
        CarActor.Reading err = CarActor.Reading.error("read failed");
        assertSame(err, AmbientLight.derive(err, CarActor.Reading.ok(AmbientLight.MODE_SET_COLOR),
            CarActor.Reading.LOADING));
    }
}
