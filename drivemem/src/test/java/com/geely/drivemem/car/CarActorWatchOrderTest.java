package com.geely.drivemem.car;

import com.geely.drivemem.util.Modes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The tick that connects to the car must already see the watched values.
 * They used to be posted to run after that tick, so the first
 * telemetry_sample row after every app restart had no drive mode or regen
 * level (seen 2026-11-15). */
public class CarActorWatchOrderTest {

    @Test public void connectingTickHasDriveAndRegenModes() {
        CarActorTestHarness h = new CarActorTestHarness();
        h.car().watchSucceeds = true;
        h.car().seedIntRaw(Modes.PROP_DRIVE, 0, 570491137);
        h.car().seedIntRaw(Modes.PROP_REGEN, 0, 537003265);
        h.car().disconnect();

        // One tick, and nothing queued after it: the values must be there.
        h.actor().tickNowForTesting();

        CarActor.Reading drive = h.actor().get("car.drive_mode");
        CarActor.Reading regen = h.actor().get("car.regen_mode");
        assertEquals(CarActor.Reading.Status.OK, drive.status);
        assertEquals(570491137, drive.value);
        assertEquals(CarActor.Reading.Status.OK, regen.status);
        assertEquals(537003265, regen.value);
    }
}
