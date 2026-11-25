package com.geely.drivemem.car;

import org.junit.Test;

import static org.junit.Assert.*;

/** car-events.log: one line when the whole car locks or unlocks. */
public class LockEventLogTest {
    @Test public void firstReadingIsWritten() {
        LockEventLog.Tracker t = new LockEventLog.Tracker();
        assertEquals("locked (first reading)", t.onDoor(1, 1));
    }

    @Test public void theOtherDoorsOfOneLockAreNotWrittenAgain() {
        LockEventLog.Tracker t = new LockEventLog.Tracker();
        t.onDoor(1, 1);
        assertNull(t.onDoor(4, 1));
        assertNull(t.onDoor(16, 1));
        assertNull(t.onDoor(64, 1));
    }

    @Test public void unlockThenLockWritesOneLineEach() {
        LockEventLog.Tracker t = new LockEventLog.Tracker();
        for (int a : new int[]{1, 4, 16, 64}) t.onDoor(a, 1);
        assertEquals("unlocked", t.onDoor(1, 0));
        assertNull(t.onDoor(4, 0));
        assertNull(t.onDoor(1, 1));          // one door still unlocked
        assertEquals("locked", t.onDoor(4, 1));
    }

    @Test public void repeatOfTheSameReadingIsSilent() {
        LockEventLog.Tracker t = new LockEventLog.Tracker();
        t.onDoor(1, 0);
        assertNull(t.onDoor(1, 0));
    }
}
