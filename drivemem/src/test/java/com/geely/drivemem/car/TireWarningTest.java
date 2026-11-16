package com.geely.drivemem.car;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TireWarningTest {
    private static CarActor.Reading ok(int v) { return CarActor.Reading.ok(v); }
    private static final CarActor.Reading ERR = CarActor.Reading.error("x");

    @Test public void noWarningWhenEveryWheelReadsZero() {
        CarActor.Reading r = TireWarning.any(ok(0), ok(0), ok(0), ok(0));
        assertEquals(CarActor.Reading.Status.OK, r.status);
        assertEquals(0, r.value);
    }

    @Test public void anyNonZeroWheelIsAWarning() {
        assertEquals(1, TireWarning.any(ok(0), ok(0), ok(2), ok(0)).value);
        assertEquals(1, TireWarning.any(ok(-1), ok(0), ok(0), ok(0)).value);
    }

    @Test public void aWheelThatCannotBeReadDoesNotHideTheOthers() {
        assertEquals(1, TireWarning.any(ERR, ok(1), ERR, ERR).value);
        assertEquals(0, TireWarning.any(ERR, ok(0), CarActor.Reading.NOT_AVAILABLE, null).value);
    }

    @Test public void noWheelReadIsAnErrorNotAFalseAllClear() {
        assertEquals(CarActor.Reading.Status.ERROR, TireWarning.any(ERR, ERR, ERR, ERR).status);
    }

    @Test public void warnsOnlyOnAnOkNonZeroNumber() {
        assertTrue(TireWarning.warns(ok(1)));
        assertFalse(TireWarning.warns(ok(0)));
        assertFalse(TireWarning.warns(ERR));
        assertFalse(TireWarning.warns(null));
        assertFalse(TireWarning.warns(CarActor.Reading.ok("x")));
    }

    // Android VehicleAreaWheel: FL 1, FR 2, RR 4, RL 8.
    @Test public void wheelKeysMatchTheStandardAreas() {
        assertEquals(TireWarning.FL, TireWarning.KEYS[0]); assertEquals(1, TireWarning.AREAS[0]);
        assertEquals(TireWarning.FR, TireWarning.KEYS[1]); assertEquals(2, TireWarning.AREAS[1]);
        assertEquals(TireWarning.RL, TireWarning.KEYS[2]); assertEquals(8, TireWarning.AREAS[2]);
        assertEquals(TireWarning.RR, TireWarning.KEYS[3]); assertEquals(4, TireWarning.AREAS[3]);
    }
}
