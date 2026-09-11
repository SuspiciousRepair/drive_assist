package com.geely.drivemem.ui;

import com.geely.drivemem.R;

import com.geely.drivemem.art.ArtView;
import com.geely.drivemem.art.PanelCardView;
import com.geely.drivemem.art.SkylineArtView;
import com.geely.drivemem.art.VaporArtView;
import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.car.Telemetry;
import com.geely.drivemem.controls.KonamiView;
import com.geely.drivemem.controls.Purge;
import com.geely.drivemem.controls.TurboMode;
import com.geely.drivemem.hvac.ComfortHub;
import com.geely.drivemem.hvac.ComfortRuler;
import com.geely.drivemem.hvac.EffortTable;
import com.geely.drivemem.net.AbrpUploader;
import com.geely.drivemem.net.MqttReporter;
import com.geely.drivemem.sensors.Obd2Reader;
import com.geely.drivemem.services.TelemetryService;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.state.GateState;
import com.geely.drivemem.state.MusicState;
import com.geely.drivemem.state.PanelState;
import com.geely.drivemem.util.BootReceiver;
import com.geely.drivemem.util.Modes;
import com.geely.drivemem.util.SpotifyClient;
import com.geely.drivemem.net.Updater;
import com.geely.drivemem.util.Style;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Main climate control screen with CarPlay-style card layout. Displays
 * HVAC controls (temperature, fan, recirculation), window purge, turbo/regen
 * mode, music playback, charging status, gate access, and HA context cards. */
