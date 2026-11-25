package com.geely.drivemem.sensors;

import android.content.Context;
import android.util.Log;

import com.geely.drivemem.state.CarDiary;
import com.geely.drivemem.util.Prefs;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Driver-labeled jolt calibration feedback log (files/jolt-feedback.tsv).
 *
 * Every time the driver taps "Log incident" (LOGGED) or "Dismiss" (DISMISSED)
 * on a detected jolt banner, one TSV row is appended with the jolt's physical
 * signature (peak G on Z/X/Y, vertical RMS, vertical jerk in g/s, speed, GPS,
 * and active sensitivity). Over time this builds labeled ground truth for the
 * car's suspension and local streets, and lets Settings suggest raising the
 * sensitivity threshold when multiple benign bumps are dismissed. */
public final class JoltFeedback {
    private static final String TAG = "DriveMem";
    public static final String FILE_NAME = "jolt-feedback.tsv";
    private static final String HEADER =
        "wall\tts_ms\tverdict\tsensitivity\tspeed_kmh\tpeak_z_g\tpeak_jerk_z_gs"
      + "\trms_z_g\tpeak_x_g\tpeak_y_g\tlat\tlon";

    private JoltFeedback() {}

    /** One parsed feedback row. */
    public static final class Sample {
        public final long tsMs;
        public final boolean logged;
        public final String sensitivity;
        public final float peakZG, peakJerkZGs, rmsZG, peakXG, peakYG;
        public final Float speedKmh;

        public Sample(long tsMs, boolean logged, String sensitivity, Float speedKmh,
                      float peakZG, float peakJerkZGs, float rmsZG, float peakXG, float peakYG) {
            this.tsMs = tsMs;
            this.logged = logged;
            this.sensitivity = sensitivity;
            this.speedKmh = speedKmh;
            this.peakZG = peakZG;
            this.peakJerkZGs = peakJerkZGs;
            this.rmsZG = rmsZG;
            this.peakXG = peakXG;
            this.peakYG = peakYG;
        }

        public float maxG() {
            return Math.max(Math.abs(peakZG), Math.max(Math.abs(peakXG), Math.abs(peakYG)));
        }
    }

    /** Aggregated calibration summary from all driver verdicts. Pure. */
    public static final class Stats {
        public final int loggedCount;
        public final int dismissedCount;
        public final float maxDismissedG;   // 0 if none dismissed
        public final float minLoggedG;      // 0 if none logged
        public final String suggestedSensitivity; // null when current setting looks good

        public Stats(int loggedCount, int dismissedCount, float maxDismissedG,
                     float minLoggedG, String suggestedSensitivity) {
            this.loggedCount = loggedCount;
            this.dismissedCount = dismissedCount;
            this.maxDismissedG = maxDismissedG;
            this.minLoggedG = minLoggedG;
            this.suggestedSensitivity = suggestedSensitivity;
        }

        public int total() { return loggedCount + dismissedCount; }
    }

    public static File file(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        return new File(dir, FILE_NAME);
    }

    /** Appends one driver verdict (LOGGED or DISMISSED) asynchronously. */
    public static void record(Context ctx, CarDiary.PendingJolt j, boolean logged) {
        if (ctx == null || j == null) return;
        final Context app = ctx.getApplicationContext();
        final String sensitivity = Prefs.getDiaryJoltSensitivity(app);
        new Thread(() -> appendSync(file(app), j, logged, sensitivity), "jolt-fb").start();
    }

