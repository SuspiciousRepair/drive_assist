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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sets the sound the car plays when it is locked with the key.
 *
 * {@code SET_LOCK_SOUND}, {@code sound} = "car", "fart" or "custom" (see
 * {@link LockSoundPolicy}). Sent ordered, the result data says "ok",
 * "no_custom" (nothing imported yet), "unknown", "adb" (the local root
 * shell did not open) or "error". Fart and custom are bind-mounted, through
 * the local root shell, over every one of the car's own sounds, so the
 * sound picked in the car's Settings plays ours, whichever it is; car takes
 * the mounts away. Why not just point the setting at our file: see
 * {@link LockSoundPolicy}. This app owns the choice (pref
 * {@link #KEY_MODE}); {@code GET_LOCK_SOUND} answers it (ordered, result
 * data "car", "fart" or "custom"). A reboot drops the mounts:
 * {@link #reapply} puts them back.
 *
 * {@code IMPORT_LOCK_SOUND} fetches the "custom" sound: from {@code url} when
 * given (http or https, also after a redirect), else
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
    static final String ACTION_GET = "com.geely.modehelper.GET_LOCK_SOUND";
    private static final String PREFS = "modehelper";
    /** Only an old helper wrote it: the car sound to give back. Read once, then removed. */
    private static final String KEY_PREV = "lock_sound_prev";
    /** The chosen sound: "car", "fart" or "custom". The one place it is kept. */
    static final String KEY_MODE = "lock_sound_mode";

    static final String NOT_FOUND = "not_found";
    static final String DOWNLOAD = "download";
    static final String ERROR = "error";
    static final String NO_CUSTOM = "no_custom";
    static final String UNKNOWN = "unknown";
    static final String ADB = "adb";

    // Mount work runs here, one job at a time, off the main thread.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    // apply() runs from WORKER and from an import thread: one at a time.
    private static final Object APPLY = new Object();

    @Override public void onReceive(Context ctx, Intent i) {
        final Context app = ctx.getApplicationContext();
        if (ACTION_GET.equals(i.getAction())) {
            if (isOrderedBroadcast()) setResult(Activity.RESULT_OK, mode(app), null);
            return;
        }
        if (ACTION.equals(i.getAction())) {
            // The root shell can take seconds (it may have to open ADB):
            // answered when done. Sent ordered by Drive Assist, so it can say
            // "import one first" instead of guessing.
            final String id = i.getStringExtra("sound");
            final PendingResult pr = goAsync();
            try {
                WORKER.execute(() -> {
                    String code = ERROR;
                    try { code = apply(app, id); }
                    finally { answer(pr, code); }
                });
            } catch (Throwable t) {
                answer(pr, ERROR);
            }
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

    /** The chosen sound. A helper older than the mounts kept no choice and
     * pointed the setting at our file instead: that is read once and kept. */
    static String mode(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String m = p.getString(KEY_MODE, null);
        if (m != null) return m;
        String current = null;
        try { current = Settings.System.getString(ctx.getContentResolver(), LockSoundPolicy.SETTING); }
        catch (Throwable ignored) {}
        m = LockSoundPolicy.idForSetting(current);
        p.edit().putString(KEY_MODE, m).commit();
        return m;
    }

    /** After a boot, or after this app was updated: puts our mount back when
     * a sound of ours is chosen and none is mounted (a reboot drops them).
     * Also moves a car left by an old helper (setting on our file, which the
     * car cannot play) onto the mounts. */
    static void reapply(Context ctx) {
        final Context app = ctx.getApplicationContext();
        String m = mode(app);
        boolean legacy = LockSoundPolicy.isOurs(currentSetting(app));
        if (!legacy && (LockSoundPolicy.CAR.equals(m) || ourMounts() > 0)) return;
        WORKER.execute(() -> Log.i(TAG, "lock sound reapply " + m + ": " + apply(app, m)));
    }

    /** Our mounts this process sees. Our own mountinfo: no root needed. */
    static int ourMounts() {
        try (InputStream in = new FileInputStream("/proc/self/mountinfo")) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return LockSoundPolicy.countMounts(b.toString("UTF-8"));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String currentSetting(Context ctx) {
        try { return Settings.System.getString(ctx.getContentResolver(), LockSoundPolicy.SETTING); }
        catch (Throwable t) { return null; }
    }

    /** Returns "ok", "no_custom" (custom asked, nothing imported), "unknown",
     * "adb" (no root shell) or "error". Blocks: never on the main thread. */
    static String apply(Context ctx, String id) {
        synchronized (APPLY) {
            try {
                SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                File dir = ctx.getFilesDir();
                File ours = new File(dir, LockSoundPolicy.fileName(id == null ? "" : id));
                boolean haveCustom = new File(dir, LockSoundPolicy.fileName(LockSoundPolicy.CUSTOM)).isFile();
                if (!LockSoundPolicy.valid(id, haveCustom)) {
                    Log.w(TAG, "lock sound: cannot apply " + id);
                    return LockSoundPolicy.CUSTOM.equals(id) ? NO_CUSTOM : UNKNOWN;
                }
                boolean car = LockSoundPolicy.CAR.equals(id);
                if (LockSoundPolicy.FART.equals(id)) copyAsset(ctx, id, ours);

                // Car with nothing of ours mounted needs no root shell.
                if (!car || ourMounts() > 0) {
                    LocalAdbShell adb = OemText.openAdb(ctx);
                    if (adb == null) { Log.w(TAG, "lock sound " + id + ": no root shell"); return ADB; }
                    String out = adb.execute(car ? LockSoundPolicy.unmountCommand()
                                                 : LockSoundPolicy.mountCommand(ours.getAbsolutePath()));
                    if (!out.contains(car ? LockSoundPolicy.REMOVED : LockSoundPolicy.MOUNTED)) {
                        Log.w(TAG, "lock sound " + id + ": " + out.trim());
                        return ERROR;
                    }
                }

                String current = currentSetting(ctx);
                String write = LockSoundPolicy.settingFor(id, current, p.getString(KEY_PREV, null));
                if (write != null) Settings.System.putString(ctx.getContentResolver(), LockSoundPolicy.SETTING, write);
                p.edit().putString(KEY_MODE, id).remove(KEY_PREV).commit();
                Log.i(TAG, "lock sound: " + id + (car ? ", mounts off" : ", mounted over " + LockSoundPolicy.CARLOCK)
                    + (write != null ? ", setting -> " + write : ", setting kept (" + current + ")"));
                return LockSoundPolicy.OK;
            } catch (Throwable t) {
                Log.w(TAG, "lock sound " + id + ": " + t);
                return ERROR;
            }
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

    // http or https only, and each redirect is followed by hand so it can be
    // checked: the platform would follow one without asking.
    private static String download(String url, File tmp, Job job) {
        HttpURLConnection c = null;
        try {
            String at = url;
            for (int followed = 0; ; followed++) {
                if (!LockSoundPolicy.webUrl(at) || job.late()) return DOWNLOAD;
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
