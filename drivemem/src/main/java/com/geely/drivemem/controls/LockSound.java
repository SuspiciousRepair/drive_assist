package com.geely.drivemem.controls;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.net.Updater;
import com.geely.drivemem.util.Prefs;

/** The sound the car plays when it is locked with the key (Config > Doors).
 *
 * modehelper owns the sound and the choice: it ships the fart, imports a
 * custom WAV (from a USB stick or a link), and mounts the chosen one over
 * the car's own sounds, so whichever one is picked in the car's Settings
 * plays ours (see modehelper's LockSoundReceiver). We only ask: SET to
 * choose, GET to show what is chosen. An old modehelper has no GET; then
 * the setting it pointed at our file is what we read.
 *
 * One small pref here: which sound fart mode gives back when switched off
 * (car or custom). Whether a custom sound exists is modehelper's to say:
 * it answers every request (ordered broadcast). */
public final class LockSound {
    static final String HELPER_SET = "com.geely.modehelper.SET_LOCK_SOUND";
    static final String HELPER_IMPORT = "com.geely.modehelper.IMPORT_LOCK_SOUND";
    static final String HELPER_GET = "com.geely.modehelper.GET_LOCK_SOUND";
    static final String SETTING = "lock_car_audio_path";

    /** Give the car back the sound it had before we changed it. */
    public static final String CAR = "car";
    /** The bundled fart (fart mode). */
    public static final String FART = "fart";
    /** The imported sound. */
    public static final String CUSTOM = "custom";

    /** Import result when modehelper did not answer (too old, or not installed). */
    public static final String NO_ANSWER = "no_answer";

    private static final String OURS = "/com.geely.modehelper/files/lock_";
    private static final String KEY_BASE = "lock_sound_base";

    public interface Result { void done(String code); }

    private LockSound() {}

    /** The id the car's setting points at: {@link #FART}, {@link #CUSTOM},
     * or {@link #CAR} for anything else (the car's own sounds, an empty
     * setting, a file we do not know). */
    public static String idForPath(String path) {
        if (path == null) return CAR;
        int at = path.indexOf(OURS);
        if (at < 0 || !path.endsWith(".wav")) return CAR;
        String id = path.substring(at + OURS.length(), path.length() - ".wav".length());
        if (FART.equals(id) || CUSTOM.equals(id)) return id;
        return CAR;
    }

    /** What fart mode gives back when it is switched off. */
    public static String base(String current, String savedBase) {
        if (!FART.equals(current)) return current;
        return CUSTOM.equals(savedBase) ? CUSTOM : CAR;
    }

    /** What is chosen now, from modehelper. {@code cb} runs on the main
     * thread with {@link #CAR}, {@link #FART} or {@link #CUSTOM}. */
    public static void mode(Context ctx, Result cb) {
        Intent i = new Intent(HELPER_GET).setPackage(Updater.HELPER_PKG);
        ordered(ctx, i, code -> {
            if (FART.equals(code) || CUSTOM.equals(code) || CAR.equals(code)) cb.done(code);
            else cb.done(current(ctx));   // old helper: no GET, the setting says
        });
    }

    /** What an old modehelper set: it pointed the car's setting at our file. */
    public static String current(Context ctx) {
        try {
            return idForPath(Settings.System.getString(ctx.getContentResolver(), SETTING));
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "lock sound read: " + t);
            return CAR;
        }
    }

    /** The sound to give back when fart mode goes off; {@code now} is what
     * {@link #mode} said. */
    public static String base(Context ctx, String now) {
        return base(now, Prefs.file(ctx).getString(KEY_BASE, CAR));
    }

    /** True when the installed modehelper is new enough (it can import). */
    public static boolean helperSupports(Context ctx) {
        try {
            return !ctx.getPackageManager().queryBroadcastReceivers(
                new Intent(HELPER_IMPORT).setPackage(Updater.HELPER_PKG), 0).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Picks a sound. Picking car or custom also makes it the one fart mode
     * gives back; switching fart mode on saves what was playing, so a sound
     * picked in the car's own Settings since then is what comes back.
     * {@code cb} runs on the main thread with modehelper's code ("ok",
     * "no_custom" when nothing was imported yet, "adb" when its root shell
     * did not open, ...) or {@link #NO_ANSWER}. {@code now} is what
     * {@link #mode} said. */
    public static void set(Context ctx, String id, String now, Result cb) {
        String keep = FART.equals(id) ? now : id;
        Intent i = new Intent(HELPER_SET).setPackage(Updater.HELPER_PKG).putExtra("sound", id);
        ordered(ctx, i, code -> {
            if ("ok".equals(code) && (CAR.equals(keep) || CUSTOM.equals(keep))) {
                Prefs.file(ctx).edit().putString(KEY_BASE, keep).apply();
            }
            cb.done(code);
        });
    }

    /** A link modehelper will download: http or https (it refuses anything
     * else, see its LockSoundPolicy.webUrl). */
    public static boolean linkAllowed(String url) {
        if (url == null) return false;
        String u = url.trim();
        for (String p : new String[]{"https://", "http://"}) {
            if (u.length() > p.length() && u.regionMatches(true, 0, p, 0, p.length())) return true;
        }
        return false;
    }

    /**
     * Imports the custom sound: from {@code url} (http or https), or LockChime.wav on a USB
     * stick when {@code url} is null. It becomes the lock sound unless
     * {@code apply} is false (fart mode is on). {@code cb} runs on the main
     * thread with modehelper's code ("ok", "not_found", "too_big",
     * "not_wav", "empty", "download", "busy", "error") or {@link #NO_ANSWER}.
     */
    public static void importSound(Context ctx, String url, boolean apply, Result cb) {
        // modehelper refuses these too; say so at once, with its own code.
        if (url != null && !linkAllowed(url)) { cb.done("download"); return; }
        Intent i = new Intent(HELPER_IMPORT).setPackage(Updater.HELPER_PKG).putExtra("apply", apply);
        if (url != null) i.putExtra("url", url);
        ordered(ctx, i, code -> {
            if ("ok".equals(code) && apply) Prefs.file(ctx).edit().putString(KEY_BASE, CUSTOM).apply();
            cb.done(code);
        });
    }

    // modehelper writes its answer into the ordered broadcast's result data.
    // No answer (old or missing helper) leaves it null.
    private static void ordered(Context ctx, Intent i, Result cb) {
        BroadcastReceiver back = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent r) {
                String code = getResultData();
                cb.done(code != null ? code : NO_ANSWER);
            }
        };
        try {
            ctx.sendOrderedBroadcast(i, null, back, new Handler(Looper.getMainLooper()),
                Activity.RESULT_CANCELED, null, null);
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "lock sound: " + t);
            cb.done(NO_ANSWER);
        }
    }
}
