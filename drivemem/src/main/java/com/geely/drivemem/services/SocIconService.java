package com.geely.drivemem.services;

import com.geely.drivemem.R;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
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

/** Battery State of Charge icon in status bar. Draws the percentage as digits
 * alongside a battery glyph filled to the current charge level. */
public class SocIconService extends Service {
    static final String TAG = "DriveMem";
    static final String CH = "drivemem_soc";
    static final int NID = 83;       // fg service notification (invisible in the bar)
    static final int NID_ICON = 84;  // notification of the status ICON (plain notify)

    private volatile boolean running = false;

    // Fed by CarActor's regular telemetry tick — "battery" is already read
    // there every 15s for MQTT, so this was pure duplicate work (its own
    // CarAccess, its own 30s poll of the exact same property) before.
    private final EntityBus.Listener battListener = (key, reading) -> {
        Float pct = (reading.status == CarActor.Reading.Status.OK && reading.value instanceof Integer)
            ? ((Integer) reading.value).floatValue() : null;
        postIcon(pct);
        Beat.mark(this, Beat.SOCICON);
    };

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        // MANDATORY before the early-return — see OutTempService.
        ensureForeground();
        if (running) return START_STICKY;
        running = true;
        EntityBus.subscribe("telemetry.battery", battListener);
        return START_STICKY;
    }

    private void ensureForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, getString(R.string.notif_soc_channel),
                NotificationManager.IMPORTANCE_MIN);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        Notification fg = new Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_soc_fg_text))
            .setOngoing(true).setShowWhen(false).build();
        startForeground(NID, fg);
    }

    private void postIcon(Float pct) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String txt = (pct == null) ? "--" : String.valueOf(Math.round(pct));
        Icon icon = Icon.createWithBitmap(drawSoc(txt, pct));

        Notification n = new Notification.Builder(this, CH)
            .setSmallIcon(icon)
            .setContentTitle(getString(R.string.notif_soc_title))
            .setContentText(pct == null ? getString(R.string.notif_soc_unavailable)
                                        : getString(R.string.notif_soc_value, txt))
            .setOngoing(true).setShowWhen(false)
            .build();
        android.os.Bundle ex = n.extras;
        ex.putBoolean("flag_status_icon_notification", true);
        // The rank field determines status bar position. Rank must avoid collisions
        // with built-in icon slots. Using rank 23, which is unused by OEM icons and
        // clear of other service icons (OutTemp=22, WifiIcon=31).
        ex.putInt("flag_status_icon_id", 17);
        ex.putString("flag_status_icon_describe", "StatusIcon_BatterySoC");
        ex.putParcelable("flag_status_icon_icon", icon);
        ex.putParcelable("flag_status_icon_pressed_icon", icon);
        ex.putBoolean("flag_status_icon_hide", false);
        ex.putInt("flag_status_icon_rank", 23);
        ex.putInt("flag_status_icon_space_x", 1);
        ex.putBoolean("flag_status_icon_is_pick_on", false);
        ex.putInt("flag_status_icon_specific_width", 0);
        try { nm.notify(NID_ICON, n); } catch (Throwable ignored) {}
    }

    // draws a big "NN" + a wireframe battery glyph beside it, filled to the
    // charge fraction — same layout as OutTempService's drawTemp(), the "%"
    // text swapped for a shape so the level reads at a glance, the way a
    // phone's own status bar does it. Unknown (pct == null) draws an empty
    // outline: nothing to fill, not a guess at zero.
    // WHITE on purpose, not the theme's text colour — see
    // OutTempService.drawTemp()'s comment, same reasoning.
    private Bitmap drawSoc(String num, Float pct) {
        int h = 96;
        Paint pn = new Paint(Paint.ANTI_ALIAS_FLAG);
        pn.setColor(Color.WHITE); pn.setFakeBoldText(true);
        pn.setTextSize(h * 0.42f);
        float wNum = pn.measureText(num);
        float gap = h * 0.05f;

        // Vertical, nub on top — a fuel-gauge shape, not the sideways phone
        // kind. Fills bottom-up to match.
        float bodyW = h * 0.20f, bodyH = h * 0.42f;
        float nubW = h * 0.09f, nubH = h * 0.045f;
        float stroke = h * 0.032f;   // thinner outline than the first pass
        float iconW = bodyW;
        float iconH = bodyH + nubH;

        int w = (int) Math.ceil(wNum + gap + iconW) + 4;
        Bitmap bmp = Bitmap.createBitmap(Math.max(w, 1), h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint.FontMetrics fm = pn.getFontMetrics();
        float y = h / 2f - (fm.ascent + fm.descent) / 2f;
        cv.drawText(num, 2, y, pn);

        float bx = 2 + wNum + gap;
        float assemblyTop = h / 2f - iconH / 2f;
        float nubTop = assemblyTop, by = assemblyTop + nubH;
        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setColor(Color.WHITE);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(stroke);
        float r = bodyW * 0.22f;
        android.graphics.RectF body = new android.graphics.RectF(bx, by, bx + bodyW, by + bodyH);
        cv.drawRoundRect(body, r, r, outline);
        cv.drawRect(bx + bodyW / 2f - nubW / 2f, nubTop,
                    bx + bodyW / 2f + nubW / 2f, by, outline);

        if (pct != null) {
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
            float inset = stroke * 0.9f;
            float frac = Math.max(0f, Math.min(1f, pct / 100f));
            android.graphics.RectF full = new android.graphics.RectF(
                bx + inset, by + inset, bx + bodyW - inset, by + bodyH - inset);
            float fillH = full.height() * frac;
            android.graphics.RectF filled = new android.graphics.RectF(
                full.left, full.bottom - fillH, full.right, full.bottom);
            float fr = Math.max(0f, r - inset);
            cv.drawRoundRect(filled, fr, fr, fill);
        }
        return bmp;
    }

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        running = false;
        EntityBus.unsubscribe("telemetry.battery", battListener);
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.cancel(NID_ICON);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
