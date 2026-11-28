package com.geely.drivemem;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.support.FakeCarAccess;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class EntityBusTest {

    /** Single worker thread that plays CarActor's car thread, or null. */
    private ExecutorService worker;
    private Thread workerThread;
    private CarActor previousInstance;

    @Before public void rememberCarActor() {
        previousInstance = CarActor.get();
    }

    @After public void restoreCarActor() {
        CarActor.setInstanceForTesting(previousInstance);
        if (worker != null) worker.shutdownNow();
        worker = null;
        workerThread = null;
    }

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

    @Test public void publishFromOtherThreadRunsListenerOnCarThread() throws Exception {
        startCarActorOnWorker();
        String key = "test.entitybus.carthread.offthread";
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        EntityBus.Listener listener = (changedKey, reading) -> ranOn.set(Thread.currentThread());

        EntityBus.subscribe(key, listener);
        try {
            // Park the car thread. If publish hops, the listener cannot have run yet.
            CountDownLatch release = new CountDownLatch(1);
            worker.execute(() -> awaitQuietly(release));

            EntityBus.publish(key, CarActor.Reading.ok(1));
            assertNull("publish from the test thread must not run the listener inline", ranOn.get());

            release.countDown();
            worker.submit(() -> { }).get(5, TimeUnit.SECONDS);   // FIFO barrier: the hop has run
            assertSame(workerThread, ranOn.get());
        } finally {
            EntityBus.unsubscribe(key, listener);
        }
    }

    @Test public void publishOnCarThreadRunsListenerInline() throws Exception {
        startCarActorOnWorker();
        String key = "test.entitybus.carthread.inline";
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        EntityBus.Listener listener = (changedKey, reading) -> ranOn.set(Thread.currentThread());

        EntityBus.subscribe(key, listener);
        try {
            // Inline delivery means the listener has already run when publish returns.
            Thread seenAfterPublish = worker.submit(() -> {
                EntityBus.publish(key, CarActor.Reading.ok(2));
                return ranOn.get();
            }).get(5, TimeUnit.SECONDS);
            assertSame(workerThread, seenAfterPublish);
        } finally {
            EntityBus.unsubscribe(key, listener);
        }
    }

    @Test public void publishWithoutCarThreadRunsListenerInline() {
        CarActor.setInstanceForTesting(new CarActor(null));   // no handler: no car thread
        String key = "test.entitybus.carthread.none";
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        EntityBus.Listener listener = (changedKey, reading) -> ranOn.set(Thread.currentThread());

        EntityBus.subscribe(key, listener);
        try {
            EntityBus.publish(key, CarActor.Reading.ok(3));
            assertSame(Thread.currentThread(), ranOn.get());
        } finally {
            EntityBus.unsubscribe(key, listener);
        }
    }

    /** Builds a CarActor whose car thread is a real worker thread, and installs it.
     * CarActor's test handler type is package-private and Android's Handler
     * methods are final, so the handler is a dynamic proxy and the test
     * constructor is reached by reflection. Posts run on the worker thread;
     * delayed posts (tick rescheduling) are dropped, so only the first tick runs. */
    private CarActor startCarActorOnWorker() throws Exception {
        worker = Executors.newSingleThreadExecutor();
        workerThread = worker.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
        ExecutorService exec = worker;
        Thread carThread = workerThread;
        // The constructor posts tick() before it finishes registering polls, so
        // posted work waits until construction is done. Otherwise tick could
        // race the constructor on the worker thread.
        CountDownLatch constructed = new CountDownLatch(1);

        Class<?> handlerType = Class.forName("com.geely.drivemem.car.CarActor$HandlerLike");
        Object handler = Proxy.newProxyInstance(
            EntityBusTest.class.getClassLoader(),
            new Class<?>[] { handlerType },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "post":
                        exec.execute(() -> {
                            awaitQuietly(constructed);
                            ((Runnable) args[0]).run();
                        });
                        return null;
                    case "isCurrentThread": return Thread.currentThread() == carThread;
                    default: return null;   // getLooper, postDelayed, removeCallbacks
                }
            });

        Constructor<CarActor> ctor =
            CarActor.class.getDeclaredConstructor(Context.class, handlerType, CarAccess.class);
        ctor.setAccessible(true);
        CarActor actor = ctor.newInstance(null, handler, new FakeCarAccess());
        constructed.countDown();
        CarActor.setInstanceForTesting(actor);
        return actor;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
