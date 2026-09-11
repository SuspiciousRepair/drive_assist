package com.geely.drivemem.car;

import com.geely.drivemem.state.CarState;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.state.GateState;
import com.geely.drivemem.state.PanelState;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Multi-subscriber event bus for car state changes. CarActor publishes
 * updates via publish(); subscribers register listeners via subscribe().
 * Delivery is synchronous on the calling thread (always CarActor's thread).
 * Subscribers needing the UI thread repost themselves as needed. */
public final class EntityBus {

    /** Listener interface for entity state changes. */
    public interface Listener { void onChange(String key, CarActor.Reading reading); }

    private static final Map<String, List<Listener>> subs = new ConcurrentHashMap<>();

    /** Registers a listener for changes to a key. */
    public static void subscribe(String key, Listener l) {
        subs.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(l);
    }

    /** Removes a listener from a key. */
    public static void unsubscribe(String key, Listener l) {
        List<Listener> list = subs.get(key);
        if (list != null) list.remove(l);
    }

    /** Publishes an update to all subscribers of a key. */
    public static void publish(String key, CarActor.Reading reading) {
        List<Listener> list = subs.get(key);
        if (list == null) return;
        for (Listener l : list) {
            try { l.onChange(key, reading); }
            catch (Throwable t) { android.util.Log.w(CarAccess.TAG, "entitybus: " + key + ": " + t); }
        }
    }

    private EntityBus() {}
}
