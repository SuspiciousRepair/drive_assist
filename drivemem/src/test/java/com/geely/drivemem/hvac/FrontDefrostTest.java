package com.geely.drivemem.hvac;

import com.geely.drivemem.car.CarActor;

import org.junit.Test;
import static org.junit.Assert.*;

public class FrontDefrostTest {

    @Test public void readsBooleanAndNumberValues() {
        assertEquals(Boolean.TRUE, FrontDefrost.on(CarActor.Reading.ok(true)));
        assertEquals(Boolean.FALSE, FrontDefrost.on(CarActor.Reading.ok(false)));
        assertEquals(Boolean.TRUE, FrontDefrost.on(CarActor.Reading.ok(1)));
        assertEquals(Boolean.FALSE, FrontDefrost.on(CarActor.Reading.ok(0)));
    }

    @Test public void unreadableIsNullNeverOff() {
        assertNull(FrontDefrost.on(null));
        assertNull(FrontDefrost.on(CarActor.Reading.error("watch failed")));
        assertNull(FrontDefrost.on(CarActor.Reading.ok("x")));
    }

    @Test public void pressAndReleaseAreLogged() {
        assertEquals("front on", FrontDefrost.transition(false, true));
        assertEquals("front off", FrontDefrost.transition(true, false));
    }

    @Test public void firstValueAtConnectIsNotAPress() {
        assertNull(FrontDefrost.transition(null, true));
        assertNull(FrontDefrost.transition(null, false));
    }

    @Test public void noChangeOrUnreadableLogsNothing() {
        assertNull(FrontDefrost.transition(true, true));
        assertNull(FrontDefrost.transition(false, false));
        assertNull(FrontDefrost.transition(true, null));
    }
}
