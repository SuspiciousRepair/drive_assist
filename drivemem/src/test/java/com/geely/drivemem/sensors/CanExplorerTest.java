package com.geely.drivemem.sensors;

import org.junit.Test;

import java.io.StringWriter;

import static org.junit.Assert.*;

/** CanExplorer.Recorder: changes only, at most one line per frame per 2 s. */
public class CanExplorerTest {
    private long now = 10_000;

    private CanExplorer.Recorder rec(StringWriter w) {
        CanExplorer.Recorder r = new CanExplorer.Recorder(w);
        r.mono = () -> now;
        r.wall = () -> 1_700_000_000_000L + now;
        r.ref = k -> k.equals(ObdHub.BATT_TEMP_C) ? 26.0f : null;
        return r;
    }

    private static long lines(StringWriter w, String prefix) {
        return w.toString().lines().filter(l -> l.startsWith(prefix)).count();
    }

    @Test public void writesChangesOnlyAndThrottles() throws Exception {
        StringWriter w = new StringWriter();
        CanExplorer.Recorder r = rec(w);
        r.onFrame(0x178, 2, new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, true);
        r.onFrame(0x178, 2, new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, false);   // same bytes
        assertEquals(1, lines(w, "F"));
        now += 500;
        r.onFrame(0x178, 2, new byte[]{9, 2, 3, 4, 5, 6, 7, 8}, false);   // changed, too soon
        assertEquals(1, lines(w, "F"));
        now += 1600;
        r.tick();                                                         // flushes the held change
        assertEquals(2, lines(w, "F"));
        assertTrue(w.toString().contains("F\t1700000012100\t178\t2\t0902030405060708"));
    }

    @Test public void referenceLineEveryFiveSeconds() throws Exception {
        StringWriter w = new StringWriter();
        CanExplorer.Recorder r = rec(w);
        r.tick();
        now += 1000; r.tick();
        assertEquals(1, lines(w, "R"));
        now += 4000; r.tick();
        assertEquals(2, lines(w, "R"));
        assertTrue(w.toString().contains(ObdHub.BATT_TEMP_C + "=26.0"));
    }
}
