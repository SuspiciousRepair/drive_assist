package com.geely.drivemem.car;

import org.junit.Test;

import static org.junit.Assert.*;

/** car-events.log: one line when the position lamps go on or off. */
public class LightEventLogTest {
    @Test public void firstReadingIsWritten() {
        assertEquals("lights on (first reading)", new LightEventLog.Tracker().onValue(1));
        assertEquals("lights off (first reading)", new LightEventLog.Tracker().onValue(0));
    }

    @Test public void eachChangeWritesOneLine() {
        LightEventLog.Tracker t = new LightEventLog.Tracker();
        t.onValue(1);
        assertEquals("lights off", t.onValue(0));
        assertEquals("lights on", t.onValue(1));
    }

    @Test public void repeatOfTheSameReadingIsSilent() {
        LightEventLog.Tracker t = new LightEventLog.Tracker();
        t.onValue(1);
        assertNull(t.onValue(1));
    }

    @Test public void describeAddsGearAndSpeed() {
        assertEquals(", gear D, 32 km/h",
            LightEventLog.describe(CarActor.Reading.ok(8), CarActor.Reading.ok(31.6f)));
    }

    @Test public void describeLeavesOutWhatIsMissing() {
        assertEquals(", gear P", LightEventLog.describe(CarActor.Reading.ok(4), null));
        assertEquals("", LightEventLog.describe(null, CarActor.Reading.error("no CAN")));
    }
}
