package com.geely.drivemem.state;

/** Parked state, broadcast-only: TripSession.onGear() is the single source of
 * truth for parked state transitions; CarState acts solely as the PubSub relay.
 * Invariant: prevents multiple gear subscribers from drifting out of sync.
 * Gates cards that apply only when driving (Turbo) or parked (Charging).
 * See docs/incidents.md#2026-09-14-carstate-gear-desync
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

    /** Test-only: sets the parked flag directly, bypassing the "one caller"
     * rule above — reportParked() stays package-private and TripSession-only
     * in production; this exists only so tests outside the state package
     * (e.g. EnergyIntegratorTest) can simulate "driving" without wiring up a
     * real TripSession. Mirrors EnergyIntegrator.resetForTesting(). */
    public static void setParkedForTesting(boolean nowParked) { parked = nowParked; }

    private CarState() {}
}
