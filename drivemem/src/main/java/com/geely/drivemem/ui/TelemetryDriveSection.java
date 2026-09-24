package com.geely.drivemem.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.controls.GeelySwitch;
import com.geely.drivemem.controls.TurboMode;
import com.geely.drivemem.util.Modes;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

import java.util.HashMap;
import java.util.Map;

/** Drive Mode panel of the Config screen — drive mode (Eco/Comfort/Sport),
 * regen mode (Low/Mid/High), turbo duration, ADAS controls (AEB/AVAS), and
 * the restore defaults button. Maintains live-updating mode card UI that
 * responds to vehicle state changes (current drive mode reported by the car),
 * with separate tracking of the user's saved standard and the car's actual
 * current mode.
 *
 * Extracted from TelemetryActivity.buildDrive() so that 2800+ line file
 * stops growing with every new Config section -- one View subclass per
 * section, same pattern ChargeStatsView/DailyStatsView already use.
 *
 * The live update mechanism: EntityBus listeners on TelemetryActivity
 * (driveListener, regenListener) subscribe to "car.drive_mode" and
 * "car.regen_mode" and call updateModeFromCar() and refreshAvasFromCar()
 * as the car's actual mode changes, keeping the card highlighting honest.
 * The user's saved standard (selDrive/selRegen) is separate and only changes
 * when they tap a card.
 */
public final class TelemetryDriveSection extends LinearLayout {
    private final Activity activity;
    private final android.os.Handler ui;
    private final TextView status;
    private final EditText fTurbo;
    private final GeelySwitch avasSwitch;

    // Drive/Regen mode cards, indexed by mode value (e.g., Modes.DRIVE_ECO).
    private final Map<Integer, LinearLayout> driveCards = new HashMap<>();
    private final Map<Integer, LinearLayout> regenCards = new HashMap<>();

    // User's saved standard: starts from prefs, changes only when they tap a
    // card, and is applied AND saved in that same tap (pickDrive/pickRegen).
    // Always known (falls back to Modes.DRIVE_ECO/REGEN_MID).
    private int selDrive, selRegen;

    // What the car reports right now. Only meaningful once driveKnown/
    // regenKnown is true; set optimistically by a tap, then confirmed or
    // corrected by the car's own watch.
    private int liveDrive, liveRegen;
    private boolean driveKnown, regenKnown;

