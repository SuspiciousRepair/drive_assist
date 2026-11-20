package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/** Extra dark's floor (Drive Assist ui/ExtraDark): the panel backlight below
 * Android's own floor.
 *
 * Android clamps every brightness to 10 of 255 on this unit. The panel still
 * lights at 2 (1 is off), measured by the owner over adb on 2026-11-17. The
 * backlight file (/sys/class/leds/lcd-backlight/brightness) is owned by
 * system, but SELinux denies system_app (this app) even a search of it
 * (avc: denied { search } sysfs_leds), so the write goes through the car's
 * own root adbd (LocalAdbShell, su domain), like the CAN reader's start.
 * When ADB is off it is turned on for the write (auto-off after
 * {@link #ADB_MINUTES} as a backstop), so the floor always works (owner,
 * 2026-11-17), and turned off again right after the write unless something
 * else switched it on meanwhile (AdbControl.openCount, as CanReader does):
 * a night drive with Darkest mode must not keep the root shell open (review,
 * 2026-11-20). ADB that is already on is used as it is: its own window is
 * not touched.
 *
 *  - floor=true:  backlight to {@link #FLOOR}, or to 1 (panel off) when
 *    level=1 (darkness 100 %).
 *    Every dark level is watched: "keep"=true broadcasts say Drive Assist
 *    is still there, and when they stop for BacklightPolicy.BEAT_TIMEOUT_MS
 *    the car's own level comes back by itself, so a crash never leaves the
 *    panel dark, and neither does a floor sent by any other app.
 *  - floor=false: backlight back to the car's own brightness setting
 *    (Settings.System.SCREEN_BRIGHTNESS; on this unit the backlight value is
 *    the setting value, measured 166 = 166). Android also rewrites it when
 *    Drive Assist drops its window override; this covers the case where
 *    Android's target did not change and so it writes nothing.
 *
 * Exported because Drive Assist is another app. A spoofed broadcast can dim
 * the panel or turn it off only until the heartbeat times out (no "keep"
 * from it, so about 15 to 20 s), or put the car's own level back.
 */
public class BacklightFloorReceiver extends BroadcastReceiver {
    static final String PATH = "/sys/class/leds/lcd-backlight/brightness";
    static final int FLOOR = BacklightPolicy.FLOOR;
    static final int ADB_MINUTES = 2;
    static final long PORT_WAIT_MS = 8000;

    /** One worker for every request, in the order they came. Each broadcast
     * used to get its own thread; with ADB off both "floor" and "restore"
     * waited on the same port, then wrote in any order, and a floor written
     * last left the panel at 2 while Drive Assist thought it was undimmed. */
    private static final java.util.concurrent.ExecutorService WORKER =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    /** The latest request. Read just before the write, so the last broadcast
     * always wins, even if an older job is still waiting for the port. */
    private static volatile boolean wanted;
    /** The dark level asked for with the latest floor: 2 or 1 (off). */
    private static volatile int wantedLevel = FLOOR;
    /** Heartbeat (see the class comment): on while a floor holds. */
    private static volatile boolean watched;
    private static volatile long lastBeat;
    private static java.util.concurrent.ScheduledExecutorService watcher;

    @Override public void onReceive(Context ctx, Intent i) {
        final Context app = ctx.getApplicationContext();
        long now = android.os.SystemClock.elapsedRealtime();
        if (i.getBooleanExtra("keep", false)) {   // "still here": no write
            lastBeat = now;
            return;
        }
        wanted = i.getBooleanExtra("floor", false);
        wantedLevel = BacklightPolicy.darkLevel(i.getIntExtra("level", FLOOR));
        lastBeat = now;
        // Every floor is watched, whoever sent it (review, 2026-11-20): one
        // with no heartbeat behind it is undone after BEAT_TIMEOUT_MS.
        watched = wanted;
        if (watched) watch(app);
        final PendingResult pr = goAsync();
        WORKER.execute(() -> {
            try {
                write(app);
            } catch (Throwable t) {
                Log.w(CarMode.TAG, "backlight: " + t);
            } finally {
                pr.finish();
            }
        });
    }

    /** Starts the heartbeat check once; it then looks every 5 s (two
     * volatile reads while nothing is dark). */
    private static synchronized void watch(Context app) {
        if (watcher != null) return;
        watcher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        watcher.scheduleWithFixedDelay(() -> {
            if (!wanted || !watched) return;
            if (!BacklightPolicy.expired(lastBeat, android.os.SystemClock.elapsedRealtime())) return;
            wanted = false;
            watched = false;
            Log.w(CarMode.TAG, "backlight: Drive Assist stopped saying it is there, light back");
            WORKER.execute(() -> {
                try { write(app); } catch (Throwable t) { Log.w(CarMode.TAG, "backlight: " + t); }
            });
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void write(Context app) throws Exception {
        LocalAdbShell adb = new LocalAdbShell();
        int openedAt = -1;   // AdbControl.openCount() after we opened, -1: not ours
        try {
            if (!adb.portOpen(500) && !AdbControl.isEnabled(app)) {
                AdbControl.set(app, true, ADB_MINUTES);
                openedAt = AdbControl.openCount();
            }
            writeOpen(app, adb);
        } finally {
            if (BacklightPolicy.closeAdbAfter(openedAt, AdbControl.openCount())) {
                AdbControl.set(app, false, 0);
                Log.i(CarMode.TAG, "backlight: ADB off again");
            }
        }
    }

    private static void writeOpen(Context app, LocalAdbShell adb) throws Exception {
        if (!adb.portOpen(500)) {
            long until = System.currentTimeMillis() + PORT_WAIT_MS;
            while (!adb.portOpen(1000) && System.currentTimeMillis() < until) {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
            if (!adb.portOpen(500)) {
                Log.w(CarMode.TAG, "backlight: local ADB did not open, staying at Android's level");
                return;
            }
        }
        boolean floor = wanted;
        int level = floor ? wantedLevel
            : Settings.System.getInt(app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
        if (level < 0) { Log.w(CarMode.TAG, "backlight: car level unknown, nothing to restore"); return; }
        level = floor ? BacklightPolicy.darkLevel(level) : BacklightPolicy.restoreLevel(level);
        String out = adb.execute("echo " + level + " > " + PATH + " && cat " + PATH);
        Log.i(CarMode.TAG, "backlight: " + (floor ? "floor " : "restore ") + level + " -> " + out.trim());
    }
}
