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
import java.util.Locale;

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

        if (accelerometer != null) {
            boolean ok = sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
            Log.i(TAG, "registerListener(accelerometer, FASTEST) = " + ok);
        }
        if (linearAccel != null) {
            boolean ok = sensorManager.registerListener(this, linearAccel, SensorManager.SENSOR_DELAY_FASTEST);
            Log.i(TAG, "registerListener(linearAccel, FASTEST) = " + ok);
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
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            double mag = Math.sqrt(x * x + y * y + z * z);
            writeLine("accel.log", "wall\tepochMs\tsensorNs\tx\ty\tz\tmagnitude",
                "" + e.timestamp + '\t' + x + '\t' + y + '\t' + z + '\t' + mag);
            accelSamples++;
        } else if (e.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            double mag = Math.sqrt(x * x + y * y + z * z);
            writeLine("linear_accel.log", "wall\tepochMs\tsensorNs\tx\ty\tz\tmagnitude",
                "" + e.timestamp + '\t' + x + '\t' + y + '\t' + z + '\t' + mag);
            linearSamples++;
        } else if (e.sensor.getType() == Sensor.TYPE_LIGHT) {
            float lux = e.values[0];
            writeLine("light.log", "wall\tepochMs\tsensorNs\tlux", "" + e.timestamp + '\t' + lux);
            lightSamples++;
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private File dataFile(String name) {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        return new File(dir, name);
    }

    private void writeLine(String fileName, String header, String rowTail) {
        String row = LOG_FMT.format(new Date()) + '\t' + System.currentTimeMillis() + '\t' + rowTail;
        try {
            File f = dataFile(fileName);
            if (f.exists() && f.length() > LOG_CAP_BYTES) {
                f.delete(); // rotate: start over, don't grow forever
            }
            boolean fresh = !f.exists();
            FileWriter w = new FileWriter(f, true);
            try {
                if (fresh) w.write(header + "\n");
                w.write(row + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, fileName + ": log write failed: " + t);
        }
    }
}
