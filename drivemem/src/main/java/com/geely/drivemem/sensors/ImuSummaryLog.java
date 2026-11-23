package com.geely.drivemem.sensors;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** files/imu-summary.tsv: compact 1 Hz driving summary of head-unit IMU data,
 * written only while out of Park. Pullable via ADB to inspect real road
 * roughness and driving-smoothness distributions before committing to a CarDb
 * schema bump or final Road/Driver Score curves.
 *
 * Rotates at 5 MB into imu-summary.prev.tsv (~14+ hours of driving per file)
 * so a drive is never lost to rotation. */
final class ImuSummaryLog {
    private static final String TAG = "DriveMem";
    static final String FILE = "imu-summary.tsv";
    private static final String PREV_FILE = "imu-summary.prev.tsv";
    private static final long CAP_BYTES = 5L * 1024 * 1024;
    private static final int FLUSH_EVERY = 10;
    private static final String HEADER =
        "wall\tts_ms\tn\tcal_z\tcal_xy\tspeed_kmh\tdrive_kw\tbrake\tlat\tlon"
      + "\trms_z\tpeak_z\tmean_x\tpeak_x\tmean_y\tpeak_y\tjerk_rms"
      + "\traw_x\traw_y\traw_z";

    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final Date scratchDate = new Date();
    private final StringBuilder sb = new StringBuilder(220);

    private File file;
    private BufferedWriter writer;
    private long bytes;
    private int unflushed;

    synchronized void write(Context ctx, long nowMs, ImuFilter.Summary s,
                            Float speedKmh, Float driveKw, Integer brake,
                            LocationHub.Fix fix) {
        if (ctx == null || s == null) return;
        try {
            if (file == null) {
                File dir = ctx.getExternalFilesDir(null);
                if (dir == null) dir = ctx.getFilesDir();
                file = new File(dir, FILE);
            }
            if (writer == null) openWriter();
            sb.setLength(0);
            scratchDate.setTime(nowMs);
            sb.append(fmt.format(scratchDate)).append('\t')
              .append(nowMs).append('\t')
              .append(s.count).append('\t')
              .append(s.calZ ? 1 : 0).append('\t')
              .append(s.calXy ? 1 : 0).append('\t');
            appendOpt(sb, speedKmh, "%.1f"); sb.append('\t');
            appendOpt(sb, driveKw, "%.1f"); sb.append('\t');
            if (brake != null) sb.append(brake);
            sb.append('\t');
            if (fix != null) {
                sb.append(String.format(Locale.US, "%.5f\t%.5f", fix.lat, fix.lon));
            } else {
                sb.append('\t');
            }
            sb.append(String.format(Locale.US,
                "\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\n",
                s.rmsZ, s.peakZ, s.meanX, s.peakX, s.meanY, s.peakY, s.jerkRms,
                s.rawX, s.rawY, s.rawZ));
            String line = sb.toString();
            writer.write(line);
            bytes += line.length();
            if (++unflushed >= FLUSH_EVERY) {
                writer.flush();
                unflushed = 0;
            }
            if (bytes >= CAP_BYTES) rotate();
        } catch (Throwable t) {
            closeQuietly();
            Log.w(TAG, "imu: summary log write failed: " + t);
        }
    }

    synchronized void flush() {
        if (writer == null) return;
        try {
            writer.flush();
            unflushed = 0;
        } catch (Throwable t) {
            closeQuietly();
        }
    }

    synchronized void close() {
        flush();
        closeQuietly();
    }

    private void openWriter() throws java.io.IOException {
        boolean fresh = !file.exists() || file.length() == 0;
        writer = new BufferedWriter(new FileWriter(file, true), 4096);
        bytes = fresh ? 0 : file.length();
        if (fresh) {
            writer.write(HEADER);
            writer.newLine();
            bytes += HEADER.length() + 1;
        }
    }

    private void rotate() {
        closeQuietly();
        if (file == null) return;
        File prev = new File(file.getParentFile(), PREV_FILE);
        if (prev.exists()) prev.delete();
        file.renameTo(prev);
        bytes = 0;
        unflushed = 0;
    }

    private void closeQuietly() {
        if (writer != null) {
            try { writer.close(); } catch (Throwable ignored) {}
            writer = null;
        }
        unflushed = 0;
    }

    private static void appendOpt(StringBuilder out, Float v, String fmt) {
        if (v != null && Float.isFinite(v)) out.append(String.format(Locale.US, fmt, v));
    }
}
