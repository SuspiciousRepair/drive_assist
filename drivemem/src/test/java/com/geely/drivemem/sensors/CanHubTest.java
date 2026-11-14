package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarActorTestHarness;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** CanHub: CAN values reach the hub (CLAUDE.md rule 7), cheaply. */
public class CanHubTest {

    static final class FakeSource implements CanHub.Source {
        boolean live = true;
        Float v = 404.23f, a = -15.98f, kw = -6.4597f, climate = 1.6f, drive = 0.04f, charger = 6812f;
        @Override public boolean live() { return live; }
        @Override public Float packV() { return v; }
        @Override public Float packA() { return a; }
        @Override public Float powerKw() { return kw; }
        @Override public Float climateKw() { return climate; }
        @Override public Float driveKw() { return drive; }
        @Override public Float chargerW() { return charger; }
    }

    @Test public void valuesReachTheHubQuantized() {
        CarActorTestHarness h = new CarActorTestHarness();
        FakeSource s = new FakeSource();
        CanHub.register(h.actor(), s);
        h.tickNow();
        CarActor a = h.actor();
        assertEquals(1, ((Number) a.get(CanHub.LIVE).value).intValue());
        assertEquals(404.2f, CanHub.get(a, CanHub.PACK_V), 1e-4);
        assertEquals(-16.0f, CanHub.get(a, CanHub.PACK_A), 1e-4);
        assertEquals(-6.46f, CanHub.get(a, CanHub.POWER_KW), 1e-4);
        assertEquals(1.6f, CanHub.get(a, CanHub.CLIMATE_KW), 1e-4);
        assertEquals(6810f, CanHub.get(a, CanHub.CHARGER_W), 1e-4);
    }

    /** Jitter below one step is not a change, so EntityBus stays quiet. */
    @Test public void jitterDoesNotRepublish() {
        CarActorTestHarness h = new CarActorTestHarness();
        FakeSource s = new FakeSource();
        h.observeKey(CanHub.PACK_V);
        CanHub.register(h.actor(), s);
        h.tickNow();
        s.v = 404.21f;   // same 0.1 V step
        h.tickNow();
        s.v = 404.24f;
        h.tickNow();
        List<CarActor.Reading> got = h.getPublished(CanHub.PACK_V);
        assertEquals("published once, not on every jitter", 1, got.size());
        s.v = 405.0f;
        h.tickNow();
        assertEquals(2, h.getPublished(CanHub.PACK_V).size());
        h.stopObservingKey(CanHub.PACK_V);
    }

    @Test public void readerGoneMeansNotAvailable() {
        CarActorTestHarness h = new CarActorTestHarness();
        FakeSource s = new FakeSource();
        CanHub.register(h.actor(), s);
        h.tickNow();
        s.live = false; s.v = null; s.a = null; s.kw = null;
        h.tickNow();
        assertNull(CanHub.get(h.actor(), CanHub.POWER_KW));
        assertEquals(CarActor.Reading.Status.NOT_AVAILABLE, h.actor().get(CanHub.PACK_V).status);
        assertEquals(0, ((Number) h.actor().get(CanHub.LIVE).value).intValue());
    }

    @Test public void unregisterClearsEveryKey() {
        CarActorTestHarness h = new CarActorTestHarness();
        CanHub.register(h.actor(), new FakeSource());
        h.tickNow();
        CanHub.unregister(h.actor());
        h.tickNow();
        for (String k : CanHub.KEYS) {
            assertEquals(k, CarActor.Reading.Status.NOT_AVAILABLE, h.actor().get(k).status);
        }
    }

    @Test public void quantize() {
        assertEquals(404.2f, CanHub.quantize(404.23f, 0.1), 1e-4);
        assertEquals(6810f, CanHub.quantize(6812f, 10), 1e-4);
        assertNull(CanHub.quantize(null, 0.1));
        assertNull(CanHub.quantize(Float.NaN, 0.1));
    }
}
