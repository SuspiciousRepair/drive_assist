package com.geely.drivemem.services;

import com.geely.drivemem.R;
import com.geely.drivemem.ui.ExtraDark;
import com.geely.drivemem.util.LocalAdb;
import com.geely.drivemem.util.Prefs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** Darkest mode "On every screen" (#130): the same rules as the Dashboard's
 * (ExtraDark: the car's dark mode, the mode's hours, out of Park, 60 s with
 * no touch), but darkening every app through one overlay window.
 *
 * One window, two shapes:
 *  - awake: 1 x 1 px in a corner, transparent, FLAG_WATCH_OUTSIDE_TOUCH. It
 *    hears every touch on the screen as ACTION_OUTSIDE (no position, which
 *    is all it needs): that is the "no touch for 60 s" clock.
 *  - dimmed: the whole screen, black at the driver's darkness, with the
 *    backlight override at the panel floor. It takes the touch that wakes
 *    the screen, so nobody presses a button they could not see; the rest of
 *    that gesture stays with this window, which is already back to 1 x 1.
 *
 * The car's own system bars sit above application overlays, so they get the
 * backlight floor only. Runs while Darkest mode and "On every screen" are
 * both on (ensureAll, Config); stops itself otherwise. */
public final class DarkestOverlayService extends Service {
    /** startService action: Config's Try button, dim now. */
    public static final String ACTION_TRY = "com.geely.drivemem.DARKEST_TRY";

    private static final String CH = "drivemem_darkest";
    private static final int NID = 86;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View view;
    private WindowManager.LayoutParams lp;
    private ExtraDark dark;
    private boolean destroyed, granting, pendingTry;

    /** Starts or stops the service to match the two switches. */
    public static void sync(Context ctx) {
        Intent s = new Intent(ctx, DarkestOverlayService.class);
        if (Prefs.getExtraDark(ctx) && Prefs.getExtraDarkGlobal(ctx)) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(s);
                else ctx.startService(s);
            } catch (Throwable t) { android.util.Log.w("DriveMem", "darkest start: " + t); }
        } else {
            ctx.stopService(s);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ensureForeground();   // first, always: see OverlayService
        if (!Prefs.getExtraDark(this) || !Prefs.getExtraDarkGlobal(this)) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null && ACTION_TRY.equals(intent.getAction())) pendingTry = true;
        if (!Settings.canDrawOverlays(this)) { requestGrantThenShow(); return START_STICKY; }
        if (view == null) show();
        takeTry();
        return START_STICKY;
    }

    private void takeTry() {
        if (pendingTry && dark != null) { pendingTry = false; dark.tryNow(); }
    }

    // Same self-grant as OverlayService (#115).
    private void requestGrantThenShow() {
        if (granting) return;
        granting = true;
        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            try { ok = LocalAdb.grantOverlayPermission(app); }
            catch (Throwable t) { android.util.Log.w("DriveMem", "darkest self-grant: " + t); }
            final boolean granted = ok;
            handler.post(() -> {
                granting = false;
                if (destroyed) return;
                if (granted && Settings.canDrawOverlays(this)) {
                    if (view == null) show();
                    takeTry();
                } else {
                    stopSelf();
                }
            });
        }, "darkest-grant").start();
    }

    private void show() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        view = new View(this);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        lp = new WindowManager.LayoutParams(1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        dark = new ExtraDark(this, handler, this::apply);
        apply(false);
        // Every touch anywhere (OUTSIDE while awake), or the touch on the
        // shade while dimmed: ExtraDark wakes, and swallows the waking touch.
        view.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { dark.wake(); return false; }
            return dark.onTouch(e);
        });
        try { wm.addView(view, lp); }
        catch (Throwable t) { android.util.Log.w("DriveMem", "darkest overlay: " + t); view = null; stopSelf(); return; }
        dark.resume();
    }

    /** ExtraDark.Target: the window's two shapes (see the class comment). */
    private void apply(boolean dimmed) {
        if (dimmed) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.screenBrightness = ExtraDark.BACKLIGHT_FLOOR;
            view.setBackgroundColor(Color.BLACK);
            view.setAlpha(ExtraDark.shadeAlpha(this));
        } else {
            lp.width = 1;
            lp.height = 1;
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            view.setBackgroundColor(Color.TRANSPARENT);
            view.setAlpha(1f);
        }
        if (view.getParent() != null) {
            try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
        }
    }

    private void ensureForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, getString(R.string.cfg_extra_dark_label),
                NotificationManager.IMPORTANCE_MIN);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        Notification fg = new Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.cfg_extra_dark_label))
            .setOngoing(true).setShowWhen(false).build();
        startForeground(NID, fg);
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (dark != null) dark.pause();   // light back, gear listener off
        handler.removeCallbacksAndMessages(null);
        if (view != null && view.getParent() != null) {
            try { wm.removeView(view); } catch (Throwable ignored) {}
        }
        view = null;
        super.onDestroy();
    }
}
