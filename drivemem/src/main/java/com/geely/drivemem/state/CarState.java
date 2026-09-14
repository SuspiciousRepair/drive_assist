package com.geely.drivemem.state;

/** Parked state, broadcast-only: TripSession is the one place car.gear gets
 * turned into "parked or not" — it already has to (grace periods, segment
 * stitching, its own test suite) — so this class does not read car.gear
 * itself any more. Two independent subscriptions to the same raw property,
 * each keeping its own latch, is exactly how they drifted apart on
 * 2026-09-14: a charge session outlived a trip start because this class's
 * old observe() only compared against ITS OWN last value, and never saw the
 * edge TripSession had already caught. Now there is one detector
 * (TripSession.onGear) and this is purely the PubSub relay other consumers
 * (Turbo, Charging, Valet, Gate) subscribe to or read — reportParked() is
 * called only from there, right at the edge, so the two can no longer
 * disagree about whether or when one happened.
 * Gates cards that apply only when driving (Turbo) or parked (Charging).
 * Parked defaults to true; becomes false on the first real report. */
public final class CarState {
    public interface Listener { void onParked(boolean parked); }
    private static final java.util.List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static void addListener(Listener l) { if (l != null && !listeners.contains(l)) listeners.add(l); }
    public static void removeListener(Listener l) { if (l != null) listeners.remove(l); }
    public static void setListener(Listener l) {
        listeners.clear();
        if (l != null) listeners.add(l);
    }

    // Default true (safer to hide Turbo until TripSession reports otherwise).
    private static volatile boolean parked = true;

    public static boolean isParked() { return parked; }

    /** Called by TripSession.onGear() only, exactly once per confirmed edge —
     * see the class comment for why nothing else should feed this. The
     * equality check is a defensive no-op backstop, not a second detector:
     * by the time this runs, TripSession has already decided an edge
     * happened. */
    static void reportParked(boolean nowParked) {
        if (nowParked != parked) {
            parked = nowParked;
            for (Listener l : listeners) {
                try { l.onParked(nowParked); } catch (Throwable ignored) {}
            }
        }
    }

    /** No-op kept only so existing call sites (ChargeSession, TelemetryService)
     * don't need to change — there is nothing left to subscribe to here now
     * that TripSession owns the car.gear read. */
    public static void ensureSubscribed() {}

    private CarState() {}
}
