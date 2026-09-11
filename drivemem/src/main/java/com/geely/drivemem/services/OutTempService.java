package com.geely.drivemem.services;

import com.geely.drivemem.R;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.hvac.ComfortRuler;
import com.geely.drivemem.util.Beat;
import com.geely.drivemem.util.Style;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.util.Log;

/** Outside temperature icon in status bar. Displays the temperature as digits
 * via a notification with a custom bitmap icon. */
public class OutTempService extends Service {
    static final String TAG = "DriveMem";
    static final String CH = "drivemem_outtemp";
    static final int NID = 77;       // foreground service notification (invisible in the bar)
    static final int NID_ICON = 78;  // notification of the status ICON (plain notify)
    static final String KEY = "telemetry.outside_temp";
    private boolean fgStarted = false;
    private volatile boolean running = false;
    private volatile Float lastTemp = null;

    // Fed by CarActor's own registered poll (registerPoll below) — this
    // service no longer owns a CarAccess or a timer of its own.
    private final EntityBus.Listener tempListener = (key, reading) -> {
        lastTemp = (reading.status == CarActor.Reading.Status.OK && reading.value instanceof Float)
            ? (Float) reading.value : null;
        postIcon(lastTemp);
        Beat.mark(this, Beat.OUTTEMP);
    };

    // SCREEN_ON can only be registered at runtime (not in the manifest). Since this
    // service stays alive in the foreground, it is the one that turns the Wi-Fi —
    // and itself — back on when the head unit WAKES (the car suspends, it does not boot).
    private final android.content.BroadcastReceiver wakeRx = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent it) {
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    c.getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null && !wm.isWifiEnabled()) {
                    Log.i(TAG, "wake: turning Wi-Fi back on -> " + wm.setWifiEnabled(true));
                }
            } catch (Throwable t) { Log.w(TAG, "wake wifi: " + t); }
            // re-assert the status bar icon (SystemUI may have cleared it
            // during sleep) — from the last known reading, not a fresh car
            // read: CarActor's own poll is already keeping it current.
            if (running) postIcon(lastTemp);
        }
    };

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        // MANDATORY: every startForegroundService() demands a matching
        // startForeground(), even if the service is already running. Without it
        // the system kills the app with RemoteServiceException. That is why it
        // comes BEFORE the early-return.
        ensureForeground();
        if (running) return START_STICKY;
        running = true;
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction("android.intent.action.ACTION_POWER_CONNECTED");
            registerReceiver(wakeRx, f);
        } catch (Throwable t) { Log.w(TAG, "register wake: " + t); }
        // The poll itself is CarActor's own always-on baseline now (its
        // constructor) — ComfortRuler depends on the same key, and a cache
        // key existing only because THIS service happens to be running
        // was the wrong shape. Only the subscription is this service's own.
        CarActor.get(this);   // ensure it exists — harmless if already built
        EntityBus.subscribe(KEY, tempListener);
        return START_STICKY;
    }

    // channel created once; the fg service notification is idempotent
    private void ensureForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, getString(R.string.notif_outtemp_channel),
                NotificationManager.IMPORTANCE_MIN);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        Notification fg = new Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_outtemp_fg_text))
            .setOngoing(true).setShowWhen(false).build();
        startForeground(NID, fg);
        fgStarted = true;
    }

    private void postIcon(Float tempC) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String txt = (tempC == null) ? "--" : String.valueOf(Math.round(tempC));
        Icon icon = Icon.createWithBitmap(drawTemp(txt));

        // The ICON notification — a PLAIN notify() (it does not belong to the fg
        //    service), which is why Geely accepts it in the status icon pipeline.
        Notification n = new Notification.Builder(this, CH)
            .setSmallIcon(icon)
            .setContentTitle(getString(R.string.notif_outtemp_title))
            .setContentText(tempC == null ? getString(R.string.notif_outtemp_unavailable)
                                          : getString(R.string.notif_outtemp_value, txt))
            .setOngoing(true).setShowWhen(false)
            .build();
        android.os.Bundle ex = n.extras;
        ex.putBoolean("flag_status_icon_notification", true);
        ex.putInt("flag_status_icon_id", 15);
        ex.putString("flag_status_icon_describe", "StatusIcon_OutsideTemperature");
        ex.putParcelable("flag_status_icon_icon", icon);
        ex.putParcelable("flag_status_icon_pressed_icon", icon);
        ex.putBoolean("flag_status_icon_hide", false);
        ex.putInt("flag_status_icon_rank", 22);
        ex.putInt("flag_status_icon_space_x", 1);
        ex.putBoolean("flag_status_icon_is_pick_on", false);
        ex.putInt("flag_status_icon_specific_width", 0);
        try { nm.notify(NID_ICON, n); } catch (Throwable ignored) {}
    }

    // draws a big "NN" + a small "°C" beside it, into a wide bitmap. SystemUI
    // scales it to the bar slot; smaller text inside the canvas => smaller in the bar.
    // Target: discreet, about the same size as the clock.
    private Bitmap drawTemp(String num) {
        int h = 96;
        // WHITE, on purpose, not the theme's text colour — the car's own
        // status-icon renderer tints this bitmap by its own alpha (a mask,
        // not a picture), driven by Style.edgeToEdge's LIGHT_STATUS_BAR flag,
        // the same way it tints its own native icons. A non-white source
        // fought that tinting instead of feeding it — see Style.edgeToEdge.
        Paint pn = new Paint(Paint.ANTI_ALIAS_FLAG);
        pn.setColor(Color.WHITE); pn.setFakeBoldText(true);
        pn.setTextSize(h * 0.42f); // number: smaller than it used to be
        Paint pu = new Paint(Paint.ANTI_ALIAS_FLAG);
        pu.setColor(Color.WHITE);
        pu.setTextSize(h * 0.26f); // "°C" much smaller
        String unit = "°C";
        float wNum = pn.measureText(num);
        float gap = h * 0.03f;
        float wUnit = pu.measureText(unit);
        int w = (int) Math.ceil(wNum + gap + wUnit) + 4;
        Bitmap bmp = Bitmap.createBitmap(Math.max(w, 1), h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint.FontMetrics fm = pn.getFontMetrics();
        float y = h / 2f - (fm.ascent + fm.descent) / 2f;
        cv.drawText(num, 2, y, pn);
        cv.drawText(unit, 2 + wNum + gap, y, pu); // baseline aligned
        return bmp;
    }

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        running = false;
        try { unregisterReceiver(wakeRx); } catch (Throwable ignored) {}
        EntityBus.unsubscribe(KEY, tempListener);
        // The poll itself stays registered — CarActor's own baseline, not
        // this service's to tear down; ComfortRuler still needs it.
        // remove the status bar icon (plain notify id 78 does not go away on its own)
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.cancel(NID_ICON);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
