package com.geely.accelprobe;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** Thin launcher: starts/stops AccelLoggerService, which does the actual
 * sensor logging and keeps running when this Activity is backgrounded,
 * switched away from, or reaped by the car's launcher (see
 * AccelLoggerService's own doc comment for why an Activity alone can't
 * survive an hour-long drive). Safe to leave this screen and open
 * Drive Assist / navigation once the service is started. */
public class AccelProbeActivity extends Activity {
    private static final int LIVE_READOUT_PERIOD_MS = 500;
    private final Handler handler = new Handler();
    private TextView liveReadout;

    // Polls AccelLoggerService.latestLinMag purely for a live "does this
    // look right" sanity check -- near-zero at rest, a real spike under
    // real motion -- without persisting a single extra byte to disk (see
    // that field's own comment for why a second log file was tried and
    // dropped). Only runs while this screen is visible; the service's own
    // computation keeps going regardless.
    private final Runnable liveReadoutTick = new Runnable() {
        @Override public void run() {
            liveReadout.setText(String.format(Locale.US,
                "Live linear-accel magnitude: %.3f m/s²", AccelLoggerService.latestLinMag));
            handler.postDelayed(this, LIVE_READOUT_PERIOD_MS);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        TextView tv = new TextView(this);
        tv.setTextSize(14f);
        tv.setPadding(0, 0, 0, 32);
        root.addView(tv);

        liveReadout = new TextView(this);
        liveReadout.setTextSize(14f);
        liveReadout.setPadding(0, 0, 0, 32);
        root.addView(liveReadout);

        Button startBtn = new Button(this);
        startBtn.setText("Start logging");
        startBtn.setOnClickListener(v -> {
            startForegroundService(new Intent(this, AccelLoggerService.class));
            tv.setText("Service started. Safe to switch to Drive Assist / navigation now --\n"
                + "it keeps logging in the background. Come back here and tap Stop when done.");
        });
        root.addView(startBtn);

        Button stopBtn = new Button(this);
        stopBtn.setText("Stop logging");
        stopBtn.setOnClickListener(v -> {
            stopService(new Intent(this, AccelLoggerService.class));
            tv.setText("Service stopped.");
        });
        root.addView(stopBtn);

        setContentView(root);

        tv.setText(isServiceRunning()
            ? "Service is already running in the background."
            : "Not running. Tap Start, then you can switch away.");
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(liveReadoutTick);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(liveReadoutTick);
    }

    private boolean isServiceRunning() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo s : am.getRunningServices(Integer.MAX_VALUE)) {
            if (AccelLoggerService.class.getName().equals(s.service.getClassName())) return true;
        }
        return false;
    }
}
