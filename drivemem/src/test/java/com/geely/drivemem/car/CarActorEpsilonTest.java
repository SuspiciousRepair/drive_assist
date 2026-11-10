package com.geely.drivemem.car;

import org.junit.Test;
import static org.junit.Assert.*;

/** The change filter must find a field by its cache key ("telemetry.x"). */
public class CarActorEpsilonTest {
    @Test public void rawSpeedGetsTheJitterDeadband() {
        assertEquals(0.05, CarActor.epsilonFor("telemetry.speed"), 1e-9);
    }
    @Test public void roundedBatteryIsExact() {
        assertEquals(0.0, CarActor.epsilonFor("telemetry.battery"), 1e-9);
    }
    @Test public void nonFieldKeysAreExact() {
        assertEquals(0.0, CarActor.epsilonFor("car.is_charging"), 1e-9);
    }
}
