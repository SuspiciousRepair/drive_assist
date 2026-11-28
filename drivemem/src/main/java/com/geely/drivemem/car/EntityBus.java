package com.geely.drivemem.car;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Multi-subscriber event bus for car state changes. CarActor publishes
 * updates via publish(); subscribers register listeners via subscribe().
 * Delivery runs on CarActor's thread: a publish made from any other thread
 * is posted there first. With no CarActor thread (unit tests), delivery is
 * inline on the caller. Subscribers needing the UI thread repost themselves. */
public final class EntityBus {

    /** Listener interface for entity state changes. */
    public interface Listener { void onChange(String key, CarActor.Reading reading); }

    private static final Map<String, CopyOnWriteArrayList<Listener>> subs = new ConcurrentHashMap<>();

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

    /** Publishes an update to all subscribers of a key. A publish from a thread
     * other than CarActor's is posted to CarActor's thread and delivered there,
     * so listeners never run on the caller's thread while a car thread exists. */
    public static void publish(String key, CarActor.Reading reading) {
        CarActor actor = CarActor.get();
        if (actor != null && !actor.isOnCarThread()) {
            actor.runOnCarThread(() -> publish(key, reading));
            return;
        }
        CopyOnWriteArrayList<Listener> list = subs.get(key);
        if (list == null) return;
        for (Listener l : list) {
            try { l.onChange(key, reading); }
            catch (Throwable t) { android.util.Log.w(CarAccess.TAG, "entitybus: " + key + ": " + t); }
        }
    }

    private EntityBus() {}
}