public class ComfortActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView hint;
    // elements that follow the colour of the cabin ambient light
    private ArtView art;
    private boolean fullBleed;
    private PanelCardView card;      // HA context card's WebView
    private LinearLayout panelCard;  // the card it lives in -- see panelCard()
    private TextView closeBtn;       // X to dismiss the card
    private boolean lastPanelAvailable = false;
    private String currentPayload = "";
    private String shownPayload = null;   // the card actually on screen (null = none)
    private int dismissedHash = 0;   // hash of the dismissed card (persisted; local, no broker)
    // comfort ruler: the on-screen scale + the step logic
    private ComfortRuler comfortRuler;
    private TextView bigOutside;
    private android.widget.ImageView scaleView;
    private android.widget.ImageView recircBtn;
    private boolean recircOn = false;
    private android.widget.ImageView windDirIcon;
    private android.widget.ImageView rearDefrostIcon;
    private int currentWindDir = 0;
    private boolean rearDefrostOn = false;
    // Status sidebar (left edge): connection state only, no
    // control surface — see statusSidebar()/refreshStatusSidebar().
    private static final int MODE_CARDS = 0;
    private static final int MODE_DRIVING_STATS = 1;
    private static final int MODE_CHARGE_STATS = 2;
    private int mainViewMode = MODE_CARDS;

    private android.widget.ImageView haStatusIcon, abrpStatusIcon, obd2StatusIcon;
    private android.widget.ImageView cardsDockIcon, statsDockIcon, chargeDockIcon;
    private DailyStatsView dailyStatsView;
    private FrameLayout statsContainer;
    private ChargeStatsView chargeStatsView;
    private FrameLayout chargeStatsContainer;
    private FrameLayout konamiZone;
    // purge: one button, both directions. The Purge object holds where each pane
    // was before it moved, so closing puts them back rather than shutting them.
    private android.widget.ImageView purgeBtn;
    // Real per-area readings, kept only to answer "is ANY window still open"
    // without re-reading the car — filled by car.window_pos pushes.
    private final java.util.Map<Integer, Integer> windowPos = new java.util.HashMap<>();
    private final Purge purge = new Purge();
    private boolean purgeOpen = false;

    /** Gate card: shown only while HA says the gate is reachable. */
    private GateCard gateCard;

    /** Turbo card: 30s Sport-mode boost. The left half shows Turbo with a
     * countdown bar; the right half is a Strong-Regen toggle (no timer).
     * Both halves share one outer card and visibility rule (driving-only).
     * TurboMode itself is process-wide (see its header comment). */
    private android.widget.ImageView turboBarView;
    private LinearLayout turboCardView;   // visibility gated on CarState — driving only, see the listener below
    private boolean turboEnabledAtBuild;   // so onResume can notice a Config change and recreate()
    private TextView regenGlyphView;      // right half's glyph, retinted to reflect real car state

    // Music card: spot #4, art + title/artist + skip/play-pause. Visible only
    // while HA actually has a track loaded (title non-empty) — same GONE-
    // until-there's-something-to-show pattern as Portão, and the same
    // repackColumns() trigger on a real change (see the listener below).
    private LinearLayout musicCard;
    private android.widget.ImageView musicArtView;
    private TextView musicTitleView, musicArtistView;
    private boolean lastMusicAvailable = false;
    private String lastMusicArtUrl = null;   // avoids re-fetching the same art on every state ping

    // Charge card: active session progress or retained completed charge with cost input.
    private LinearLayout chargeCard;
    private TextView chargeCardTitle;
    private LinearLayout chargeActiveLayout;
    private LinearLayout chargeCompletedLayout;
    private TextView chargeSocView, chargeElapsedView, chargeRemainingView;
    private android.widget.ImageView chargeBarView;
    private TextView chargeCompletedMetrics, chargeCompletedCostText;
    private TextView chargeCostActionBtn, chargeDismissBtn;
    private ChargeSession.Summary retainedChargeSession = null;
    private boolean lastChargeAvailable = false;

    // The column flow: kept as fields, not onCreate locals, because Portão's
    // visibility changes AFTER the initial pack (HA answers over MQTT a beat
    // later) and needs a full re-pack when it does — see repackColumns() and
    // the gate listener. `cards` is the same three LinearLayouts every time;
    // repacking never rebuilds a card, only where it lands.
    private LinearLayout band, columns;
    private java.util.List<LinearLayout> cards;
    private boolean lastGateAvailable = false;

    // Both, not just the id: Config may change only Appearance (not the
    // theme itself) while this screen is paused, e.g. after returning from
    // picking Light/Dark/Auto, and a theme id alone can no longer stand in
    // for what this screen actually looks like.
    private String themeId;       // theme this screen was drawn with
    private boolean themeLight;   // ...and which of its Palettes

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Style.load(this);                  // before any View: the palette rules the drawing
        themeId = Style.current().id;
        themeLight = Style.LIGHT;
        prefs = getSharedPreferences("drivemem", MODE_PRIVATE);
        turboEnabledAtBuild = prefs.getBoolean("turbo_enabled", true);
        dismissedHash = prefs.getInt("panel_dismissed", 0);   // the dismissal survives a restart
        // BORROWED, not owned: the ruler belongs to the process now, so an MQTT
        // tap works with this screen closed. See ComfortHub.
        comfortRuler = ComfortHub.get(this);
        ComfortHub.addListener(rulerListener);

        // EVERY art full-bleeds now: it takes the WHOLE SCREEN and itself gives
        // the left back to the theme's background (a horizontal veil), so the
        // scene passes underneath the controls and vanishes, with no hard edge
        // in the middle of the screen. This is the ONLY place theme and art meet.
        art = (Style.ART == Style.ART_VAPOR) ? new VaporArtView(this) : new SkylineArtView(this);
        fullBleed = art.fullBleed();

        Style.edgeToEdge(this);
        FrameLayout screen = new FrameLayout(this);
        screen.setBackground(Style.screenBg());

        if (fullBleed) {
            screen.addView(art, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // THE APP BAND: ONE ROW, THREE EQUAL COLUMNS, ONE SCROLL. Commands
        // (cards) prefer the left columns, the pop-up (art + HA window) prefers
        // the rightmost one — but all three live in the SAME HorizontalScrollView,
        // as equal-width members of the SAME `columns` row. Nothing is a special
        // fixed sibling exempt from scrolling: if cards ever need a third column,
        // the row simply gets wider than the band and the whole thing scrolls,
        // pop-up included, instead of the pop-up holding a reserved slot the
        // cards have to squeeze around (that mismatch — the art column sized to
        // a literal screen third while the card columns were sized to fit the
        // padded band — is what clipped the Turbo card's top edge).
        band = new LinearLayout(this);
        band.setOrientation(LinearLayout.HORIZONTAL);
        int padH = Style.dp(this, 36), padV = Style.dp(this, 32);
        // Top clearance: statusBarHeight() keeps content below the system
        // status bar; padTop is additional breathing room above the cards,
        // separate from padV so they can be adjusted independently.
        int padTop = Style.dp(this, 12);
        // Background (art/screenBg) runs full screen under the status bar;
        // the actual cards start below it, extra padding on top only.
        // Left padding also clears the status sidebar (added below, outside
        // this scrolling row) so the Clima card never sits under it — its
        // own edge margin + width + a card-to-card gap (not the wider padH,
        // see statusSidebar()), replacing padH entirely on this one side.
        int sidebarClearance = Style.dp(this, SIDEBAR_EDGE_MARGIN_DP + SIDEBAR_WIDTH_DP + SIDEBAR_CARD_GAP_DP);
        band.setPadding(sidebarClearance, padTop + Style.statusBarHeight(this), padH, padV);

        columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        android.widget.HorizontalScrollView hscroll = new android.widget.HorizontalScrollView(this);
        hscroll.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hscroll.setHorizontalScrollBarEnabled(false);
        hscroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hscroll.addView(columns);
        band.addView(hscroll);

        // Clima, Portão, Turbo are packed in conceptual order, but their
        // actual layout position changes: packColumns() re-runs whenever
        // Portão's visibility changes, allowing Turbo to reclaim its slot
        // when Portão is hidden. This reflowing avoids leaving gaps.
        cards = new java.util.ArrayList<>();
        cards.add(climaCard());
        gateCard = new GateCard(this);
        gateCard.setOnHintListener(msg -> hint.setText(msg));
        lastGateAvailable = gateVisible();
        gateCard.setVisibility(lastGateAvailable ? View.VISIBLE : View.GONE);
        cards.add(gateCard);
        if (turboEnabledAtBuild) cards.add(turboCard());
        cards.add(musicCard());
        chargeCard = chargeCard();
        cards.add(chargeCard);
        // Last, on purpose: the HA context panel used to be a fixed rightmost
        // column the art drew a window around. It's an ordinary card now,
        // lowest packing priority, so it only claims a column when there's
        // room left — the middle one if little else is showing, the third if
        // the first two fill up — same re-flow
        // Gate/Music/Charge already get from repackColumns(). See
        // panelCard() and historical/ARTE.md for what this replaced.
        panelCard = panelCard();
        cards.add(panelCard);

        repackColumns();
        screen.addView(band, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Daily Statistics view on home screen (swappable with band cards)
        statsContainer = new FrameLayout(this);
        statsContainer.setPadding(sidebarClearance, padTop + Style.statusBarHeight(this), padH, padV);
        android.widget.ScrollView statsScroll = new android.widget.ScrollView(this);
        statsScroll.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        statsScroll.setVerticalScrollBarEnabled(false);
        statsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        dailyStatsView = new DailyStatsView(this, null);
        statsScroll.addView(dailyStatsView);
        statsContainer.addView(statsScroll);
        statsContainer.setVisibility(View.GONE);
        screen.addView(statsContainer, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Charging Statistics view on home screen (swappable with band cards)
        chargeStatsContainer = new FrameLayout(this);
        chargeStatsContainer.setPadding(sidebarClearance, padTop + Style.statusBarHeight(this), padH, padV);
        android.widget.ScrollView chargeStatsScroll = new android.widget.ScrollView(this);
        chargeStatsScroll.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        chargeStatsScroll.setVerticalScrollBarEnabled(false);
        chargeStatsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        chargeStatsView = new ChargeStatsView(this, null);
        chargeStatsScroll.addView(chargeStatsView);
        chargeStatsContainer.addView(chargeStatsScroll);
        chargeStatsContainer.setVisibility(View.GONE);
        screen.addView(chargeStatsContainer, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Status sidebar: a fixed strip pinned to the left edge, OUTSIDE
        // `band`'s scrolling row on purpose — unlike the cards, it must
        // never scroll out of view. band's own left padding (above) already
        // clears this width.
        LinearLayout sidebar = statusSidebar();
        FrameLayout.LayoutParams sidebarLp = new FrameLayout.LayoutParams(
            Style.dp(this, SIDEBAR_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        sidebarLp.leftMargin = Style.dp(this, SIDEBAR_EDGE_MARGIN_DP);
        sidebarLp.topMargin = padTop + Style.statusBarHeight(this);
        sidebarLp.bottomMargin = padV;
        screen.addView(sidebar, sidebarLp);

        // The hidden Konami pad: pinned to the physical right third of the
        // SCREEN, not to wherever panelCard happens to be packed — the code
        // is muscle memory (CLAUDE.md: "the right third"), so its location
        // must never move with the card layout. A plain fixed overlay on
        // `screen`, outside `band`'s scrolling row entirely, on purpose: it
        // does not scroll, does not track any card's position, and simply
        // sits on top of whatever's physically there (the art, ordinarily).
        // Known, accepted tradeoff: if a rare 4th+ overflow column ever
        // scrolled a REAL card underneath this zone, this would swallow
        // taps meant for it — not handled, because it cannot happen with
        // today's card set and handling a case nobody has hit yet is not
        // worth the complexity (see this project's own house rule against
        // that). Only offered outside Noturno; there is nowhere to go from
        // there, and it is not installed at all, so a second run is
        // impossible.
        if (Style.ART != Style.ART_VAPOR) {
            konamiZone = new FrameLayout(this);
            konamiZone.addView(new KonamiView(this, this::konamiUnlock),
                new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // width filled in by sizeKonamiZone() below -- band has no real
            // width yet on this very first pass, same reason repackColumns()
            // itself retries via post().
            FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
            klp.rightMargin = padH;
            klp.topMargin = padTop + Style.statusBarHeight(this);
            klp.bottomMargin = padV;
            screen.addView(konamiZone, klp);
            sizeKonamiZone(konamiZone);
        }

        setContentView(screen);

        CarActor.get(this).runOnCarThread(() -> {
            CarAccess c = CarActor.get(this).rawAccess();
            boolean ok = c.isReady() || c.connect(getApplicationContext());
            // Does NOT touch the setpoint just because the screen was opened —
            // that surprised the user (whoever had set 28 C saw the value pulled
            // back).
            ui.post(() -> { if (ok) refresh(); else hint.setText(getString(R.string.ac_connect_failed)); });
        });
    }

    // Column width is (band's inner width - 2 gaps) / 3 — a REAL measurement
    // of the band, not a guessed dp figure, shared with the fixed Konami
    // zone (onCreate) so the two never drift apart. 0 (not yet measured) is
    // a valid answer here — every caller already has to handle it (see
    // repackColumns()'s own retry, and sizeKonamiZone() below).
    private int columnWidth() {
        int gapH = Style.dp(this, 24);
        int bandInnerW = band.getWidth() - band.getPaddingLeft() - band.getPaddingRight();
        return (bandInnerW - 2 * gapH) / 3;
    }

    // band has no real width on the very first onCreate pass — same reason
    // repackColumns() retries via post(). Runs once; the zone's width never
    // needs to change again after that (it does not track cards at all).
    private void sizeKonamiZone(FrameLayout zone) {
        int w = columnWidth();
        if (w <= 0) { band.post(() -> sizeKonamiZone(zone)); return; }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) zone.getLayoutParams();
        lp.width = w;
        zone.setLayoutParams(lp);
    }

    // Packs `cards` (Clima, Portão, Turbo, Music, Charge, the HA panel —
    // always that conceptual order, panel last/lowest priority) into
    // equal-width columns inside `columns`, left to right, a new column
    // whenever the next card would run past the available height — past
    // three columns' worth the shared HorizontalScrollView (see onCreate)
    // scrolls the whole row instead of anyone getting squeezed.
    //
    // RE-ENTRANT ON PURPOSE: this is not a one-time layout, it is called
    // again every time any card's visibility changes (see the gate/music/
    // panel listeners), so a hidden card actually gives its slot back to
    // the next one instead of leaving a hole where a static, one-time pack
    // had already decided it would go. `columns.removeAllViews()` discards
    // the old column LinearLayouts (cheap, disposable containers); the CARD
    // views themselves are the same objects every time — LinearLayout
    // requires a child to be detached from any previous parent before it
    // is re-added, so each one is pulled out of whatever it was previously
    // sitting in before this rebuilds around it.
    private void repackColumns() {
        int availH = columns.getHeight();
        int colW = columnWidth();
        if (availH <= 0 || colW <= 0) {
            columns.post(this::repackColumns);
            return;
        }
        columns.removeAllViews();
        int gapV = Style.dp(this, 16), gapH = Style.dp(this, 24);

        LinearLayout col = null;
        int used = 0;
        for (LinearLayout card : cards) {
            ViewGroup oldParent = (ViewGroup) card.getParent();
            if (oldParent != null) oldParent.removeView(card);

            // GONE cards cost nothing to pack, same as inside a plain
            // LinearLayout — a card behind a hidden one moves up to take its
            // place, because this whole pack is redone from scratch every
            // time visibility changes, not computed once and left stale.
            int cardH = 0;
            if (card.getVisibility() != View.GONE) {
                card.measure(
                    View.MeasureSpec.makeMeasureSpec(colW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                cardH = card.getMeasuredHeight();
            }
            boolean startNew = (col == null) || (used + gapV + cardH > availH);
            if (startNew) {
                col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams clp =
                    new LinearLayout.LayoutParams(colW, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (columns.getChildCount() > 0) clp.leftMargin = gapH;
                columns.addView(col, clp);
                used = 0;
            } else {
                Style.gap(col, this, 16);
                used += gapV;
            }
            col.addView(card);
            used += cardH;
        }
    }

    // The Clima card: title, outside temp, the effort scale, the two ask tiles
    // (cooler/warmer) and the two switch tiles (recirculate/purge), the cog to
    // Config. See the handoff README, "Clima card", for every number here.
    private LinearLayout climaCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        // 26, not the handoff's 38 — see the ScrollView comment in onCreate:
        // with Turbo added, 38 everywhere left no room before the car's own
        // bottom bar without scrolling. This trims "air" (padding, gaps), not
        // the ❄/☀ and switch tiles — those are touch targets, not whitespace.
        int pad = Style.dp(this, 26);
        c.setPadding(pad, pad, pad, pad);
        c.setBackground(Style.card(Style.cardFillColor(), this));

        // The outside reading up front: it is the only thermometer this car has.
        LinearLayout tempRow = new LinearLayout(this);
        tempRow.setOrientation(LinearLayout.HORIZONTAL);
        tempRow.setGravity(Gravity.BOTTOM);       // approximates the mock's baseline align
        bigOutside = new TextView(this);
        bigOutside.setTextColor(Style.TEXT); bigOutside.setTextSize(112);
        bigOutside.setLetterSpacing(-0.04f);
        // "--" (no reading) and "°" do NOT go to strings.xml: they are a marker
        // and a unit, the same in any language — and the "--" is already born
        // inside fmt().
        bigOutside.setText("--°");
        tempRow.addView(bigOutside);
        TextView outsideLbl = new TextView(this);
        outsideLbl.setTextColor(Style.TEXT_DIM); outsideLbl.setTextSize(26);
        outsideLbl.setText(getString(R.string.ac_outside_label));
        outsideLbl.setPadding(Style.dp(this, 16), 0, 0, Style.dp(this, 16));
        tempRow.addView(outsideLbl);
        c.addView(tempRow);
        Style.gap(c, this, 18);

        // The effort scale: drawn into a Bitmap, same idiom as the old ruler —
        // needs the width already measured, redrawn only when something in it
        // actually changed (see redrawScale()).
        scaleView = new android.widget.ImageView(this);
        scaleView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 15)));
        c.addView(scaleView);
        Style.gap(c, this, 18);

        // Ask row: glyph only, no text label, no coloured outline — side carries
        // the direction, icons tinted Style.TEXT to match the other buttons in
        // this card. No debounce, on purpose: a locked-out button would only
        // make the user fight it. What avoids the jolt is not sending
        // contradictory writes, not delaying the tap (see ComfortRuler).
        LinearLayout askRow = new LinearLayout(this);
        askRow.setOrientation(LinearLayout.HORIZONTAL);
        askRow.addView(iconTileVector(R.drawable.ic_snowflake, Style.TEXT, +1), tileLp(96, 0));
        askRow.addView(iconTileVector(R.drawable.ic_weather_sunny, Style.TEXT, -1), tileLp(96, 14));
        c.addView(askRow);
        Style.gap(c, this, 18);

        // Switch row: recirculation and the window purge, now living with the
        // climate conversation instead of a separate commands column.
        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        recircBtn = iconSwitchTile(R.drawable.ic_hvac_cycle_on, 10);
        recircBtn.setOnClickListener(v -> {
            final boolean want = !recircOn;
            CarActor.get(this).runOnCarThread(() -> {
                boolean ok = CarActor.get(this).rawAccess().setHvacFlag(CarAccess.HVAC_RECIRC_ON, want);
                ui.post(() -> {
                    if (ok) setRecirc(want);       // only move it if the car agreed
                    hint.setText(!ok ? getString(R.string.ac_recirc_refused)
                        : want ? getString(R.string.ac_recirc_on)
                                : getString(R.string.ac_recirc_off));
                });
            });
        });
        switchRow.addView(recircBtn, tileLp(96, 0));
        purgeBtn = iconSwitchTile(R.drawable.ic_window_lower, 7);
        purgeBtn.setOnClickListener(v -> {
            purgeBtn.setEnabled(false);      // the glass takes seconds; one press is one move
            CarActor.get(this).runOnCarThread(() -> {
                CarAccess pc = CarActor.get(this).rawAccess();
                // WHICH WAY THE BUTTON ACTS IS READ, NOT REMEMBERED. A hand can
                // move a window, and the car shuts them all on lock, so a stored
                // flag would be wrong exactly when it mattered.
                final Boolean was = purge.anyOpen(pc);
                final int moved = (was == null) ? 0
                                : was ? purge.close(pc) : purge.open(pc);
                ui.post(() -> {
                    hint.setText(was == null ? getString(R.string.purge_unreadable)
                        : moved == 0 ? getString(R.string.purge_nothing)
                        : was ? getString(R.string.purge_closing)
                              : getString(R.string.purge_opening));
                    purgeBtn.setEnabled(true);
                    // The icon itself updates from car.window_pos pushes
                    // (windowPosListener) the instant the glass actually
                    // crosses the open/shut line — not this click, not a
                    // timer. That is the real "the car senses it faster"
                    // answer: the old delay was the ~10s ambient poll being
                    // the only thing that ever re-read the property.
                });
            });
        });
        switchRow.addView(purgeBtn, tileLp(96, 14));
        c.addView(switchRow);
        Style.gap(c, this, 18);

        // Footer: airflow direction, rear defroster, and status hint text
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);

        windDirIcon = new android.widget.ImageView(this);
        windDirIcon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams windLp = new LinearLayout.LayoutParams(Style.dp(this, 36), Style.dp(this, 36));
        windLp.rightMargin = Style.dp(this, 8);
        windDirIcon.setLayoutParams(windLp);
        windDirIcon.setVisibility(View.GONE);
        footer.addView(windDirIcon);

        rearDefrostIcon = new android.widget.ImageView(this);
        rearDefrostIcon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        rearDefrostIcon.setImageResource(R.drawable.ic_hvac_rear_defrost);
        LinearLayout.LayoutParams rearLp = new LinearLayout.LayoutParams(Style.dp(this, 36), Style.dp(this, 36));
        rearLp.rightMargin = Style.dp(this, 8);
        rearDefrostIcon.setLayoutParams(rearLp);
        rearDefrostIcon.setVisibility(View.GONE);
        rearDefrostIcon.setOnClickListener(v -> {
            final boolean want = !rearDefrostOn;
            CarActor.get(this).runOnCarThread(() -> {
                boolean ok = CarActor.get(this).rawAccess().setRearDefrost(want);
                if (ok) ui.post(() -> setRearDefrost(want));
            });
        });
        footer.addView(rearDefrostIcon);

        hint = new TextView(this);
        hint.setTextColor(Style.TEXT_DIM); hint.setTextSize(20);
        footer.addView(hint);
        c.addView(footer);

        setRecirc(false);
        setPurge(false);          // drawn dim; the first read corrects it if not
        return c;
    }

    /** Left-edge status rail: one rounded card containing status icons
     * (HA/MQTT, ABRP, OBD2) arranged CarPlay-dock style. Room is reserved
     * below for future stat icons, before the settings cog at the bottom. */
    static final int SIDEBAR_WIDTH_DP = 84;
    static final int SIDEBAR_EDGE_MARGIN_DP = 8;
    // Same rhythm as the gap BETWEEN the three main cards (repackColumns'
    // own gapH), not the wider screen-edge inset (padH) — that mismatch is
    // exactly what made the gap to Clima read as too big.
    static final int SIDEBAR_CARD_GAP_DP = 24;

    private LinearLayout statusSidebar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setPadding(0, Style.dp(this, 20), 0, Style.dp(this, 14));
        bar.setBackground(Style.card(Style.cardFillColor(), this));

        // Small icons in a 2-wide grid: line 1 has HA/MQTT + ABRP,
        // line 2 has OBD2 (with room for a 4th icon).
        haStatusIcon = statusIcon(R.drawable.ic_home_assistant);
        abrpStatusIcon = statusIcon(R.drawable.ic_route);
        obd2StatusIcon = statusIcon(R.drawable.ic_bluetooth);

        LinearLayout statusRow1 = new LinearLayout(this);
        statusRow1.setOrientation(LinearLayout.HORIZONTAL);
        statusRow1.setGravity(Gravity.CENTER_VERTICAL);
        statusRow1.addView(haStatusIcon, statusIconLp(0));
        statusRow1.addView(abrpStatusIcon, statusIconLp(4));
        bar.addView(statusRow1, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Style.gap(bar, this, 4);

        LinearLayout statusRow2 = new LinearLayout(this);
        statusRow2.setOrientation(LinearLayout.HORIZONTAL);
        statusRow2.setGravity(Gravity.CENTER_VERTICAL);
        statusRow2.addView(obd2StatusIcon, statusIconLp(0));
        bar.addView(statusRow2, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Flexible space pushing dock to the middle of the rail
        View spacerTop = new View(this);
        bar.addView(spacerTop, new LinearLayout.LayoutParams(0, 0, 1f));

        // CarPlay-style middle dock with larger icons almost as wide as the bar (84dp)
        cardsDockIcon = createDockButton(R.drawable.ic_cards_view, () -> setMainViewMode(MODE_CARDS));
        statsDockIcon = createDockButton(R.drawable.ic_chart_bar, () -> setMainViewMode(MODE_DRIVING_STATS));
        chargeDockIcon = createDockButton(R.drawable.ic_ev_station, () -> setMainViewMode(MODE_CHARGE_STATS));

        bar.addView(cardsDockIcon);
        Style.gap(bar, this, 14);
        bar.addView(statsDockIcon);
        Style.gap(bar, this, 14);
        bar.addView(chargeDockIcon);

        // Flexible space pushing settings cog to the bottom
        View spacerBottom = new View(this);
        bar.addView(spacerBottom, new LinearLayout.LayoutParams(0, 0, 1f));

        bar.addView(Style.cogButton(this,
            () -> startActivity(new Intent(this, TelemetryActivity.class))));

        refreshStatusSidebar();
        return bar;
    }

    private android.widget.ImageView createDockButton(int drawableRes, Runnable onClick) {
        android.widget.ImageView v = new android.widget.ImageView(this);
        v.setImageResource(drawableRes);
        v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = Style.dp(this, 12);
        v.setPadding(pad, pad, pad, pad);
        int size = Style.dp(this, 62);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        v.setLayoutParams(lp);
        if (onClick != null) {
            v.setOnClickListener(view -> onClick.run());
        }
        return v;
    }

    private void setMainViewMode(int mode) {
        if (mainViewMode == mode) return;
        mainViewMode = mode;
        if (mainViewMode == MODE_DRIVING_STATS) {
            band.setVisibility(View.GONE);
            statsContainer.setVisibility(View.VISIBLE);
            chargeStatsContainer.setVisibility(View.GONE);
            if (konamiZone != null) konamiZone.setVisibility(View.GONE);
            if (dailyStatsView != null) dailyStatsView.refresh();
        } else if (mainViewMode == MODE_CHARGE_STATS) {
            band.setVisibility(View.GONE);
            statsContainer.setVisibility(View.GONE);
            chargeStatsContainer.setVisibility(View.VISIBLE);
            if (konamiZone != null) konamiZone.setVisibility(View.GONE);
            if (chargeStatsView != null) chargeStatsView.refresh();
        } else {
            statsContainer.setVisibility(View.GONE);
            chargeStatsContainer.setVisibility(View.GONE);
            band.setVisibility(View.VISIBLE);
            if (konamiZone != null) konamiZone.setVisibility(View.VISIBLE);
        }
        updateDockButtons();
    }

    private void updateDockButtons() {
        if (cardsDockIcon == null || statsDockIcon == null || chargeDockIcon == null) return;
        cardsDockIcon.setBackground(Style.card(mainViewMode == MODE_CARDS ? Style.CARD_ON : 0x00000000, this, 18));
        cardsDockIcon.setColorFilter(mainViewMode == MODE_CARDS ? Style.onFill(Style.CARD_ON) : Style.TEXT_DIM);

        statsDockIcon.setBackground(Style.card(mainViewMode == MODE_DRIVING_STATS ? Style.CARD_ON : 0x00000000, this, 18));
        statsDockIcon.setColorFilter(mainViewMode == MODE_DRIVING_STATS ? Style.onFill(Style.CARD_ON) : Style.TEXT_DIM);

        chargeDockIcon.setBackground(Style.card(mainViewMode == MODE_CHARGE_STATS ? Style.CARD_ON : 0x00000000, this, 18));
        chargeDockIcon.setColorFilter(mainViewMode == MODE_CHARGE_STATS ? Style.onFill(Style.CARD_ON) : Style.TEXT_DIM);
    }

    private android.widget.ImageView statusIcon(int drawableRes) {
        android.widget.ImageView v = new android.widget.ImageView(this);
        v.setImageResource(drawableRes);
        // FIT_CENTER, not CENTER_INSIDE: CENTER_INSIDE never draws a vector
        // past its own declared dp size (24dp here), no matter how much
        // room the ImageView has — that was the actual "icon too small"
        // bug, not the padding. FIT_CENTER scales the vector's viewport to
        // fill the available space instead.
        v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = Style.dp(this, 1);
        v.setPadding(pad, pad, pad, pad);
        return v;
    }

    private LinearLayout.LayoutParams statusIconLp(int leftGap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            Style.dp(this, 30), Style.dp(this, 30));
        lp.leftMargin = Style.dp(this, leftGap);
        return lp;
    }

    // Connection state only — same ACCENT/TEXT_DIM tinting convention as
    // Turbo's regen glyph. Polled on the existing 10s rulerPoll cadence
    // (see refresh()) and once more on any GateState.Listener fire, which
    // is what makes the HA/MQTT icon react to a WiFi drop immediately
    // instead of waiting for the next poll.
    private void refreshStatusSidebar() {
        if (haStatusIcon == null) return;

        // Each icon only exists on screen when its service is actually
        // turned on — an icon for a service you never enabled is noise,
        // not status.
        boolean haOn = prefs.getBoolean("tele_enabled", false);
        haStatusIcon.setVisibility(haOn ? View.VISIBLE : View.GONE);
        if (haOn) haStatusIcon.setColorFilter(GateState.connected() ? Style.ACCENT : Style.TEXT_DIM);

        boolean abrpOn = prefs.getBoolean("abrp_enabled", false);
        abrpStatusIcon.setVisibility(abrpOn ? View.VISIBLE : View.GONE);
        if (abrpOn) abrpStatusIcon.setColorFilter(AbrpUploader.lastAttemptOk() ? Style.ACCENT : Style.TEXT_DIM);

        boolean obd2On = prefs.getBoolean("obd2_enabled", false);
        obd2StatusIcon.setVisibility(obd2On ? View.VISIBLE : View.GONE);
        if (obd2On) obd2StatusIcon.setColorFilter(Obd2Reader.isConnected() ? Style.ACCENT : Style.TEXT_DIM);

        updateDockButtons();
    }

    // Gate availability: available() (HA's zone call — "is the car home")
    // gates the card; connected() (this car's own broker link) ALSO gates
    // it, on purpose, not just the button — this is a security control,
    // not a convenience one, so a stale "available" the car can no longer
    // verify (Wi-Fi dropped after HA last said yes) must hide the card.
    // See gateVisible().
    private boolean gateVisible() {
        return GateState.available() && GateState.connected();
    }


    // Turbo card: title + lightning glyph, and a bar that's always there —
    // full when idle (ready), draining during a boost. Split into two mini
    // cards: left keeps this exact Turbo content and tap target, right is a
    // plain Strong-Regen toggle (no timer, no countdown — press once for
    // High, press again to go back to whatever Config's own regen default
    // is). One outer card, one background, one visibility rule for both —
    // only the tap target and inner content split in two.
    private LinearLayout turboCard() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Style.dp(this, 26);   // trimmed from the handoff's 38 — see climaCard()
        t.setPadding(pad, pad, pad, pad);
        t.setBackground(Style.card(Style.cardFillColor(), this));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setOnClickListener(v -> TurboMode.get(this).start());
        t.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(getString(R.string.turbo_title));
        title.setTextColor(Style.TEXT); title.setTextSize(28);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView glyph = new TextView(this);
        // "⚡" is an icon, not prose — same as Sport mode's glyph in Config.
        glyph.setText("⚡"); glyph.setTextColor(Style.ACCENT); glyph.setTextSize(34);
        row.addView(glyph);
        left.addView(row);

        turboBarView = new android.widget.ImageView(this);
        // ALWAYS visible, fixed height — never GONE. It used to hide while
        // idle, which changed the Turbo card's height at runtime; packColumns()
        // only measures once, at build time, so the card grew taller than its
        // reserved slot the moment a boost started, and everything after it
        // got squeezed. Full (ready) when idle, draining while a boost runs —
        // same View, same size, always, no layout surprise either way.
        // Thinner than the effort scale (10dp not 15) and a smaller top margin
        // (12dp not 20) — a decoration, not a touch target, so it is the first
        // thing trimmed to make room. Width now follows the LEFT half only
        // (post-split) — redrawTurboBar() reads its actual width at draw
        // time, so this needed no change of its own.
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 10));
        barLp.topMargin = Style.dp(this, 12);
        turboBarView.setLayoutParams(barLp);
        left.addView(turboBarView);

        // Thin divider so the two halves read as two mini cards, not one
        // wide accidental row.
        View divider = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(Style.dp(this, 1), ViewGroup.LayoutParams.MATCH_PARENT);
        divLp.leftMargin = Style.dp(this, 22);
        divLp.rightMargin = Style.dp(this, 22);
        divider.setBackgroundColor(Style.TEXT_DIM);
        divider.getBackground().setAlpha(60);
        t.addView(divider, divLp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setOnClickListener(v -> toggleStrongRegen());
        t.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout regenRow = new LinearLayout(this);
        regenRow.setOrientation(LinearLayout.HORIZONTAL);
        regenRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView regenTitle = new TextView(this);
        regenTitle.setText(getString(R.string.regen_boost_title));
        regenTitle.setTextColor(Style.TEXT); regenTitle.setTextSize(28);
        regenTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        regenRow.addView(regenTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView regenGlyph = new TextView(this);
        // Same glyph Config's own regen picker uses for High — retinted
        // (not swapped) to show on/off, so "High" always means the same
        // mark everywhere in this app.
        regenGlyph.setText("●"); regenGlyph.setTextColor(Style.TEXT_DIM); regenGlyph.setTextSize(34);
        regenRow.addView(regenGlyph);
        right.addView(regenRow);
        regenGlyphView = regenGlyph;

        // Matches the left half's bar slot so both halves land at the same
        // height — a decoration-shaped spacer, not a second progress bar
        // (this toggle has no countdown to show).
        View regenSpacer = new View(this);
        LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 10));
        spacerLp.topMargin = Style.dp(this, 12);
        regenSpacer.setLayoutParams(spacerLp);
        right.addView(regenSpacer);

        // Turbo (and now Strong Regen alongside it) only means anything
        // while driving — hidden while parked, per whatever CarState
        // already knows at build time; the listener (see below) keeps it
        // in sync as gear actually changes. Both halves share this one rule.
        t.setVisibility(CarState.isParked() ? View.GONE : View.VISIBLE);
        turboCardView = t;
        refreshRegenGlyph();
        return t;
    }

    // Plain two-state toggle, no timer: High, or back to whatever Config's
    // own regen default is ("regen" pref, same one TelemetryActivity saves).
    // Reads the real car state first rather than tracking a local boolean —
    // if regen was changed from Config since this screen last drew, the
    // toggle must reflect reality, not a stale guess.
    private void toggleStrongRegen() {
        CarActor.get(this).read("regen_mode", cur -> {
            boolean isHigh = (cur instanceof Integer) && (Integer) cur == Modes.REGEN_HIGH;
            int target = isHigh
                ? getSharedPreferences("drivemem", MODE_PRIVATE).getInt("regen", Modes.REGEN_MID)
                : Modes.REGEN_HIGH;
            CarActor.get(this).cast("regen_mode", target, r -> {
                if (r.applied) runOnUiThread(() -> tintRegenGlyph(target == Modes.REGEN_HIGH));
            });
        });
    }

    private void refreshRegenGlyph() {
        CarActor.get(this).read("regen_mode", cur -> {
            boolean isHigh = (cur instanceof Integer) && (Integer) cur == Modes.REGEN_HIGH;
            runOnUiThread(() -> tintRegenGlyph(isHigh));
        });
    }

    private void tintRegenGlyph(boolean high) {
        if (regenGlyphView != null) regenGlyphView.setTextColor(high ? Style.ACCENT : Style.TEXT_DIM);
    }

    // Music card: album art + title/artist, then skip/play-pause tiles. Starts
    // GONE — nothing to show until the first drivemem/geely/media/state arrives —
    // same as Portão, and packed the same re-flowing way (see repackColumns()).
    private LinearLayout musicCard() {
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(this, 26);
        m.setPadding(pad, pad, pad, pad);
        m.setBackground(Style.card(Style.cardFillColor(), this));
        m.setVisibility(View.GONE);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        musicArtView = new android.widget.ImageView(this);
        musicArtView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        int artSize = Style.dp(this, 80);
        musicArtView.setLayoutParams(new LinearLayout.LayoutParams(artSize, artSize));
        musicArtView.setBackground(Style.tile(this));   // placeholder fill until art loads
        musicArtView.setClipToOutline(true);
        final int artRadius = Style.dp(this, Style.RADIUS_DP - 8);
        musicArtView.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), artRadius);
            }
        });
        row.addView(musicArtView);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);
        musicTitleView = new TextView(this);
        musicTitleView.setTextColor(Style.TEXT); musicTitleView.setTextSize(25);
        musicTitleView.setTypeface(null, android.graphics.Typeface.BOLD);
        musicTitleView.setMaxLines(1);
        musicTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(musicTitleView);
        musicArtistView = new TextView(this);
        musicArtistView.setTextColor(Style.TEXT_DIM); musicArtistView.setTextSize(20);
        musicArtistView.setMaxLines(1);
        musicArtistView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        musicArtistView.setPadding(0, Style.dp(this, 4), 0, 0);
        textCol.addView(musicArtistView);
        LinearLayout.LayoutParams textLp =
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = Style.dp(this, 18);
        row.addView(textCol, textLp);
        m.addView(row);
        Style.gap(m, this, 18);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        // "⏯"/"⏭" are icons, not prose — same convention as ❄/☀/⚡.
        btnRow.addView(mediaTile("⏯", MusicState::playPause), tileLp(76, 0));
        btnRow.addView(mediaTile("⏭", MusicState::next), tileLp(76, 14));
        m.addView(btnRow);

        musicCard = m;
        return m;
    }

    // Charge card: active session progress or retained completed charge with cost input.
    private LinearLayout chargeCard() {
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(this, 26);
        m.setPadding(pad, pad, pad, pad);
        m.setBackground(Style.card(Style.cardFillColor(), this));
        m.setVisibility(View.GONE);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        chargeCardTitle = new TextView(this);
        chargeCardTitle.setText(getString(R.string.charge_card_title));
        chargeCardTitle.setTextColor(Style.TEXT); chargeCardTitle.setTextSize(22);
        chargeCardTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(chargeCardTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView glyph = new TextView(this);
        glyph.setText("🔋"); glyph.setTextSize(28);
        row.addView(glyph);
        m.addView(row);

        // --- Active charging layout ---
        chargeActiveLayout = new LinearLayout(this);
        chargeActiveLayout.setOrientation(LinearLayout.VERTICAL);

        chargeSocView = new TextView(this);
        chargeSocView.setTextColor(Style.TEXT); chargeSocView.setTextSize(52);
        chargeSocView.setTypeface(null, android.graphics.Typeface.BOLD);
        chargeSocView.setPadding(0, Style.dp(this, 4), 0, 0);
        chargeActiveLayout.addView(chargeSocView);

        chargeBarView = new android.widget.ImageView(this);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 14));
        barLp.topMargin = Style.dp(this, 10);
        chargeBarView.setLayoutParams(barLp);
        chargeActiveLayout.addView(chargeBarView);

        LinearLayout durRow = new LinearLayout(this);
        durRow.setOrientation(LinearLayout.HORIZONTAL);
        durRow.setPadding(0, Style.dp(this, 8), 0, 0);
        chargeElapsedView = new TextView(this);
        chargeElapsedView.setTextColor(Style.TEXT_DIM); chargeElapsedView.setTextSize(16);
        durRow.addView(chargeElapsedView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        chargeRemainingView = new TextView(this);
        chargeRemainingView.setTextColor(Style.TEXT_DIM); chargeRemainingView.setTextSize(16);
        chargeRemainingView.setGravity(Gravity.END);
        durRow.addView(chargeRemainingView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        chargeActiveLayout.addView(durRow);

        m.addView(chargeActiveLayout);

        // --- Completed charging layout (retained for cost input) ---
        chargeCompletedLayout = new LinearLayout(this);
        chargeCompletedLayout.setOrientation(LinearLayout.VERTICAL);
        chargeCompletedLayout.setVisibility(View.GONE);

        chargeCompletedMetrics = new TextView(this);
        chargeCompletedMetrics.setTextColor(Style.TEXT);
        chargeCompletedMetrics.setTextSize(20);
        chargeCompletedMetrics.setTypeface(null, android.graphics.Typeface.BOLD);
        chargeCompletedMetrics.setPadding(0, Style.dp(this, 12), 0, Style.dp(this, 4));
        chargeCompletedLayout.addView(chargeCompletedMetrics);

        chargeCompletedCostText = new TextView(this);
        chargeCompletedCostText.setTextColor(Style.TEXT_DIM);
        chargeCompletedCostText.setTextSize(16);
        chargeCompletedLayout.addView(chargeCompletedCostText);

        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arLp.topMargin = Style.dp(this, 14);
        actRow.setLayoutParams(arLp);

        chargeDismissBtn = Style.cardButton(this, getString(R.string.charge_cost_btn_dismiss), false, () -> {
            if (retainedChargeSession != null) {
                ChargeSession.dismissSession(this, retainedChargeSession.id);
                retainedChargeSession = null;
            }
            hideCharging();
        });
        actRow.addView(chargeDismissBtn);
        Style.gap(actRow, this, 10);

        chargeCostActionBtn = Style.cardButton(this, getString(R.string.charge_cost_btn_input), true, () -> {
            if (retainedChargeSession != null) {
                if (!CarState.isParked()) {
                    android.widget.Toast.makeText(this, R.string.charge_cost_parked_only, android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                activeCostDialog = ChargeCostDialog.show(this, retainedChargeSession.id, retainedChargeSession.kwh,
                    retainedChargeSession.socStart, retainedChargeSession.socEnd, retainedChargeSession.cost, () -> {
                        retainedChargeSession = ChargeSession.getLatestUndismissed(this);
                        if (retainedChargeSession != null) {
                            showCompletedCharging(retainedChargeSession);
                        }
                    });
            }
        });
        actRow.addView(chargeCostActionBtn);
        chargeCompletedLayout.addView(actRow);

        m.addView(chargeCompletedLayout);

        return m;
    }

    // Generic glyph tile — same shell as the ask/switch tiles, but a plain
    // Runnable instead of iconTile()'s hardcoded comfortRuler.tap(), since
    // these two buttons don't belong to the ruler.
    private TextView mediaTile(String glyph, Runnable onClick) {
        TextView t = new TextView(this);
        t.setText(glyph); t.setTextColor(Style.TEXT); t.setTextSize(30);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Style.tile(this));
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    // Fetches album art on a background thread (java.net — HA's own signed
    // URL, same trusted host the OTA apk comes from, no library needed for
    // one occasional small image) and swaps it in on the UI thread. Skipped
    // entirely when the URL has not actually changed, so a state ping that
    // only moves `playing` does not re-fetch the same picture.
    private void loadMusicArt(String url) {
        if (url == null || url.equals(lastMusicArtUrl)) return;
        lastMusicArtUrl = url;
        new Thread(() -> {
            android.graphics.Bitmap bmp = null;
            try {
                java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(6000); conn.setReadTimeout(6000);
                bmp = android.graphics.BitmapFactory.decodeStream(conn.getInputStream());
            } catch (Throwable t) { android.util.Log.w(CarAccess.TAG, "music art: " + t); }
            final android.graphics.Bitmap fbmp = bmp;
            // url.equals(lastMusicArtUrl): a newer fetch may have started (and
            // maybe even finished) while this one was in flight — do not let a
            // slow, stale request stomp a fresher picture that already landed.
            if (fbmp != null) ui.post(() -> {
                if (musicArtView != null && url.equals(lastMusicArtUrl)) musicArtView.setImageBitmap(fbmp);
            });
        }, "music-art").start();
    }

    // Glyph-only ask tile: fainter fill than the card, a 1dp hairline, colour
    // carries the direction (COOL/HEAT) — the handoff explicitly says no
    // coloured outline here, unlike the old tapCard().
    private TextView iconTile(String glyph, int color, int dir) {
        TextView t = new TextView(this);
        t.setText(glyph); t.setTextColor(color); t.setTextSize(34);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Style.tile(this));
        t.setOnClickListener(v -> comfortRuler.tap(dir));   // the UI follows via onChange
        return t;
    }

    // Switch tile shell: same faint fill/hairline as the ask tiles. setRecirc/
    // setPurge repaint it lit (accent fill) or unlit (Style.tile()).
    private TextView switchTile(String label) {
        TextView t = new TextView(this);
        t.setText(label); t.setTextColor(Style.TEXT); t.setTextSize(25);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Style.tile(this));
        return t;
    }

    // Same shell as switchTile, a vector icon instead of a text label —
    // setRecirc() swaps the drawable and tints it the same way switchTile's
    // text used to be tinted. FIT_CENTER, not CENTER_INSIDE — see
    // statusIcon()'s comment, same bug made this icon render tiny inside a
    // much bigger tile.
    private android.widget.ImageView iconSwitchTile(int drawableRes, int padDp) {
        android.widget.ImageView v = new android.widget.ImageView(this);
        v.setImageResource(drawableRes);
        v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = Style.dp(this, padDp);
        v.setPadding(pad, pad, pad, pad);
        v.setBackground(Style.tile(this));
        return v;
    }

    // Vector icon ask tile: like iconSwitchTile but with a tap listener for
    // climate direction changes (cooler/warmer). Color is applied as a tint
    // filter (PorterDuff.Mode.SRC_IN).
    private android.widget.ImageView iconTileVector(int drawableRes, int color, int dir) {
        android.widget.ImageView v = new android.widget.ImageView(this);
        v.setImageResource(drawableRes);
        v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = Style.dp(this, 28);
        v.setPadding(pad, pad, pad, pad);
        v.setBackground(Style.tile(this));
        v.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        v.setOnClickListener(view -> comfortRuler.tap(dir));
        return v;
    }

    // flex:1, height h, with the row's 14dp gap split as a leading margin on
    // every tile after the first (leftMargin 0 on the first, 14dp from then on).
    private LinearLayout.LayoutParams tileLp(int h, int leftGap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Style.dp(this, h), 1f);
        lp.leftMargin = Style.dp(this, leftGap);
        return lp;
    }

    // The code came in. Rearm the entry BEFORE recreate(): the fresh
    // VaporArtView reads introPlayed while it is being measured, so setting it
    // afterwards would arrive too late and the car would simply be there.
    private void konamiUnlock() {
        VaporArtView.playIntro();
        Style.setTransient(this, "noturno");
        recreate();
    }

    // the answer to a tap: hint text and scale only, no trip to the car (the
    // 10 s refresh takes care of the real values)
    private void syncRuler() {
        hint.setText(comfortRuler.status());
        redrawScale();
        setWindDirection(comfortRuler.direction());
    }

    // the scale is drawn into a Bitmap, so it needs the width already measured;
    // approx is part of the key, same reasoning as the old ruler's range check
    private int scalePointer = Integer.MIN_VALUE, scaleW = -1;
    private boolean scaleApprox = false;
    private boolean scaleDefrosting = false;

    private void redrawScale() {
        if (scaleView == null) return;
        int w = scaleView.getWidth();
        if (w <= 0) { scaleView.post(this::redrawScale); return; }
        int level = comfortRuler.pointer();
        boolean approx = comfortRuler.approx();
        boolean defrosting = comfortRuler.defrosting();
        // Standing still nothing moved: with no change, there is nothing to
        // redraw (the 10 s poll used to allocate a new Bitmap every time for
        // exactly this reason).
        if (level == scalePointer && w == scaleW && approx == scaleApprox
                && defrosting == scaleDefrosting) return;
        scalePointer = level; scaleW = w; scaleApprox = approx; scaleDefrosting = defrosting;
        scaleView.setImageBitmap(Style.effortScale(this, w, scaleView.getHeight(), level, approx, defrosting));
    }

    // held as a field so onDestroy can drop exactly this subscription
    private final Runnable rulerListener = this::syncRuler;

    // relaxing back in the silence, memory of the band and detection of a manual adjustment
    private final Runnable rulerPoll = new Runnable() {
        @Override public void run() {
            comfortRuler.maintenance();
            refresh();
            ui.postDelayed(this, 10000);
        }
    };

    private void refresh() {
        refreshStatusSidebar();
        // Outside temp already lives in CarActor's own cache (kept fresh by
        // its always-on 15s poll, independent of this screen) — no need for
        // a live read of our own any more.
        CarActor.Reading tr = CarActor.get(this).get("telemetry.outside_temp");
        final String ftxt = (tr.status == CarActor.Reading.Status.OK)
            ? fmt(((Number) tr.value).floatValue()) + "°" : "--°";
        // setText invalidates even with identical text, and standing still
        // these two come out the same from reading to reading
        if (!ftxt.contentEquals(bigOutside.getText())) bigOutside.setText(ftxt);
        redrawScale();
        CarActor.get(this).runOnCarThread(() -> {
            CarAccess c = CarActor.get(this).rawAccess();
            if (!c.isReady()) return;
            // the car is the authority: a change made on its own AC screen
            // has to move this button too
            Boolean rc = c.readHvacFlag(CarAccess.HVAC_RECIRC_ON);
            if (rc != null) { final boolean frc = rc; ui.post(() -> setRecirc(frc)); }
            // Same authority argument as recirculation: a window moved on the
            // native panel, or shut by the car on lock, has to move this label.
            Boolean po = purge.anyOpen(c);
            if (po != null) { final boolean fpo = po; ui.post(() -> setPurge(fpo)); }
            Boolean pwr = c.readHvacFlag(CarAccess.HVAC_POWER_ON);
            Integer dir = c.readIntRaw(EffortTable.DIR_PROP, 0);
            final int fdir = (pwr != null && pwr && dir != null) ? dir : 0;
            ui.post(() -> setWindDirection(fdir));
            Boolean rd = c.readRearDefrost();
            if (rd != null) { final boolean frd = rd; ui.post(() -> setRearDefrost(frd)); }
        });
    }

    private String fmt(float v) { return Float.isNaN(v) ? "--" : String.format(java.util.Locale.US, "%.0f", v); }

    // applies the ambient light colour to every detail that follows it.
    // A theme that does not follow the cabin (Noturno) uses its own accent — at
    // night the whole point of it is precisely NOT to carry the cabin's
    // blue/violet light onto the screen.
    private int lastAmbient = 0;

    private void applyAmbient(int rgb) {
        int raw = 0xFF000000 | (rgb & 0xFFFFFF);
        int c = Style.FOLLOW_AMBIENT ? raw : Style.ACCENT;
        // The art always receives the REAL cabin colour and decides what to do
        // with it: Noturno does not let the cabin rule the CONTROLS (no blue in
        // your face at night), but the panel's city does follow the car's RGB.
        // (Both arts already filter a repeated colour, so this does not redraw.)
        if (art != null) art.setAmbient(raw);
        // Skip repaints of the same color: setBackgroundColor and setTextColor
        // invalidate even with identical values, and this 4s poll would otherwise
        // request frames unnecessarily.
        if (c == lastAmbient) return;
        lastAmbient = c;
        if (card != null) card.setAccent(c);
        setRecirc(recircOn);      // re-tint: lit means the ambient accent
        setPurge(purgeOpen);
        redrawTurboBar(turboBarFraction < 0 ? 1f : turboBarFraction);   // re-tint, same fraction
        if (gateCard != null) gateCard.setAccent(c);
        if (windDirIcon != null && currentWindDir != 0) {
            windDirIcon.setColorFilter(Style.TEXT, android.graphics.PorterDuff.Mode.SRC_IN);
        }
    }

    // HA context panel -> re-posted on the UI thread
    private final PanelState.Listener panelListener = json -> ui.post(() -> showPanel(json));

    // Gate availability/state -> re-posted on the UI thread. The card's
    // visibility IS the availability cue (see the handoff); the lamp is static.
    // A re-pack only on an actual CHANGE in the combined visibility — `state`
    // (open/opening/...) fires this listener too, far more often, and never
    // moves whether Portão is in the flow at all, only what its own label
    // says. Fires on a `connected()` change too (GateState.fire() does not
    // distinguish), which is exactly what hides the card the instant Wi-Fi
    // drops, not just when HA's own availability changes.
    private final GateState.Listener gateListener = (available, state) ->
        ui.post(() -> {
            boolean visible = gateVisible();
            if (gateCard != null) {
                gateCard.setVisibility(visible ? View.VISIBLE : View.GONE);
                gateCard.refreshStatus();
            }
            refreshStatusSidebar();   // connected() just changed; don't wait for the next poll
            if (visible != lastGateAvailable) {
                lastGateAvailable = visible;
                if (columns != null) repackColumns();
            }
        });

    // "Available" here just means "HA has a title for us" — no separate
    // available/unavailable topic like the gate, since a missing title
    // already says everything: nothing is loaded worth showing a card for.
    private final MusicState.Listener musicListener = (playing, title, artist, artUrl) ->
        ui.post(() -> {
            boolean available = title != null && !title.isEmpty();
            if (musicCard != null) musicCard.setVisibility(available ? View.VISIBLE : View.GONE);
            if (available) {
                musicTitleView.setText(title);
                musicArtistView.setText(artist == null ? "" : artist);
                loadMusicArt(artUrl);
            }
            if (available != lastMusicAvailable) {
                lastMusicAvailable = available;
                if (columns != null) repackColumns();
            }
        });

    // ChargeSession calls back from TelemetryService's own loop thread, not
    // the UI thread — unlike TurboMode/MusicState, this one DOES need ui.post.
    private final ChargeSession.ProgressListener chargeListener = new ChargeSession.ProgressListener() {
        @Override public void onProgress(int socStart, int socNow, long startWallMs, long nowWallMs) {
            // Charging only makes sense while parked — see CarState's header
            // for why this is a real check, not just tidiness.
            ui.post(() -> {
                if (CarState.isParked()) showCharging(socStart, socNow, startWallMs, nowWallMs);
                else hideCharging();
            });
        }
        @Override public void onCompleted(ChargeSession.Summary s) {
            ui.post(() -> showCompletedCharging(s));
        }
        @Override public void onIdle() {
            ui.post(ComfortActivity.this::checkRetainedCharge);
        }
    };
    private final EntityBus.Listener chargeBusListener = (key, reading) -> ui.post(this::checkRetainedCharge);

    private Updater.UpdateInfo pendingUpdate = null;
    private android.app.AlertDialog activeUpdateDialog = null;
    private android.app.AlertDialog activeCostDialog = null;

    private void promptUpdateIfParked(Updater.UpdateInfo info) {
        if (info == null) return;
        pendingUpdate = info;

        // VEHICLE SAFETY: strictly forbid popups while car is in motion or not in Park (P)
        if (!CarState.isParked()) {
            android.util.Log.i("ComfortActivity", "Update (" + info.versionName + ") deferred: vehicle is driving / not in park.");
            return;
        }

        if (activeUpdateDialog != null && activeUpdateDialog.isShowing()) return;

        activeUpdateDialog = UpdateDialog.show(this, info, () -> {
            pendingUpdate = null;
            activeUpdateDialog = null;
            Updater.update(getApplicationContext(), info.apkUrl, s -> {
                android.util.Log.i("ComfortActivity", "Update step: " + s);
            });
        }, () -> {
            // Driver declined/dismissed
            pendingUpdate = null;
            activeUpdateDialog = null;
        });
    }

    // Turbo only means anything while driving; Charging only while parked —
    // re-derive both from their own current state whenever gear changes,
    // since a gear change and a charging/turbo change are independent events.
    private final CarState.Listener carStateListener = parked -> ui.post(() -> {
        if (turboCardView != null) {
            boolean visible = turboEnabledAtBuild && !parked;
            if ((turboCardView.getVisibility() == View.VISIBLE) != visible) {
                turboCardView.setVisibility(visible ? View.VISIBLE : View.GONE);
                if (columns != null) repackColumns();
            }
        }
        if (parked && ChargeSession.isCharging()) {
            showCharging(ChargeSession.currentSocStart(), ChargeSession.currentSocEnd(),
                ChargeSession.currentStartWallMs(), System.currentTimeMillis());
        } else if (retainedChargeSession != null) {
            showCompletedCharging(retainedChargeSession);
        } else {
            checkRetainedCharge();
        }

        if (!parked) {
            // DRIVING SAFETY: immediately dismiss any active dialogs if vehicle leaves Park!
            if (activeUpdateDialog != null && activeUpdateDialog.isShowing()) {
                try { activeUpdateDialog.dismiss(); } catch (Throwable ignored) {}
                activeUpdateDialog = null;
            }
            if (activeCostDialog != null && activeCostDialog.isShowing()) {
                try { activeCostDialog.dismiss(); } catch (Throwable ignored) {}
                activeCostDialog = null;
            }
        } else {
            // SAFELY PARKED: present pending update if one was deferred while driving
            if (pendingUpdate != null && (activeUpdateDialog == null || !activeUpdateDialog.isShowing())) {
                promptUpdateIfParked(pendingUpdate);
            }
        }
    });

    private void showCharging(int socStart, int socNow, long startWallMs, long nowWallMs) {
        if (chargeCard == null) return;
        retainedChargeSession = null;
        boolean wasVisible = lastChargeAvailable;
        lastChargeAvailable = true;
        chargeCard.setVisibility(View.VISIBLE);

        chargeActiveLayout.setVisibility(View.VISIBLE);
        chargeCompletedLayout.setVisibility(View.GONE);
        chargeCardTitle.setText(getString(R.string.charge_card_title));

        chargeSocView.setText(getString(R.string.charge_card_soc, socNow));
        long elapsedMs = Math.max(0, nowWallMs - startWallMs);
        chargeElapsedView.setText(getString(R.string.charge_card_elapsed, elapsedMs / 60000));
        Long remainingMs = ChargeSession.estimateRemainingMs(socNow, elapsedMs);
        if (socNow >= 100) {
            chargeRemainingView.setText(getString(R.string.charge_card_ready));
        } else if (remainingMs != null) {
            chargeRemainingView.setText(getString(R.string.charge_card_remaining, remainingMs / 60000));
        } else if (elapsedMs < 35_000) {
            chargeRemainingView.setText(getString(R.string.charge_card_calculating));
        } else {
            chargeRemainingView.setText(getString(R.string.charge_card_unknown));
        }
        redrawChargeBar(socStart, socNow);
        if (!wasVisible && columns != null) repackColumns();
    }

    private void showCompletedCharging(ChargeSession.Summary s) {
        if (chargeCard == null || s == null) return;
        retainedChargeSession = s;
        boolean wasVisible = lastChargeAvailable;
        lastChargeAvailable = true;
        chargeCard.setVisibility(View.VISIBLE);

        chargeActiveLayout.setVisibility(View.GONE);
        chargeCompletedLayout.setVisibility(View.VISIBLE);
        chargeCardTitle.setText(getString(R.string.charge_completed_title));

        String socStr = (s.socStart >= 0 && s.socEnd >= 0) ? (s.socStart + "% ➔ " + s.socEnd + "%") : "";
        chargeCompletedMetrics.setText(String.format(Locale.US, "+%.1f kWh  •  %s  (%s)", s.kwh, socStr, s.durationLabel()));

        if (s.cost != null && s.cost >= 0) {
            chargeCompletedCostText.setText(getString(R.string.charge_cost_label) + ": " + s.costLabel() + " (" + s.costPerKwhLabel() + ")");
            chargeCostActionBtn.setText(getString(R.string.charge_cost_btn_edit));
        } else {
            chargeCompletedCostText.setText(getString(R.string.charge_cost_not_set));
            chargeCostActionBtn.setText(getString(R.string.charge_cost_btn_input));
        }

        if (!wasVisible && columns != null) repackColumns();
    }

    private void checkRetainedCharge() {
        if (CarState.isParked() && ChargeSession.isCharging()) {
            showCharging(ChargeSession.currentSocStart(), ChargeSession.currentSocEnd(),
                ChargeSession.currentStartWallMs(), System.currentTimeMillis());
        } else {
            ChargeSession.Summary s = ChargeSession.getLatestUndismissed(this);
            if (s != null) {
                showCompletedCharging(s);
            } else {
                hideCharging();
            }
        }
    }

    private void hideCharging() {
        if (chargeCard == null || !lastChargeAvailable) return;
        lastChargeAvailable = false;
        chargeCard.setVisibility(View.GONE);
        if (columns != null) repackColumns();
    }

    private int chargeBarW = -1, chargeBarStart = -1, chargeBarNow = -1;

    private void redrawChargeBar(int socStart, int socNow) {
        if (chargeBarView == null) return;
        int w = chargeBarView.getWidth();
        if (w <= 0) { chargeBarView.post(() -> redrawChargeBar(socStart, socNow)); return; }
        if (w == chargeBarW && socStart == chargeBarStart && socNow == chargeBarNow) return;
        chargeBarW = w; chargeBarStart = socStart; chargeBarNow = socNow;
        int color = Style.FOLLOW_AMBIENT ? lastAmbient : Style.ACCENT;
        if (color == 0) color = Style.ACCENT;
        chargeBarView.setImageBitmap(Style.chargeBar(this, w, chargeBarView.getHeight(),
            socStart / 100f, socNow / 100f, color));
    }

    // TurboMode already calls back on the UI thread — no ui.post needed here.
    // Full (1f) while idle, not hidden — see the comment on turboBarView.
    private final TurboMode.Listener turboListener = (active, fraction) ->
        redrawTurboBar(active ? fraction : 1f);

    private float turboBarFraction = -1f;
    private int turboBarW = -1;
    private int turboBarColor = 0;

    private void redrawTurboBar(float fraction) {
        if (turboBarView == null) return;
        int w = turboBarView.getWidth();
        if (w <= 0) { turboBarView.post(() -> redrawTurboBar(fraction)); return; }
        // Same ambient-or-fallback accent as recirc/purge — "full colour of the
        // car", not a fixed app colour, ready or draining alike.
        int color = Style.FOLLOW_AMBIENT ? lastAmbient : Style.ACCENT;
        if (color == 0) color = Style.ACCENT;
        if (fraction == turboBarFraction && w == turboBarW && color == turboBarColor) return;
        turboBarFraction = fraction; turboBarW = w; turboBarColor = color;
        turboBarView.setImageBitmap(Style.turboBar(this, w, turboBarView.getHeight(), fraction, color));
    }

    // HA context panel card: an ordinary card, using the same
    // Style.card(cardFillColor()) shell every other card uses — no more
    // art-drawn window/glass, see ARTE.md/historical for what this replaced.
    // A plain LinearLayout (matching `cards`' element type) wrapping a
    // FrameLayout, so the dismiss X can overlay the WebView's top-right
    // corner instead of needing its own row. Starts GONE: nothing to show
    // until the first real payload arrives (see showPanel()).
    private LinearLayout panelCard() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackground(Style.card(Style.cardFillColor(), this));
        outer.setVisibility(View.GONE);

        FrameLayout inner = new FrameLayout(this);
        card = new PanelCardView(this);
        // WRAP_CONTENT height: a WebView does not always settle on the right
        // one immediately (see PanelCardView.setOnLoaded()), so this card's
        // slot is re-measured (repackColumns()) once the content, and again
        // once a late-loading image, actually finish.
        inner.addView(card, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setOnLoaded(() -> { if (outer.getVisibility() == View.VISIBLE) repackColumns(); });

        // X to dismiss the card. Always present in the layout (unlike the
        // old window's card, this card is never visible without also having
        // real content, so there is no "card up, X not yet needed" state).
        closeBtn = new TextView(this);
        // "✕" is an icon (close), not prose: the same glyph in any language.
        closeBtn.setText("✕");
        closeBtn.setTextColor(Style.TEXT_ON); closeBtn.setTextSize(22);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setBackground(Style.card(Style.LIGHT ? 0x66FFFFFF : 0x66000000, this));
        FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(
            Style.dp(this, 50), Style.dp(this, 50), Gravity.TOP | Gravity.END);
        xlp.topMargin = Style.dp(this, 12); xlp.rightMargin = Style.dp(this, 12);
        closeBtn.setLayoutParams(xlp);
        closeBtn.setOnClickListener(v -> dismissCard());
        inner.addView(closeBtn);

        outer.addView(inner, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return outer;
    }

    // Shows the card when HA sends HTML; an empty payload ("", "{}", null)
    // hides it. An ordinary card — just setVisibility() + repackColumns() on
    // an actual change, same idiom as Gate/Music/Charge, no more art
    // crossfade/window-open choreography (there is no window to open, and
    // blanking the art no longer matters since this card never sat on top of
    // it exclusively).
    private void showPanel(String payload) {
        String s = (payload == null) ? "" : payload.trim();
        currentPayload = s;
        boolean empty = s.isEmpty() || s.equals("{}");
        // show only if there IS content AND it was NOT dismissed (a local
        // comparison, without depending on the broker to clear it). Different
        // content => it comes back.
        boolean has = !empty && s.hashCode() != dismissedHash;
        if (has) {
            // THE CARD ALREADY UP DOES NOT ARRIVE AGAIN — HA republishing an
            // unchanged payload (or this screen resuming) must not reload it.
            if (panelCard.getVisibility() == View.VISIBLE && s.equals(shownPayload)) return;
            shownPayload = s;
            card.setCardHtml(s);
        } else {
            closeCard();
            return;
        }
        if (has != lastPanelAvailable) {
            lastPanelAvailable = has;
            panelCard.setVisibility(View.VISIBLE);
            repackColumns();
        }
    }

    // X: dismisses the current card. Entirely OFF-LINE — it stores the hash
    // in SharedPreferences; it only comes back when the content changes. It
    // does not depend on the broker (neither the retained payload nor a
    // reconnect brings it back).
    private void dismissCard() {
        dismissedHash = currentPayload.hashCode();
        prefs.edit().putInt("panel_dismissed", dismissedHash).apply();
        closeCard();
    }

    private void closeCard() {
        shownPayload = null;   // nothing on screen: the next card enters for real
        if (!lastPanelAvailable) return;
        lastPanelAvailable = false;
        panelCard.setVisibility(View.GONE);
        repackColumns();
    }

    // Fed by CarActor's registerPoll (started in onResume, stopped in
    // onPause below) instead of this screen owning its own CarAccess +
    // Thread + postDelayed loop. Listener callbacks arrive on CarActor's
    // own thread — bounced to `ui` here, same as every other subscriber.
    private final EntityBus.Listener ambientColorListener = (key, reading) -> {
        if (reading.status == CarActor.Reading.Status.OK)
            ui.post(() -> applyAmbient((Integer) reading.value));
    };
    // Brightness rides along as its own poll now rather than the same
    // binder round trip as colour — CarActor doesn't batch arbitrary raw
    // reads the way Telemetry.read() batches its own field list, and two
    // 4s polls cost nothing extra weighed against the code this replaces.
    private final EntityBus.Listener ambientBrightnessListener = (key, reading) -> {
        if (reading.status == CarActor.Reading.Status.OK)
            ui.post(() -> { if (art != null) art.setAmbientBrightness((Integer) reading.value); });
    };
    // Pushed the instant recirculation is toggled from the CAR'S OWN AC
    // screen (car.hvac_recirc, armed in CarActor.armDiscreteWatches) — a
    // press made from Drive Assist itself already updates the icon synchronously
    // in the click listener; this is for catching up with the OTHER side.
    private final EntityBus.Listener hvacRecircListener = (key, reading) -> {
        if (reading.status == CarActor.Reading.Status.OK)
            ui.post(() -> setRecirc((Boolean) reading.value));
    };
    private final EntityBus.Listener hvacDirectionListener = (key, reading) -> {
        if (reading.status == CarActor.Reading.Status.OK && reading.value instanceof Integer)
            ui.post(() -> setWindDirection((Integer) reading.value));
    };
    private final EntityBus.Listener hvacRearDefrostListener = (key, reading) -> {
        if (reading.status == CarActor.Reading.Status.OK) {
            final boolean on = (reading.value instanceof Boolean) ? (Boolean) reading.value
                : (reading.value instanceof Number) && ((Number) reading.value).intValue() != 0;
            ui.post(() -> setRearDefrost(on));
        }
    };
    // Pushed the instant a pane's own position changes (car.window_pos,
    // armed in CarActor.armDiscreteWatches) — value is {area, position},
    // same shape as car.door_pos. Recomputes "any window still open" from
    // real per-area readings, same threshold Purge itself uses.
    private final EntityBus.Listener windowPosListener = (key, reading) -> {
        if (reading.status != CarActor.Reading.Status.OK) return;
        int[] av = (int[]) reading.value;
        ui.post(() -> {
            windowPos.put(av[0], av[1]);
            boolean open = false;
            for (int v : windowPos.values()) if (v >= Purge.OPEN_AT_LEAST) { open = true; break; }
            setPurge(open);
        });
    };
    // speed -> art (faster = faster waves). 1 s is smooth enough.
    private final EntityBus.Listener speedListener = (key, reading) -> {
        if (reading.status == CarActor.Reading.Status.OK) {
            float s = ((Number) reading.value).floatValue();
            ui.post(() -> { if (art != null) art.setSpeed(s); });
        }
    };

    private void startCarActorPolls() {
        CarActor a = CarActor.get(this);
        // ambient_color/ambient_brightness are CarActor's own always-on polls
        // now (see its constructor) — only subscribe here, nothing to
        // register/unregister for them.
        a.registerPoll("telemetry.speed", 1000, c -> {
            Float v = c.readSpeed();
            return (v != null) ? CarActor.Reading.ok(v) : CarActor.Reading.error("read failed");
        });
        EntityBus.subscribe("telemetry.ambient_color", ambientColorListener);
        EntityBus.subscribe("telemetry.ambient_brightness", ambientBrightnessListener);
        EntityBus.subscribe("telemetry.speed", speedListener);
        EntityBus.subscribe("car.window_pos", windowPosListener);
        EntityBus.subscribe("car.hvac_recirc", hvacRecircListener);
        EntityBus.subscribe("car.hvac_direction", hvacDirectionListener);
        EntityBus.subscribe("car.hvac_rear_defrost", hvacRearDefrostListener);

        // Subscriptions above cover future changes only. EntityBus does not
        // replay past values, so ask the car directly for current state to
        // avoid race conditions where the screen comes up without the current
        // ambient color/brightness already applied.
        a.runOnCarThread(() -> {
            CarAccess c = a.rawAccess();
            Integer col = c.readAmbientColor();
            Integer bri = c.readAmbientBrightness();
            ui.post(() -> {
                if (col != null) applyAmbient(col);
                if (bri != null && art != null) art.setAmbientBrightness(bri);
            });
        });
    }

    private void stopCarActorPolls() {
        CarActor a = CarActor.get(this);
        a.unregisterPoll("telemetry.speed");
        EntityBus.unsubscribe("telemetry.ambient_color", ambientColorListener);
        EntityBus.unsubscribe("telemetry.ambient_brightness", ambientBrightnessListener);
        EntityBus.unsubscribe("telemetry.speed", speedListener);
        EntityBus.unsubscribe("car.window_pos", windowPosListener);
        EntityBus.unsubscribe("car.hvac_recirc", hvacRecircListener);
        EntityBus.unsubscribe("car.hvac_direction", hvacDirectionListener);
        EntityBus.unsubscribe("car.hvac_rear_defrost", hvacRearDefrostListener);
    }

    @Override protected void onResume() {
        super.onResume();
        // theme OR appearance changed on the Config screen -> redraw this
        // whole screen (see the themeId/themeLight comment above)
        Style.load(this);
        if (!Style.current().id.equals(themeId) || Style.LIGHT != themeLight) { recreate(); return; }
        // same idea: the Turbo toggle in Config only takes effect on the next
        // build of this screen, same as a theme change
        if (prefs.getBoolean("turbo_enabled", true) != turboEnabledAtBuild) { recreate(); return; }
        // Opening the screen re-arms the watchdog. Alarms are canceled on
        // install and force-stop; only opening the screen always arrives,
        // making it the fallback to restore the monitoring chain. ensureAll
        // is idempotent: it's a no-op if already running.
        try {
            BootReceiver.scheduleWatchdog(this);
            BootReceiver.ensureAll(this);
        } catch (Throwable t) { android.util.Log.w(CarAccess.TAG, "resume ensure: " + t); }
        startCarActorPolls();
        ui.removeCallbacks(rulerPoll);
        ui.post(rulerPoll);
        PanelState.setListener(panelListener);
        showPanel(PanelState.get());
        GateState.setListener(gateListener);
        // Availability can have changed while this screen was paused (car
        // drove home, or away, with the screen off) — same "did it actually
        // change" re-pack the listener does, not just a visibility flip.
        boolean nowVisible = gateVisible();
        if (gateCard != null) {
            gateCard.setVisibility(nowVisible ? View.VISIBLE : View.GONE);
            gateCard.refreshStatus();
        }
        if (nowVisible != lastGateAvailable) {
            lastGateAvailable = nowVisible;
            if (columns != null) repackColumns();
        }
        TurboMode turbo = TurboMode.get(this);
        turbo.setListener(turboListener);
        turboBarFraction = -1f; turboBarW = -1;   // force one redraw even if the fraction matches
        turboListener.onTurbo(turbo.active(), turbo.fraction());
        // Strong-Regen toggle has no live listener like Turbo's countdown --
        // just re-read on resume, same "screen was off, world may have
        // moved on" reasoning as the gate/music checks around this.
        refreshRegenGlyph();
        MusicState.setListener(musicListener);
        // Same "changed while paused" re-check as the gate, and the same
        // reason: a track can start or end while the screen is off.
        boolean nowMusicAvailable = MusicState.title() != null && !MusicState.title().isEmpty();
        if (musicCard != null) musicCard.setVisibility(nowMusicAvailable ? View.VISIBLE : View.GONE);
        if (nowMusicAvailable) {
            musicTitleView.setText(MusicState.title());
            musicArtistView.setText(MusicState.artist() == null ? "" : MusicState.artist());
            loadMusicArt(MusicState.artUrl());
        }
        if (nowMusicAvailable != lastMusicAvailable) {
            lastMusicAvailable = nowMusicAvailable;
            if (columns != null) repackColumns();
        }
        ChargeSession.setProgressListener(chargeListener);
        CarState.setListener(carStateListener);
        chargeBarW = -1;   // force one redraw even if the numbers match
        checkRetainedCharge();
        EntityBus.subscribe("charge.cost_updated", chargeBusListener);
        EntityBus.subscribe("charge.dismissed", chargeBusListener);
        EntityBus.subscribe("charge.completed", chargeBusListener);
        if (turboCardView != null) {
            boolean turboVisible = turboEnabledAtBuild && !CarState.isParked();
            turboCardView.setVisibility(turboVisible ? View.VISIBLE : View.GONE);
        }
        // Screen-scoped, not process-wide like Turbo/the gate: nothing bad
        // happens if this stops polling while the screen is off, unlike
        // abandoning a countdown mid-boost.
        SpotifyClient.startPolling(this);
        try {
            registerReceiver(updateReceiver, new IntentFilter(Updater.ACTION_UPDATE_AVAILABLE));
        } catch (Throwable ignored) {}
        if (CarState.isParked() && pendingUpdate != null && (activeUpdateDialog == null || !activeUpdateDialog.isShowing())) {
            promptUpdateIfParked(pendingUpdate);
        }
    }

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Updater.ACTION_UPDATE_AVAILABLE.equals(intent.getAction())) return;
            String vn = intent.getStringExtra("versionName");
            int vc = intent.getIntExtra("versionCode", 0);
            String cl = intent.getStringExtra("changelog");
            String url = intent.getStringExtra("url");
            promptUpdateIfParked(new Updater.UpdateInfo(vn, vc, cl, url));
        }
    };

    @Override protected void onPause() {
        super.onPause();
        EntityBus.unsubscribe("charge.cost_updated", chargeBusListener);
        EntityBus.unsubscribe("charge.dismissed", chargeBusListener);
        EntityBus.unsubscribe("charge.completed", chargeBusListener);
        try { unregisterReceiver(updateReceiver); } catch (Throwable ignored) {}
        if (activeUpdateDialog != null && activeUpdateDialog.isShowing()) {
            try { activeUpdateDialog.dismiss(); } catch (Throwable ignored) {}
            activeUpdateDialog = null;
        }
        if (activeCostDialog != null && activeCostDialog.isShowing()) {
            try { activeCostDialog.dismiss(); } catch (Throwable ignored) {}
            activeCostDialog = null;
        }
        if (gateCard != null) gateCard.onPause();
        SpotifyClient.stopPolling();
        TurboMode.get(this).setListener(null);
        stopCarActorPolls();
        ui.removeCallbacks(rulerPoll);
        PanelState.setListener(null);
        GateState.setListener(null);
        MusicState.setListener(null);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopCarActorPolls();
        ui.removeCallbacks(rulerPoll);
        // NOT shutdown(): the ruler outlives this screen and shares
        // CarActor's one connection. Only the subscription is ours to drop.
        ComfortHub.removeListener(rulerListener);
    }

    // Same lit/unlit language as recirculation, and the same reason: the button
    // says what it will DO next, so it has to know what the glass is doing now.
    // Reads on a worker thread — WINDOW_POS is a car property, not a local flag,
    // and it answers unavailable with the car asleep.
    private void refreshPurge() {
        if (purgeBtn == null) return;
        CarActor.get(this).runOnCarThread(() -> {
            Boolean open = purge.anyOpen(CarActor.get(this).rawAccess());
            if (open == null) return;        // asleep or unreadable: leave the label alone
            ui.post(() -> setPurge(open));
        });
    }

    // Icon showing intended action: open arrow when closed, close arrow
    // when open. Window position has no natural state drawing, so the
    // icon indicates the next action.
    private void setPurge(boolean open) {
        purgeOpen = open;
        if (purgeBtn == null) return;
        int accent = Style.FOLLOW_AMBIENT ? lastAmbient : Style.ACCENT;
        if (accent == 0) accent = Style.ACCENT;
        purgeBtn.setImageResource(open ? R.drawable.ic_window_raise : R.drawable.ic_window_lower);
        purgeBtn.setColorFilter(open ? Style.onFill(accent) : Style.TEXT);
        purgeBtn.setBackground(open ? Style.card(accent, this, Style.RADIUS_DP - 8) : Style.tile(this));
    }

    // Icon showing current state: car+loop icon lit while recirculating,
    // car outline dim while on outside air. Plays a one-shot animation on
    // state change; subsequent ambient polls re-tint only.
    private void setRecirc(boolean on) {
        if (recircBtn == null) return;
        boolean stateChanged = (on != recircOn);
        recircOn = on;

        int accent = Style.FOLLOW_AMBIENT ? lastAmbient : Style.ACCENT;
        if (accent == 0) accent = Style.ACCENT;

        // The static icon at rest: ic_hvac_cycle_off while on, ic_hvac_cycle_on while off.
        int staticIcon = on ? R.drawable.ic_hvac_cycle_off : R.drawable.ic_hvac_cycle_on;
        int colorFilter = on ? Style.onFill(accent) : Style.TEXT;
        android.graphics.drawable.Drawable bgDrawable = on ? Style.card(accent, this, Style.RADIUS_DP - 8) : Style.tile(this);

        if (stateChanged && recircBtn.getDrawable() != null) {
            // State changed: play the opposite state's animation, then settle
            // on the static icon for the new state. The opposite resource plays
            // during transition to the target state.
            int animRes = on ? R.drawable.recirc_anim_off : R.drawable.recirc_anim_on;
            recircBtn.setImageResource(animRes);
            recircBtn.setColorFilter(colorFilter);
            recircBtn.setBackground(bgDrawable);

            final android.graphics.drawable.Drawable animDrawable = recircBtn.getDrawable();
            if (animDrawable instanceof android.graphics.drawable.AnimatedImageDrawable) {
                android.graphics.drawable.AnimatedImageDrawable anim = (android.graphics.drawable.AnimatedImageDrawable) animDrawable;
                anim.setRepeatCount(0);
                anim.start();
                anim.registerAnimationCallback(new android.graphics.drawable.Animatable2.AnimationCallback() {
                    @Override public void onAnimationEnd(android.graphics.drawable.Drawable d) {
                        // Animation finished: show the static icon for the final state.
                        recircBtn.setImageResource(staticIcon);
                        ((android.graphics.drawable.AnimatedImageDrawable) animDrawable).clearAnimationCallbacks();
                    }
                });
            } else {
                // Animation resource failed to load or is not animated: fall back to static icon.
                recircBtn.setImageResource(staticIcon);
            }
        } else {
            // No state change or first call: set the static icon and re-tint.
            recircBtn.setImageResource(staticIcon);
            recircBtn.setColorFilter(colorFilter);
            recircBtn.setBackground(bgDrawable);
        }
    }

    private void setWindDirection(int dir) {
        currentWindDir = dir;
        if (windDirIcon == null) return;
        int resId = 0;
        switch (dir) {
            case EffortTable.DIR_FACE: // 1
                resId = R.drawable.ic_hvac_dir_face;
                break;
            case EffortTable.DIR_FEET: // 2
                resId = R.drawable.ic_hvac_dir_feet;
                break;
            case EffortTable.DIR_FACE_FEET: // 3
                resId = R.drawable.ic_hvac_dir_face_feet;
                break;
            case EffortTable.DIR_GLASS: // 4
                resId = R.drawable.ic_hvac_dir_defrost;
                break;
            case EffortTable.DIR_GLASS_FEET: // 6
                resId = R.drawable.ic_hvac_dir_defrost_feet;
                break;
            case 7:
                resId = R.drawable.ic_hvac_dir_all;
                break;
            default:
                resId = 0;
                break;
        }
        if (resId != 0) {
            windDirIcon.setImageResource(resId);
            windDirIcon.setColorFilter(Style.TEXT, android.graphics.PorterDuff.Mode.SRC_IN);
            windDirIcon.setVisibility(View.VISIBLE);
        } else {
            windDirIcon.setVisibility(View.GONE);
        }
    }

    private void setRearDefrost(boolean on) {
        rearDefrostOn = on;
        if (rearDefrostIcon == null) return;
        rearDefrostIcon.setVisibility(on ? View.VISIBLE : View.GONE);
    }
}
