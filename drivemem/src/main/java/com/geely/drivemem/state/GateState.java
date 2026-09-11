package com.geely.drivemem.state;

import com.geely.drivemem.net.MqttReporter;
import com.geely.drivemem.services.TelemetryService;
import com.geely.drivemem.ui.ComfortActivity;

/** Gate control state, bridging Home Assistant and the UI.
 * HA owns proximity/availability decisions; the UI shows a button and sends
 * toggle commands. Mirrors PanelState (MQTT -> UI) with added UI -> MQTT direction.
 * Service and UI share one process, so static state is valid both ways. */
public final class GateState {

    // ---- MQTT -> UI: is the gate reachable, and what is it doing? ----
    public interface Listener { void onGate(boolean available, String state); }

    private static volatile boolean available;
    // open | closed | opening | closing | null when HA has not said yet.
    // Kept as the raw cover state rather than a boolean: "opening" and "closing"
    // are the two moments a driver most wants to see, and a boolean would have to
    // round them to one of the ends.
    private static volatile String state;
    private static volatile Listener listener;

    public static void setAvailable(boolean a) {
        available = a;
        fire();
    }

    public static void setState(String s) {
        state = (s == null || s.isEmpty()) ? null : s.trim().toLowerCase(java.util.Locale.US);
        fire();
    }

    private static void fire() {
        Listener l = listener;
        if (l != null) l.onGate(available, state);
    }

    public static boolean available() { return available; }
    public static String state() { return state; }
    public static void setListener(Listener l) { listener = l; }

    // ---- MQTT -> UI: does THIS car have a live broker link right now? ----
    // Separate from `available` on purpose. `available` is HA's call — per the
    // comment above, HA already owns the zone logic, so it is expected to only
    // publish "online" while this car's device_tracker is in the home zone.
    // That answers "should the Portão card be showing at all". This answers a
    // narrower question — "do we currently have a wire to send a command
    // through" — so a Wi-Fi hiccup greys the button instead of hiding the whole
    // card (which would otherwise flicker every time the LAN blips).
    private static volatile boolean connected;

    public static void setConnected(boolean c) { connected = c; fire(); }
    public static boolean connected() { return connected; }

    // ---- UI -> MQTT: single toggle verb ----
    // Gate motor has one toggle line (open -> stop -> close -> stop -> open).
    // Display state (Abrir/Fechar) is determined by reading current state,
    // separate from what the button sends. The Sender interface prevents direct
    // access to pub() which must only run on the actor thread.
    public interface Sender { void pressGate(); }

    private static volatile Sender sender;

    public static void setSender(Sender s) { sender = s; }

    // Compare-and-clear protects against race if close/buildReporter order changes.
    public static void clearSender(Sender s) { if (sender == s) sender = null; }

    /** @return false when there is no MQTT link to ask through. */
    public static boolean press() {
        Sender s = sender;
        if (s == null) return false;
        s.pressGate();
        return true;
    }

    private GateState() {}
}
