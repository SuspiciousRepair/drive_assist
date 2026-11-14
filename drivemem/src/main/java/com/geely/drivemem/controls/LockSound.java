package com.geely.drivemem.controls;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.net.Updater;

/** The sound the car plays when it is locked with the key (Config > Doors).
 *
 * The car plays the file named by {@code Settings.System lock_car_audio_path}.
 * modehelper ships the sounds, copies the chosen one into its own files dir
 * and points that setting at it (see modehelper's LockSoundReceiver). We only
 * ask, and read the setting back to show what the car really has: a sound
 * picked in the car's own Settings replaces ours, and the screen must say so.
 *
 * Nothing is saved here. The car's setting is the truth. */
public final class LockSound {
    static final String HELPER_SET = "com.geely.modehelper.SET_LOCK_SOUND";
    static final String SETTING = "lock_car_audio_path";

    /** Give the car back the sound it had before we changed it. */
    public static final String CAR = "car";
    /** Same ids as modehelper's LockSoundPolicy.SOUNDS, in button order. */
    public static final String[] SOUNDS = {"flatulence", "flatus"};

    private static final String OURS = "/com.geely.modehelper/files/lock_";

    private LockSound() {}

    /** The id the car's setting points at: one of {@link #SOUNDS}, or {@link #CAR}
     * for anything else (the car's own sounds, an empty setting, a file we do
     * not know). */
    public static String idForPath(String path) {
        if (path == null) return CAR;
        int at = path.indexOf(OURS);
        if (at < 0 || !path.endsWith(".wav")) return CAR;
        String id = path.substring(at + OURS.length(), path.length() - ".wav".length());
        for (String s : SOUNDS) if (s.equals(id)) return id;
        return CAR;
    }

    /** What the car will play now. */
    public static String current(Context ctx) {
        try {
            return idForPath(Settings.System.getString(ctx.getContentResolver(), SETTING));
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "lock sound read: " + t);
            return CAR;
        }
    }

    /** True when the installed modehelper is new enough to set the sound. */
    public static boolean helperSupports(Context ctx) {
        try {
            return !ctx.getPackageManager().queryBroadcastReceivers(
                new Intent(HELPER_SET).setPackage(Updater.HELPER_PKG), 0).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void set(Context ctx, String id) {
        try {
            ctx.sendBroadcast(new Intent(HELPER_SET).setPackage(Updater.HELPER_PKG).putExtra("sound", id));
        } catch (Throwable t) { Log.w(CarAccess.TAG, "lock sound set: " + t); }
    }
}
