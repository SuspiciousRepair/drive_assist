package com.geely.drivemem.state;

import com.geely.drivemem.sensors.EnergySource;
import com.geely.drivemem.util.Modes;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Cutting a trip into parts per drive mode and regen level (phase 0 of
 * plan/active/TRIP-ROUTES-ROADMAP.md). */
public class ModeSegmentsTest {
    private static final int ECO = Modes.DRIVE_ECO, SPORT = Modes.DRIVE_SPORT;
    private static final int LOW = Modes.REGEN_LOW, HIGH = Modes.REGEN_HIGH;
    private static final double D = 1e-9;

    private static ModeSegments.Row row(long s, double odo, Integer drive, Integer regen,
                                        double spent, double regen_kwh, boolean measured) {
        return new ModeSegments.Row(s * 1000, odo, drive, regen, spent, regen_kwh, spent - regen_kwh,
            measured, !measured && (spent != 0 || regen_kwh != 0));
    }

    @Test public void oneModeIsOnePart() {
        List<ModeSegments.Segment> s = ModeSegments.build(Arrays.asList(
            row(30, 100.0, ECO, LOW, 0.10, 0.01, true),
            row(60, 100.5, ECO, LOW, 0.12, 0.02, true),
            row(90, 101.0, ECO, LOW, 0.08, 0.00, true)), 0);
        assertEquals(1, s.size());
        assertEquals(0, s.get(0).startMs);
        assertEquals(90_000, s.get(0).endMs);
        // The first row has no row before it, so its km is not known.
        assertEquals(1.0, s.get(0).km, D);
        assertEquals(0.30, s.get(0).spentKwh, D);
        assertEquals(0.03, s.get(0).regenKwh, D);
        assertEquals(0.27, s.get(0).netKwh, D);
        assertEquals(EnergySource.MEASURED, s.get(0).source());
    }

    @Test public void aModeChangeStartsANewPartAtTheRowBefore() {
        List<ModeSegments.Segment> s = ModeSegments.build(Arrays.asList(
            row(30, 100.0, ECO, LOW, 0.1, 0, true),
            row(60, 100.4, ECO, LOW, 0.1, 0, true),
            row(90, 101.0, SPORT, LOW, 0.3, 0, true),
            row(120, 101.7, SPORT, HIGH, 0.2, 0.1, true)), 0);
        assertEquals(3, s.size());
        assertEquals(ECO, (int) s.get(0).drive);
        assertEquals(0.4, s.get(0).km, D);
        // The 30 s that ended at the Sport row belong to Sport.
        assertEquals(SPORT, (int) s.get(1).drive);
        assertEquals(60_000, s.get(1).startMs);
        assertEquals(90_000, s.get(1).endMs);
        assertEquals(0.6, s.get(1).km, D);
        assertEquals(HIGH, (int) s.get(2).regen);
        assertEquals(0.7, s.get(2).km, D);
        assertEquals(0.1, s.get(2).regenKwh, D);
    }

    @Test public void rowsWithoutModesFormTheirOwnPart() {
        List<ModeSegments.Segment> s = ModeSegments.build(Arrays.asList(
            row(30, 100.0, null, null, 0.1, 0, false),
            row(60, 100.5, ECO, LOW, 0.1, 0, false)), 0);
        assertEquals(2, s.size());
        assertNull(s.get(0).drive);
        assertNull(s.get(0).regen);
        assertEquals(EnergySource.ESTIMATED, s.get(1).source());
    }

    @Test public void unknownOrBackwardsOdometerAddsNoDistance() {
        List<ModeSegments.Segment> s = ModeSegments.build(Arrays.asList(
            row(30, 100.0, ECO, LOW, 0, 0, false),
            row(60, -1, ECO, LOW, 0, 0, false),
            row(90, 100.3, ECO, LOW, 0, 0, false),
            row(120, 99.0, ECO, LOW, 0, 0, false)), 0);
        assertEquals(0.3, s.get(0).km, D);
        // Not measured and no energy moved: not an estimate of anything.
        assertEquals(EnergySource.NO_DATA, s.get(0).source());
    }

    @Test public void noRowsNoParts() {
        assertTrue(ModeSegments.build(Collections.<ModeSegments.Row>emptyList(), 0).isEmpty());
    }

    @Test public void labelsMatchTheTripColumn() {
        assertEquals("OBD_MEASURED", ModeSegments.label(EnergySource.MEASURED));
        assertEquals("MIXED", ModeSegments.label(EnergySource.MIXED));
        assertEquals("SOC_ESTIMATED", ModeSegments.label(EnergySource.ESTIMATED));
        assertNull(ModeSegments.label(EnergySource.NO_DATA));
    }
}
