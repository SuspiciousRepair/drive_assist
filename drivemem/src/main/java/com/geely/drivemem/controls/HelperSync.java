package com.geely.drivemem.controls;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.net.Updater;
import com.geely.drivemem.util.Prefs;

/** Talks to modehelper, which remembers drive/regen state across restarts and
 * re-applies explicit Drive Assist AEB/AVAS choices (issues #12, #13, #201).
 *
 * {@link #push} syncs only non-mode runtime toggles (CAN energy) — safe on
 * every watchdog tick. It deliberately does NOT push saved AEB/AVAS values:
 * older versions copied the car's AVAS state into "avas_on", so that key held
 * values nobody chose (review #2). Explicit choices reach modehelper on user
 * actions ({@link #setDrive}, {@link #setRegen}, {@link #applyNow},
 * {@link #toggleAvas}), and temporary boosts pause auto-learning via
 * {@link #holdDrive} and {@link #holdRegen}. */
public final class HelperSync {
    static final String SET_MODE = "com.geely.modehelper.SET_MODE";

    private HelperSync() {}

    public static void push(Context ctx) {
        Intent i = base();
        // "Exact energy": modehelper starts roadcastd. Always on; synced on
        // every watchdog tick, so a helper reinstalled later still learns it.
        i.putExtra(Prefs.KEY_CAN_ENERGY, true);
        send(ctx, i);
    }

    /** User picked a drive mode in Drive Assist: save it in modehelper at once
     * and release any active Turbo hold. */
    public static void setDrive(Context ctx, int mode) {
        send(ctx, base().putExtra("drive", mode));
    }

    /** User picked a regen level in Drive Assist (Config or Regen cycle button):
     * save it in modehelper at once and release any Strong-regen boost hold. */
    public static void setRegen(Context ctx, int level) {
        send(ctx, base().putExtra("regen", level));
    }

    /** Pauses drive-mode auto-learning while a timed Turbo boost is active
     * (durationMs > 0), or releases the pause immediately (durationMs <= 0). */
    public static void holdDrive(Context ctx, long durationMs) {
        send(ctx, base().putExtra("drive_hold_ms", Math.max(0L, durationMs)));
    }

    /** Pauses regen auto-learning while a Strong-regen toggle boost is active. */
    public static void holdRegen(Context ctx, boolean hold) {
        send(ctx, base().putExtra("regen_hold", hold));
    }

    /** User tapped the AEB or AVAS switch: modehelper writes it to the car now
     * and keeps it across restarts. Drive Assist keeps no copy: its old
     * "aeb_on"/"avas_on" went stale whenever the setting changed anywhere
     * else, and the switches drew from it. */
    public static void applyNow(Context ctx, String key, boolean on) {
        Intent i = base().putExtra("apply_now", true);
        if ("avas".equals(key)) i.putExtra("avas", on ? 1 : 0); else i.putExtra("aeb", on);
        send(ctx, i);
    }

    /** Home-screen AVAS button: modehelper flips what the car has now and
     * saves it as the AVAS choice. Drive Assist cannot read AVAS, so it does
     * not pick the target itself (see AvasState). */
    public static void toggleAvas(Context ctx) {
        send(ctx, base().putExtra("avas_toggle", true));
    }

    /** Asks modehelper to report the car's AVAS mode now (AvasState). */
    public static void queryAvas(Context ctx) {
        send(ctx, base().putExtra("avas_query", true));
    }

    /** After a restore: gives modehelper the restored settings it keeps its
     * own copy of. Only keys the backup held, so nothing is invented. The
     * switches that {@link #push}, WifiKeep and AdbGate re-send on every
     * watchdog tick need nothing here. AEB/AVAS are left out for the same
     * reason push leaves them out (values nobody chose, review #2). */
    public static void pushRestored(Context ctx, java.util.Map<String, Object> s) {
        Intent i = base();
        boolean any = false;
        for (String k : new String[]{"drive", "regen", "dashcam_limit_gb"}) {
            Object v = s.get(k);
            if (v instanceof Integer) { i.putExtra(k, (int) (Integer) v); any = true; }
        }
        if (any) send(ctx, i);
        Object parked = s.get("parked_monitoring");
        if (parked instanceof Boolean) {
            send(ctx, new Intent("com.geely.modehelper.PARKED_MONITORING")
                .setClassName(Updater.HELPER_PKG, "com.geely.modehelper.ParkedMonitoringReceiver")
                .putExtra("on", (Boolean) parked ? 1 : 0));
        }
        Object dash = s.get(Prefs.KEY_DASHCAM_ON);
        if (dash instanceof Boolean) {
            send(ctx, new Intent("com.geely.modehelper.DASHCAM")
                .setClassName(Updater.HELPER_PKG, "com.geely.modehelper.DashReceiver")
                .putExtra("on", (Boolean) dash ? 1 : 0));
        }
    }

    private static Intent base() { return new Intent(SET_MODE).setPackage(Updater.HELPER_PKG); }

    private static void send(Context ctx, Intent i) {
        try { ctx.sendBroadcast(i); }
        catch (Throwable t) { Log.w(CarAccess.TAG, "helper sync: " + t); }
    }
}