    public TelemetryDriveSection(Activity activity) {
        super(activity);
        this.activity = activity;
        this.ui = new android.os.Handler(android.os.Looper.getMainLooper());
        setOrientation(VERTICAL);

        // Load saved standards from prefs.
        selDrive = Prefs.getDriveMode(activity, Modes.DRIVE_ECO);
        selRegen = Prefs.getRegen(activity, Modes.REGEN_MID);

        // Everything for this page builds into `left` (still capped at
        // pageWidth, same as before) instead of straight into this view.
        // The right side of the Config panel has space for a car render,
        // which is filled by the background image now.
        LinearLayout left = new LinearLayout(activity);
        left.setOrientation(LinearLayout.VERTICAL);

        left.addView(Style.header(activity, activity.getString(R.string.cfg_drive_header)));
        status = new TextView(activity);
        status.setTextColor(Style.TEXT); status.setTextSize(16);
        status.setTypeface(null, Typeface.BOLD);
        status.setText(activity.getString(R.string.cfg_connecting));
        left.addView(status);

        // the glyphs "🍃 ☁ ⚡ ◦ ◉ ●" are icons, not text: they do not get translated
        //
        // Capped width, not MATCH_PARENT: small tiles/fields/rows stretched
        // across the whole panel turn into oversized slabs. pageWidth keeps
        // everything on this page a sane, consistent size -- applied to
        // every row below (drive, regen, turbo, actions), not just the mode
        // cards.
        int pageWidth = Style.dp(activity, 960);
        LinearLayout driveRow = new LinearLayout(activity);
        driveRow.setOrientation(LinearLayout.HORIZONTAL);
        driveRow.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        driveRow.addView(modeCard(R.drawable.ic_mode_eco, activity.getString(R.string.cfg_mode_eco), Modes.DRIVE_ECO, Style.GOOD, driveCards, () -> pickDrive(Modes.DRIVE_ECO)));
        driveRow.addView(modeCard(R.drawable.ic_mode_comfort, activity.getString(R.string.cfg_mode_comfort), Modes.DRIVE_COMFORT, Style.WARN, driveCards, () -> pickDrive(Modes.DRIVE_COMFORT)));
        driveRow.addView(modeCard(R.drawable.ic_mode_sport, activity.getString(R.string.cfg_mode_sport), Modes.DRIVE_SPORT, Style.DANGER, driveCards, () -> pickDrive(Modes.DRIVE_SPORT)));
        left.addView(driveRow);

        left.addView(Style.header(activity, activity.getString(R.string.cfg_regen_header)));
        LinearLayout regenRow = new LinearLayout(activity);
        regenRow.setOrientation(LinearLayout.HORIZONTAL);
        regenRow.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        regenRow.addView(modeCard("◦", activity.getString(R.string.cfg_regen_low), Modes.REGEN_LOW, Style.CARD_ON, regenCards, () -> pickRegen(Modes.REGEN_LOW)));
        regenRow.addView(modeCard("◉", activity.getString(R.string.cfg_regen_mid), Modes.REGEN_MID, Style.CARD_ON, regenCards, () -> pickRegen(Modes.REGEN_MID)));
        regenRow.addView(modeCard("●", activity.getString(R.string.cfg_regen_high), Modes.REGEN_HIGH, Style.CARD_ON, regenCards, () -> pickRegen(Modes.REGEN_HIGH)));
        left.addView(regenRow);

        left.addView(Style.header(activity, activity.getString(R.string.turbo_header)));
        // Toggle and duration side by side, not the toggle then a
        // full-pageWidth field below it -- a 3-digit seconds value never
        // needed the same width as the mode-card rows above it.
        LinearLayout turboRow = new LinearLayout(activity);
        turboRow.setOrientation(LinearLayout.HORIZONTAL);
        turboRow.setGravity(Gravity.CENTER_VERTICAL);
        turboRow.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout turboToggle = Style.toggleRow(activity, activity.getString(R.string.turbo_enable_label),
            Prefs.getTurboEnabled(activity),
            on -> Prefs.setTurboEnabled(activity, on));
        turboRow.addView(turboToggle);

        TextView durLbl = new TextView(activity);
        durLbl.setText(activity.getString(R.string.turbo_duration_label));
        durLbl.setTextColor(Style.TEXT_DIM); durLbl.setTextSize(14);
        LinearLayout.LayoutParams durLblLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        durLblLp.leftMargin = Style.dp(activity, 28);
        durLbl.setLayoutParams(durLblLp);
        turboRow.addView(durLbl);

        fTurbo = new EditText(activity);
        fTurbo.setText(String.valueOf(Prefs.getTurboDurationS(activity, TurboMode.DEFAULT_DURATION_S)));
        fTurbo.setInputType(InputType.TYPE_CLASS_NUMBER);
        fTurbo.setTextColor(Style.TEXT); fTurbo.setTextSize(17);
        fTurbo.setBackground(Style.card(Style.CARD, activity));
        int fPad = Style.dp(activity, 12);
        fTurbo.setPadding(fPad, fPad, fPad, fPad);
        LinearLayout.LayoutParams fTurboLp = new LinearLayout.LayoutParams(
            Style.dp(activity, 90), ViewGroup.LayoutParams.WRAP_CONTENT);
        fTurboLp.leftMargin = Style.dp(activity, 10);
        fTurbo.setLayoutParams(fTurboLp);
        turboRow.addView(fTurbo);
        left.addView(turboRow);
        // Saves on every keystroke that parses, not on blur — blur never
        // reliably fired here (dismissing the on-screen number pad hides the
        // IME but does not necessarily move focus off the EditText, so a
        // save-on-blur listener could go the whole session without firing
        // once — reported as "edited it, it reverted to 30", which was
        // really "it never saved at all"). Raw value is saved as typed,
        // never rewritten under the cursor; TurboMode.start() clamps
        // 5..120s at the moment it's actually read.
        fTurbo.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                try { Prefs.setTurboDurationS(activity, Integer.parseInt(s.toString().trim())); }
                catch (NumberFormatException ignored) {}
            }
        });

        left.addView(Style.header(activity, activity.getString(R.string.cfg_adas_header)));

        // AEB: needs Park + its own confirmation, since writing this property
        // directly (via modehelper) skips the OEM's own warning dialog entirely
        // — see plan/ADAS-CONTROLS-ROADMAP.md. Built without the toggleRow()
        // helper's callback param (passed null, overridden below) so the
        // listener can hold a reference to its own switch, to revert it
        // silently on cancel/not-parked without rebuilding the whole screen.
        LinearLayout aebRow = Style.toggleRow(activity, activity.getString(R.string.cfg_aeb_label), Prefs.getAebOn(activity), null);
        GeelySwitch aebSwitch = (GeelySwitch) aebRow.getChildAt(0);
        aebSwitch.setOnToggle(on -> {
            if (!on) {
                // No Park requirement, by design: applies in any condition,
                // driving or parked — see plan/ADAS-CONTROLS-ROADMAP.md.
                new android.app.AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.cfg_aeb_confirm_title))
                    .setMessage(activity.getString(R.string.cfg_aeb_confirm_body))
                    .setPositiveButton(activity.getString(R.string.cfg_aeb_confirm_turn_off), (d, w) -> {
                        Prefs.setAebOn(activity, false);
                        sendAdasPreference("aeb", false);
                    })
                    .setNegativeButton(android.R.string.cancel, (d, w) -> aebSwitch.setCheckedSilently(true))
                    .setOnCancelListener(d -> aebSwitch.setCheckedSilently(true))
                    .show();
            } else {
                Prefs.setAebOn(activity, true);
                sendAdasPreference("aeb", true);
            }
        });
        aebRow.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        left.addView(aebRow);

        // AVAS mute: no Park-gate, no confirmation — much lower stakes (a
        // pedestrian-warning chime, not braking), matching a "quick per-drive
        // toggle" per plan/AVAS-MUTE-ROADMAP.md's still-open question.
        //
        // Built without toggleRow()'s callback param, same as the AEB row
        // above, so refreshAvasFromCar() can move the switch on its own once
        // the live read comes back — modehelper now mirrors an OEM-side
        // change into its own saved preference (see ModeHelperService), so
        // Drive Assist's own saved "avas_on" can go stale the moment the
        // owner picks a different sound in OEM Settings. Reading the car
        // directly on every screen entry is what keeps this switch honest.
        LinearLayout avasRow = Style.toggleRow(activity, activity.getString(R.string.cfg_avas_label), Prefs.getAvasOn(activity), null);
        avasSwitch = (GeelySwitch) avasRow.getChildAt(0);
        avasSwitch.setOnToggle(on -> {
            Prefs.setAvasOn(activity, on);
            sendAdasPreference("avas", on);
        });
        avasRow.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        left.addView(avasRow);
        refreshAvasFromCar();

        left.addView(Style.header(activity, activity.getString(R.string.cfg_actions_header)));
        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        actionRow.addView(Style.action(activity, activity.getString(R.string.cfg_btn_restore_defaults), Style.ACCENT, this::restoreDriveDefaults));
        left.addView(actionRow);

        // No separate car cutout here any more -- the whole Config screen's
        // background is now the OEM day/night render (see Style.configScreenBg,
        // wired in onCreate), which already puts a (larger) car in this same
        // empty region. A second, smaller one floating on top of it would
        // have doubled up instead of filling anything.
        addView(left);

        highlight();
        refresh();
    }

    // Regen (Fraca/Média/Forte) has no real icon set -- these three dots are
    // a plain geometric affordance, not a brand/style pastiche in need of a
    // license, so they stay a plain glyph.
    private LinearLayout modeCard(String symbol, String label, int key, int onColor,
                                  Map<Integer, LinearLayout> reg, Runnable onClick) {
        LinearLayout card = modeCardShell(label, key, onColor, reg, onClick);
        TextView ic = new TextView(activity);
        ic.setText(symbol); ic.setTextColor(Style.TEXT); ic.setTextSize(34);
        ic.setGravity(Gravity.CENTER);
        card.addView(ic, 0);
        return card;
    }

    // Eco/Comfort/Sport get a real vector icon (Google Material Symbols,
    // Apache-2.0 -- see the drawable files' own header comments) instead of
    // an emoji glyph, tinted the same way the label already is so selection
    // recolors both together (see paintCard()).
    private LinearLayout modeCard(int iconRes, String label, int key, int onColor,
                                  Map<Integer, LinearLayout> reg, Runnable onClick) {
        LinearLayout card = modeCardShell(label, key, onColor, reg, onClick);
        ImageView ic = new ImageView(activity);
        ic.setImageResource(iconRes);
        ic.setColorFilter(Style.TEXT, android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(Style.dp(activity, 34), Style.dp(activity, 34));
        ic.setLayoutParams(icLp);
        card.addView(ic, 0);
        return card;
    }

    // Shared shell: padding, label, click, sizing/tag/registration -- the
    // two overloads above differ only in how the icon view itself is built,
    // so that's the only thing each adds, at index 0 (before the label).
    private LinearLayout modeCardShell(String label, int key, int onColor,
                                       Map<Integer, LinearLayout> reg, Runnable onClick) {
        LinearLayout card = new LinearLayout(activity);
        // Icon inline with its label, not stacked -- a horizontal card has
        // room to let both read as a single, decently large lockup instead
        // of two smaller lines competing for the same narrow column.
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER);
        int pad = Style.dp(activity, 8);
        card.setPadding(pad, pad, pad, pad);
        TextView lb = new TextView(activity);
        lb.setText(label); lb.setTextColor(Style.TEXT); lb.setTextSize(21);
        lb.setTypeface(null, Typeface.BOLD);
        lb.setGravity(Gravity.CENTER);
        lb.setPadding(Style.dp(activity, 10), 0, 0, 0);
        card.addView(lb);
        card.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Style.dp(activity, 120), 1f);
        int m = Style.dp(activity, 4);
        lp.setMargins(m, Style.dp(activity, 8), m, 0);
        card.setLayoutParams(lp);
        card.setTag(onColor);
        reg.put(key, card);
        return card;
    }

    // Fill = your standard (selDrive/selRegen, always known). Border = what
    // the car reports right now (liveDrive/liveRegen, only once driveKnown/
    // regenKnown). A card can carry both, one, or neither — that overlap (or
    // lack of it) is the whole point: it is what makes "car booted into
    // something other than your standard" visible instead of silent.
    private void highlight() {
        for (Map.Entry<Integer, LinearLayout> e : driveCards.entrySet())
            paintCard(e.getValue(), e.getKey() == selDrive, driveKnown && e.getKey() == liveDrive);
        for (Map.Entry<Integer, LinearLayout> e : regenCards.entrySet())
            paintCard(e.getValue(), e.getKey() == selRegen, regenKnown && e.getKey() == liveRegen);
    }

    // card background + text: on the light theme the selected fill is dark blue,
    // so the text has to turn light (otherwise it disappears). "isLive" adds a
    // thicker accent-coloured stroke on top, independent of the fill. Each
    // card's own onColor (set at build time, in modeCard's tag) replaces the
    // one shared CARD_ON fill so Eco/Comfort/Sport read as green/amber/red at
    // a glance instead of only by their label.
    private void paintCard(LinearLayout card, boolean selected, boolean isLive) {
        int onColor = (card.getTag() instanceof Integer) ? (Integer) card.getTag() : Style.CARD_ON;
        int fill = selected ? onColor : Style.CARD;
        android.graphics.drawable.GradientDrawable g = Style.card(fill, activity);
        if (isLive) g.setStroke(Style.dp(activity, Style.STROKE_DP + 2), Style.ACCENT);
        card.setBackground(g);
        int fg = Style.onFill(fill);
        for (int i = 0; i < card.getChildCount(); i++) {
            View ch = card.getChildAt(i);
            if (ch instanceof TextView) ((TextView) ch).setTextColor(fg);
            else if (ch instanceof ImageView) ((ImageView) ch).setColorFilter(fg, android.graphics.PorterDuff.Mode.SRC_IN);
        }
    }

    // Applies to the car AND saves as the startup standard in the same tap —
    // no separate Apply / Salvar padrão step to remember. A boost in
    // progress no longer owns drive_mode past a manual pick: it ends right
    // here (TurboMode.selectDriveMode), so the tap always reflects on the
    // car immediately.
    private void pickDrive(int v) {
        selDrive = v;
        Prefs.setDriveMode(activity, v);
        notifyHelperDefault();
        if (TurboMode.get(activity).active()) TurboMode.get(activity).selectDriveMode(v);
        else CarActor.get(activity).cast("drive_mode", v);
        // Trusted as sent, not waited on — see CarActor's own comment for
        // why a cast doesn't block here.
        liveDrive = v; driveKnown = true;
        highlight();
        status.setText(activity.getString(R.string.cfg_applied, Modes.driveName(selDrive), Modes.regenName(selRegen)));
    }

    private void pickRegen(int v) {
        selRegen = v;
        Prefs.setRegen(activity, v);
        notifyHelperDefault();
        CarActor.get(activity).cast("regen_mode", v);
        liveRegen = v; regenKnown = true;
        highlight();
        status.setText(activity.getString(R.string.cfg_applied, Modes.driveName(selDrive), Modes.regenName(selRegen)));
    }

    // Tells the system helper (com.geely.modehelper) about the new startup
    // default — it is the one that, running as uid system, re-applies it
    // when the car wakes (here, as a normal app, we do not survive suspend).
    private void notifyHelperDefault() {
        try {
            android.content.Intent i = new android.content.Intent("com.geely.modehelper.SET_MODE");
            i.setPackage("com.geely.modehelper");
            i.putExtra("drive", selDrive).putExtra("regen", selRegen);
            activity.sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    // Single explicit reset for both pickers, to Eco + Mid regen — the same
    // pair this screen starts from on a fresh install and modehelper falls
    // back to on its own (ModeHelperService, CarMode.DRIVE_ECO/REGEN_MID).
    private void restoreDriveDefaults() {
        pickDrive(Modes.DRIVE_ECO);
        pickRegen(Modes.REGEN_MID);
        status.setText(activity.getString(R.string.cfg_restored_defaults));
    }

    // Same SET_MODE broadcast notifyHelperDefault() sends for drive/regen,
    // but for one AEB/AVAS preference at a time — modehelper's receiver treats each
    // extra as independent and optional, so this never touches the other
    // three. Drive Assist cannot write either property itself (confirmed for
    // AVAS, expected for AEB — see plan/ADAS-CONTROLS-ROADMAP.md); this only
    // updates the preference modehelper's own poll loop enforces while
    // parked, so the actual car write lands within a few seconds, not
    // instantly.
    private void sendAdasPreference(String key, boolean on) {
        try {
            android.content.Intent i = new android.content.Intent("com.geely.modehelper.SET_MODE");
            i.setPackage("com.geely.modehelper");
            if ("avas".equals(key)) i.putExtra("avas", on ? 1 : 0);
            else i.putExtra(key, on);
            activity.sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    // A fresh read (CarActor.read -> CarDataHub), not a cache lookup — this
    // is the one seed value the screen needs the instant it opens, and the
    // discrete watch above may not have delivered its first event yet.
    // NEVER touches selDrive/selRegen — that field is the user's standard,
    // and a stale/failed/booting-into-factory-default read here must not be
    // able to overwrite it. See the field comment.
    private void refresh() {
        CarActor a = CarActor.get(activity);
        a.read("drive_mode", d -> {
            if (d instanceof Integer) { liveDrive = (Integer) d; driveKnown = true; }
            ui.post(this::afterRefresh);
        });
        a.read("regen_mode", r -> {
            if (r instanceof Integer) { liveRegen = (Integer) r; regenKnown = true; }
            ui.post(this::afterRefresh);
        });
    }

    private void afterRefresh() {
        highlight();
        if (!driveKnown || !regenKnown) status.setText(activity.getString(R.string.cfg_mode_unknown));
        else updateCurrentStatus();
    }

    // Shared by refresh() and the live watch callbacks — one place that
    // knows how to render "what the car reports now" as text.
    private void updateCurrentStatus() {
        if (status == null || !driveKnown || !regenKnown) return;
        status.setText(activity.getString(R.string.cfg_current, Modes.driveName(liveDrive), Modes.regenName(liveRegen)));
    }

    // Public method called by TelemetryActivity's EntityBus listeners when
    // the car reports a new drive or regen mode. Updates the live-mode
    // border and status text.
    public void updateModeFromCar(int newDrive, int newRegen, boolean driveOk, boolean regenOk) {
        if (driveOk) { liveDrive = newDrive; driveKnown = true; }
        if (regenOk) { liveRegen = newRegen; regenKnown = true; }
        ui.post(() -> {
            highlight();
            updateCurrentStatus();
        });
    }

    // A fresh read (CarActor.read -> CarDataHub) of the AVAS mode from the car,
    // not a cache lookup — this keeps the AVAS switch honest when the setting
    // can change outside of this app.
    public void refreshAvasFromCar() {
        if (avasSwitch == null) return;
        CarActor.get(activity).runOnCarThread(() -> {
            CarAccess c = CarActor.get(activity).rawAccess();
            if (!c.isReady() && !c.connect(activity.getApplicationContext())) return;
            Object mode = c.audioCall("getAVASMode");
            if (!(mode instanceof Integer)) return;
            boolean on = ((Integer) mode) != 0;
            ui.post(() -> {
                Prefs.setAvasOn(activity, on);
                avasSwitch.setCheckedSilently(on);
            });
        });
    }
}
