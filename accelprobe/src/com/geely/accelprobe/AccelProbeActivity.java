package com.geely.accelprobe;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Discovery probe: does this head unit have usable motion/light sensors,
 * and does acceleration correlate with real OBD2 power draw? Logs raw
 * sensor readings to plain tab-separated files so they can be lined up
 * offline against drivemem's own obd2-reading.log -- see correlate.py,
 * and README.md for why that needs a nearest-timestamp join, not an exact
 * one, and why it's capped at about +-1s either way.
 *
 * accel.log is raw TYPE_ACCELEROMETER (includes gravity -- diluted the
 * first correlation attempt, see README). linear_accel.log is
 * TYPE_LINEAR_ACCELERATION, which the platform defines as gravity-removed
 * -- the better candidate for an actual g-force/power correlation. */
public class AccelProbeActivity extends Activity implements SensorEventListener {
    private static final String TAG = "AccelProbe";
    private static final long LOG_CAP_BYTES = 5L * 1024 * 1024;
    private static final SimpleDateFormat LOG_FMT =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private final StringBuilder ui = new StringBuilder();
    private TextView tv;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor linearAccel;
    private Sensor light;
    private long accelSamples = 0;
    private long linearSamples = 0;
    private long lightSamples = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        tv = new TextView(this);
        tv.setTextSize(13f);
        tv.setPadding(24, 40, 24, 24);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = find(Sensor.TYPE_ACCELEROMETER, "TYPE_ACCELEROMETER");
        linearAccel = find(Sensor.TYPE_LINEAR_ACCELERATION, "TYPE_LINEAR_ACCELERATION");
        light = find(Sensor.TYPE_LIGHT, "TYPE_LIGHT");

        // Register here, in onCreate, and only unregister in onDestroy --
        // NOT the usual onResume/onPause pairing. Confirmed live via
        // `dumpsys sensorservice`: this head unit's launcher briefly steals
        // focus right after any app launches (com.flyme.auto.launcher),
        // which fires onPause() a fraction of a second in. The sensor was
        // never the problem -- dumpsys showed it actively delivering real
        // data the whole time, to a system component (com.njda.adapter)
        // that was simply never told to stop. Registering once here means
        // a transient focus loss can't tear the listener down mid-drive.
        if (accelerometer != null) {
            boolean ok = sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
            line("registerListener(accelerometer, FASTEST) = " + ok);
        }
        if (linearAccel != null) {
            // Gravity-free, per its own definition, and "no batching" in
            // dumpsys sensorservice's Sensor List -- unlike raw
            // ACCELEROMETER's FIFO, so this may sidestep the whole
            // "First flush pending" stall entirely. Confirming live.
            boolean ok = sensorManager.registerListener(this, linearAccel, SensorManager.SENSOR_DELAY_FASTEST);
            line("registerListener(linearAccel, FASTEST) = " + ok);
        }
        if (light != null) {
            boolean ok = sensorManager.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
            line("registerListener(light, NORMAL) = " + ok);
        }
    }

    private Sensor find(int type, String label) {
        Sensor s = sensorManager != null ? sensorManager.getDefaultSensor(type) : null;
        if (s == null) {
            line("No " + label + " sensor on this device.");
        } else {
            line("Found " + label + ": " + s.getName()
                + "  maxRange=" + s.getMaximumRange()
                + "  resolution=" + s.getResolution()
                + "  minDelayUs=" + s.getMinDelay());
        }
        return s;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        sensorManager.unregisterListener(this);
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            double mag = Math.sqrt(x * x + y * y + z * z);
            writeLine("accel.log", "wall\tepochMs\tsensorNs\tx\ty\tz\tmagnitude",
                "" + e.timestamp + '\t' + x + '\t' + y + '\t' + z + '\t' + mag);
            accelSamples++;
            if (accelSamples % 20 == 0) {
                line(String.format(Locale.US, "accel #%d  x=%.3f y=%.3f z=%.3f |a|=%.3f",
                    accelSamples, x, y, z, mag));
            }
        } else if (e.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            double mag = Math.sqrt(x * x + y * y + z * z);
            writeLine("linear_accel.log", "wall\tepochMs\tsensorNs\tx\ty\tz\tmagnitude",
                "" + e.timestamp + '\t' + x + '\t' + y + '\t' + z + '\t' + mag);
            linearSamples++;
            if (linearSamples % 20 == 0) {
                line(String.format(Locale.US, "linear #%d  x=%.3f y=%.3f z=%.3f |a|=%.3f",
                    linearSamples, x, y, z, mag));
            }
        } else if (e.sensor.getType() == Sensor.TYPE_LIGHT) {
            float lux = e.values[0];
            writeLine("light.log", "wall\tepochMs\tsensorNs\tlux",
                "" + e.timestamp + '\t' + lux);
            lightSamples++;
            line(String.format(Locale.US, "light #%d  lux=%.1f", lightSamples, lux));
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.i(TAG, sensor.getName() + " accuracy -> " + accuracy);
    }

    private File dataFile(String name) {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        return new File(dir, name);
    }

    /** rowTail is everything after the wall/epochMs columns, which this
     * method always prepends itself so every log file shares the same
     * first two columns regardless of which sensor wrote it. */
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

    private void line(String s) {
        Log.i(TAG, s);
        ui.append(s).append('\n');
        runOnUiThread(() -> tv.setText(ui.toString()));
    }
}
