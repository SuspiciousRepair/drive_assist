package com.geely.drivemem;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.controls.AvasState;

import org.junit.Test;
import static org.junit.Assert.*;

public class AvasStateTest {
    @Test public void zeroIsOffAnyTypeIsOn() {
        assertEquals(Boolean.FALSE, AvasState.on(CarActor.Reading.ok(0)));
        assertEquals(Boolean.TRUE, AvasState.on(CarActor.Reading.ok(1)));
        assertEquals(Boolean.TRUE, AvasState.on(CarActor.Reading.ok(3)));
    }

    @Test public void unknownIsNull() {
        assertNull(AvasState.on(null));
        assertNull(AvasState.on(CarActor.Reading.NOT_AVAILABLE));
        assertNull(AvasState.on(CarActor.Reading.error("x")));
        assertNull(AvasState.on(CarActor.Reading.ok("ERR:no permission")));
    }
}
