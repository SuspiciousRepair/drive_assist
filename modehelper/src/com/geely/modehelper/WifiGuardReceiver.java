package com.geely.modehelper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

// While connected to the trusted home network (SSID "car"), adb stays open
// indefinitely and a partial wakelock prevents suspension. When the network
// changes or is lost, both are immediately torn down. This is a dev convenience
// for safe testing at home, with the same tight closure as timer-based windows.
//
// Not event-driven: Android blocks implicit broadcasts (like WIFI_STATE_CHANGE)
// from manifest-registered receivers on background apps (API 26+). Instead, a
// self-rescheduling alarm checks every 2 minutes, using the same
// ELAPSED_REALTIME_WAKEUP pattern and suspend-aware logic as AdbControl's timer.
public class WifiGuardReceiver extends BroadcastReceiver {
    static final String TAG = "ModeHelper";
    static final String PREF_TRUSTED_SSID = "trusted_ssid";
    static final String DEFAULT_TRUSTED_SSID = "car";
    public static final String ACTION_SET_TRUSTED = "com.geely.modehelper.SET_TRUSTED_WIFI";
    static final String ACTION_CHECK = "com.geely.modehelper.WIFI_GUARD_CHECK";
    static final long INTERVAL_MS = 2 * 60_000L;   // 2 min: responsive yet inexpensive to poll
    private static final int ALARM_ID = 2002;

    private static volatile boolean active = false;
    private static PowerManager.WakeLock wakeLock;

    @Override public void onReceive(Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_SET_TRUSTED.equals(action)) {
            String ssid = intent.getStringExtra("ssid");
            if (ssid != null) ssid = ssid.trim();
            app.getSharedPreferences("modehelper", Context.MODE_PRIVATE)
               .edit().putString(PREF_TRUSTED_SSID, ssid != null ? ssid : "").apply();
            Log.i(TAG, "wifiguard: trusted SSID updated to \"" + (ssid != null ? ssid : "") + "\"");
            boolean onTrusted = isOnTrustedWifi(app);
            if (onTrusted) activate(app);
            else if (active) deactivate(app);
            return;
        }

        try {
            boolean onTrusted = isOnTrustedWifi(app);
            if (onTrusted) activate(app);           // every tick — reasserts indefinite, see activate()'s comment
            else if (active) deactivate(app);
        } finally {
            scheduleNext(app);   // always reschedule, even if the check above threw
        }
    }

    /** Starts the WiFi guard check loop. Called once from BootReceiver. */
    static void start(Context ctx) { scheduleNext(ctx.getApplicationContext()); }

    private static void scheduleNext(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            long at = SystemClock.elapsedRealtime() + INTERVAL_MS;
            PendingIntent pi = alarm(ctx);
            if (android.os.Build.VERSION.SDK_INT >= 23)
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            else
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        } catch (Throwable t) { Log.w(TAG, "wifiguard: could not schedule next check: " + t); }
    }

    private static PendingIntent alarm(Context ctx) {
        Intent i = new Intent(ctx, WifiGuardReceiver.class).setAction(ACTION_CHECK);
        return PendingIntent.getBroadcast(ctx, ALARM_ID, i,
            PendingIntent.FLAG_UPDATE_CURRENT
                | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    public static String getTrustedSsid(Context ctx) {
        return ctx.getSharedPreferences("modehelper", Context.MODE_PRIVATE)
                  .getString(PREF_TRUSTED_SSID, DEFAULT_TRUSTED_SSID);
    }

    private static boolean isOnTrustedWifi(Context ctx) {
        String want = getTrustedSsid(ctx);
        if (want == null || want.isEmpty()) return false;
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return false;
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            if (info == null) return false;
            String ssid = info.getSSID();
            if (ssid == null) return false;
            ssid = ssid.replace("\"", "");   // getSSID() quotes a real SSID
            return want.equals(ssid);
        } catch (Throwable t) {
            Log.w(TAG, "wifiguard: ssid check: " + t);
            return false;
        }
    }

    /** Activates indefinite adb and wakelock while on trusted WiFi. Must be called
     * on every tick (not just the first), because other adb operations reset the
     * timer; skipping reassertion would allow the timer to expire mid-session despite
     * being on the trusted network. Calling is a cheap no-op when already active. */
    private static synchronized void activate(Context ctx) {
        boolean wasActive = active;
        active = true;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "modehelper:home-wifi");
            }
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        } catch (Throwable t) { Log.w(TAG, "wifiguard: wakelock acquire: " + t); }
        AdbControl.setIndefinite(ctx, true);
        if (!wasActive)
            Log.i(TAG, "wifiguard: on trusted WiFi (\"" + getTrustedSsid(ctx) + "\") — adb + wakelock indefinite");
    }

    /** Disables indefinite adb and releases the wakelock. */
    private static synchronized void deactivate(Context ctx) {
        if (!active) return;
        active = false;
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        catch (Throwable t) { Log.w(TAG, "wifiguard: wakelock release: " + t); }
        AdbControl.setIndefinite(ctx, false);
        Log.i(TAG, "wifiguard: left trusted WiFi — adb + wakelock released");
    }
}
