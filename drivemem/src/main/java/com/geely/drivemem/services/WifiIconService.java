package com.geely.drivemem.services;

import com.geely.drivemem.R;

import com.geely.drivemem.util.Beat;
import com.geely.drivemem.util.Style;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

/** WiFi status icon in the status bar (concentric arcs). Tapping opens the native
 * WiFi settings. Uses separate foreground service and icon notifications. */
public class WifiIconService extends Service {
    static final String TAG = "DriveMem";
    static final String CH = "drivemem_wifi";
    static final int NID = 79;        // fg service notification (invisible)
    static final int NID_ICON = 80;   // ICON notification (plain notify)
    static final int STATUS_ICON_ID = 98;  // values used by the original app
    static final int STATUS_RANK = 31;

    private HandlerThread thread; private Handler h;
    private volatile boolean running = false;

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        ensureForeground();               // mandatory before the early-return (see OutTempService)
        if (running) return START_STICKY;
        running = true;
        thread = new HandlerThread("wifiicon"); thread.start();
        h = new Handler(thread.getLooper());
        h.post(this::tick);
        return START_STICKY;
    }

    private void ensureForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, getString(R.string.notif_wifi_channel),
                NotificationManager.IMPORTANCE_MIN);
            c.setShowBadge(false); nm.createNotificationChannel(c);
        }
        Notification fg = new Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_wifi_fg_text))
            .setOngoing(true).setShowWhen(false).build();
        startForeground(NID, fg);
    }

    private void tick() {
        if (!running) return;
        try { postIcon(); } catch (Throwable t) { Log.w(TAG, "wifiicon: " + t); }
        Beat.mark(this, Beat.WIFIICON);
        if (running) h.postDelayed(this::tick, 20000); // every 20s
    }

    // 0 = no wifi/turned off, 1..4 = signal levels
    private int level() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return 0;
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1) return 0;
            return Math.max(1, WifiManager.calculateSignalLevel(info.getRssi(), 5));
        } catch (Throwable t) { return 0; }
    }

    private String ssid() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            android.net.wifi.WifiInfo info = (wm != null) ? wm.getConnectionInfo() : null;
            if (info == null) return getString(R.string.notif_wifi_no_network);
            String s = info.getSSID();
            return (s == null) ? getString(R.string.notif_wifi_no_network) : s.replace("\"", "");
        } catch (Throwable t) { return getString(R.string.notif_wifi_no_network); }
    }

    private void postIcon() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        int lvl = level();
        Icon icon = Icon.createWithBitmap(drawWifi(lvl));

        // tapping the icon opens the NATIVE Wi-Fi screen
        Intent open = new Intent("android.settings.WIFI_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, STATUS_ICON_ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT
                | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification n = new Notification.Builder(this, CH)
            .setSmallIcon(icon)
            .setContentTitle(getString(R.string.notif_wifi_title))
            .setContentText(lvl == 0 ? getString(R.string.notif_wifi_disconnected) : ssid())
            .setContentIntent(pi)
            .setOngoing(true).setShowWhen(false)
            .build();
        android.os.Bundle ex = n.extras;
        ex.putBoolean("flag_status_icon_notification", true);
        ex.putInt("flag_status_icon_id", STATUS_ICON_ID);
        ex.putString("flag_status_icon_describe", "StatusIcon_Wifi");
        ex.putParcelable("flag_status_icon_icon", icon);
        ex.putParcelable("flag_status_icon_pressed_icon", icon);
        ex.putBoolean("flag_status_icon_hide", false);
        ex.putInt("flag_status_icon_rank", STATUS_RANK);
        ex.putInt("flag_status_icon_space_x", 1);
        ex.putBoolean("flag_status_icon_is_pick_on", false);
        ex.putInt("flag_status_icon_specific_width", 0);
        try { nm.notify(NID_ICON, n); } catch (Throwable ignored) {}
    }

    // fan of concentric arcs (like the system wifi icon)
    private Bitmap drawWifi(int level) {
        int size = 96;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(size * 0.06f);

        // Draw smaller with margins so icon scales consistently with others in the bar.
        // Use white (alpha channel only); the system renderer tints by alpha.
        int lit = Color.WHITE;
        int dim = 0x40FFFFFF;
        float cx = size / 2f, cy = size * 0.60f;
        for (int i = 0; i < 3; i++) {
            float r = size * (0.11f + i * 0.105f);
            boolean on = level >= (i + 2);      // level 2 lights the 1st arc, and so on
            p.setColor(on ? lit : dim);
            RectF rect = new RectF(cx - r, cy - r, cx + r, cy + r);
            cv.drawArc(rect, 215f, 110f, false, p);
        }
        // dot at the base: lit if there is any connection at all
        p.setStyle(Paint.Style.FILL);
        p.setColor(level >= 1 ? lit : dim);
        cv.drawCircle(cx, cy, size * 0.055f, p);
        return bmp;
    }

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        running = false;
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NID_ICON); } catch (Throwable ignored) {}
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
