package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.IOException;

/** Removes everything of ours from the car, for an owner who is about to hand
 * it to a workshop. Drive Assist's Cleanup screen sends this; it cannot do
 * these steps itself — stopping a root daemon, writing ADB_ENABLED, and a
 * silent uninstall all need the system privileges only this app has.
 *
 * In order:
 *   0. the CAN reader switch and the Wi-Fi guard are turned off, so neither
 *      starts roadcastd or re-opens ADB while the steps below run;
 *   1. the car's own lock sound comes back (our mount over the car's
 *      sounds is taken away, through the local root shell);
 *   2. roadcastd is stopped and {@link CanReaderPolicy#DIR} is deleted — a
 *      reader another app started, in another dir, is never touched; the
 *      OEM translations copies are unmounted and {@link OemTextPolicy#DIR}
 *      is deleted, so the car's apps show their factory text;
 *   3. "keep ADB always on" is cleared, so nothing re-opens ADB after we go;
 *   4. ADB is left OFF ({@code close_adb} true, for a workshop visit) or ON
 *      with no auto-off (false, so the owner keeps the shell);
 *   5. Drive Assist and then this helper are uninstalled, silently.
 *
 * Nothing is left on the car to listen for a signal. After a close, the owner
 * gets back in only by unlocking the car again the same way as the first
 * time. That is the deliberate trade for a car a workshop cannot tell was
 * ever modified.
 *
 * It does NOT undo the deep root (ro.secure=0, test-keys): that lives in the
 * system image from recovery, not in our apps, and is not ours to change.
 *
 * Exported for the same reason as the other receivers: Drive Assist is a
 * different signer and must reach it. The worst a rogue caller can do is
 * remove OUR two apps and the CAN reader and set ADB to a known state — no
 * capability a caller does not already have on this always-root unit, and the
 * normal path is behind a two-tap confirm in Drive Assist's own UI. */
public class CleanupReceiver extends BroadcastReceiver {
    static final String TAG = "ModeHelper";
    static final String ACTION = "com.geely.modehelper.CLEANUP";
    static final String DRIVEMEM = "com.geely.drivemem";
    static final String SELF = "com.geely.modehelper";
    /** The short ADB window we open only to stop the daemon, if ADB was off. */
    static final int ADB_WIPE_MINUTES = 2;

    @Override public void onReceive(Context ctx, Intent i) {
        final Context app = ctx.getApplicationContext();
        final boolean closeAdb = i.getBooleanExtra("close_adb", false);
        final PendingResult pr = goAsync();
        new Thread(() -> {
            String detail = null;
            try {
                detail = run(app, closeAdb);
            } catch (Throwable t) {
                detail = "error: " + t;
                Log.w(TAG, "cleanup: " + t);
            } finally {
                Log.i(TAG, "cleanup: done, close_adb=" + closeAdb
                    + (detail != null ? " (" + detail + ")" : ""));
                try { pr.finish(); } catch (Throwable ignored) {}
                // Last of all, remove the apps. Drive Assist first; this
                // helper very last, after a pause, so our own death does not
                // drop the other request before the system has taken it.
                try { Installer.uninstall(app, DRIVEMEM); } catch (Throwable ignored) {}
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                try { Installer.uninstall(app, SELF); } catch (Throwable ignored) {}
            }
        }, "cleanup").start();
    }

    /** The privileged steps 1–4. Returns a short note when something went
     * wrong, or null when all was well. Does not uninstall (the caller does
     * that after, so a failure here still leaves the car cleaner). */
    static String run(Context app, boolean closeAdb) {
        String note = null;
        // 0. our own actors off first. The CAN reader's switch stayed on, so
        // its once-a-minute check could restart roadcastd (opening ADB to do
        // it) after step 2 stopped it; a Wi-Fi guard tick could hold ADB on
        // indefinitely after step 3 closed it.
        try { CanReader.setEnabled(app, false); }
        catch (Throwable t) { note = "can switch: " + t; }
        try { WifiGuardReceiver.stopForCleanup(app); }
        catch (Throwable t) { note = append(note, "wifi guard: " + t); }

        // 1. the car's own lock sound back
        try { LockSoundReceiver.apply(app, "car"); }
        catch (Throwable t) { note = append(note, "lock sound: " + t); }

        // 2. stop and delete roadcastd — needs the root adb shell
        LocalAdbShell adb = new LocalAdbShell();
        try {
            if (!adb.portOpen(1000) && AdbControl.set(app, true, ADB_WIPE_MINUTES)) {
                waitPort(adb, 8000);
            }
            if (adb.portOpen(1000)) {
                String out = adb.execute(CanReaderPolicy.wipeCommand());
                if (!out.contains(CanReaderPolicy.WIPED)) note = append(note, "wipe: " + out);
                // 2b. the car's own app text back: our patched copies off
                app.getSharedPreferences(OemText.PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(OemText.K_ON, false).commit();
                out = adb.execute(OemTextPolicy.wipeCommand(java.util.Collections.<String>emptyList()));
                if (!out.contains(OemTextPolicy.WIPED)) note = append(note, "oem text: " + out);
            } else {
                note = append(note, "could not open ADB to stop the CAN reader or remove the OEM text");
            }
        } catch (IOException e) {
            note = append(note, "CAN reader: " + e);
        }

        // 3. the final ADB state the owner asked for. The "always on" flag is
        // set to match, so that if this helper outlives the request for a
        // moment (its own uninstall is the very last step), it holds ADB in
        // the SAME state rather than fighting it:
        //   - close: flag off, then ADB off with no window — nothing re-opens
        //     it, and the way back in is a fresh unlock of the car;
        //   - keep:  flag on, then ADB on with no auto-off — if the helper
        //     lingers at all it keeps ADB on, and once it is gone ADB stays on
        //     with nothing of ours left to turn it off.
        try {
            if (closeAdb) {
                AdbControl.setAlwaysOn(app, false);
                AdbControl.set(app, false, 0);
            } else {
                AdbControl.setAlwaysOn(app, true);
                AdbControl.setIndefinite(app, true);
            }
        } catch (Throwable t) { note = append(note, "adb: " + t); }
        return note;
    }

    private static boolean waitPort(LocalAdbShell adb, long ms) {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            if (adb.portOpen(1000)) return true;
            try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    private static String append(String note, String s) {
        return note == null ? s : note + "; " + s;
    }
}
