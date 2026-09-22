package com.geely.accelprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Does the actual sensor logging, independent of AccelProbeActivity's
 * lifecycle -- a foreground Service, not an Activity, because the fix
 * that made a single test drive work (register in onCreate, unregister in
 * onDestroy) only survives a *transient* focus loss. It doesn't survive
 * this car's launcher fully reaping a backgrounded app's task after it
 * sits idle a while (confirmed live: "RecentAppMonitor: removeTask ...
 * state=destroyed"), which is fatal for anything longer than a few
 * minutes -- exactly what a real drive needs. A foreground Service with
 * an active notification isn't tied to any Activity's task/recents entry
 * at all, so the launcher reaping AccelProbeActivity's task can't touch
 * it. Same registration/logging logic AccelProbeActivity used to own
 * directly; see accelprobe/README.md's "Status" section for why each
 * sensor is handled the way it is. */
public class AccelLoggerService extends Service implements SensorEventListener {
    private static final String TAG = "AccelProbe";
    private static final String CHANNEL_ID = "accelprobe_logging";
    private static final int NOTIF_ID = 1;
    private static final long LOG_CAP_BYTES = 5L * 1024 * 1024;
    private static final SimpleDateFormat LOG_FMT =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    // Rolling-average gravity estimate over the raw accelerometer, same
    // constant and same math already proven in correlate.py's
    // linear_magnitudes(): TYPE_LINEAR_ACCELERATION registers "active" in
    // dumpsys sensorservice but never once delivered an event over a full
    // ~9 hour real-drive run (confirmed live, 2026-09-22) -- a dead MTK
    // sensor HAL path, not a registration bug.
    //
    // In-memory only, NOT logged to its own file: every raw sample this
    // needs is already in accel.log, so a linear-accel value is fully
    // recoverable after the fact with zero information loss -- that's
    // exactly what correlate.py's lin_magnitude column already does.
    // Writing a second per-sample log here would just be a duplicate of
    // data already on disk, doubling the accelerometer write rate for
    // nothing new (tried it, reported live as unnecessary -- "why not
    // compute in memory?"). Only the LATEST value is kept, for
    // AccelProbeActivity's live on-screen readout -- a real-time "does
    // this look like near-zero at rest / spike under real motion" sanity
    // check that pulling and post-processing logs can't give you.
    // ~0.5s of samples at whatever rate accelerometer is actually
    // registered at (SENSOR_DELAY_GAME = 50Hz -> 25 samples). Must track
    // that registration -- this is a sample COUNT, so halving the rate
    // without halving this would silently double the real-world window
    // the gravity estimate smooths over.
    private static final int GRAVITY_WINDOW = 25;
    private final float[] gravityRing = new float[GRAVITY_WINDOW * 3];
    private int gravityRingPos = 0;
    private int gravityRingCount = 0;
    private double gravitySumX, gravitySumY, gravitySumZ;
    static volatile double latestLinMag = 0;   // read by AccelProbeActivity's live readout

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor linearAccel;
    private Sensor light;
    private long accelSamples = 0;
    private long linearSamples = 0;
    private long lightSamples = 0;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        startForeground(NOTIF_ID, buildNotification());

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = find(Sensor.TYPE_ACCELEROMETER, "TYPE_ACCELEROMETER");
        linearAccel = find(Sensor.TYPE_LINEAR_ACCELERATION, "TYPE_LINEAR_ACCELERATION");
        light = find(Sensor.TYPE_LIGHT, "TYPE_LIGHT");

        // GAME (50Hz), not FASTEST (~400Hz, what the original discovery
        // pass used to answer "does this sensor deliver anything real at
        // all"): that question's answered now, and OBD2 -- everything
        // this gets correlated against -- only logs once every 2 seconds
        // (Obd2Reader.POLL_MS). 400Hz was ~8x more resolution than
        // anything downstream can use, for ~8x the CPU (measured live:
        // ~20% of one core -> see the same drop reflected in
        // GRAVITY_WINDOW below). 50Hz still resolves a bump/shake's shape
        // (well under a second) just fine.
        if (accelerometer != null) {
            boolean ok = sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            Log.i(TAG, "registerListener(accelerometer, GAME) = " + ok);
        }
        if (linearAccel != null) {
            boolean ok = sensorManager.registerListener(this, linearAccel, SensorManager.SENSOR_DELAY_GAME);
            Log.i(TAG, "registerListener(linearAccel, GAME) = " + ok);
        }
        if (light != null) {
            boolean ok = sensorManager.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
            Log.i(TAG, "registerListener(light, NORMAL) = " + ok);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY: if the OS ever does kill this (foreground services
        // are exempt from ordinary LMK/task-reaping, but not from real
        // memory pressure), ask it to recreate us with a null intent
        // rather than redelivering the last one -- onCreate() re-does
        // everything needed, there's no per-Intent state here.
        return START_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "AccelProbe logging", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AccelProbe")
            .setContentText("Logging sensors in the background")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build();
    }

    private Sensor find(int type, String label) {
        Sensor s = sensorManager != null ? sensorManager.getDefaultSensor(type) : null;
        Log.i(TAG, s == null ? "No " + label + " sensor on this device."
            : "Found " + label + ": " + s.getName());
        return s;
    }

    @Override public void onDestroy() {
        super.onDestroy();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        for (OpenLog log : openLogs.values()) closeQuietly(log.writer);
        openLogs.clear();
    }

    /** Updates the rolling gravity estimate with a new raw sample, and
     * returns the linear (gravity-removed) component of THAT sample.
     * Same running-sum-over-a-ring approach as correlate.py's
     * linear_magnitudes(): O(1) per sample rather than re-averaging the
     * whole window every time. */
    private float[] pushAndRemoveGravity(float x, float y, float z) {
        int slot = gravityRingPos * 3;
        if (gravityRingCount == GRAVITY_WINDOW) {
            // Ring is full: evict the sample this write is about to
            // overwrite from the running sums before adding the new one.
            gravitySumX -= gravityRing[slot];
            gravitySumY -= gravityRing[slot + 1];
            gravitySumZ -= gravityRing[slot + 2];
        } else {
            gravityRingCount++;
        }
        gravityRing[slot] = x; gravityRing[slot + 1] = y; gravityRing[slot + 2] = z;
        gravitySumX += x; gravitySumY += y; gravitySumZ += z;
        gravityRingPos = (gravityRingPos + 1) % GRAVITY_WINDOW;

        float gx = (float) (gravitySumX / gravityRingCount);
        float gy = (float) (gravitySumY / gravityRingCount);
        float gz = (float) (gravitySumZ / gravityRingCount);
        return new float[]{x - gx, y - gy, z - gz};
    }

    @Override public void onSensorChanged(SensorEvent e) {
        String wall = LOG_FMT.format(new Date());
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            double mag = Math.sqrt(x * x + y * y + z * z);
            writeLine(wall, "accel.log", "wall\tepochMs\tsensorNs\tx\ty\tz\tmagnitude",
                "" + e.timestamp + '\t' + x + '\t' + y + '\t' + z + '\t' + mag);
            accelSamples++;

            float[] lin = pushAndRemoveGravity(x, y, z);
            latestLinMag = Math.sqrt(lin[0] * lin[0] + lin[1] * lin[1] + lin[2] * lin[2]);
        } else if (e.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            double mag = Math.sqrt(x * x + y * y + z * z);
            writeLine(wall, "linear_accel.log", "wall\tepochMs\tsensorNs\tx\ty\tz\tmagnitude",
                "" + e.timestamp + '\t' + x + '\t' + y + '\t' + z + '\t' + mag);
            linearSamples++;
        } else if (e.sensor.getType() == Sensor.TYPE_LIGHT) {
            float lux = e.values[0];
            writeLine(wall, "light.log", "wall\tepochMs\tsensorNs\tlux", "" + e.timestamp + '\t' + lux);
            lightSamples++;
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private File dataFile(String name) {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        return new File(dir, name);
    }

    /** One cached, held-open FileWriter per log, plus an in-memory running
     * byte count so rotation doesn't need to stat() the file every sample. */
    private static final class OpenLog {
        FileWriter writer;
        long bytes;
        File file;
        int unflushed;
    }
    // flush() still calls into the OS on every invocation even with no
    // fsync. Cheaper now that accel.log runs at 50Hz instead of ~400Hz,
    // but still not free at any rate -- flushing every FLUSH_EVERY
    // samples instead of every single one trades up to ~400ms of the
    // newest rows on an unclean kill (fine for a debug tool that's
    // started/stopped by hand) for a real cut in syscall count.
    private static final int FLUSH_EVERY = 20;
    private final Map<String, OpenLog> openLogs = new HashMap<>();

    private static void closeQuietly(FileWriter w) {
        if (w == null) return;
        try { w.close(); } catch (Throwable ignored) { }
    }

    // accel.log ran up to 400 writeLine() calls/sec back when this was
    // registered at SENSOR_DELAY_FASTEST -- opening a fresh FileWriter
    // and stat()-ing the file on every single call (the original code)
    // was a real, measurable CPU/IO cost at that rate, not just in
    // theory, and the fix below is worth keeping even at today's lower
    // GAME rate. One
    // FileWriter per log file, opened once and reused, with the rotation
    // size tracked in memory instead of re-stat()ing the file every
    // write. Only the actual write (and the rare rotation) still touches
    // the filesystem.
    private void writeLine(String wall, String fileName, String header, String rowTail) {
        String row = wall + '\t' + System.currentTimeMillis() + '\t' + rowTail;
        OpenLog log = openLogs.get(fileName);
        if (log == null) {
            log = new OpenLog();
            openLogs.put(fileName, log);
        }
        try {
            if (log.file == null) log.file = dataFile(fileName);   // path resolved once, ever
            if (log.writer == null) {
                boolean fresh = !log.file.exists();
                log.writer = new FileWriter(log.file, true);
                log.bytes = fresh ? 0 : log.file.length();
                if (fresh) {
                    log.writer.write(header + "\n");
                    log.bytes += header.length() + 1;
                }
            }
            String line = row + "\n";
            log.writer.write(line);
            if (++log.unflushed >= FLUSH_EVERY) {
                log.writer.flush();
                log.unflushed = 0;
            }
            log.bytes += line.length();
            if (log.bytes > LOG_CAP_BYTES) {
                closeQuietly(log.writer);
                log.writer = null;   // rotate: delete and reopen fresh on the next sample
                log.file.delete();
            }
        } catch (Throwable t) {
            // Self-heal, don't stay broken forever: the original code
            // reopened from scratch on every single call, so an external
            // deletion or storage hiccup was invisible -- next call just
            // worked. Caching the FileWriter across calls (for the CPU/IO
            // win above) reintroduced that failure mode: a held-open
            // FileWriter whose underlying file got deleted out from under
            // it (confirmed live -- `adb shell rm` on accel.log while this
            // service was running) throws on every subsequent write
            // forever, since nothing ever nulled it back out. Closing and
            // clearing it here means the very next sample reopens fresh,
            // matching the old behavior's resilience.
            closeQuietly(log.writer);
            log.writer = null;
            Log.w(TAG, fileName + ": log write failed, will reopen next sample: " + t);
        }
    }
}
