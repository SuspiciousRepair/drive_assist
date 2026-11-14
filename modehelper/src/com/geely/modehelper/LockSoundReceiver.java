package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Sets the sound the car plays when it is locked with the key.
 *
 * Drive Assist sends {@code SET_LOCK_SOUND} with {@code sound} = "car" or a
 * bundled id (see {@link LockSoundPolicy}). We copy the bundled wav into our
 * files dir and point {@code Settings.System lock_car_audio_path} at it.
 * CarService runs as uid system too and may read our files, so the car plays
 * it with no root and no remount. The setting survives a reboot.
 *
 * Exported, like the other receivers: the id is checked against the bundled
 * list, so a caller can only pick one of our sounds or give the car's back. */
public class LockSoundReceiver extends BroadcastReceiver {
    static final String TAG = "ModeHelper";
    static final String ACTION = "com.geely.modehelper.SET_LOCK_SOUND";
    private static final String PREFS = "modehelper";
    private static final String KEY_PREV = "lock_sound_prev";

    @Override public void onReceive(Context ctx, Intent i) {
        if (!ACTION.equals(i.getAction())) return;
        apply(ctx.getApplicationContext(), i.getStringExtra("sound"));
    }

    static void apply(Context ctx, String id) {
        try {
            String current = Settings.System.getString(ctx.getContentResolver(), LockSoundPolicy.SETTING);
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String prev = p.getString(KEY_PREV, null);
            File dir = ctx.getFilesDir();
            LockSoundPolicy.Decision d = LockSoundPolicy.decide(current, prev, id, dir.getAbsolutePath());
            if (d == null) { Log.w(TAG, "lock sound: unknown id " + id); return; }

            if (LockSoundPolicy.isKnown(id)) copyAsset(ctx, id, new File(dir, LockSoundPolicy.fileName(id)));
            // Save what to give back BEFORE replacing it, so a crash in
            // between cannot lose the car's own sound.
            if (d.savedPrev == null) p.edit().remove(KEY_PREV).commit();
            else p.edit().putString(KEY_PREV, d.savedPrev).commit();
            if (d.write != null) {
                Settings.System.putString(ctx.getContentResolver(), LockSoundPolicy.SETTING, d.write);
            }
            Log.i(TAG, "lock sound: " + id + " -> " + (d.write != null ? d.write : "unchanged (" + current + ")"));
        } catch (Throwable t) {
            Log.w(TAG, "lock sound " + id + ": " + t);
        }
    }

    // Always rewritten: a few hundred KB, only on a user tap, and it means a
    // helper update with a new recording replaces the old file.
    private static void copyAsset(Context ctx, String id, File out) throws Exception {
        File tmp = new File(out.getPath() + ".tmp");
        try (InputStream in = ctx.getAssets().open("locksound/" + id + ".wav");
             OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
        }
        if (!tmp.renameTo(out)) throw new java.io.IOException("rename " + tmp + " -> " + out);
    }
}
