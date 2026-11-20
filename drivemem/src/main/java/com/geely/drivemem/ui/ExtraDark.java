package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.Context;
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
 * "On every screen" (Prefs.getExtraDarkGlobal): the same rules run in
 * DarkestOverlayService instead, with a real overlay window over every app
 * (its own Target), and the Dashboard's element stays idle so the two never
 * stack. Only one of the two is ever enabled: see runs().
 *
 * Below that floor: 2 of 255 still lights the panel (1 is off), measured
 * 2026-11-17, and the owner wanted it darker than 10. Darkness 100 % asks
 * for 1, the panel off (2026-11-20); while the panel is dark Drive Assist
 * sends modehelper a heartbeat, and modehelper lights it again by itself
 * when the heartbeat stops (a crash must never leave the screen dark). Android will not go
 * below 10 and SELinux keeps modehelper off the backlight file, so
 * modehelper writes it through the car's local root adbd
 * (BacklightFloorReceiver), turning ADB on for a short window if it is off.
 * FLOOR_DELAY_MS after the dim,
 * so Android has finished its own ramp to 10 and does not write over it.
 * Undim asks for the car's level back as well as dropping the override.
 *
 * "Night" is the car's dark mode (Configuration.uiMode night), the same
 * signal the Auto appearance follows, AND the mode's own hours (Config,
 * default 21:00 to 06:00): the car's dark mode starts too early in the
 * evening for it (#130). */
public final class ExtraDark {
    /** No touch for this long while driving at night: dim. */
    static final long DIM_AFTER_MS = 60_000L;
    /** Asks for the lowest backlight: the system clamps it to the panel's
     * floor (10 of 255 on this unit), the darkest it goes. */
    public static final float BACKLIGHT_FLOOR = 1f / 255f;
    /** After the dim, before asking modehelper for the real floor. */
    static final long FLOOR_DELAY_MS = 2000L;
    /** Default opacity of the black shade: 0.7 lets 30 % of the dimmed light
     * through, so the speed and the numbers can still be read. The driver
     * picks another in Config (0 to 100 %, PercentBar). */
    public static final float SHADE_ALPHA = 0.7f;
    /** Darkness at which the panel is turned off (backlight 1) instead of
     * dimmed (owner, 2026-11-20): the "blank screen" of #130. */
    public static final int OFF_PCT = 100;
    /** While dark, Drive Assist tells modehelper it is still there this
     * often; modehelper lights the panel by itself when that stops
     * (BacklightPolicy, 15 s). */
    static final long BEAT_MS = 5_000L;

    /** A stored percent as an opacity: 0 % no layer (the backlight floor
     * only), 100 % black. Out of range values are kept in range. */
    public static float shadeAlpha(int pct) {
        return Math.max(0, Math.min(OFF_PCT, pct)) / 100f;
    }

    /** Whether this darkness turns the panel off. */
    static boolean panelOff(int pct) {
        return pct >= OFF_PCT;
    }

    /** The backlight level asked of modehelper: 1 (off) at 100 %, else 2,
     * the lowest lit level. */
    static int backlightLevel(int pct) {
        return panelOff(pct) ? 1 : 2;
    }

    /** The driver's choice, as an opacity. */
    public static float shadeAlpha(Context ctx) {
        return shadeAlpha(Prefs.getExtraDarkShadePct(ctx));
    }
    static final String HELPER_FLOOR = "com.geely.modehelper.BacklightFloorReceiver";
    /** Intent extra on ComfortActivity: Config's Try button. */
    public static final String EXTRA_TRY = "darkest_try";
    private static final String GEAR_KEY = "car.gear";

    /** What dimming does on screen: the Dashboard's window and element, or
     * the overlay window over every app. */
    public interface Target {
        /** Dims (backlight override + shade) or brings the light back. */
        void apply(boolean dimmed);
    }

    private final Context ctx;
    private final Handler ui;
    /** True for the overlay over every app, false for the Dashboard. */
    private final boolean overlay;
    private Target target;
    private boolean enabled, dimmed, swallowing;
    private View shade;
    private long lastWakeMs;
    private final Runnable check = this::check;
    private final Runnable floor = this::floorNow;
    // "Still here" to modehelper while dark: see BEAT_MS.
    private final Runnable beat = new Runnable() {
        @Override public void run() {
            if (!dimmed) return;
            helperKeep();
            ui.postDelayed(this, BEAT_MS);
        }
    };
    private final EntityBus.Listener gearListener;

    /** The Dashboard's own Darkest mode: window brightness + an element. */
    public ExtraDark(Activity activity, Handler ui) {
        this(activity, ui, false, null);
        this.target = dimmed -> {
            WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
            lp.screenBrightness = dimmed ? BACKLIGHT_FLOOR : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            activity.getWindow().setAttributes(lp);
            if (shade != null) {
                shade.setAlpha(shadeAlpha(activity));
                shade.setVisibility(dimmed ? View.VISIBLE : View.GONE);
            }
        };
    }

    /** Darkest mode over every app (DarkestOverlayService). */
    public ExtraDark(Context ctx, Handler ui, Target target) {
        this(ctx, ui, true, target);
    }

    private ExtraDark(Context ctx, Handler ui, boolean overlay, Target target) {
        this.ctx = ctx;
        this.ui = ui;
        this.overlay = overlay;
        this.target = target;
        this.gearListener = (key, reading) -> ui.post(this::wake);
    }

    /** Whether this copy (Dashboard or overlay) runs: the switch on, and the
     * overlay only "On every screen", the Dashboard only without it. */
    static boolean runs(boolean on, boolean everyScreen, boolean overlay) {
        return on && everyScreen == overlay;
    }

    /** Adds the shade as the last child of the Dashboard's root, so it is
     * above all the other elements. Call once, after the root is built. */
    public void attach(ViewGroup root) {
        shade = new View(ctx);
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
    static boolean shouldDim(boolean enabled, boolean night, boolean inHours, boolean parked, long quietMs) {
        return enabled && night && inHours && !parked && quietMs >= DIM_AFTER_MS;
    }

    /** Minutes after midnight: is {@code now} inside start..end? The window
     * may cross midnight (21:00 to 06:00). Start equal to end: all day. */
    static boolean inHours(int nowMin, int startMin, int endMin) {
        if (startMin == endMin) return true;
        if (startMin < endMin) return nowMin >= startMin && nowMin < endMin;
        return nowMin >= startMin || nowMin < endMin;
    }

    private boolean inHoursNow() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        return inHours(now, Prefs.getExtraDarkStartMin(ctx), Prefs.getExtraDarkEndMin(ctx));
    }

    public void resume() {
        enabled = runs(Prefs.getExtraDark(ctx), Prefs.getExtraDarkGlobal(ctx), overlay);
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

    public boolean isDimmed() { return dimmed; }

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
        if (shouldDim(enabled, night(), inHoursNow(), CarState.isParked(), quiet)) {
            dim();
        } else if (enabled && !dimmed) {
            // Parked, daytime or outside the hours now: look again later (one post a minute).
            ui.postDelayed(check, DIM_AFTER_MS);
        }
    }

    private boolean night() {
        int m = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    private void dim() {
        target.apply(true);
        dimmed = true;
        ui.postDelayed(floor, FLOOR_DELAY_MS);
    }

    private void undim() {
        if (!dimmed) return;
        ui.removeCallbacks(floor);
        ui.removeCallbacks(beat);
        target.apply(false);
        dimmed = false;
        helperFloor(false);
    }

    /** FLOOR_DELAY_MS after the dim: the real floor, then the heartbeat. */
    private void floorNow() {
        if (!dimmed) return;
        helperFloor(true);
        ui.postDelayed(beat, BEAT_MS);
    }

    /** floor: the panel's lowest lit level; !floor: the car's own level back.
     * Does nothing without modehelper (see its receiver). */
    private void helperFloor(boolean floor) {
        try {
            ctx.sendBroadcast(new android.content.Intent("com.geely.modehelper.BACKLIGHT_FLOOR")
                .setClassName(com.geely.drivemem.net.Updater.HELPER_PKG, HELPER_FLOOR)
                .putExtra("floor", floor)
                .putExtra("level", backlightLevel(Prefs.getExtraDarkShadePct(ctx)))
                .putExtra("beat", true));
        } catch (Throwable ignored) {}
    }

    /** "Still here": modehelper keeps the panel dark only while these come. */
    private void helperKeep() {
        try {
            ctx.sendBroadcast(new android.content.Intent("com.geely.modehelper.BACKLIGHT_FLOOR")
                .setClassName(com.geely.drivemem.net.Updater.HELPER_PKG, HELPER_FLOOR)
                .putExtra("keep", true));
        } catch (Throwable ignored) {}
    }
}
