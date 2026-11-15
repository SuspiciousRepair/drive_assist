package com.geely.drivemem.services;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.controls.AvasState;
import com.geely.drivemem.controls.HelperSync;
import com.geely.drivemem.controls.RegenTap;
import com.geely.drivemem.controls.TurboMode;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.hvac.ComfortHub;
import com.geely.drivemem.hvac.ComfortRuler;
import com.geely.drivemem.util.AppForeground;
import com.geely.drivemem.util.LayoutWait;
import com.geely.drivemem.util.Style;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small floating button, reachable over any app, that expands into live
 * Climate and Turbo controls — same state as the home screen's own cards
 * (ComfortHub / TurboMode are both process-level singletons), just a second
 * live view onto it, not a copy. Drag-to-reposition, same shape as sapinho's
 * DriveRegenFloaterService (see SAPINHO-ANALYSIS.md): a WindowManager
 * overlay with a collapsed/expanded pair of views, one touch listener that
 * tells a drag from a tap by how far the finger actually moved. */
public final class OverlayService extends Service {
    private static volatile boolean sRunning = false;
    public static boolean isRunning() { return sRunning; }

    private static final long AUTO_COLLAPSE_MS = 6000;
    private static final long TURBO_POLL_MS = 200; // see turboPoll's own comment: why polling, not a listener
    private static final int MOVE_SLOP_PX = 8;      // sapinho's own threshold for drag vs tap

    private static final String PREFS = "drivemem";
    private static final String PREF_X = "overlay_x";
    private static final String PREF_Y = "overlay_y";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams lp;
    private View collapsed;
    private View expanded;
    private ImageView scaleView;
    private View turboBar;
    private TextView regenGlyphView;
    private ImageView avasIconView;
    private ComfortRuler ruler;
    private boolean isExpanded = false;

    private boolean dragging = false;
    private int touchStartX, touchStartY;
    private float touchRawStartX, touchRawStartY;

    private final Runnable collapseRunnable = this::collapse;
    private final Runnable climateListener = this::refreshClimate;
    // Rebuild on the way back to hidden (foreground == true), not on the way
    // back to visible: cheaper to pay the cost once right after a theme
    // change than to guess whether one happened every time the driver
    // switches away to some other app.
    private final AppForeground.Listener foregroundListener = foreground -> {
        if (foreground) rebuildContent();
        overlayView.setVisibility(foreground ? View.GONE : View.VISIBLE);
    };

    // TurboMode.Listener is a single slot (TurboMode.java:59, "private volatile
    // Listener listener") — ComfortActivity's own turbo bar already occupies it
    // while the home screen is open. Taking it here would silently cut that bar
    // off the moment the overlay opens. Polling is the one way to watch Turbo
    // from a second place without fighting over that slot, so it only runs
    // while the panel is actually open (see expand()/collapse()).
    private final Runnable turboPoll = new Runnable() {
        @Override public void run() {
            if (!isExpanded) return;
            refreshTurbo();
            handler.postDelayed(this, TURBO_POLL_MS);
        }
    };

