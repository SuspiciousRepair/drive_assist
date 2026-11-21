package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarActor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** DashBrake: what the dashcam is told about the brake pedal, and when. */
public class DashBrakeTest {

    /** Records what the core asks for. */
    private static final class Out implements DashBrake.Core.Out {
        final List<String> log = new ArrayList<>();
        boolean timer;
        @Override public void send(int state) { log.add("send " + state); }
        @Override public void repeatIn(long ms) { timer = true; log.add("repeat " + ms); }
        @Override public void cancelRepeat() { timer = false; }
        String take() { String s = String.join(", ", log); log.clear(); return s; }
    }

    @Test public void hubReadingBecomesAState() {
        assertEquals(DashBrake.PRESSED, DashBrake.state(CarActor.Reading.ok(1)));
        assertEquals(DashBrake.RELEASED, DashBrake.state(CarActor.Reading.ok(0)));
        assertEquals(DashBrake.UNKNOWN, DashBrake.state(CarActor.Reading.NOT_AVAILABLE));
        assertEquals(DashBrake.UNKNOWN, DashBrake.state(CarActor.Reading.error("x")));
        assertEquals(DashBrake.UNKNOWN, DashBrake.state(CarActor.Reading.ok("1")));
        assertEquals(DashBrake.UNKNOWN, DashBrake.state(null));
    }

    @Test public void pressIsSentAndRepeatedUntilRelease() {
        DashBrake.Core c = new DashBrake.Core();
        Out o = new Out();
        c.onState(DashBrake.PRESSED, o);
        assertEquals("send 1, repeat " + DashBrake.REPEAT_MS, o.take());
        c.onRepeat(o);
        c.onRepeat(o);
        assertEquals("send 1, repeat 2000, send 1, repeat 2000", o.take());
        c.onState(DashBrake.RELEASED, o);
        assertEquals("send 0", o.take());
        assertFalse("release stops the repeat", o.timer);
        // A timer that was already on its way fires into nothing.
        c.onRepeat(o);
        assertEquals("", o.take());
    }

    @Test public void theSameStateIsNotSentTwice() {
        DashBrake.Core c = new DashBrake.Core();
        Out o = new Out();
        c.onState(DashBrake.RELEASED, o);
        c.onState(DashBrake.RELEASED, o);
        assertEquals("send 0", o.take());
        c.onState(DashBrake.PRESSED, o);
        c.onState(DashBrake.PRESSED, o);
        assertEquals("send 1, repeat 2000", o.take());
    }

    @Test public void readerGoingDownIsSentAsUnknownAndStopsTheRepeat() {
        DashBrake.Core c = new DashBrake.Core();
        Out o = new Out();
        c.onState(DashBrake.PRESSED, o);
        o.take();
        c.onState(DashBrake.UNKNOWN, o);
        assertEquals("send -1", o.take());
        assertFalse(o.timer);
        c.onRepeat(o);
        assertEquals("", o.take());
    }

    @Test public void theFirstStateIsAlwaysSentEvenUnknown() {
        DashBrake.Core c = new DashBrake.Core();
        Out o = new Out();
        c.onRepeat(o);                      // nothing known yet
        assertEquals("", o.take());
        c.onState(DashBrake.UNKNOWN, o);
        assertEquals("send -1", o.take());
    }
}
