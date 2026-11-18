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
 * When ADB is off it is turned on for {@link #ADB_MINUTES} (the same auto-off
 * window the cleanup uses), so the floor always works (owner, 2026-11-17).
 * ADB that is already on is used as it is: its own window is not touched.
 *
 *  - floor=true:  backlight to {@link #FLOOR}.
 *  - floor=false: backlight back to the car's own brightness setting
 *    (Settings.System.SCREEN_BRIGHTNESS; on this unit the backlight value is
 *    the setting value, measured 166 = 166). Android also rewrites it when
 *    Drive Assist drops its window override; this covers the case where
 *    Android's target did not change and so it writes nothing.
 *
 * Exported because Drive Assist is another app. A spoofed broadcast can only
 * dim the panel to its lowest lit level or put the car's own level back.
 */
public class BacklightFloorReceiver extends BroadcastReceiver {
    static final String PATH = "/sys/class/leds/lcd-backlight/brightness";
    static final int FLOOR = 2;
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

    @Override public void onReceive(Context ctx, Intent i) {
        wanted = i.getBooleanExtra("floor", false);
        final Context app = ctx.getApplicationContext();
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

    private static void write(Context app) throws Exception {
        LocalAdbShell adb = new LocalAdbShell();
        if (!adb.portOpen(500)) {
            if (!AdbControl.isEnabled(app)) AdbControl.set(app, true, ADB_MINUTES);
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
        int level = floor ? FLOOR
            : Settings.System.getInt(app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
        if (level < 0) { Log.w(CarMode.TAG, "backlight: car level unknown, nothing to restore"); return; }
        level = Math.max(FLOOR, Math.min(255, level));
        String out = adb.execute("echo " + level + " > " + PATH + " && cat " + PATH);
        Log.i(CarMode.TAG, "backlight: " + (floor ? "floor " : "restore ") + level + " -> " + out.trim());
    }
}
