package com.geely.drivemem;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EntityBusTest {

    @Test public void subscribingTheSameListenerTwiceDeliversOnce() {
        String key = "test.entitybus.duplicate";
        AtomicInteger calls = new AtomicInteger();
        EntityBus.Listener listener = (changedKey, reading) -> calls.incrementAndGet();

        EntityBus.subscribe(key, listener);
        EntityBus.subscribe(key, listener);
        EntityBus.publish(key, CarActor.Reading.ok(1));
        awaitCalls(calls, 1);
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

    @Test public void publishesInOrderWithoutWaitingForListenerWork() throws Exception {
        String key = "test.entitybus.order";
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(2);
        List<Integer> values = Collections.synchronizedList(new ArrayList<>());
        EntityBus.Listener listener = (changedKey, reading) -> {
            int value = (Integer) reading.value;
            values.add(value);
            if (value == 1) {
                firstStarted.countDown();
                try { allowFirstToFinish.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            delivered.countDown();
        };

        EntityBus.subscribe(key, listener);
        EntityBus.publish(key, CarActor.Reading.ok(1));
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        EntityBus.publish(key, CarActor.Reading.ok(2));
        allowFirstToFinish.countDown();
        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        EntityBus.unsubscribe(key, listener);

        assertEquals(java.util.Arrays.asList(1, 2), values);
    }

    private static void awaitCalls(AtomicInteger calls, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (calls.get() < expected && System.nanoTime() < deadline) Thread.yield();
        assertEquals(expected, calls.get());
    }
}