    static synchronized void appendSync(File f, CarDiary.PendingJolt j, boolean logged, String sensitivity) {
        try {
            boolean fresh = !f.exists() || f.length() == 0;
            try (BufferedWriter w = new BufferedWriter(new FileWriter(f, true))) {
                if (fresh) {
                    w.write(HEADER);
                    w.newLine();
                }
                String wall = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(j.tsMs));
                String spd = j.speedKmh != null && Float.isFinite(j.speedKmh)
                    ? String.format(Locale.US, "%.1f", j.speedKmh) : "";
                String lat = j.lat != null ? String.format(Locale.US, "%.5f", j.lat) : "";
                String lon = j.lon != null ? String.format(Locale.US, "%.5f", j.lon) : "";
                w.write(String.format(Locale.US,
                    "%s\t%d\t%s\t%s\t%s\t%.3f\t%.2f\t%.3f\t%.3f\t%.3f\t%s\t%s\n",
                    wall, j.tsMs, logged ? "LOGGED" : "DISMISSED",
                    sensitivity != null ? sensitivity : ImuHub.JOLT_MEDIUM,
                    spd, j.peakZG, j.peakJerkZGs, j.rmsZG, j.peakXG, j.peakYG, lat, lon));
            }
        } catch (Throwable t) {
            Log.w(TAG, "jolt feedback write failed: " + t);
        }
    }

    /** Reads and summarizes all recorded feedback samples. */
    public static Stats readStats(Context ctx) {
        if (ctx == null) return summarize(new ArrayList<>(), ImuHub.JOLT_MEDIUM);
        String cur = Prefs.getDiaryJoltSensitivity(ctx);
        return summarize(readSamples(file(ctx)), cur);
    }

    static List<Sample> readSamples(File f) {
        List<Sample> out = new ArrayList<>();
        if (f == null || !f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first && line.startsWith("wall\t")) { first = false; continue; }
                first = false;
                String[] c = line.split("\t", -1);
                if (c.length < 10) continue;
                try {
                    long ts = Long.parseLong(c[1]);
                    boolean logged = "LOGGED".equals(c[2]);
                    String sens = c[3];
                    Float spd = c[4].isEmpty() ? null : Float.parseFloat(c[4]);
                    float pz = Float.parseFloat(c[5]);
                    float pjz = Float.parseFloat(c[6]);
                    float rz = Float.parseFloat(c[7]);
                    float px = Float.parseFloat(c[8]);
                    float py = Float.parseFloat(c[9]);
                    out.add(new Sample(ts, logged, sens, spd, pz, pjz, rz, px, py));
                } catch (NumberFormatException ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "jolt feedback read failed: " + t);
        }
        return out;
    }

    /** Pure summary & preset suggestion from labeled jolt samples. */
    public static Stats summarize(List<Sample> samples, String currentSensitivity) {
        int logged = 0, dismissed = 0;
        float maxDisG = 0f;
        float minLogG = Float.MAX_VALUE;
        int dismissedAboveCurrent = 0;
        float[] curTh = ImuHub.joltThresholds(currentSensitivity);
        float curFloor = curTh != null ? Math.min(curTh[0], curTh[1]) : Float.MAX_VALUE;

        if (samples != null) {
            for (Sample s : samples) {
                float g = s.maxG();
                if (s.logged) {
                    logged++;
                    if (g < minLogG) minLogG = g;
                } else {
                    dismissed++;
                    if (g > maxDisG) maxDisG = g;
                    if (g >= curFloor) dismissedAboveCurrent++;
                }
            }
        }
        if (logged == 0) minLogG = 0f;

        // Suggest stepping sensitivity down (higher G floor) when at least 2 benign bumps
        // were dismissed above the current floor and no real incident was logged below the next floor.
        String suggest = null;
        if (dismissedAboveCurrent >= 2) {
            if (ImuHub.JOLT_HIGH.equals(currentSensitivity) && (logged == 0 || minLogG >= 0.85f)) {
                suggest = ImuHub.JOLT_MEDIUM;
            } else if (ImuHub.JOLT_MEDIUM.equals(currentSensitivity) && (logged == 0 || minLogG >= 1.10f)) {
                suggest = ImuHub.JOLT_LOW;
            }
        }
        return new Stats(logged, dismissed, maxDisG, minLogG, suggest);
    }
}
