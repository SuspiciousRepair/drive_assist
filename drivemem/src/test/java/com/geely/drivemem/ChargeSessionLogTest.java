package com.geely.drivemem;

import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.util.DbMigration;

import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

// parseLine() is the read side of charge.log's old, no-longer-written
// format — nothing writes this file any more (ChargeSession now inserts
// into CarDb directly, see its own header comment), but parseLine()
// survives purely to read pre-existing history during the one-time DB
// migration (DbMigration.migrateCharge()). A caller-facing bug here
// silently corrupts imported history rather than crashing (readOneFile
// skips a bad line and moves on, by design).
public class ChargeSessionLogTest {

    // parseLine is a package-private static with no public accessor;
    // reflection keeps this test from having to widen its visibility just
    // to be tested.
    private static ChargeSession.Summary parseLine(String line) throws Exception {
        Method m = ChargeSession.class.getDeclaredMethod("parseLine", String.class);
        m.setAccessible(true);
        return (ChargeSession.Summary) m.invoke(null, line);
    }

    @Test public void parsesAKnownGoodLegacyLine() throws Exception {
        // A hand-written line matching the old HEADER exactly:
        // start\tend\tduration_s\tsoc_start\tsoc_end\tkwh\tavg_power_w\tsamples\todo_start
        ChargeSession.Summary s = parseLine(
            "2023-11-14 22:13:20\t2023-11-15 01:13:20\t10800\t30\t95\t28.95\t3470\t480\t4097.4");
        assertNotNull(s);
        assertEquals(30, s.socStart);
        assertEquals(95, s.socEnd);
        assertEquals(28.95, s.kwh, 0.01);
        assertEquals(3470, s.avgPowerW, 1);
        assertEquals(480, s.samples);
        assertEquals(4097.4, s.odoStart, 0.1);
    }

    @Test public void missingOdoColumnFallsBackToUnknown() throws Exception {
        // Shape of a log line written before odo_start existed — 8 columns,
        // no trailing odometer field at all.
        ChargeSession.Summary s = parseLine(
            "2026-01-01 10:00:00\t2026-01-01 11:00:00\t3600\t30\t80\t20.00\t4000\t120");
        assertNotNull(s);
        assertEquals(-1.0, s.odoStart, 0.0);
    }

    @Test public void emptyTrailingOdoColumnFallsBackToUnknown() throws Exception {
        ChargeSession.Summary s = parseLine(
            "2026-01-01 10:00:00\t2026-01-01 11:00:00\t3600\t30\t80\t20.00\t4000\t120\t");
        assertNotNull(s);
        assertEquals(-1.0, s.odoStart, 0.0);
    }

    @Test public void tooFewColumnsIsSkippedNotThrown() throws Exception {
        assertNull(parseLine("2026-01-01 10:00:00\t2026-01-01 11:00:00\t3600"));
    }

    @Test public void garbageLineIsSkippedNotThrown() throws Exception {
        assertNull(parseLine("this is not a charge log line at all"));
    }

    @Test public void blankLineIsSkippedNotThrown() throws Exception {
        assertNull(parseLine(""));
    }
}
