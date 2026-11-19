package com.geely.drivemem.sensors;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** files/can-events.log: what happened to "Exact energy" (CAN), so a trip
 * with no split can be explained after the fact. logcat keeps only minutes;
 * on 2026-11-19 the CAN reader restarted at 15:38 and two trips had no
 * split, and nothing was left to say why.
 *
 * One line per change, never per reading:
 * - "app ...": this app's connection to roadcastd (CanEnergyReader).
 * - "helper ...": modehelper's report on the daemon (started, why, failed).
 * - "trip without split: ...": a trip ended with no split, and why.
 *
 * The same line again within SAME_EVENT_MS is not written. 1 MB, then the
 * file starts over. Text only; it holds no vehicle readings. */
public final class CanEventLog {
    static final String TAG = "DriveMem";
    static final String FILE = "can-events.log";
    private static final long CAP_BYTES = 1024L * 1024;
    static final long SAME_EVENT_MS = 10 * 60_000L;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private static volatile Context app;
    private static String lastMsg;
    private static long lastAtMs;

    private CanEventLog() {}

    public static void init(Context ctx) {
        if (app == null && ctx != null) app = ctx.getApplicationContext();
    }

    public static void log(Context ctx, String msg) {
        init(ctx);
        log(msg);
    }

    public static synchronized void log(String msg) {
        long now = System.currentTimeMillis();
        if (!shouldWrite(msg, now)) return;
        Log.i(TAG, "can: " + msg);
        Context c = app;
        if (c == null) return;
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) dir = c.getFilesDir();
            File f = new File(dir, FILE);
            if (f.exists() && f.length() > CAP_BYTES) f.delete();   // start over, never grow forever
            boolean fresh = !f.exists();
            try (FileWriter w = new FileWriter(f, true)) {
                if (fresh) w.write("wall\tevent\n");
                w.write(FMT.format(new Date(now)) + '\t' + msg.replaceAll("[\\r\\n\\t]+", " ") + '\n');
            }
        } catch (Throwable t) {
            Log.w(TAG, "can: " + FILE + " write: " + t);
        }
    }

    /** False for the same line as the last one, within SAME_EVENT_MS. */
    static synchronized boolean shouldWrite(String msg, long now) {
        if (msg.equals(lastMsg) && now - lastAtMs < SAME_EVENT_MS && now >= lastAtMs) return false;
        lastMsg = msg;
        lastAtMs = now;
        return true;
    }

    static synchronized void resetForTest() { lastMsg = null; lastAtMs = 0; }
}
