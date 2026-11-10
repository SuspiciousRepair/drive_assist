package com.geely.drivemem.controls;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.net.Updater;
import com.geely.drivemem.util.Prefs;

/** Sends the saved drive mode, regen, AEB and AVAS values and their
 * "Restore at start" switches to modehelper, which is the one that writes
 * them to the car after a wake (issues #12, #13, #16).
 *
 * {@link #push} is a quiet sync — safe on every watchdog tick, it never makes
 * modehelper write anything. {@link #applyNow} is a user tap: modehelper
 * writes that one value at once, whatever the restore switch says. */
public final class HelperSync {
    static final String SET_MODE = "com.geely.modehelper.SET_MODE";

    private HelperSync() {}

    public static void push(Context ctx) {
        Intent i = base();
        if (Prefs.has(ctx, "drive"))   i.putExtra("drive", Prefs.getDriveMode(ctx, 0));
        if (Prefs.has(ctx, "regen"))   i.putExtra("regen", Prefs.getRegen(ctx, 0));
        if (Prefs.has(ctx, "aeb_on"))  i.putExtra("aeb", Prefs.getAebOn(ctx));
        if (Prefs.has(ctx, "avas_on")) i.putExtra("avas", Prefs.getAvasOn(ctx) ? 1 : 0);
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
