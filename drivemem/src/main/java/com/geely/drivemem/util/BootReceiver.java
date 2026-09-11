package com.geely.drivemem.util;

import com.geely.drivemem.art.VaporArtView;
import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.services.OutTempService;
import com.geely.drivemem.services.SocIconService;
import com.geely.drivemem.services.TelemetryService;
import com.geely.drivemem.services.WifiIconService;
import com.geely.drivemem.ui.ComfortActivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

// TODO: before boot or reinstall, should send out all telemetry/ABRP/MQTT messages that are still in the queue, so that the car's state is not lost. The actor will do that, but it is not running yet at this point. The only way to do it here is to start the actor service and let it run for a few seconds before the system kills it. That is a bit of a hack, but it would be better than losing the messages.
/** Handles boot, app replacement, watchdog, and debug actions via broadcasts.
 *
 * Applies saved modes inside onReceive using goAsync() to avoid blocking
 * the main thread (Android 9 does not allow Services to start from background receivers).
 */
public class BootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        // Watchdog tick: revalidate Wi-Fi and services, then reschedule.
        if ("com.geely.drivemem.WATCHDOG".equals(a)) {
            Log.i(CarAccess.TAG, "watchdog TICK");
            // Reschedule first so the chain survives if ensureAll throws.
            scheduleWatchdog(ctx);
            try { ensureAll(ctx); } catch (Throwable t) { Log.w(CarAccess.TAG, "watchdog: " + t); }
            return;
        }

        // App replacement: reinstall cancels the watchdog's PendingIntent, but since
        // this unit suspends (not shuts down), BOOT_COMPLETED may be delayed days.
        // Reattach the watchdog immediately to prevent telemetry from going silent.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Log.i(CarAccess.TAG, "apk updated — reattaching watchdog and services");
            scheduleWatchdog(ctx);
            try { ensureAll(ctx); } catch (Throwable t) { Log.w(CarAccess.TAG, "replaced: " + t); }
            return;
        }

        // Car-property-probing debug actions (CHARGE, HVAC, DISCOVER, FANTEST,
        // READPROP, READGENERIC, WRAPREAD, WRITEPROP, PROPLIST, AUDIOPROBE,
        // HUBTEST, CONFIGCHECK) live in Diagnostics.java now, not here — this
        // receiver stays the one every adb command/script targets by name, it
        // just hands off anything in Diagnostics.ACTIONS.
        if (Diagnostics.ACTIONS.contains(a)) {
            final PendingResult prg = goAsync();
            Diagnostics.handle(ctx, intent, prg::finish);
            return;
        }

        // DEBUG: put the panel into a theme from adb.
        //   --es id noturno
        // Noturno is transient BY DESIGN — it dies with the process — so every
        // `adb install` drops the panel back to the saved theme. That makes the
        // art impossible to iterate on over the cable without a human re-entering
        // the Konami code after each build, which is how a whole round of changes
        // got shipped and screenshotted without once being seen.
        // CLEAR_TASK rather than a reference to the running Activity: onCreate
        // has to run again for Style.load to pick the transient id up.
        if ("com.geely.drivemem.THEME".equals(a)) {
            String id = intent.getStringExtra("id");
            if (id == null) id = "noturno";
            if ("noturno".equals(id)) VaporArtView.playIntro();
            Style.setTransient(ctx, id);
            try {
                ctx.startActivity(new Intent(ctx, ComfortActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            } catch (Throwable t) { Log.w(CarAccess.TAG, "theme: " + t); }
            return;
        }

        // debug command: turn services on without touching the screen (via adb broadcast)
        if ("com.geely.drivemem.WIFIICON_ON".equals(a)) {
            ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE).edit().putBoolean("wifiicon_on", true).apply();
            Intent svc = new Intent(ctx, WifiIconService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc); else ctx.startService(svc);
            return;
        }
        if ("com.geely.drivemem.OUTTEMP_ON".equals(a)) {
            ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE).edit().putBoolean("outtemp_on", true).apply();
            Intent svc = new Intent(ctx, OutTempService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc); else ctx.startService(svc);
            return;
        }
        if ("com.geely.drivemem.SOCICON_ON".equals(a)) {
            ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE).edit().putBoolean("soc_on", true).apply();
            Intent svc = new Intent(ctx, SocIconService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc); else ctx.startService(svc);
            return;
        }
        // The head unit SUSPENDS instead of shutting down (uptime does not reset),
        // so BOOT_COMPLETED may NOT fire on a normal start.
        //
        // CAREFUL: the isWake branch below does NOT cover that, however much it
        // looks like it does. SCREEN_ON is NEVER delivered to a manifest receiver
        // (only to one registered at runtime, by platform decision) and it is not
        // even declared in our intent-filter — that is, it is DEAD CODE. What
        // really catches the car waking up is the watchdog ALARM:
        // ELAPSED_REALTIME_WAKEUP counts the suspended time, and an alarm that
        // came due during the suspension fires the moment the car wakes. That is
        // why what matters is the alarm EXISTING — see MY_PACKAGE_REPLACED up
        // top and the ensureAll in ComfortActivity's onResume.
        boolean isBoot = Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a);
        boolean isWake = Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_USER_PRESENT.equals(a);
        if (!isBoot && !isWake) return;

        // safety net: a periodic alarm that revalidates everything, because the
        // head unit SUSPENDS (no boot) and services can be killed during sleep.
        scheduleWatchdog(ctx);

        // turns the Wi-Fi back on (the car does not reconnect by itself after shutdown)
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && !wm.isWifiEnabled()) {
                Log.i(CarAccess.TAG, "boot: turning Wi-Fi on -> " + wm.setWifiEnabled(true));
            }
        } catch (Throwable t) { Log.w(CarAccess.TAG, "boot wifi failed: " + t); }

        // telemetry: restarted on boot if enabled (foreground service, allowed)
        try {
            if (ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE)
                    .getBoolean("tele_enabled", false)) {
                Intent svc = new Intent(ctx, TelemetryService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
                else ctx.startService(svc);
            }
        } catch (Throwable t) { Log.w(CarAccess.TAG, "boot tele failed: " + t); }

        // temperature in the status bar: also restarted on boot if it was on
        try {
            if (ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE)
                    .getBoolean("outtemp_on", false)) {
                Intent svc = new Intent(ctx, OutTempService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
                else ctx.startService(svc);
                Log.i(CarAccess.TAG, "boot: bringing temp in the status bar back up");
            }
        } catch (Throwable t) { Log.w(CarAccess.TAG, "boot outtemp failed: " + t); }

        // battery % in the status bar: also restarted on boot if it was on
        try {
            if (ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE)
                    .getBoolean("soc_on", false)) {
                Intent svc = new Intent(ctx, SocIconService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
                else ctx.startService(svc);
                Log.i(CarAccess.TAG, "boot: bringing SoC in the status bar back up");
            }
        } catch (Throwable t) { Log.w(CarAccess.TAG, "boot soc failed: " + t); }
        // mode memory moved out of here: com.geely.modehelper (a system app) is
        // what takes care of that now.
    }

    // Watchdog interval. 10 min is a middle ground: fast enough to bring Wi-Fi
    // and the services back shortly after you get into the car, and spaced out
    // enough not to drain the 12V battery with the car parked for days.
    // (API 28 also limits setExactAndAllowWhileIdle to ~1 firing/9 min under
    // Doze, so asking for less than that would achieve nothing.)
    static final long WATCHDOG_MS = 10 * 60 * 1000L;

    // Schedules the next tick. ELAPSED_REALTIME_WAKEUP counts the suspended
    // time, and an alarm that came due during the suspension fires the moment the
    // car wakes up — exactly the behaviour we want.
    public static void scheduleWatchdog(Context ctx) {
        try {
            android.app.AlarmManager am =
                (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(ctx, BootReceiver.class).setAction("com.geely.drivemem.WATCHDOG");
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                ctx, 1001, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    | (android.os.Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0));
            long next = android.os.SystemClock.elapsedRealtime() + WATCHDOG_MS;
            if (android.os.Build.VERSION.SDK_INT >= 23)
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, next, pi);
            else am.setExact(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, next, pi);
        } catch (Throwable t) { Log.w(CarAccess.TAG, "watchdog schedule: " + t); }
    }

    // How long without a beat before a service counts as stuck. Generous: 4x the
    // loop interval, with a floor, so a late tick does not cause a restart.
    static final long STALE_TELE_MS    = 5 * 60 * 1000L;  // 30s loop
    static final long STALE_OUTTEMP_MS = 2 * 60 * 1000L;  // 15s tick
    static final long STALE_WIFI_MS    = 2 * 60 * 1000L;  // 20s tick
    static final long STALE_SOC_MS     = 3 * 60 * 1000L;  // 30s tick
    // MQTT: far looser. A broker down for minutes is a NORMAL situation (the
    // actor reconnects on its own); restarting the service over that would be
    // counter-productive. Only a LONG silence means the actor is really dead.
    static final long STALE_MQTT_MS    = 20 * 60 * 1000L;

    // makes sure Wi-Fi is on and that the enabled services are running AND ALIVE.
    //
    // This used to only call startForegroundService(), which is a no-op when the
    // service already has a ServiceRecord — including a ZOMBIE one. Now, if the
    // service's beat is old, we stop it and start it again, so that the broken
    // instance (stuck thread, dead MQTT client) is really thrown away.
    public static void ensureAll(Context ctx) {
        android.content.SharedPreferences p =
            ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && !wm.isWifiEnabled()) {
                Log.i(CarAccess.TAG, "watchdog: turning Wi-Fi back on -> " + wm.setWifiEnabled(true));
            }
        } catch (Throwable ignored) {}

        ensureService(ctx, p.getBoolean("outtemp_on", false),
            OutTempService.class, Beat.OUTTEMP, STALE_OUTTEMP_MS);
        ensureService(ctx, p.getBoolean("tele_enabled", false),
            TelemetryService.class, Beat.TELE, STALE_TELE_MS);
        // The TELE beat only says the loop went round. With the actor, the loop
        // never gets stuck, so we also watch whether MQTT is REALLY publishing.
        // Much bigger limit: a broker down for a few minutes is normal and must
        // not restart the service (the actor reconnects by itself).
        ensureService(ctx, p.getBoolean("tele_enabled", false),
            TelemetryService.class, Beat.MQTT, STALE_MQTT_MS);
        ensureService(ctx, p.getBoolean("wifiicon_on", false),
            WifiIconService.class, Beat.WIFIICON, STALE_WIFI_MS);
        ensureService(ctx, p.getBoolean("soc_on", false),
            SocIconService.class, Beat.SOCICON, STALE_SOC_MS);
    }

    private static void ensureService(Context ctx, boolean enabled,
            Class<?> cls, String who, long staleMs) {
        if (!enabled) return;
        try {
            Intent s = new Intent(ctx, cls);
            // stuck? tear the zombie instance down before starting again
            if (Beat.isStale(ctx, who, staleMs) && Beat.mayRestart(ctx, who)) {
                Beat.log(who + " no beat for a long time — restarting");
                try { ctx.stopService(s); } catch (Throwable ignored) {}
                Beat.clear(ctx, who);
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(s);
            else ctx.startService(s);
        } catch (Throwable t) { Log.w(CarAccess.TAG, "ensure " + who + ": " + t); }
    }

}
