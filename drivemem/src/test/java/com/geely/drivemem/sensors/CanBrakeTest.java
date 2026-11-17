package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarActor;

import org.junit.Test;

import static org.junit.Assert.*;

/** CanBrake: the brake pedal switch becomes can.brake = 1/0, or
 * NOT_AVAILABLE whenever the car says it cannot vouch for it. */
public class CanBrakeTest {

    private static int value(CarActor.Reading r) {
        assertEquals(CarActor.Reading.Status.OK, r.status);
        return ((Number) r.value).intValue();
    }

    @Test public void pressAndRelease() {
        CanBrake b = new CanBrake();
        assertEquals(1, value(b.onSignal(CanBrake.STATUS, 1, true)));
        assertEquals(0, value(b.onSignal(CanBrake.STATUS, 0, true)));
    }

    @Test public void unknownUntilSeen() {
        CanBrake b = new CanBrake();
        assertSame(CarActor.Reading.NOT_AVAILABLE, b.current());
        // the invalid flag alone says nothing about the pedal
        assertSame(CarActor.Reading.NOT_AVAILABLE, b.onSignal(CanBrake.INVALID, 0, true));
    }

    @Test public void invalidFlagOrNotObservedHidesThePedal() {
        CanBrake b = new CanBrake();
        b.onSignal(CanBrake.STATUS, 1, true);
        assertSame(CarActor.Reading.NOT_AVAILABLE, b.onSignal(CanBrake.INVALID, 1, true));
        assertEquals(1, value(b.onSignal(CanBrake.INVALID, 0, true)));
        assertSame(CarActor.Reading.NOT_AVAILABLE, b.onSignal(CanBrake.STATUS, 1, false));
    }

    @Test public void otherSignalsAreNotItsBusiness() {
        CanBrake b = new CanBrake();
        assertNull(b.onSignal("BMSH_BattVolt", 400, true));
    }

    @Test public void resetForgetsThePedal() {
        CanBrake b = new CanBrake();
        b.onSignal(CanBrake.STATUS, 1, true);
        b.reset();
        assertSame(CarActor.Reading.NOT_AVAILABLE, b.current());
    }
}
