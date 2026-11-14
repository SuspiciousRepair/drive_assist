package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarActorTestHarness;

import org.junit.Test;

import static org.junit.Assert.*;

/** EnergyHub: one place picks the measured source, CAN first, then OBD2. */
public class EnergyHubTest {
    private static final CarActor.Reading NA = CarActor.Reading.NOT_AVAILABLE;
    private static CarActor.Reading ok(Object v) { return CarActor.Reading.ok(v); }

    @Test public void pickPrefersLiveCan() {
        assertEquals(5.0f, EnergyHub.pick(ok(1), ok(5.0f), ok(7.0f)).value);
        assertEquals("can", EnergyHub.pickSource(ok(1), ok(5.0f), ok(7.0f)).value);
    }

    @Test public void pickFallsBackToObd2() {
        assertEquals("CAN not live", 7.0f, EnergyHub.pick(ok(0), ok(5.0f), ok(7.0f)).value);
        assertEquals("CAN has no value", 7.0f, EnergyHub.pick(ok(1), NA, ok(7.0f)).value);
        assertEquals("obd2", EnergyHub.pickSource(NA, NA, ok(7.0f)).value);
    }

    @Test public void pickNothingMeasured() {
        assertSame(NA, EnergyHub.pick(NA, NA, NA));
        assertSame(NA, EnergyHub.pickSource(ok(0), ok(5.0f), NA));
    }

    /** End to end in the hub: CAN and OBD2 keys in, energy.* keys out. */
    @Test public void derivedInTheHub() {
        CarActorTestHarness h = new CarActorTestHarness();
        CanHubTest.FakeSource can = new CanHubTest.FakeSource();
        FakeObd obd = new FakeObd();
        CanHub.register(h.actor(), can);
        ObdHub.register(h.actor(), obd);
        EnergyHub.register(h.actor());
        h.tickNow();
        assertEquals(-6.46f, CanHub.get(h.actor(), EnergyHub.POWER_KW), 1e-4);
        assertEquals("can", h.actor().get(EnergyHub.SOURCE).value);

        can.live = false; can.kw = null; can.v = null; can.a = null;
        h.tickNow();
        assertEquals(-6.28f, CanHub.get(h.actor(), EnergyHub.POWER_KW), 1e-4);
        assertEquals(405.2f, CanHub.get(h.actor(), EnergyHub.PACK_V), 1e-4);
        assertEquals("obd2", h.actor().get(EnergyHub.SOURCE).value);

        ObdHub.unregister(h.actor());
        h.tickNow();
        assertNull(CanHub.get(h.actor(), EnergyHub.POWER_KW));
        assertEquals(CarActor.Reading.Status.NOT_AVAILABLE, h.actor().get(EnergyHub.SOURCE).status);
    }

    static final class FakeObd implements ObdHub.Source {
        @Override public boolean connected() { return true; }
        @Override public Float soc() { return 68.2f; }
        @Override public Float packV() { return 405.2f; }
        @Override public Float packA() { return -15.5f; }
        @Override public Float powerKw() { return -6.2806f; }
        @Override public Float battTempC() { return 25.0f; }
    }
}
