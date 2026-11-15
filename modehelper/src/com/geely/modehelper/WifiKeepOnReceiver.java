package com.geely.modehelper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Log;

// Keeps WiFi turned on across boot and suspend/resume cycles.
//
// Not event-driven, same reasoning as WifiGuardReceiver: Android blocks
// implicit broadcasts (like WIFI_STATE_CHANGED) from manifest-registered
// receivers on background apps (API 26+). A self-rescheduling alarm checks
// periodically instead — this also covers the head unit's own suspend/resume
// cycle (it suspends rather than shuts down), not just a cold BOOT_COMPLETED.
//
// Only while the user's "Keep Wi-Fi on" switch in Drive Assist is ON (issue
// #14). Drive Assist owns the switch and pushes it here with ACTION_SET on every
// watchdog tick; this keeps a copy because it runs on its own alarm. Until the
// first push arrives the copy reads ON, which is what every install did before
// the switch existed.
public class WifiKeepOnReceiver extends BroadcastReceiver {
    static final String TAG = "ModeHelper";
    static final String ACTION_CHECK = "com.geely.modehelper.WIFI_KEEPON_CHECK";
    public static final String ACTION_SET = "com.geely.modehelper.SET_WIFI_KEEPON";
    static final String PREF_KEEP_ON = "wifi_keep_on";
    static final long INTERVAL_MS = 2 * 60_000L;   // same cadence as WifiGuardReceiver
    private static final int ALARM_ID = 2003;

    @Override public void onReceive(Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        if (intent != null && ACTION_SET.equals(intent.getAction())) {
            boolean on = intent.getBooleanExtra("on", true);
            if (on != enabled(app)) {
                app.getSharedPreferences("modehelper", Context.MODE_PRIVATE)
                   .edit().putBoolean(PREF_KEEP_ON, on).apply();
                Log.i(TAG, "wifi-keepon: switch is now " + (on ? "ON" : "OFF"));
            }
            if (on) ensureOn(app);
            return;   // the alarm chain is already running; do not start a second one
        }
        try { ensureOn(app); }
        finally { scheduleNext(app); }
    }

    /** Runs one immediate check and starts the recurring one. Called once from BootReceiver. */
    static void start(Context ctx) {
        Context app = ctx.getApplicationContext();
        ensureOn(app);
        scheduleNext(app);
    }

    /** The user's "Keep Wi-Fi on" switch, as last pushed by Drive Assist. */
    static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences("modehelper", Context.MODE_PRIVATE)
                  .getBoolean(PREF_KEEP_ON, true);
    }

    static void ensureOn(Context ctx) {
        if (!enabled(ctx)) return;
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null && !wm.isWifiEnabled()) {
                wm.setWifiEnabled(true);
                Log.i(TAG, "wifi-keepon: WiFi was off, turned it back on");
            }
        } catch (Throwable t) { Log.w(TAG, "wifi-keepon: check failed: " + t); }
    }

    private static void scheduleNext(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            long at = SystemClock.elapsedRealtime() + INTERVAL_MS;
            PendingIntent pi = alarm(ctx);
            if (android.os.Build.VERSION.SDK_INT >= 23)
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            else
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        } catch (Throwable t) { Log.w(TAG, "wifi-keepon: could not schedule next check: " + t); }
    }

    private static PendingIntent alarm(Context ctx) {
        Intent i = new Intent(ctx, WifiKeepOnReceiver.class).setAction(ACTION_CHECK);
        return PendingIntent.getBroadcast(ctx, ALARM_ID, i,
            PendingIntent.FLAG_UPDATE_CURRENT
                | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }
}
