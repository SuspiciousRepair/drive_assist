package com.geely.accelprobe;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Thin launcher: starts/stops AccelLoggerService, which does the actual
 * sensor logging and keeps running when this Activity is backgrounded,
 * switched away from, or reaped by the car's launcher (see
 * AccelLoggerService's own doc comment for why an Activity alone can't
 * survive an hour-long drive). Safe to leave this screen and open
 * Drive Assist / navigation once the service is started. */
public class AccelProbeActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        TextView tv = new TextView(this);
        tv.setTextSize(14f);
        tv.setPadding(0, 0, 0, 32);
        root.addView(tv);

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

    private boolean isServiceRunning() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo s : am.getRunningServices(Integer.MAX_VALUE)) {
            if (AccelLoggerService.class.getName().equals(s.service.getClassName())) return true;
        }
        return false;
    }
}
