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

/** Discovery probe: does this head unit have a usable accelerometer? Logs
 * raw TYPE_ACCELEROMETER readings (includes gravity -- this is a first pass,
 * not a calibrated g-force meter) to a plain tab-separated file so they can
 * be lined up offline, by wall-clock second, against drivemem's own
 * obd2-reading.log (same "wall" column format -- see Obd2Reader.LOG_FMT) to
 * see whether accel spikes correspond to real OBD2 power draw. */
public class AccelProbeActivity extends Activity implements SensorEventListener {
    private static final String TAG = "AccelProbe";
    private static final long LOG_CAP_BYTES = 5L * 1024 * 1024;
    private static final SimpleDateFormat LOG_FMT =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private final StringBuilder ui = new StringBuilder();
    private TextView tv;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private long samples = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        tv = new TextView(this);
        tv.setTextSize(13f);
        tv.setPadding(24, 40, 24, 24);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager != null
            ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;

        if (accelerometer == null) {
            line("No TYPE_ACCELEROMETER sensor on this device. Nothing to log.");
            return;
        }
        line("Found: " + accelerometer.getName()
            + "  maxRange=" + accelerometer.getMaximumRange()
            + "  resolution=" + accelerometer.getResolution()
            + "  minDelayUs=" + accelerometer.getMinDelay());
        line("Logging to " + logFile().getAbsolutePath());
    }

    @Override protected void onResume() {
        super.onResume();
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (accelerometer != null) sensorManager.unregisterListener(this);
    }

    @Override public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        double mag = Math.sqrt(x * x + y * y + z * z);
        writeLine(e.timestamp, x, y, z, mag);
        samples++;
        if (samples % 20 == 0) {
            line(String.format(Locale.US, "#%d  x=%.3f y=%.3f z=%.3f |a|=%.3f",
                samples, x, y, z, mag));
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.i(TAG, "accuracy -> " + accuracy);
    }

    private File logFile() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        return new File(dir, "accel.log");
    }

    private void writeLine(long sensorTimestampNs, float x, float y, float z, double mag) {
        String row = LOG_FMT.format(new Date()) + '\t' + System.currentTimeMillis() + '\t'
            + sensorTimestampNs + '\t' + x + '\t' + y + '\t' + z + '\t' + mag;
        try {
            File f = logFile();
            if (f.exists() && f.length() > LOG_CAP_BYTES) {
                f.delete(); // rotate: start over, don't grow forever
            }
            boolean fresh = !f.exists();
            FileWriter w = new FileWriter(f, true);
            try {
                if (fresh) {
                    w.write("wall\tepochMs\tsensorNs\tx\ty\tz\tmagnitude\n");
                }
                w.write(row + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "log write failed: " + t);
        }
    }

    private void line(String s) {
        Log.i(TAG, s);
        ui.append(s).append('\n');
        runOnUiThread(() -> tv.setText(ui.toString()));
    }
}
