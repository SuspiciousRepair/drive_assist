package com.geely.modehelper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

// Toggling network adb. The head unit has ro.secure=0 and adbd listening on
// :5555 across all interfaces, so adb is a network-wide root shell. This lives
// in modehelper (not in Drive Assist/drivemem) because writing ADB_ENABLED requires
// WRITE_SECURE_SETTINGS, which only platform-signed, system-uid apps have.
// The implementation uses Settings.Global instead of the persist.adb.tcp.port
// property because SELinux does not allow non-root processes to write it.
public final class AdbControl {
    static final String TAG = "ModeHelper";

    public static final String ACTION_SET      = "com.geely.modehelper.SET_ADB";
    public static final String ACTION_AUTO_OFF = "com.geely.modehelper.ADB_AUTO_OFF";

    /** Auto-off window duration (15 min) and maximum duration (2 hours).
     * Every adb session has a countdown; forgetting to close it is the normal case. */
    public static final int DEFAULT_MINUTES = 15;
    public static final int MAX_MINUTES     = 120;

    private static final int ALARM_ID = 2001;

    private AdbControl() {}

    public static boolean isEnabled(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(),
                                          Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Throwable t) {
            Log.w(TAG, "adb: cannot read the setting: " + t);
            return false;
        }
    }

    /** Enables or disables network adb with an optional auto-off timer (in minutes).
     * Returns the actual state after the operation, since WRITE_SECURE_SETTINGS
     * may be revoked if the build's signature or platform-signing status changes. */
    public static boolean set(Context ctx, boolean on, int minutes) {
        try {
            Settings.Global.putInt(ctx.getContentResolver(),
                                   Settings.Global.ADB_ENABLED, on ? 1 : 0);
        } catch (SecurityException e) {
            // Permission denied indicates the build is not platform-signed or
            // is not running with sharedUserId=android.uid.system.
            Log.e(TAG, "adb: WRITE_SECURE_SETTINGS refused — is this build still "
                     + "platform-signed and sharedUserId=android.uid.system? " + e);
            return isEnabled(ctx);
        } catch (Throwable t) {
            Log.e(TAG, "adb: could not write the setting: " + t);
            return isEnabled(ctx);
        }

        boolean now = isEnabled(ctx);
        Log.i(TAG, "adb: asked for " + (on ? "ON" : "OFF") + ", setting now reads "
                 + (now ? "ON" : "OFF"));

        if (on && now) scheduleAutoOff(ctx, clamp(minutes));
        else cancelAutoOff(ctx);
        return now;
    }

    /** Enables or disables adb without an auto-off timer. Used only by
     * WifiGuardReceiver for trusted home networks, where the network itself
     * (not a timeout) gates the adb session. */
    static boolean setIndefinite(Context ctx, boolean on) {
        try {
            Settings.Global.putInt(ctx.getContentResolver(),
                                   Settings.Global.ADB_ENABLED, on ? 1 : 0);
        } catch (Throwable t) {
            Log.e(TAG, "adb: WRITE_SECURE_SETTINGS refused (indefinite): " + t);
            return isEnabled(ctx);
        }
        cancelAutoOff(ctx);
        boolean now = isEnabled(ctx);
        Log.i(TAG, "adb: indefinite " + (on ? "ON" : "OFF") + ", setting now reads "
                 + (now ? "ON" : "OFF"));
        return now;
    }

    static int clamp(int minutes) {
        if (minutes <= 0) return DEFAULT_MINUTES;
        return Math.min(minutes, MAX_MINUTES);
    }

    /** Schedules an auto-off alarm using ELAPSED_REALTIME_WAKEUP, which counts
     * suspension time. Since this head unit suspends rather than powering down,
     * this ensures an expired timeout closes adb on wake. */
    private static void scheduleAutoOff(Context ctx, int minutes) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            long at = SystemClock.elapsedRealtime() + minutes * 60_000L;
            if (android.os.Build.VERSION.SDK_INT >= 23)
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarm(ctx));
            else
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarm(ctx));
            Log.i(TAG, "adb: closes again in " + minutes + " min");
        } catch (Throwable t) {
            // Timer failure is not an option: the timeout window is the entire
            // safety model. If it cannot be set, close adb immediately.
            Log.e(TAG, "adb: could not schedule the auto-off, closing now: " + t);
            try {
                Settings.Global.putInt(ctx.getContentResolver(),
                                       Settings.Global.ADB_ENABLED, 0);
            } catch (Throwable ignored) {}
        }
    }

    private static void cancelAutoOff(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            am.cancel(alarm(ctx));
        } catch (Throwable ignored) {}
    }

    private static PendingIntent alarm(Context ctx) {
        Intent i = new Intent(ctx, AdbReceiver.class).setAction(ACTION_AUTO_OFF);
        return PendingIntent.getBroadcast(ctx, ALARM_ID, i,
            PendingIntent.FLAG_UPDATE_CURRENT
                | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }
}
