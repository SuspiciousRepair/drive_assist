package com.geely.modehelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

/** DUMP-protected, attended-only foreground host for the Stage-1 probe. */
public final class ParkedMonitoringProbeService extends Service {
    static final String ACTION_START = "com.geely.modehelper.svc.PARKED_MONITORING_PROBE_START";
    static final String ACTION_STOP = "com.geely.modehelper.svc.PARKED_MONITORING_PROBE_STOP";
    private static final String CHANNEL = "parked_monitor_probe";
    private static final int NOTIFICATION_ID = 73;
    private static final long HEARTBEAT_INTERVAL_MS = 4_000L;
    private ParkedMonitoringProbe probe;
    private final Handler handler = new Handler();
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            // The normal helper is deliberately stopped during this isolated
            // test. Keep its watchdog from starting a second DVR consumer.
            getSharedPreferences("modehelper", MODE_PRIVATE).edit()
                .putLong("beat_poll", System.currentTimeMillis()).apply();
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            if (probe != null) probe.stop();
            stopDiagnosticHeartbeat();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;
        startAsForeground();
        startDiagnosticHeartbeat();
        if (probe == null) probe = new ParkedMonitoringProbe(this);
        probe.start();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (probe != null) probe.stop();
        stopDiagnosticHeartbeat();
        super.onDestroy();
    }

    private void startDiagnosticHeartbeat() {
        handler.removeCallbacks(heartbeat);
        heartbeat.run();
    }

    private void stopDiagnosticHeartbeat() { handler.removeCallbacks(heartbeat); }

    private void startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                "Parked-monitoring diagnostic", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Attended metadata-only camera probe");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        startForeground(NOTIFICATION_ID, builder
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Camera diagnostic active")
            .setContentText("Metadata-only DVR compatibility probe")
            .setOngoing(true)
            .build());
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
