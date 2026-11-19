package com.geely.drivemem.sensors;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/** can-events.log: one line per change, never the same line on repeat. */
public class CanEventLogTest {
    @Before public void reset() { CanEventLog.resetForTest(); }

    @Test public void sameLineIsWrittenOncePerWindow() {
        assertTrue(CanEventLog.shouldWrite("app no_daemon", 0));
        assertFalse(CanEventLog.shouldWrite("app no_daemon", 60_000));
        assertTrue(CanEventLog.shouldWrite("app no_daemon", CanEventLog.SAME_EVENT_MS + 1));
    }

    @Test public void aDifferentLineIsAlwaysWritten() {
        assertTrue(CanEventLog.shouldWrite("app live", 0));
        assertTrue(CanEventLog.shouldWrite("app dropped: reset", 1000));
        assertTrue(CanEventLog.shouldWrite("app live", 2000));
    }

    // The wall clock went back (a time sync): write, do not stay silent.
    @Test public void clockGoingBackWrites() {
        assertTrue(CanEventLog.shouldWrite("app live", 100_000));
        assertTrue(CanEventLog.shouldWrite("app live", 50_000));
    }
}
