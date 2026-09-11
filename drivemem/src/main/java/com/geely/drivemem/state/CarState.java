package com.geely.drivemem.state;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.services.TelemetryService;
import com.geely.drivemem.util.Modes;

/** Parked state derived from instant car.gear updates (not 15s telemetry).
 * Gates cards that apply only when driving (Turbo) or parked (Charging).
 * Parked defaults to true; becomes false on real gear change. */
public final class CarState {
    public interface Listener { void onParked(boolean parked); }
    private static final java.util.List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static void addListener(Listener l) { if (l != null && !listeners.contains(l)) listeners.add(l); }
    public static void removeListener(Listener l) { if (l != null) listeners.remove(l); }
    public static void setListener(Listener l) {
        listeners.clear();
        if (l != null) listeners.add(l);
    }

    // Default true (safer to hide Turbo until real gear state is known).
    private static volatile boolean parked = true;

    public static boolean isParked() { return parked; }

    private static void observe(int gear) {
        boolean nowParked = gear == Modes.GEAR_PARK_ADAPTED;
        if (nowParked != parked) {
            parked = nowParked;
            for (Listener l : listeners) {
                try { l.onParked(nowParked); } catch (Throwable ignored) {}
            }
        }
    }

    private static volatile boolean subscribed = false;

    /** Subscribes to gear changes; idempotent. */
    public static synchronized void ensureSubscribed() {
        if (subscribed) return;
        subscribed = true;
        EntityBus.subscribe("car.gear", (key, reading) -> {
            if (reading.status == CarActor.Reading.Status.OK && reading.value instanceof Integer)
                observe((Integer) reading.value);
        });
    }

    private CarState() {}
}
