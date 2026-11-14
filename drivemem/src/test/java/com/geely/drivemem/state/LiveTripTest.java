package com.geely.drivemem.state;

import com.geely.drivemem.sensors.EnergySource;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class LiveTripTest {
    private static final long T0 = 1_700_000_000_000L;

    @Test public void jitterBelowTheShownPrecisionIsEqual() {
        LiveTrip a = LiveTrip.of(T0, 8 * 60_000L + 5_000, 2.51, 0.361, 0.139, 96, 94, EnergySource.MEASURED);
        LiveTrip b = LiveTrip.of(T0, 8 * 60_000L + 40_000, 2.54, 0.358, 0.141, 96, 94, EnergySource.MEASURED);
        assertEquals(a, b);                  // same minute, 0.1 km, 0.01 kWh
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test public void aVisibleChangeIsNotEqual() {
        LiveTrip a = LiveTrip.of(T0, 8 * 60_000L, 2.5, 0.36, 0.14, 96, 94, EnergySource.MEASURED);
        assertNotEquals(a, LiveTrip.of(T0, 8 * 60_000L, 2.6, 0.36, 0.14, 96, 94, EnergySource.MEASURED));
        assertNotEquals(a, LiveTrip.of(T0, 9 * 60_000L, 2.5, 0.36, 0.14, 96, 94, EnergySource.MEASURED));
        assertNotEquals(a, LiveTrip.of(T0, 8 * 60_000L, 2.5, 0.36, 0.14, 96, 93, EnergySource.MEASURED));
        assertNotEquals(a, LiveTrip.of(T0, 8 * 60_000L, 2.5, 0.36, 0.14, 96, 94, EnergySource.MIXED));
    }

    @Test public void netAndEfficiencyUseTheSharedFormula() {
        LiveTrip t = LiveTrip.of(T0, 0, 2.5, 0.36, 0.14, 96, 94, EnergySource.MEASURED);
        assertEquals(0.22, t.netKwh(), 1e-9);
        assertEquals(8.8, t.efficiencyKwh100km(), 1e-9);   // 0.22 / 2.5 * 100
    }

    @Test public void underTwoHundredMetresHasNoEfficiency() {
        assertEquals(0, LiveTrip.of(T0, 0, 0.2, 0.05, 0, 96, 96, EnergySource.MEASURED)
            .efficiencyKwh100km(), 0);
    }

    @Test public void noneIsClosed() {
        assertFalse(LiveTrip.NONE.isOpen());
        assertTrue(LiveTrip.of(T0, 0, 0, 0, 0, -1, -1, null).isOpen());
        assertEquals(EnergySource.NO_DATA, LiveTrip.of(T0, 0, 0, 0, 0, -1, -1, null).source);
    }
}
