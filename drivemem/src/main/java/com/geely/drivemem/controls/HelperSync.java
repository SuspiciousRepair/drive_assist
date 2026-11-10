package com.geely.drivemem.controls;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.net.Updater;
import com.geely.drivemem.util.Prefs;

/** Talks to modehelper, which writes saved settings to the car after a wake
 * (issues #12, #13, #16).
 *
 * {@link #push} syncs only the four "Restore at start" switches — safe on
 * every watchdog tick. It deliberately does NOT send saved values: older
 * versions copied the car's AVAS state into "avas_on" every time the Drive
 * screen opened, so that key holds values nobody chose, and pushing it would
 * start restoring them (review #2). Values reach modehelper only from a user
 * action: a drive/regen pick, or {@link #applyNow} for an AEB/AVAS tap, which
 * modehelper writes at once, whatever the restore switch says. */
public final class HelperSync {
    static final String SET_MODE = "com.geely.modehelper.SET_MODE";

    private HelperSync() {}

    public static void push(Context ctx) {
        Intent i = base();
        for (String k : new String[]{Prefs.RESTORE_DRIVE, Prefs.RESTORE_REGEN,
                                     Prefs.RESTORE_AEB, Prefs.RESTORE_AVAS}) {
            i.putExtra(k, Prefs.getRestore(ctx, k));
        }
        send(ctx, i);
    }

    /** User tapped the AEB or AVAS switch: save and write to the car now. */
    public static void applyNow(Context ctx, String key, boolean on) {
        if ("avas".equals(key)) Prefs.setAvasOn(ctx, on); else Prefs.setAebOn(ctx, on);
        Intent i = base().putExtra("apply_now", true);
        if ("avas".equals(key)) i.putExtra("avas", on ? 1 : 0); else i.putExtra("aeb", on);
        send(ctx, i);
    }

    private static Intent base() { return new Intent(SET_MODE).setPackage(Updater.HELPER_PKG); }

    private static void send(Context ctx, Intent i) {
        try { ctx.sendBroadcast(i); }
        catch (Throwable t) { Log.w(CarAccess.TAG, "helper sync: " + t); }
    }
}
