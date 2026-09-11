package com.geely.drivemem.state;

import com.geely.drivemem.net.MqttReporter;
import com.geely.drivemem.ui.ComfortActivity;

/** Bridge for context panels from Home Assistant (MQTT). MqttReporter writes
 * current state; ComfortActivity listens and renders with crossfade. Same-process
 * static state allows immediate updates without broadcasts. */
public final class PanelState {
    public interface Listener { void onPanel(String json); }

    private static volatile String current;    // last payload (JSON) or null
    private static volatile Listener listener;

    /** Sets current panel JSON (called by MqttReporter). Notifies listener. */
    public static void set(String json) {
        current = json;
        Listener l = listener;
        if (l != null) l.onPanel(json);
    }

    public static String get() { return current; }
    public static void setListener(Listener l) { listener = l; }

    private PanelState() {}
}
