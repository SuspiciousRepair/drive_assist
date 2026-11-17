package com.geely.drivemem.controls;

import android.content.Context;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.widget.ImageView;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.util.Style;
import com.geely.drivemem.util.Prefs;

/** The Regen and AVAS quick buttons, shared by the home screen's Turbo card
 * and the floating panel (issue #15). Each screen builds its own tiles and
 * hands the icon views over; the taps, the icons and the hub listeners live
 * here, once.
 *
 * Both icons follow the hub: car.regen_mode (a push watch) and AvasState.KEY
 * (reported by modehelper). {@link #start} and {@link #stop} must be paired
 * by the owner's lifecycle. */
public final class QuickButtons {
    private static final long AVAS_RECHECK_MS = 3000;

    private final Context ctx;
    private final Handler ui;
    private ImageView regenGlyphView;
    private ImageView avasIconView;

    private final EntityBus.Listener avasListener = (key, reading) ->
        post(() -> showAvas(AvasState.on(reading)));
    private final EntityBus.Listener regenListener = (key, reading) ->
        post(() -> showRegen(RegenTap.level(reading)));
    private final Runnable avasRecheck = this::showAvasFromHub;

    /** @param ui a handler on the main thread, owned by the screen */
    public QuickButtons(Context ctx, Handler ui) {
        this.ctx = ctx;
        this.ui = ui;
    }

    /** The Regen button's icon view (null: the button is not shown). */
    public void bindRegen(ImageView glyph) {
        regenGlyphView = glyph;
        showRegen(RegenTap.current(ctx));
    }

    /** The AVAS button's icon view (null: the button is not shown). */
    public void bindAvas(ImageView icon) {
        avasIconView = icon;
        showAvasFromHub();
    }

    public boolean hasAvas() { return avasIconView != null; }

    /** Listen to the hub. Idempotent (EntityBus adds a listener once). */
    public void start() {
        EntityBus.subscribe(RegenTap.KEY, regenListener);
        EntityBus.subscribe(AvasState.KEY, avasListener);
    }

    public void stop() {
        EntityBus.unsubscribe(RegenTap.KEY, regenListener);
        EntityBus.unsubscribe(AvasState.KEY, avasListener);
        ui.removeCallbacks(avasRecheck);
    }

    /** Show what the hub has now. The hub does not replay past values, so
     * the owner calls this when its screen comes back. AVAS is also asked
     * again from modehelper, which answers into the hub. */
    public void refresh() {
        showRegen(RegenTap.current(ctx));
        if (avasIconView == null) return;
        showAvasFromHub();
        HelperSync.queryAvas(ctx);
    }

    // Strong-regen toggle, or the Low -> Mid -> High cycle when Config's
    // "steps through" switch is on. Read at tap time, so the switch works
    // without rebuilding the screen.
    public void onRegenTap() {
        RegenTap.tap(ctx, target -> post(() -> showRegen(target)));
    }

    // Drive Assist cannot read AVAS (a system permission), so modehelper
    // decides the flip from the car and reports the result into the hub
    // (AvasState). The icon flips at once when the state is known; the
    // report, a moment later, has the last word.
    public void onAvasTap() {
        HelperSync.toggleAvas(ctx);
        Boolean on = AvasState.on(CarActor.get(ctx).get(AvasState.KEY));
        if (on != null) showAvas(!on);
        // The hub publishes only changes: if the flip did not land, no event
        // comes, so show what the hub has once modehelper had time to report.
        ui.removeCallbacks(avasRecheck);
        ui.postDelayed(avasRecheck, AVAS_RECHECK_MS);
    }

    private void post(Runnable r) { ui.post(r); }

    private void showAvasFromHub() {
        showAvas(AvasState.on(CarActor.get(ctx).get(AvasState.KEY)));
    }

    // Sound icon in the accent colour when AVAS is on, muted icon dimmed when
    // off. Not known yet (no report from modehelper): the sound icon, dimmed.
    private void showAvas(Boolean on) {
        if (avasIconView == null) return;
        avasIconView.setImageResource(Boolean.FALSE.equals(on) ? R.drawable.ic_volume_off : R.drawable.ic_volume_high);
        avasIconView.setColorFilter(Boolean.TRUE.equals(on) ? Style.ACCENT : Style.TEXT_DIM, PorterDuff.Mode.SRC_IN);
    }

    private void showRegen(int level) {
        if (regenGlyphView == null) return;
        boolean cycle = Prefs.getRegenCycle(ctx);
        regenGlyphView.setImageResource(RegenTap.icon(level));
        regenGlyphView.setColorFilter(RegenTap.lit(level, cycle) ? Style.ACCENT : Style.TEXT, PorterDuff.Mode.SRC_IN);
    }
}