    private static final String CH = "drivemem_overlay";
    private static final int NID = 85;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // MANDATORY before anything else, even the permission bail-out below:
        // every startForegroundService() demands a matching startForeground()
        // in the same call, or the system kills the app with
        // RemoteServiceException (see OutTempService's own note on this).
        ensureForeground();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (overlayView == null) showOverlay();
        return START_STICKY;
    }

    private void ensureForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, getString(R.string.notif_overlay_channel),
                NotificationManager.IMPORTANCE_MIN);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        Notification fg = new Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_overlay_fg_text))
            .setOngoing(true).setShowWhen(false).build();
        startForeground(NID, fg);
    }

    @Override public void onDestroy() {
        sRunning = false;
        handler.removeCallbacksAndMessages(null);
        ComfortHub.removeListener(climateListener);
        AppForeground.removeListener(foregroundListener);
        EntityBus.unsubscribe(AvasState.KEY, avasListener);
        if (overlayView != null && windowManager != null) {
            try { windowManager.removeView(overlayView); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    private void showOverlay() {
        sRunning = true;
        ruler = ComfortHub.get(this);
        ComfortHub.addListener(climateListener);

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        overlayView = root;
        rebuildContent();

        lp = new WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        // Anchored from the BOTTOM, not the top: the expanded panel is much
        // taller than the collapsed circle, and with a top anchor low enough
        // to clear CarPlay/Android Auto's own icon dock, expanding just ran
        // the panel off the bottom of the screen. Growing upward from a
        // fixed bottom edge has no such ceiling regardless of panel height.
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        // Default: left edge, clear of Android Auto/CarPlay's own icon dock
        // above it — see setupDragAndTap() for how a person moves it anywhere
        // after. PREF_Y is now a distance from the BOTTOM edge.
        lp.x = p.getInt(PREF_X, Style.dp(this, 8));
        lp.y = p.getInt(PREF_Y, Style.dp(this, 300));
        windowManager.addView(overlayView, lp);
        refreshClimate();
        refreshTurbo();
        refreshRegen();

        // Tracking itself is already running by now — DriveMemApplication
        // .onCreate() started it, guaranteed to run before any Activity in
        // the process could, which starting it lazily from here could not
        // promise (see that class's own comment for the bug that caused).
        AppForeground.addListener(foregroundListener);
        EntityBus.subscribe(AvasState.KEY, avasListener);
        // Covers the toggle-it-on-from-Config case: our own Activity is the
        // one on screen at this exact moment, so start hidden rather than
        // waiting for the next start/stop edge to say so.
        if (AppForeground.isForeground()) overlayView.setVisibility(View.GONE);
    }

    // Style's colors (TEXT/ACCENT/cardFillColor/...) are static fields another
    // screen last set via Style.load() — a theme picker calls it indirectly
    // through recreate() (TelemetryActivity.java). A Service never gets that
    // call on its own, so without this call here the overlay would freeze at
    // whatever theme happened to be active the moment it first built its
    // views — including the class's hardcoded defaults on a cold boot where
    // no Activity has opened yet this process. Re-run from foregroundListener
    // every time our own app comes back to front, so a theme changed while
    // the overlay sat hidden is never stale by the time it reappears.
    private void rebuildContent() {
        Style.load(this);
        boolean wasExpanded = isExpanded;
        LinearLayout root = (LinearLayout) overlayView;
        root.removeAllViews();
        collapsed = buildCollapsed();
        expanded = buildExpanded();
        root.addView(collapsed);
        root.addView(expanded);
        collapsed.setVisibility(wasExpanded ? View.GONE : View.VISIBLE);
        expanded.setVisibility(wasExpanded ? View.VISIBLE : View.GONE);
        refreshClimate();
        refreshTurbo();
        refreshRegen();
    }

    // 30% transparency (70% opaque) for the COLLAPSED circle only — Style
    // .cardFillColor() is deliberately fully opaque for the app's own
    // screens (see its own comment — a translucent card read as washed out
    // once it carried real content), and the expanded panel keeps that same
    // full opacity since it's full of text/controls that need to stay
    // readable. The small idle tab is the one that should blend into
    // whatever app is actually in use underneath, not obscure it.
    private static int overlayFillColor() {
        return (0xB3 << 24) | (Style.cardFillColor() & 0x00FFFFFF);
    }

    private static final int COLLAPSED_SIZE_DP = 96;

    private View buildCollapsed() {
        ImageView tab = new ImageView(this);
        tab.setImageResource(R.drawable.ic_cards_view);   // mdi:view-dashboard-variant, same as the home dock's own Cards icon
        tab.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tab.setColorFilter(Style.TEXT, PorterDuff.Mode.SRC_IN);
        int size = Style.dp(this, COLLAPSED_SIZE_DP);
        tab.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        int pad = Style.dp(this, 26);
        tab.setPadding(pad, pad, pad, pad);
        tab.setBackground(Style.card(overlayFillColor(), this, COLLAPSED_SIZE_DP / 2));
        setupDragAndTap(tab);
        return tab;
    }

    private LinearLayout buildExpanded() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(this, 20);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackground(Style.card(Style.cardFillColor(), this));
        panel.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(this, 300), ViewGroup.LayoutParams.WRAP_CONTENT));
        setupDragAndTap(panel);

        panel.addView(sectionLabel(getString(R.string.overlay_climate_label)));
        Style.gap(panel, this, 8);

        LinearLayout askRow = new LinearLayout(this);
        askRow.setOrientation(LinearLayout.HORIZONTAL);
        askRow.addView(climateIcon(R.drawable.ic_snowflake, +1), climateIconLp(0));
        askRow.addView(climateIcon(R.drawable.ic_weather_sunny, -1), climateIconLp(14));
        panel.addView(askRow);
        Style.gap(panel, this, 14);

        scaleView = new ImageView(this);
        scaleView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 20)));
        panel.addView(scaleView);

        Style.gap(panel, this, 20);

        // Quick buttons: the same ones, in the same order, as the home
        // screen's Turbo card, picked in Config > Drive > "Buttons on the
        // Turbo card" (issue #15). Turbo (tap to boost, the bar shows the
        // countdown), Regen (RegenTap: Strong toggle or level cycle) and
        // AVAS (modehelper flips it, AvasState reports it back).
        LinearLayout turboSection = new LinearLayout(this);
        turboSection.setOrientation(LinearLayout.HORIZONTAL);
        String slots = com.geely.drivemem.ui.ComfortCardVisibility.turboCardSlots(
            Prefs.getTurboCardShows(this, Prefs.CARD_TURBO),
            Prefs.getTurboCardShows(this, Prefs.CARD_REGEN),
            Prefs.getTurboCardShows(this, Prefs.CARD_AVAS));
        turboBar = null; regenGlyphView = null; avasIconView = null;
        for (int i = 0; i < slots.length(); i++) {
            char slot = slots.charAt(i);
            View col = slot == 'T' ? turboColumn() : slot == 'R' ? regenColumn() : avasColumn();
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            // A margin, not Style.gap(): that adds a 1-px-WIDE view, which
            // in a horizontal row is no gap at all.
            if (i > 0) colLp.leftMargin = Style.dp(this, SLOT_GAP_DP);
            turboSection.addView(col, colLp);
        }
        if (slots.length() > 0) {
            panel.addView(turboSection);
            Style.gap(panel, this, 20);
        }
        panel.addView(openAppButton());

        return panel;
    }

    private static final int SLOT_GAP_DP = 14;

    // One column: label, then a tile, then a 6dp slot under it (Turbo's bar
    // lives inside its tile; the others keep the same height with a spacer).
    private LinearLayout slotColumn(String label, View.OnClickListener tap) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setOnClickListener(v -> { tap.onClick(v); resetAutoCollapse(); });
        TextView l = sectionLabel(label);
        l.setSingleLine(true);
        // ~75 dp per column with three buttons: a long label (Thai's Regen)
        // shrinks to fit instead of being cut.
        int lh = l.getLineHeight();
        l.setAutoSizeTextTypeUniformWithConfiguration(9, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        col.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, lh));
        Style.gap(col, this, 8);
        return col;
    }

    private View turboColumn() {
        LinearLayout col = slotColumn(getString(R.string.turbo_title), v -> TurboMode.get(this).start());
        // Bar lives INSIDE the tile, pinned to its bottom edge (a FrameLayout
        // stack, not a separate element below it) — a countdown baked into
        // the button it belongs to, not a stray line underneath it.
        android.widget.FrameLayout turboTile = new android.widget.FrameLayout(this);
        turboTile.setBackground(Style.tile(this));
        turboTile.setClipToOutline(true);
        TextView turboHint = new TextView(this);
        turboHint.setText("⚡");
        turboHint.setTextColor(Style.ACCENT);
        turboHint.setTextSize(40);
        turboHint.setGravity(Gravity.CENTER);
        turboHint.setPadding(0, Style.dp(this, 18), 0, Style.dp(this, 18));
        turboTile.addView(turboHint);
        LinearLayout barTrack = new LinearLayout(this);
        barTrack.setBackgroundColor(Style.TEXT_DIM & 0x33FFFFFF);
        View bar = new View(this);
        bar.setBackgroundColor(Style.ACCENT);
        bar.setLayoutParams(new LinearLayout.LayoutParams(0, Style.dp(this, 6)));
        turboBar = bar;
        barTrack.addView(bar);
        android.widget.FrameLayout.LayoutParams barTrackLp = new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 6));
        barTrackLp.gravity = Gravity.BOTTOM;
        turboTile.addView(barTrack, barTrackLp);
        col.addView(turboTile);
        return col;
    }

    private View regenColumn() {
        LinearLayout col = slotColumn(getString(R.string.regen_boost_title), v -> onRegenTap());
        TextView regenGlyph = new TextView(this);
        regenGlyph.setText("●");   // same mark Config's own regen picker uses for High
        regenGlyph.setTextColor(Style.TEXT_DIM);
        regenGlyph.setTextSize(40);
        regenGlyph.setGravity(Gravity.CENTER);
        regenGlyph.setBackground(Style.tile(this));
        regenGlyph.setPadding(0, Style.dp(this, 18), 0, Style.dp(this, 18));
        col.addView(regenGlyph);
        regenGlyphView = regenGlyph;
        return col;
    }

    private View avasColumn() {
        LinearLayout col = slotColumn(getString(R.string.avas_title), v -> onAvasTap());
        // Same tile height as the text tiles next to it (40sp glyph + 18dp
        // padding each side), so the row stays level.
        android.widget.FrameLayout tile = new android.widget.FrameLayout(this);
        tile.setBackground(Style.tile(this));
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int size = Style.dp(this, 48);
        android.widget.FrameLayout.LayoutParams ilp = new android.widget.FrameLayout.LayoutParams(size, size, Gravity.CENTER);
        ilp.topMargin = ilp.bottomMargin = Style.dp(this, 18);
        tile.addView(icon, ilp);
        col.addView(tile);
        avasIconView = icon;
        showAvas(AvasState.on(CarActor.get(this).get(AvasState.KEY)));
        return col;
    }

    private void onAvasTap() {
        HelperSync.toggleAvas(this);
        Boolean on = AvasState.on(CarActor.get(this).get(AvasState.KEY));
        if (on != null) showAvas(!on);
        // The hub publishes only changes: if the flip did not land, no event
        // comes, so show what the hub has once modehelper had time to report.
        handler.postDelayed(() -> showAvas(AvasState.on(CarActor.get(this).get(AvasState.KEY))), 3000);
    }

    private final EntityBus.Listener avasListener = (key, reading) ->
        handler.post(() -> showAvas(AvasState.on(reading)));

    // Same look as the Turbo card: sound icon in the accent colour when on,
    // muted icon dimmed when off, sound icon dimmed when not known yet.
    private void showAvas(Boolean on) {
        if (avasIconView == null) return;
        avasIconView.setImageResource(Boolean.FALSE.equals(on) ? R.drawable.ic_volume_off : R.drawable.ic_volume_high);
        avasIconView.setColorFilter(Boolean.TRUE.equals(on) ? Style.ACCENT : Style.TEXT_DIM, PorterDuff.Mode.SRC_IN);
    }

    private TextView openAppButton() {
        TextView btn = new TextView(this);
        btn.setText(getString(R.string.overlay_open_app));
        btn.setTextColor(Style.TEXT);
        btn.setTextSize(16);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        int padV = Style.dp(this, 16);
        btn.setPadding(0, padV, 0, padV);
        btn.setBackground(Style.tile(this));
        btn.setOnClickListener(v -> {
            collapse();
            Intent i = new Intent(this, com.geely.drivemem.ui.ComfortActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });
        return btn;
    }

    // Shared with the home screen's Turbo card (RegenTap), so this button
    // also follows Config's "Regen button steps through Low, Medium and
    // High". It used to be a Strong-regen toggle only, whatever that said.
    private void onRegenTap() {
        RegenTap.tap(this, target -> handler.post(() -> showRegen(target)));
    }

    private void refreshRegen() {
        if (regenGlyphView == null) return;
        RegenTap.read(this, v -> handler.post(() -> showRegen(v)));
    }

    private void showRegen(int level) {
        if (regenGlyphView == null) return;
        boolean cycle = Prefs.getRegenCycle(this);
        regenGlyphView.setText(RegenTap.mark(level, cycle));
        regenGlyphView.setTextColor(RegenTap.lit(level, cycle) ? Style.ACCENT : Style.TEXT_DIM);
    }

    private static final int CLIMATE_ICON_DP = 88;

    private ImageView climateIcon(int drawableRes, int dir) {
        ImageView v = new ImageView(this);
        v.setImageResource(drawableRes);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Style.dp(this, 22);
        v.setPadding(pad, pad, pad, pad);
        v.setBackground(Style.tile(this));
        v.setColorFilter(Style.TEXT, PorterDuff.Mode.SRC_IN);
        v.setOnClickListener(view -> { ruler.tap(dir); resetAutoCollapse(); });
        return v;
    }

    private LinearLayout.LayoutParams climateIconLp(int leftGapDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Style.dp(this, CLIMATE_ICON_DP), 1f);
        p.leftMargin = Style.dp(this, leftGapDp);
        return p;
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Style.TEXT_DIM);
        t.setTextSize(15);
        return t;
    }

    // Same idiom ComfortActivity's redrawScale() uses: the bitmap needs the
    // view's real, already-measured width. Waits for a real layout pass
    // (LayoutWait) rather than reposting blindly -- scaleView lives in the
    // expanded panel, which is View.GONE (never measured) for as long as
    // the overlay tab sits collapsed, i.e. almost always; blindly reposting
    // there spun the main thread at 100% CPU (GitHub issue #5).
    private void refreshClimate() {
        if (scaleView == null || ruler == null) return;
        int w = scaleView.getWidth();
        if (w <= 0) { LayoutWait.onNextLayout(scaleView, this::refreshClimate); return; }
        scaleView.setImageBitmap(Style.effortScale(this, w, scaleView.getHeight(),
            ruler.pointer(), ruler.approx(), ruler.defrosting()));
    }

    private void refreshTurbo() {
        if (turboBar == null) return;
        TurboMode t = TurboMode.get(this);
        ViewGroup.LayoutParams p = turboBar.getLayoutParams();
        if (p instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) p).weight = t.active() ? Math.max(0.02f, t.fraction()) : 1f;
            turboBar.setLayoutParams(p);
        }
    }

    private void setupDragAndTap(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = false;
                    touchStartX = lp.x;
                    touchStartY = lp.y;
                    touchRawStartX = event.getRawX();
                    touchRawStartY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (event.getRawX() - touchRawStartX);
                    int dy = (int) (event.getRawY() - touchRawStartY);
                    if (Math.abs(dx) > MOVE_SLOP_PX || Math.abs(dy) > MOVE_SLOP_PX) dragging = true;
                    if (dragging) {
                        lp.x = touchStartX + dx;
                        // Minus, not plus: lp.y is a distance from the BOTTOM
                        // edge under Gravity.BOTTOM, so a finger moving down
                        // (positive dy) must SHRINK it, not grow it.
                        lp.y = touchStartY - dy;
                        windowManager.updateViewLayout(overlayView, lp);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt(PREF_X, lp.x).putInt(PREF_Y, lp.y).apply();
                    } else {
                        toggleExpand();
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    private void toggleExpand() {
        if (isExpanded) collapse(); else expand();
    }

    private void expand() {
        isExpanded = true;
        collapsed.setVisibility(View.GONE);
        expanded.setVisibility(View.VISIBLE);
        refreshClimate();
        refreshRegen();
        if (avasIconView != null) HelperSync.queryAvas(this);
        handler.post(turboPoll);
        resetAutoCollapse();
    }

    private void collapse() {
        isExpanded = false;
        expanded.setVisibility(View.GONE);
        collapsed.setVisibility(View.VISIBLE);
        handler.removeCallbacks(collapseRunnable);
    }

    private void resetAutoCollapse() {
        handler.removeCallbacks(collapseRunnable);
        handler.postDelayed(collapseRunnable, AUTO_COLLAPSE_MS);
    }
}
