package com.geely.modehelper;

/** Edits Android's enabled_notification_listeners list (Settings.Secure) for
 * one fixed component: Drive Assist's MediaAccessListener. Pure Java, so its
 * test runs without Android.
 *
 * The list is "pkg/cls:pkg/cls:...". Being on it is what lets an app read
 * the car's media sessions -- the Bluetooth music the phone sends -- via
 * MediaSessionManager.getActiveSessions(). The component is hard-coded on
 * purpose: MediaAccessReceiver is exported, and it must never add any other
 * app to the list (a listener there can read every notification). */
final class MediaAccessPolicy {
    static final String COMPONENT = "com.geely.drivemem/com.geely.drivemem.services.MediaAccessListener";

    private MediaAccessPolicy() {}

    /** The list with COMPONENT added (on) or removed (off); every other entry
     * kept, in order. Null when nothing changes. */
    static String edit(String current, boolean on) {
        String cur = current == null ? "" : current;
        StringBuilder out = new StringBuilder();
        boolean present = false;
        for (String e : cur.split(":")) {
            String t = e.trim();
            if (t.isEmpty()) continue;
            if (t.equals(COMPONENT)) { present = true; if (!on) continue; }
            if (out.length() > 0) out.append(':');
            out.append(t);
        }
        if (on && present) return null;
        if (!on && !present) return null;
        if (on) { if (out.length() > 0) out.append(':'); out.append(COMPONENT); }
        return out.toString();
    }
}
