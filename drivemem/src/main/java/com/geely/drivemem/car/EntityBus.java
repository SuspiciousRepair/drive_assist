package com.geely.drivemem.car;

import com.geely.drivemem.state.CarState;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.state.GateState;
import com.geely.drivemem.state.PanelState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Multi-subscriber event bus for car state changes. CarActor publishes
 * updates via publish(); subscribers register listeners via subscribe().
 * Publishing only enqueues work: listener code never runs on CarActor's
 * single VHAL thread, so a slow database/UI/network listener cannot delay
 * car polling or a driver command. One FIFO dispatcher preserves publication
 * order for the state machines that consume related events. Subscribers still
 * repost to the UI thread when they touch Views. */
public final class EntityBus {

    /** Listener interface for entity state changes. */
    public interface Listener { void onChange(String key, CarActor.Reading reading); }

    private static final Map<String, CopyOnWriteArrayList<Listener>> subs = new ConcurrentHashMap<>();
    private static final ExecutorService dispatcher = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "entity-bus");
            t.setDaemon(true);
            return t;
        }
    });

    /** Registers a listener for changes to a key.
     *
     * Registration is idempotent per key/listener pair. Most clients already
     * pair their lifecycle subscribe and unsubscribe calls, but the bus is the
     * one shared boundary that can prevent an accidental double start from
     * fanning every later car update into duplicate UI, network, or storage
     * work. */
    public static void subscribe(String key, Listener l) {
        if (key == null || l == null) return;
        subs.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).addIfAbsent(l);
    }

    /** Removes a listener from a key. */
    public static void unsubscribe(String key, Listener l) {
        if (key == null || l == null) return;
        CopyOnWriteArrayList<Listener> list = subs.get(key);
        if (list != null) list.remove(l);
    }

    /** Enqueues an update to all subscribers of a key and returns immediately.
     * Listener exceptions are contained so one faulty subscriber cannot stop
     * later deliveries. */
    public static void publish(String key, CarActor.Reading reading) {
        CopyOnWriteArrayList<Listener> list = subs.get(key);
        if (list == null) return;
        dispatcher.execute(() -> {
            for (Listener l : list) {
                try { l.onChange(key, reading); }
                catch (Throwable t) { android.util.Log.w(CarAccess.TAG, "entitybus: " + key + ": " + t); }
            }
        });
    }

    private EntityBus() {}
}
