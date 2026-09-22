package com.geely.drivemem;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class EntityBusTest {

    @Test public void subscribingTheSameListenerTwiceDeliversOnce() {
        String key = "test.entitybus.duplicate";
        AtomicInteger calls = new AtomicInteger();
        EntityBus.Listener listener = (changedKey, reading) -> calls.incrementAndGet();

        EntityBus.subscribe(key, listener);
        EntityBus.subscribe(key, listener);
        EntityBus.publish(key, CarActor.Reading.ok(1));
        EntityBus.unsubscribe(key, listener);

        assertEquals(1, calls.get());
    }

    @Test public void unsubscribeStopsDelivery() {
        String key = "test.entitybus.unsubscribe";
        AtomicInteger calls = new AtomicInteger();
        EntityBus.Listener listener = (changedKey, reading) -> calls.incrementAndGet();

        EntityBus.subscribe(key, listener);
        EntityBus.unsubscribe(key, listener);
        EntityBus.publish(key, CarActor.Reading.ok(1));

        assertEquals(0, calls.get());
    }
}
