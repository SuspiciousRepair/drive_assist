package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.SystemClock;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.util.Prefs;

/** Darkest mode (#89, #130; called "Extra dark" before): at night, after a
 * while of driving with no touch, the Dashboard dims the panel further. A
 * touch, a gear change or leaving the screen brings the car's own brightness
 * back.
 *
 * The panel backlight goes to its floor via a WINDOW override
 * (WindowManager.LayoutParams.screenBrightness), so it is gone when the
 * Dashboard is left. The car's own brightness setting is never written, so
 * there is no second copy of it to drift. Measured on the car 2026-11-16:
 * the backlight (/sys/class/leds/lcd-backlight) follows Android's
 * brightness, 10..255; 10 is the floor (dumpsys display:
 * mScreenBrightnessRangeMinimum).
 *
 * On top of the backlight, a black shade: one more element of the
 * Dashboard's own layout, the last child of its root so it draws above every
 * other view (attach()). It is an element, not a window overlay, because it
 * only darkens Drive Assist. Shown on dim, gone on undim. Brought back
 * 2026-11-19 (#130): with the backlight alone, a user saw no change.
 *
 * Below that floor: 2 of 255 still lights the panel (1 is off), measured
 * 2026-11-17, and the owner wanted it darker than 10. Android will not go
 * below 10 and SELinux keeps modehelper off the backlight file, so
 * modehelper writes it through the car's local root adbd
 * (BacklightFloorReceiver), turning ADB on for a short window if it is off.
 * FLOOR_DELAY_MS after the dim,
 * so Android has finished its own ramp to 10 and does not write over it.
 * Undim asks for the car's level back as well as dropping the override.
 *
 * "Night" is the car's dark mode (Configuration.uiMode night), the same
 * signal the Auto appearance follows. */
public final class ExtraDark {
    /** No touch for this long while driving at night: dim. */
    static final long DIM_AFTER_MS = 60_000L;
    /** Asks for the lowest backlight: the system clamps it to the panel's
     * floor (10 of 255 on this unit), the darkest it goes. */
    static final float BACKLIGHT_FLOOR = 1f / 255f;
    /** After the dim, before asking modehelper for the real floor. */
    static final long FLOOR_DELAY_MS = 2000L;
    /** Opacity of the black shade: 0.7 lets 30 % of the dimmed light through,
     * so the speed and the numbers can still be read. */
    static final float SHADE_ALPHA = 0.7f;
    static final String HELPER_FLOOR = "com.geely.modehelper.BacklightFloorReceiver";
    /** Intent extra on ComfortActivity: Config's Try button. */
    public static final String EXTRA_TRY = "darkest_try";
    private static final String GEAR_KEY = "car.gear";

    private final Activity activity;
    private final Handler ui;
    private boolean enabled, dimmed, swallowing;
    private View shade;
    private long lastWakeMs;
    private final Runnable check = this::check;
    private final Runnable floor = () -> { if (dimmed) helperFloor(true); };
    private final EntityBus.Listener gearListener;

    public ExtraDark(Activity activity, Handler ui) {
        this.activity = activity;
        this.ui = ui;
        this.gearListener = (key, reading) -> ui.post(this::wake);
    }

    /** Adds the shade as the last child of the Dashboard's root, so it is
     * above all the other elements. Call once, after the root is built. */
    public void attach(ViewGroup root) {
        shade = new View(activity);
        shade.setBackgroundColor(Color.BLACK);
        shade.setAlpha(SHADE_ALPHA);
        shade.setVisibility(View.GONE);
        shade.setClickable(false);
        shade.setFocusable(false);
        shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Above any child that lifts itself with elevation.
        shade.setTranslationZ(10_000f);
        root.addView(shade, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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

    /** Config's Try button: dim now, whatever the hour, the gear or the
     * quiet time, and even with the switch off, so the driver sees what the
     * mode looks like. A touch or a gear change undoes it, as always. */
    public void tryNow() {
        lastWakeMs = SystemClock.uptimeMillis();
        ui.removeCallbacks(check);
        dim();
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
        if (shade != null) shade.setVisibility(View.VISIBLE);
        dimmed = true;
        ui.postDelayed(floor, FLOOR_DELAY_MS);
    }

    private void undim() {
        if (!dimmed) return;
        ui.removeCallbacks(floor);
        setWindow(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        if (shade != null) shade.setVisibility(View.GONE);
        dimmed = false;
        helperFloor(false);
    }

    /** floor: the panel's lowest lit level; !floor: the car's own level back.
     * Does nothing without modehelper (see its receiver). */
    private void helperFloor(boolean floor) {
        try {
            activity.sendBroadcast(new android.content.Intent("com.geely.modehelper.BACKLIGHT_FLOOR")
                .setClassName(com.geely.drivemem.net.Updater.HELPER_PKG, HELPER_FLOOR)
                .putExtra("floor", floor));
        } catch (Throwable ignored) {}
    }

    private void setWindow(float level) {
        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
        lp.screenBrightness = level;
        activity.getWindow().setAttributes(lp);
    }
}
