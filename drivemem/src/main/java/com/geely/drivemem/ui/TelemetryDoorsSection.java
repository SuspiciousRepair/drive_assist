package com.geely.drivemem.ui;

import android.app.Activity;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.controls.DoorWindow;
import com.geely.drivemem.controls.LockSound;
import com.geely.drivemem.controls.Purge;
import com.geely.drivemem.hvac.ComfortHub;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

/** Doors and glass panel of the Config screen -- window-on-door-open and
 * per-pane crack-open targets. Its own section because everything in it
 * moves part of the car, sometimes while nobody is looking at the screen --
 * a different promise from anything else in Config, and it deserves a page
 * rather than a heading somebody scrolls past on the way to the MQTT host.
 *
 * Everything here stays OFF by default. See DoorWindow and Purge: the
 * window lock cannot be read on this car and is not enforced against our
 * writes, so nothing on this page is protected by it.
 *
 * Extracted from TelemetryActivity.buildDoors(), same pattern as
 * TelemetrySpotifySection. */
public final class TelemetryDoorsSection extends LinearLayout {

    public TelemetryDoorsSection(Activity activity) {
        super(activity);
        setOrientation(VERTICAL);

        // One column (the car render fills the rest of the screen), two
        // cards: the door-opens-window switch, and the four pane targets laid
        // out 2x2 the way the panes sit in the car.
        LinearLayout col = ConfigCards.column(activity);
        addView(ConfigCards.columns(activity, col));

        LinearLayout doorCard = ConfigCards.card(activity, activity.getString(R.string.cfg_doors_header));
        doorCard.addView(Style.toggleRow(activity, activity.getString(R.string.cfg_window_on_door),
            DoorWindow.enabled(activity), on -> {
                Prefs.setWindowOnDoor(activity, on);
                // The watch itself is always registered; the pref only decides
                // whether an event acts. So nothing has to be started or stopped
                // here, and flipping it mid-session cannot make the next door
                // event look like the first one.
                ComfortHub.get(activity);
            }));
        doorCard.addView(ConfigCards.hint(activity, activity.getString(R.string.cfg_window_on_door_hint)));
        ConfigCards.add(col, doorCard);

        // Per-pane crack-all-windows target. See Purge's own comment: a
        // single raw position value doesn't open every pane the same real
        // amount, since each pane's regulator/gearing maps position units
        // to physical travel differently. One field per pane instead of
        // one shared value.
        LinearLayout purgeCard = ConfigCards.card(activity, activity.getString(R.string.cfg_purge_open_header));
        purgeCard.addView(ConfigCards.hint(activity, activity.getString(R.string.cfg_purge_open_hint)));
        purgeCard.addView(paneRow(activity,
            purgeTargetRow(activity, Purge.AREAS[0], activity.getString(R.string.cfg_purge_open_fl)),
            purgeTargetRow(activity, Purge.AREAS[1], activity.getString(R.string.cfg_purge_open_fr))));
        purgeCard.addView(paneRow(activity,
            purgeTargetRow(activity, Purge.AREAS[2], activity.getString(R.string.cfg_purge_open_rl)),
            purgeTargetRow(activity, Purge.AREAS[3], activity.getString(R.string.cfg_purge_open_rr))));
        ConfigCards.add(col, purgeCard);

        ConfigCards.add(col, lockSoundCard(activity));
    }

    // The sound the car plays when locked with the key. One button per sound,
    // lit by what the car's setting really says (LockSound.current), not by
    // what was last tapped here: a pick in the car's own Settings wins, and
    // this card must show it. Moves nothing, so unlike the rest of the page it
    // is not a safety question; it only needs modehelper to be new enough.
    private static LinearLayout lockSoundCard(Activity activity) {
        LinearLayout card = ConfigCards.card(activity, activity.getString(R.string.cfg_lock_sound_header));
        String[] ids = {LockSound.CAR, LockSound.SOUNDS[0], LockSound.SOUNDS[1]};
        String[] labels = {
            activity.getString(R.string.cfg_lock_sound_car),
            activity.getString(R.string.cfg_lock_sound_fart1),
            activity.getString(R.string.cfg_lock_sound_fart2)};
        boolean supported = LockSound.helperSupports(activity);
        TextView[] buttons = new TextView[ids.length];
        android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String now = LockSound.current(activity);
        for (int k = 0; k < ids.length; k++) {
            String id = ids[k];
            TextView b = Style.cardButton(activity, labels[k], id.equals(now), supported ? () -> {
                LockSound.set(activity, id);
                paintLockButtons(activity, buttons, ids, id);
                // modehelper answers in well under a second; read the car's
                // setting back so the lit button is the truth, not our hope.
                ui.postDelayed(() -> paintLockButtons(activity, buttons, ids, LockSound.current(activity)), 1500);
            } : null);
            if (!supported) b.setAlpha(0.4f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (k > 0) lp.leftMargin = Style.dp(activity, 10);
            row.addView(b, lp);
            buttons[k] = b;
        }
        card.addView(row);
        card.addView(ConfigCards.hint(activity, activity.getString(
            supported ? R.string.cfg_lock_sound_hint : R.string.cfg_lock_sound_needs_helper)));
        return card;
    }

    private static void paintLockButtons(Activity activity, TextView[] buttons, String[] ids, String selected) {
        for (int k = 0; k < buttons.length; k++) {
            int fill = ids[k].equals(selected) ? Style.CARD_ON : Style.CARD;
            buttons[k].setBackground(Style.card(fill, activity));
            buttons[k].setTextColor(Style.onFill(fill));
        }
    }

    // Front pane on the left, the other side's pane on the right: one row
    // per seat row, same as looking down at the car.
    private static LinearLayout paneRow(Activity activity, LinearLayout left, LinearLayout right) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    // Number-only input, saved on every keystroke that parses rather than on
    // blur (blur unreliably fires with the on-screen number pad here -- the
    // original "edited it, it reverted" report that taught us that), raw
    // text never rewritten under the cursor. Clamped to WINDOW_POS's real
    // 0..100 range at the point of use (Purge.targetsFromPrefs), not here --
    // this just has to reject non-integer input, not decide what's a sane
    // window position.
    private static LinearLayout purgeTargetRow(Activity activity, int area, String label) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Style.dp(activity, 4), 0, Style.dp(activity, 4));

        TextView lbl = new TextView(activity);
        lbl.setText(label);
        lbl.setTextColor(Style.TEXT); lbl.setTextSize(17);
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
            Style.dp(activity, 160), ViewGroup.LayoutParams.WRAP_CONTENT);
        lbl.setLayoutParams(lblLp);
        row.addView(lbl);

        EditText field = new EditText(activity);
        field.setText(String.valueOf(Prefs.file(activity).getInt(Purge.prefKey(area), Purge.OPEN)));
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setTextColor(Style.TEXT); field.setTextSize(17);
        field.setBackground(Style.card(Style.CARD, activity));
        int fPad = Style.dp(activity, 12);
        field.setPadding(fPad, fPad, fPad, fPad);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(
            Style.dp(activity, 90), ViewGroup.LayoutParams.WRAP_CONTENT);
        fieldLp.leftMargin = Style.dp(activity, 10);
        field.setLayoutParams(fieldLp);
        row.addView(field);

        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                try { Prefs.file(activity).edit().putInt(Purge.prefKey(area), Integer.parseInt(s.toString().trim())).apply(); }
                catch (NumberFormatException ignored) {}
            }
        });
        return row;
    }
}
