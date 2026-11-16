package com.geely.modehelper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import java.util.concurrent.atomic.AtomicBoolean;

/** Sets the sound the car plays when it is locked with the key.
 *
 * {@code SET_LOCK_SOUND}, {@code sound} = "car", "fart" or "custom" (see
 * {@link LockSoundPolicy}). Sent ordered, the result data says "ok",
 * "no_custom" (nothing imported yet), "unknown" or "error". We put the file in our files dir and point
 * {@code Settings.System lock_car_audio_path} at it. CarService runs as uid
 * system too and may read our files, so the car plays it with no root and no
 * remount. The setting survives a reboot.
 *
 * {@code IMPORT_LOCK_SOUND} fetches the "custom" sound: from {@code url} when
 * given (https only, and a redirect must stay https), else
 * {@code LockChime.wav} at the root of a USB stick. The file must be a WAV of
 * 1 MB or less. With {@code apply} (default true) it then becomes the lock
 * sound. Send it as an ordered broadcast: the result data is a
 * {@link LockSoundPolicy} code ("ok", "too_big", "busy", ...) or
 * "not_found" / "download" / "error".
 *
 * One import at a time (they share one temp file): a second one gets "busy".
 * The whole import has {@link LockSoundPolicy#FETCH_DEADLINE_MS}; at that
 * point the broadcast is answered and the connection is cut, so a slow
 * server cannot hold the broadcast until the system kills this process.
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
    static final String NO_CUSTOM = "no_custom";
    static final String UNKNOWN = "unknown";

    @Override public void onReceive(Context ctx, Intent i) {
        final Context app = ctx.getApplicationContext();
        if (ACTION.equals(i.getAction())) {
            String code = apply(app, i.getStringExtra("sound"));
            // Sent ordered by Drive Assist, so it can say "import one first"
            // instead of guessing whether we hold a custom sound.
            if (isOrderedBroadcast()) setResult(LockSoundPolicy.OK.equals(code)
                ? Activity.RESULT_OK : Activity.RESULT_FIRST_USER, code, null);
            return;
        }
        if (!ACTION_IMPORT.equals(i.getAction())) return;
        final String url = i.getStringExtra("url");
        final boolean thenApply = i.getBooleanExtra("apply", true);
        final boolean fromLink = url != null && !url.trim().isEmpty();
        if (!IMPORTING.compareAndSet(false, true)) {
            Log.i(TAG, "lock sound import: refused, one is running");
            if (isOrderedBroadcast()) setResult(Activity.RESULT_FIRST_USER, LockSoundPolicy.BUSY, null);
            return;
        }
        // A download can take seconds: off the main thread, and the result
        // goes back when it is done, or at the deadline, whichever is first.
        final PendingResult pr = goAsync();
        final Job job = new Job();
        final Handler main = new Handler(Looper.getMainLooper());
        final Runnable deadline = () -> {
            if (!job.claim()) return;   // the import answered first
            Log.w(TAG, "lock sound import: deadline, gave up");
            answer(pr, fromLink ? DOWNLOAD : ERROR);
            job.cut();
        };
        main.postDelayed(deadline, LockSoundPolicy.FETCH_DEADLINE_MS);
        try {
            new Thread(() -> {
                String code = ERROR;
                boolean mine = false;
                try {
                    code = importSound(app, url, job);
                    // importSound claims the answer before it replaces the
                    // file; any other code is still to claim.
                    mine = job.importOwns || job.claim();
                    if (mine && LockSoundPolicy.OK.equals(code) && thenApply) apply(app, LockSoundPolicy.CUSTOM);
                } catch (Throwable t) {
                    Log.w(TAG, "lock sound import: " + t);
                    code = ERROR;
                    mine = job.importOwns || job.claim();
                } finally {
                    main.removeCallbacks(deadline);
                    IMPORTING.set(false);
                    Log.i(TAG, "lock sound import " + (fromLink ? "(link)" : "(usb)") + ": " + code
                        + (mine ? "" : " (after the deadline, dropped)"));
                    if (mine) answer(pr, code);
                }
            }, "lock-sound-import").start();
        } catch (Throwable t) {
            // No thread: answer now, or the broadcast and the guard hang.
            main.removeCallbacks(deadline);
            IMPORTING.set(false);
            if (job.claim()) answer(pr, ERROR);
        }
    }

    private static void answer(PendingResult pr, String code) {
        try {
            pr.setResult(LockSoundPolicy.OK.equals(code) ? Activity.RESULT_OK : Activity.RESULT_FIRST_USER,
                code, null);
        } catch (Throwable ignored) {
            // not sent ordered: there is no result to set
        }
        pr.finish();
    }

    // One import at a time: two would write the same lock_custom.wav.tmp.
    // Freed when the import thread really ends, not at the deadline, since
    // until then that thread may still hold the temp file.
    private static final AtomicBoolean IMPORTING = new AtomicBoolean();

    /** One running import: who answers the broadcast (the import or the
     * deadline, never both), and the connection to cut at the deadline. */
    static final class Job {
        private final long deadlineAt = SystemClock.elapsedRealtime() + LockSoundPolicy.FETCH_DEADLINE_MS;
        private final AtomicBoolean answered = new AtomicBoolean();
        private volatile HttpURLConnection conn;
        /** Set when the import thread itself claimed the answer. */
        volatile boolean importOwns;

        /** True for the one caller that may answer the broadcast. */
        boolean claim() { return answered.compareAndSet(false, true); }

        long leftMs() { return deadlineAt - SystemClock.elapsedRealtime(); }

        /** True once the deadline answered, or is due. */
        boolean late() { return answered.get() || leftMs() <= 0; }

        // disconnect() closes the socket, which ends a read blocked on it.
        // Off the main thread: it may touch the network.
        void cut() {
            final HttpURLConnection c = conn;
            if (c == null) return;
            new Thread(() -> {
                try { c.disconnect(); } catch (Throwable ignored) {}
            }, "lock-sound-cut").start();
        }
    }

    /** Returns "ok", "no_custom" (custom asked, nothing imported), "unknown" or "error". */
    static String apply(Context ctx, String id) {
        try {
            String current = Settings.System.getString(ctx.getContentResolver(), LockSoundPolicy.SETTING);
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String prev = p.getString(KEY_PREV, null);
            File dir = ctx.getFilesDir();
            boolean haveCustom = new File(dir, LockSoundPolicy.fileName(LockSoundPolicy.CUSTOM)).isFile();
            LockSoundPolicy.Decision d = LockSoundPolicy.decide(current, prev, id, dir.getAbsolutePath(), haveCustom);
            if (d == null) {
                Log.w(TAG, "lock sound: cannot apply " + id);
                return LockSoundPolicy.CUSTOM.equals(id) ? NO_CUSTOM : UNKNOWN;
            }

            if (LockSoundPolicy.FART.equals(id)) copyAsset(ctx, id, new File(dir, LockSoundPolicy.fileName(id)));
            // Save what to give back BEFORE replacing it, so a crash in
            // between cannot lose the car's own sound.
            if (d.savedPrev == null) p.edit().remove(KEY_PREV).commit();
            else p.edit().putString(KEY_PREV, d.savedPrev).commit();
            if (d.write != null) {
                Settings.System.putString(ctx.getContentResolver(), LockSoundPolicy.SETTING, d.write);
            }
            Log.i(TAG, "lock sound: " + id + " -> " + (d.write != null ? d.write : "unchanged (" + current + ")"));
            return LockSoundPolicy.OK;
        } catch (Throwable t) {
            Log.w(TAG, "lock sound " + id + ": " + t);
            return ERROR;
        }
    }

    /** Fetches the custom sound into our files dir. Returns a result code;
     * the old custom sound stays until a new one passes every check. "ok"
     * means the file was replaced, and that this call claimed the answer. */
    static String importSound(Context ctx, String url, Job job) throws IOException {
        File out = new File(ctx.getFilesDir(), LockSoundPolicy.fileName(LockSoundPolicy.CUSTOM));
        File tmp = new File(out.getPath() + ".tmp");
        try {
            if (url != null && !url.trim().isEmpty()) {
                String code = download(url.trim(), tmp, job);
                if (code != null) return code;
            } else {
                File src = findOnUsb();
                if (src == null) return NOT_FOUND;
                String code = LockSoundPolicy.checkWav(null, src.length());
                if (LockSoundPolicy.TOO_BIG.equals(code) || LockSoundPolicy.EMPTY.equals(code)) return code;
                try (InputStream in = new FileInputStream(src)) { copyCapped(in, tmp, job); }
            }
            String code = LockSoundPolicy.checkWav(head(tmp), tmp.length());
            if (!LockSoundPolicy.OK.equals(code)) return code;
            // Claim the answer BEFORE the file changes: after the deadline
            // said "failed", the sound must not change behind the user.
            if (!job.claim()) return ERROR;
            job.importOwns = true;
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

    // https only, and each redirect is followed by hand so it can be
    // checked: the platform would follow one without asking.
    private static String download(String url, File tmp, Job job) {
        HttpURLConnection c = null;
        try {
            String at = url;
            for (int followed = 0; ; followed++) {
                if (!LockSoundPolicy.httpsUrl(at) || job.late()) return DOWNLOAD;
                c = (HttpURLConnection) new URL(at).openConnection();
                job.conn = c;
                // Each wait is cut to what is left of the whole deadline;
                // Job.cut() ends anything these two do not cover.
                int wait = (int) Math.max(1, Math.min(10000, job.leftMs()));
                c.setConnectTimeout(wait);
                c.setReadTimeout(wait);
                c.setInstanceFollowRedirects(false);
                int status = c.getResponseCode();
                if (!LockSoundPolicy.isRedirect(status)) {
                    if (status / 100 != 2) return DOWNLOAD;
                    break;
                }
                String next = LockSoundPolicy.redirectTarget(at, c.getHeaderField("Location"), followed);
                c.disconnect();
                if (next == null) return DOWNLOAD;
                at = next;
            }
            // Refuse early when the server says it is too big; still capped
            // below, since the header is optional and can lie.
            if (c.getContentLengthLong() > LockSoundPolicy.MAX_BYTES) return LockSoundPolicy.TOO_BIG;
            try (InputStream in = c.getInputStream()) {
                if (!copyCapped(in, tmp, job)) return LockSoundPolicy.TOO_BIG;
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "lock sound download: " + t);
            return DOWNLOAD;
        } finally {
            job.conn = null;
            if (c != null) c.disconnect();
        }
    }

    // Copies at most MAX_BYTES + 1 bytes: one more than allowed is enough to
    // know it is too big, and a huge file never fills the disk. Stops at the
    // import's deadline: a server that drips bytes passes every read timeout.
    private static boolean copyCapped(InputStream in, File out, Job job) throws IOException {
        long total = 0;
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                if (job.late()) throw new IOException("import deadline");
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
