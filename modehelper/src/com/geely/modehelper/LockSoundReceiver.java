package com.geely.modehelper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Sets the sound the car plays when it is locked with the key.
 *
 * {@code SET_LOCK_SOUND}, {@code sound} = "car", "fart" or "custom" (see
 * {@link LockSoundPolicy}). We put the file in our files dir and point
 * {@code Settings.System lock_car_audio_path} at it. CarService runs as uid
 * system too and may read our files, so the car plays it with no root and no
 * remount. The setting survives a reboot.
 *
 * {@code IMPORT_LOCK_SOUND} fetches the "custom" sound: from {@code url} when
 * given (http or https), else {@code LockChime.wav} at the root of a USB
 * stick. The file must be a WAV of 1 MB or less. With {@code apply} (default
 * true) it then becomes the lock sound. Send it as an ordered broadcast: the
 * result data is a {@link LockSoundPolicy} code ("ok", "too_big", ...) or
 * "not_found" / "download" / "error".
 *
 * Exported, like the other receivers. A caller can pick one of these sounds,
 * give the car's back, or import a small checked WAV into our own dir;
 * nothing else. */
public class LockSoundReceiver extends BroadcastReceiver {
    static final String TAG = "ModeHelper";
    static final String ACTION = "com.geely.modehelper.SET_LOCK_SOUND";
    static final String ACTION_IMPORT = "com.geely.modehelper.IMPORT_LOCK_SOUND";
    private static final String PREFS = "modehelper";
    private static final String KEY_PREV = "lock_sound_prev";

    static final String NOT_FOUND = "not_found";
    static final String DOWNLOAD = "download";
    static final String ERROR = "error";

    @Override public void onReceive(Context ctx, Intent i) {
        final Context app = ctx.getApplicationContext();
        if (ACTION.equals(i.getAction())) {
            apply(app, i.getStringExtra("sound"));
            return;
        }
        if (!ACTION_IMPORT.equals(i.getAction())) return;
        final String url = i.getStringExtra("url");
        final boolean thenApply = i.getBooleanExtra("apply", true);
        // A download can take seconds: off the main thread, and the result
        // goes back when it is done.
        final PendingResult pr = goAsync();
        new Thread(() -> {
            String code = ERROR;
            try {
                code = importSound(app, url);
                if (LockSoundPolicy.OK.equals(code) && thenApply) apply(app, LockSoundPolicy.CUSTOM);
            } catch (Throwable t) {
                Log.w(TAG, "lock sound import: " + t);
            } finally {
                Log.i(TAG, "lock sound import " + (url != null ? "(link)" : "(usb)") + ": " + code);
                pr.setResult(LockSoundPolicy.OK.equals(code) ? Activity.RESULT_OK : Activity.RESULT_FIRST_USER,
                    code, null);
                pr.finish();
            }
        }, "lock-sound-import").start();
    }

    static void apply(Context ctx, String id) {
        try {
            String current = Settings.System.getString(ctx.getContentResolver(), LockSoundPolicy.SETTING);
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String prev = p.getString(KEY_PREV, null);
            File dir = ctx.getFilesDir();
            boolean haveCustom = new File(dir, LockSoundPolicy.fileName(LockSoundPolicy.CUSTOM)).isFile();
            LockSoundPolicy.Decision d = LockSoundPolicy.decide(current, prev, id, dir.getAbsolutePath(), haveCustom);
            if (d == null) { Log.w(TAG, "lock sound: cannot apply " + id); return; }

            if (LockSoundPolicy.FART.equals(id)) copyAsset(ctx, id, new File(dir, LockSoundPolicy.fileName(id)));
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

    /** Fetches the custom sound into our files dir. Returns a result code;
     * the old custom sound stays until a new one passes every check. */
    static String importSound(Context ctx, String url) throws IOException {
        File out = new File(ctx.getFilesDir(), LockSoundPolicy.fileName(LockSoundPolicy.CUSTOM));
        File tmp = new File(out.getPath() + ".tmp");
        try {
            if (url != null && !url.trim().isEmpty()) {
                String code = download(url.trim(), tmp);
                if (code != null) return code;
            } else {
                File src = findOnUsb();
                if (src == null) return NOT_FOUND;
                String code = LockSoundPolicy.checkWav(null, src.length());
                if (LockSoundPolicy.TOO_BIG.equals(code) || LockSoundPolicy.EMPTY.equals(code)) return code;
                try (InputStream in = new FileInputStream(src)) { copyCapped(in, tmp); }
            }
            String code = LockSoundPolicy.checkWav(head(tmp), tmp.length());
            if (!LockSoundPolicy.OK.equals(code)) return code;
            if (!tmp.renameTo(out)) throw new IOException("rename " + tmp + " -> " + out);
            return LockSoundPolicy.OK;
        } finally {
            tmp.delete();
        }
    }

    // LockChime.wav at the root of any mounted stick, any letter case.
    // "emulated" and "self" are internal storage, never the stick.
    private static File findOnUsb() {
        File[] vols = new File("/storage").listFiles();
        if (vols == null) return null;
        for (File v : vols) {
            String n = v.getName();
            if (n.equals("emulated") || n.equals("self")) continue;
            File[] files = v.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.isFile() && f.getName().equalsIgnoreCase(LockSoundPolicy.USB_NAME)) return f;
            }
        }
        return null;
    }

    private static String download(String url, File tmp) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(url);
            String proto = u.getProtocol();
            if (!"https".equals(proto) && !"http".equals(proto)) return DOWNLOAD;
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() / 100 != 2) return DOWNLOAD;
            // Refuse early when the server says it is too big; still capped
            // below, since the header is optional and can lie.
            if (c.getContentLengthLong() > LockSoundPolicy.MAX_BYTES) return LockSoundPolicy.TOO_BIG;
            try (InputStream in = c.getInputStream()) {
                if (!copyCapped(in, tmp)) return LockSoundPolicy.TOO_BIG;
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "lock sound download: " + t);
            return DOWNLOAD;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // Copies at most MAX_BYTES + 1 bytes: one more than allowed is enough to
    // know it is too big, and a huge file never fills the disk.
    private static boolean copyCapped(InputStream in, File out) throws IOException {
        long total = 0;
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > LockSoundPolicy.MAX_BYTES) return false;
                os.write(buf, 0, n);
            }
        }
        return true;
    }

    private static byte[] head(File f) throws IOException {
        byte[] b = new byte[12];
        try (InputStream in = new FileInputStream(f)) {
            int got = 0;
            for (int n; got < b.length && (n = in.read(b, got, b.length - got)) > 0; ) got += n;
            if (got < b.length) return null;
        }
        return b;
    }

    // Always rewritten: a few hundred KB, only on a user tap, and it means a
    // helper update with a new recording replaces the old file.
    private static void copyAsset(Context ctx, String id, File out) throws IOException {
        File tmp = new File(out.getPath() + ".tmp");
        try (InputStream in = ctx.getAssets().open("locksound/" + id + ".wav");
             OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
        }
        if (!tmp.renameTo(out)) throw new IOException("rename " + tmp + " -> " + out);
    }
}
