package com.geely.drivemem.util;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.services.TelemetryService;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/** Liveness heartbeat for background services.
 *
 * Services mark a heartbeat on every loop iteration. The watchdog detects stuck
 * services (e.g., blocked in VHAL binder calls) by comparing heartbeat timestamps.
 * Kept in SharedPreferences so it persists across process death/restart.
 */
public final class Beat {
    public static final String TELE = "tele";
    public static final String OUTTEMP = "outtemp";
    public static final String WIFIICON = "wifiicon";
    public static final String SOCICON = "socicon";
    public static final String MQTT = "mqtt";

    private static final String PREFS = "drivemem";
    private static final long MIN_RESTART_GAP_MS = 30 * 60 * 1000L; // anti-loop

    private Beat() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Records a heartbeat for a service. Called at the end of each loop iteration. */
    public static void mark(Context c, String who) {
        try {
            prefs(c).edit().putLong("beat_" + who, System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {}
    }

    // true if the service looks stuck: a beat exists, and it is older than
    // staleMs. No beat at all (first run after an update) does NOT count as
    // stuck — otherwise the watchdog would restart a service that just came up.
    static boolean isStale(Context c, String who, long staleMs) {
        try {
            SharedPreferences p = prefs(c);
            long beat = p.getLong("beat_" + who, 0L);
            if (beat == 0L) return false;
            long now = System.currentTimeMillis();
            // the clock went backwards (NTP/GPS synced): re-anchor and wait
            if (beat > now) { p.edit().putLong("beat_" + who, now).apply(); return false; }
            return (now - beat) > staleMs;
        } catch (Throwable t) { return false; }
    }

    // Rate-limits restarts: without this, a service that dies right after coming
    // up would turn into endless churn (with the foreground notification
    // flickering).
    static boolean mayRestart(Context c, String who) {
        try {
            SharedPreferences p = prefs(c);
            long last = p.getLong("restart_" + who, 0L);
            long now = System.currentTimeMillis();
            if (last > now) { p.edit().putLong("restart_" + who, now).apply(); return false; }
            if (now - last < MIN_RESTART_GAP_MS) return false;
            p.edit().putLong("restart_" + who, now).apply();
            return true;
        } catch (Throwable t) { return false; }
    }

    // forget the beat when stopping on purpose, so it does not trigger a restart
    // later
    public static void clear(Context c, String who) {
        try { prefs(c).edit().remove("beat_" + who).apply(); } catch (Throwable ignored) {}
    }

    static void log(String msg) { Log.i(CarAccess.TAG, "beat: " + msg); }
}
