package com.geely.modehelper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/** Starts ModeHelperService and adb/WiFi guards at boot. Also runs a liveness
 * watchdog — mirrors drivemem's own BootReceiver (util/Beat.java +
 * scheduleWatchdog/ensureAll), which exists for the same reason here: this
 * head unit SUSPENDS instead of rebooting, so BOOT_COMPLETED can be days away,
 * and a killed ModeHelperService would otherwise never come back. That is
 * what let a valet-parking Comfort-mode drift go uncorrected — see
 * plan/MODEHELPER-WATCHDOG-ROADMAP.md.
 */
public class BootReceiver extends BroadcastReceiver {
    static final String WATCHDOG = "com.geely.modehelper.WATCHDOG";

    // Same cadence as drivemem's, for the same reason: setExactAndAllowWhileIdle
    // is limited to ~1 firing/9min under Doze on API 28, so anything shorter
    // would not actually fire more often.
    static final long WATCHDOG_MS = 10 * 60 * 1000L;

    // The poll loop ticks every 4s and marks a heartbeat every iteration
    // (ModeHelperService.pollLoop()). 60s is many missed ticks — generous
    // enough that a single slow car-binder call never trips this — but the
    // real detection latency is bounded by WATCHDOG_MS above regardless,
    // since that is the only cadence this gets checked on.
    static final long STALE_POLL_MS = 60 * 1000L;

    static final String PREFS = "modehelper";
    private static final long MIN_RESTART_GAP_MS = 30 * 60 * 1000L; // anti-loop, same as drivemem's Beat

    @Override public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();

        if (WATCHDOG.equals(a)) {
            // Reschedule first so the chain survives if the stale-check throws.
            scheduleWatchdog(ctx);
            try { ensureAlive(ctx); } catch (Throwable t) { Log.w(CarMode.TAG, "watchdog: " + t); }
            return;
        }

        Log.i(CarMode.TAG, "boot received: " + a + " - starting helper");
        try {
            Intent svc = new Intent(ctx, ModeHelperService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
        } catch (Throwable t) { Log.w(CarMode.TAG, "boot start failed: " + t); }

        // Arm (or re-arm) the watchdog. On a real boot this is the first arm;
        // on MY_PACKAGE_REPLACED (this app was just reinstalled) this re-attaches
        // it immediately rather than waiting for a boot that may not come for days.
        scheduleWatchdog(ctx);

        // Open adb for 5 minutes at boot (to survive reconnects after reboots without
        // user intervention). WifiGuardReceiver extends this to indefinite on the
        // trusted "car" network, independent of this window.
        try {
            AdbControl.set(ctx.getApplicationContext(), true, 5);
            Log.i(CarMode.TAG, "adb open for 5 min at boot (longer if on \"car\" WiFi)");
        } catch (Throwable t) { Log.w(CarMode.TAG, "boot adb open failed: " + t); }

        // Start WiFi guard polling.
        try { WifiGuardReceiver.start(ctx); }
        catch (Throwable t) { Log.w(CarMode.TAG, "wifiguard start failed: " + t); }

        // Keep WiFi turned on (own implementation — see WifiKeepOnReceiver's header).
        try { WifiKeepOnReceiver.start(ctx); }
        catch (Throwable t) { Log.w(CarMode.TAG, "wifi-keepon start failed: " + t); }
    }

    // ELAPSED_REALTIME_WAKEUP counts the suspended time, and an alarm that came
    // due during the suspension fires the moment the car wakes up — same
    // reasoning as drivemem's scheduleWatchdog().
    static void scheduleWatchdog(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(ctx, BootReceiver.class).setAction(WATCHDOG);
            PendingIntent pi = PendingIntent.getBroadcast(
                ctx, 2001, i, PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0));
            long next = SystemClock.elapsedRealtime() + WATCHDOG_MS;
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, pi);
            else am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, pi);
        } catch (Throwable t) { Log.w(CarMode.TAG, "watchdog schedule: " + t); }
    }

    // If ModeHelperService's poll loop has gone quiet for too long, tear down
    // the (possibly zombie) instance and start a fresh one. Rate-limited so a
    // service that dies right after coming up cannot turn into restart churn.
    private static void ensureAlive(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long beat = p.getLong("beat_poll", 0L);
        if (beat == 0L) return; // never started yet — not stuck, just new
        long now = System.currentTimeMillis();
        if (beat > now) { p.edit().putLong("beat_poll", now).apply(); return; } // clock went backwards
        if (now - beat <= STALE_POLL_MS) return; // alive

        long lastRestart = p.getLong("restart_poll", 0L);
        if (now - lastRestart < MIN_RESTART_GAP_MS) return; // rate-limited
        p.edit().putLong("restart_poll", now).remove("beat_poll").apply();

        Log.i(CarMode.TAG, "watchdog: ModeHelperService beat stale (" + (now - beat) + "ms) — restarting");
        try {
            Intent svc = new Intent(ctx, ModeHelperService.class);
            try { ctx.stopService(svc); } catch (Throwable ignored) {}
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
        } catch (Throwable t) { Log.w(CarMode.TAG, "watchdog restart: " + t); }
    }
}
