package com.geely.drivemem.car;

import com.geely.drivemem.support.FakeCarAccess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic test harness for CarActor. Enables controlled simulation of
 * vehicle property polling sequences with manual time progression, eliminating
 * races and real delays.
 *
 * Usage:
 * <pre>
 *   CarActorTestHarness h = new CarActorTestHarness();
 *   h.car().seedIntRaw(605291008, 0, 12);  // charge_a = 12A
 *   h.tickNow();  // Trigger immediate poll
 *   assertEquals(1, (Integer) h.actor().get("car.is_charging").value);
 * </pre>
 */
public class CarActorTestHarness {
    private final FakeCarAccess fakeCarAccess;
    private final TestHandler testHandler;
    private final CarActor actor;
    private final Map<String, List<CarActor.Reading>> published = new HashMap<>();
    private EntityBus.Listener captureListener;

    public CarActorTestHarness() {
        this.fakeCarAccess = new FakeCarAccess();
        this.testHandler = new TestHandler();
        this.actor = new CarActor(null, testHandler, fakeCarAccess);

        // Capture everything published on EntityBus for assertions.
        this.captureListener = (key, reading) -> {
            published.computeIfAbsent(key, k -> new ArrayList<>()).add(reading);
        };
    }

    /** Returns the underlying FakeCarAccess for property setup. */
    public FakeCarAccess car() { return fakeCarAccess; }

    /** Returns the CarActor instance for assertions. */
    public CarActor actor() { return actor; }

    /** Seed a raw int property as if the car reported it. */
    public void seedProperty(int prop, int area, int value) {
        fakeCarAccess.seedIntRaw(prop, area, value);
    }

    /** Subscribe to capture all changes published on a key. */
    public void observeKey(String key) {
        EntityBus.subscribe(key, captureListener);
    }

    /** Unsubscribe from observing a key. */
    public void stopObservingKey(String key) {
        EntityBus.unsubscribe(key, captureListener);
    }

    /** Runs one real tick, forcing every poll to be due first. tick() itself
     * reschedules its own next run via postDelayed(), which TestHandler
     * queues into `delayed`, not `pending` -- so draining `pending` alone
     * only ever runs the FIRST tick queued by CarActor's constructor; every
     * later call would silently do nothing and callers would see stale
     * state. Calling tickNowForTesting() directly makes each call a real,
     * independent tick regardless of what's sitting in either queue. */
    public void tickNow() {
        actor.resetTimingForTesting();
        testHandler.runAllPending();
        actor.tickNowForTesting();
        testHandler.runAllPending();
    }

    /** Advance "wall-clock time" by delta and trigger polls that are due. */
    public void advanceTime(long deltaMs) {
        testHandler.advanceElapsedTime(deltaMs);
        actor.resetTimingForTesting();
        testHandler.runAllPending();
        actor.tickNowForTesting();
        testHandler.runAllPending();
    }

    /** Returns all readings published to a key in order. Empty if never published. */
    public List<CarActor.Reading> getPublished(String key) {
        return published.getOrDefault(key, new ArrayList<>());
    }

    /** Reset all captured publications. */
    public void clearPublished() {
        published.clear();
    }

    /** Manually call tick() once with current elapsed time. */
    public void tick() {
        tickNow();
    }

    /**
     * Synchronous HandlerLike implementation for testing. Runs posted runnables
     * immediately or after manual time advancement. Allows deterministic test
     * scenarios without real delays or threading.
     */
    static class TestHandler implements CarActor.HandlerLike {
        private long elapsedMs = 0;
        private final List<Runnable> pending = new ArrayList<>();
        private final List<DelayedRunnable> delayed = new ArrayList<>();

        public void post(Runnable action) {
            pending.add(action);
        }

        public void postDelayed(Runnable action, long delayMillis) {
            delayed.add(new DelayedRunnable(elapsedMs + delayMillis, action));
        }

        public void removeCallbacks(Runnable action) {
            pending.remove(action);
            delayed.removeIf(dr -> dr.runnable == action);
        }

        // No real thread backs this handler -- null tells assertCarThread()
        // there's nothing to check against, same as it already treats any
        // other null looper.
        public android.os.Looper getLooper() { return null; }

        void advanceElapsedTime(long deltaMs) {
            elapsedMs += deltaMs;
        }

        void runAllPending() {
            // Run immediate posts first.
            while (!pending.isEmpty()) {
                Runnable r = pending.remove(0);
                r.run();
            }

            // Run any delayed tasks that are now due.
            List<DelayedRunnable> now = new ArrayList<>();
            delayed.stream()
                .filter(dr -> dr.atMs <= elapsedMs)
                .forEach(now::add);
            delayed.removeAll(now);
            for (DelayedRunnable dr : now) {
                dr.runnable.run();
            }
        }

        private static class DelayedRunnable {
            final long atMs;
            final Runnable runnable;
            DelayedRunnable(long atMs, Runnable r) { this.atMs = atMs; this.runnable = r; }
        }
    }
}
