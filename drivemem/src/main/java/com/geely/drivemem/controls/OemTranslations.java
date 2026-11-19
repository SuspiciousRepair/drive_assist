package com.geely.drivemem.controls;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.net.Updater;

/** The "OEM translations" integration (Config > Integrations): better text
 * for the car's own apps, from the integration-oem-translations repo.
 *
 * modehelper does all of it (its OemText: download, patch a copy of each
 * car app, bind-mount the copy, put it back after a reboot, take it away
 * when a patched app crashes) and owns the state. We only ask, with ordered
 * broadcasts, and keep the last answer in memory to draw the tile. */
public final class OemTranslations {
    private OemTranslations() {}

    static final String INSTALL = "com.geely.modehelper.OEM_TEXT_INSTALL";
    static final String REMOVE = "com.geely.modehelper.OEM_TEXT_REMOVE";
    static final String STATUS = "com.geely.modehelper.OEM_TEXT_STATUS";

    public static final String OFF = "off", BUSY = "busy", ON = "on", ERROR = "error";
    /** modehelper did not answer: not installed, or older than this feature. */
    public static final String NO_HELPER = "no_helper";

    /** modehelper's answer. */
    public static final class Status {
        public final String state;     // OFF, BUSY, ON, ERROR or NO_HELPER
        public final String step;      // while BUSY: download, patch, mount, remove
        public final String result;    // last result: ok, partial, download, format, adb, patch, none, error, system, hash
        public final String note;
        public final String crashed;   // packages whose copy a crash took away, comma separated
        public final int apps;         // apps with fixed text now
        public final String system;    // the car's system version (ro.build.version.versionname)

        Status(Bundle b) {
            state = b != null ? b.getString("state", NO_HELPER) : NO_HELPER;
            step = b != null ? b.getString("step", "") : "";
            result = b != null ? b.getString("result", "") : "";
            note = b != null ? b.getString("note", "") : "";
            crashed = b != null ? b.getString("crashed", "") : "";
            apps = b != null ? b.getInt("apps", 0) : 0;
            system = b != null ? b.getString("system", "") : "";
        }
    }

    public interface Done { void done(Status s, String code); }

    private static volatile Status last;

    /** The last answer, or null before the first one. */
    public static Status last() { return last; }

    /** Downloads the files, patches and mounts (also "Update"). */
    public static void install(Context ctx, Done cb) { send(ctx, INSTALL, cb); }

    /** Takes every copy away: the car's apps show their factory text. */
    public static void remove(Context ctx, Done cb) { send(ctx, REMOVE, cb); }

    /** Asks for the state; cb may be null. */
    public static void refresh(Context ctx, Done cb) { send(ctx, STATUS, cb); }

    private static void send(Context ctx, String action, Done cb) {
        Intent i = new Intent(action).setPackage(Updater.HELPER_PKG);
        BroadcastReceiver back = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent r) {
                Bundle b = getResultCode() == Activity.RESULT_OK ? getResultExtras(false) : null;
                Status s = new Status(b);
                last = s;
                if (cb != null) cb.done(s, b != null ? getResultData() : NO_HELPER);
            }
        };
        try {
            ctx.sendOrderedBroadcast(i, null, back, new Handler(Looper.getMainLooper()),
                Activity.RESULT_CANCELED, null, null);
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "oem translations: " + t);
            Status s = new Status(null);
            last = s;
            if (cb != null) cb.done(s, NO_HELPER);
        }
    }
}
