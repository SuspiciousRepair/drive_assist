package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.util.Prefs;

/** Extra dark (#89): at night, after a while of driving with no touch, the
 * Dashboard dims the panel further. A touch, a gear change or leaving the
 * screen brings the car's own brightness back.
 *
 * The panel backlight goes to its floor via a WINDOW override
 * (WindowManager.LayoutParams.screenBrightness), so it is gone when the
 * Dashboard is left. The car's own brightness setting is never written, so
 * there is no second copy of it to drift. Measured on the car 2026-11-16:
 * the backlight (/sys/class/leds/lcd-backlight) follows Android's
 * brightness, 10..255; 10 is the floor (dumpsys display:
 * mScreenBrightnessRangeMinimum). A black shade on top was tried in sample
 * images and dropped: the owner found the floor dark enough.
 *
 * "Night" is the car's dark mode (Configuration.uiMode night), the same
 * signal the Auto appearance follows. */
public final class ExtraDark {
    /** No touch for this long while driving at night: dim. */
    static final long DIM_AFTER_MS = 60_000L;
    /** Asks for the lowest backlight: the system clamps it to the panel's
     * floor (10 of 255 on this unit), the darkest it goes. */
    static final float BACKLIGHT_FLOOR = 1f / 255f;
    private static final String GEAR_KEY = "car.gear";

    private final Activity activity;
    private final Handler ui;
    private boolean enabled, dimmed, swallowing;
    private long lastWakeMs;
    private final Runnable check = this::check;
    private final EntityBus.Listener gearListener;

    public ExtraDark(Activity activity, Handler ui) {
        this.activity = activity;
        this.ui = ui;
        this.gearListener = (key, reading) -> ui.post(this::wake);
    }

    /** Whether the screen should be dimmed now. */
    static boolean shouldDim(boolean enabled, boolean night, boolean parked, long quietMs) {
        return enabled && night && !parked && quietMs >= DIM_AFTER_MS;
    }

    public void resume() {
        enabled = Prefs.getExtraDark(activity);
        if (enabled) EntityBus.subscribe(GEAR_KEY, gearListener);
        wake();
    }

    public void pause() {
        EntityBus.unsubscribe(GEAR_KEY, gearListener);
        ui.removeCallbacks(check);
        undim();
        swallowing = false;
    }

    /** A touch, a gear change, a Park/drive edge: back to the car's own
     * brightness, and the quiet time starts again. */
    public void wake() {
        lastWakeMs = SystemClock.uptimeMillis();
        undim();
        ui.removeCallbacks(check);
        if (enabled) ui.postDelayed(check, DIM_AFTER_MS);
    }

    /** Called from the activity's dispatchTouchEvent. Returns true when the
     * touch only woke a dimmed screen and must not reach the views: nobody
     * presses a button they could not see. */
    public boolean onTouch(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            swallowing = dimmed;
            wake();
            return swallowing;
        }
        if (!swallowing) return false;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallowing = false;
        return true;
    }

    private void check() {
        long quiet = SystemClock.uptimeMillis() - lastWakeMs;
        if (shouldDim(enabled, night(), CarState.isParked(), quiet)) {
            dim();
        } else if (enabled && !dimmed) {
            // Parked or daytime now: look again later (one post a minute).
            ui.postDelayed(check, DIM_AFTER_MS);
        }
    }

    private boolean night() {
        int m = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    private void dim() {
        setWindow(BACKLIGHT_FLOOR);
        dimmed = true;
    }

    private void undim() {
        if (!dimmed) return;
        setWindow(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        dimmed = false;
    }

    private void setWindow(float level) {
        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
        lp.screenBrightness = level;
        activity.getWindow().setAttributes(lp);
    }
}
