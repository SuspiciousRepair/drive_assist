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
import com.geely.drivemem.controls.GeelySwitch;
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

    // The sound the car plays when locked with the key. Everything here is
    // painted from what modehelper says is chosen (LockSound.mode), not from
    // what was last tapped. Moves nothing, so unlike the rest of the
    // page it is not a safety question; it only needs a new enough modehelper.
    //
    // Fart mode is a switch on top of the normal pick: on plays the bundled
    // fart, off gives back the pick below it (car's sound or the imported one).
    private static LinearLayout lockSoundCard(Activity activity) {
        LinearLayout card = ConfigCards.card(activity, activity.getString(R.string.cfg_lock_sound_header));
        boolean supported = LockSound.helperSupports(activity);
        LockSoundViews v = new LockSoundViews();
        v.supported = supported;
        // A first guess until modehelper answers (old helpers: the answer).
        v.mode = LockSound.current(activity);
        // Every answer from modehelper repaints from what it says is chosen,
        // so the screen is the truth, not our hope.
        LockSound.Result after = code -> {
            if ("no_custom".equals(code)) v.status.setText(R.string.cfg_lock_sound_import_first);
            else if ("adb".equals(code)) v.status.setText(R.string.cfg_lock_sound_no_shell);
            else if (LockSound.NO_ANSWER.equals(code)) v.status.setText(R.string.cfg_lock_sound_needs_helper);
            paintLockSound(activity, v);
        };

        // 1. Fart mode.
        LinearLayout fartRow = Style.toggleRow(activity, activity.getString(R.string.cfg_lock_sound_fart_mode),
            LockSound.FART.equals(v.mode), on -> {
                if (!supported) { paintLockSound(activity, v); return; }
                LockSound.set(activity, on ? LockSound.FART : LockSound.base(activity, v.mode), v.mode, after);
            });
        v.fart = (GeelySwitch) fartRow.getChildAt(0);
        card.addView(fartRow);

        // 2. The normal sound: what plays when fart mode is off.
        card.addView(subLabel(activity, R.string.cfg_lock_sound_pick_label, 14));
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {activity.getString(R.string.cfg_lock_sound_car), activity.getString(R.string.cfg_lock_sound_custom)};
        for (int k = 0; k < v.ids.length; k++) {
            String id = v.ids[k];
            TextView b = Style.cardButton(activity, labels[k], false, () -> {
                if (!supported) return;
                v.status.setText(null);
                LockSound.set(activity, id, v.mode, after);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (k > 0) lp.leftMargin = Style.dp(activity, 12);
            row.addView(b, lp);
            v.buttons[k] = b;
        }
        v.pick = row;
        card.addView(row);

        // 3. Import the custom sound: LockChime.wav from a USB stick, or a
        // link. modehelper copies it into the car and checks it (WAV, 1 MB or
        // less), so the stick can come out afterwards.
        card.addView(subLabel(activity, R.string.cfg_lock_sound_import_label, 26));
        LinearLayout imp = new LinearLayout(activity);
        imp.setOrientation(LinearLayout.VERTICAL);
        TextView usb = Style.cardButton(activity, activity.getString(R.string.cfg_lock_sound_import_usb), false,
            () -> runImport(activity, v, null));
        imp.addView(usb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout linkRow = new LinearLayout(activity);
        linkRow.setOrientation(LinearLayout.HORIZONTAL);
        linkRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText link = new EditText(activity);
        link.setHint(R.string.cfg_lock_sound_link_hint);
        link.setSingleLine(true);
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        link.setTextColor(Style.TEXT); link.setHintTextColor(Style.TEXT_DIM); link.setTextSize(18);
        link.setBackground(Style.card(Style.CARD, activity));
        int fPad = Style.dp(activity, 18);
        link.setPadding(fPad, fPad, fPad, fPad);
        linkRow.addView(link, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView get = Style.cardButton(activity, activity.getString(R.string.cfg_lock_sound_download), false, () -> {
            String url = link.getText().toString().trim();
            if (url.isEmpty()) { v.status.setText(R.string.cfg_lock_sound_link_empty); return; }
            runImport(activity, v, url);
        });
        get.setMinWidth(Style.dp(activity, 180));
        LinearLayout.LayoutParams getLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        getLp.leftMargin = Style.dp(activity, 12);
        linkRow.addView(get, getLp);
        LinearLayout.LayoutParams linkRowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        linkRowLp.topMargin = Style.dp(activity, 12);
        imp.addView(linkRow, linkRowLp);
        card.addView(imp);

        // What the last tap did: its own line, so it is not lost in the hint.
        v.status = new TextView(activity);
        v.status.setTextColor(Style.TEXT);
        v.status.setTextSize(17);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = Style.dp(activity, 14);
        card.addView(v.status, stLp);

        TextView hint = ConfigCards.hint(activity, activity.getString(
            supported ? R.string.cfg_lock_sound_hint : R.string.cfg_lock_sound_needs_helper));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = Style.dp(activity, 10);
        card.addView(hint, hLp);

        if (!supported) {
            fartRow.setAlpha(0.4f);
            row.setAlpha(0.4f);
            imp.setAlpha(0.4f);
            usb.setClickable(false);
            get.setClickable(false);
            link.setEnabled(false);
        }
        paintLockSound(activity, v);
        return card;
    }

    // Small dim heading inside a card, with room above it to split the card
    // into parts at a glance.
    private static TextView subLabel(Activity activity, int text, int topDp) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(Style.TEXT_DIM);
        t.setTextSize(15);
        t.setPadding(0, Style.dp(activity, topDp), 0, Style.dp(activity, 8));
        return t;
    }

    private static final class LockSoundViews {
        final String[] ids = {LockSound.CAR, LockSound.CUSTOM};
        final TextView[] buttons = new TextView[2];
        boolean supported;
        /** What modehelper last said is chosen. */
        String mode;
        GeelySwitch fart;
        LinearLayout pick;
        TextView status;
    }

    private static void runImport(Activity activity, LockSoundViews v, String url) {
        if (!v.supported) return;
        v.status.setText(R.string.cfg_lock_sound_importing);
        // With fart mode on, the import only becomes what fart mode gives back.
        boolean apply = !LockSound.FART.equals(v.mode);
        LockSound.importSound(activity, url, apply, code -> {
            v.status.setText(importMessage(code, url != null));
            paintLockSound(activity, v);
        });
    }

    private static int importMessage(String code, boolean fromLink) {
        switch (code) {
            case "ok":        return R.string.cfg_lock_sound_import_ok;
            case "not_found": return R.string.cfg_lock_sound_import_not_found;
            case "too_big":   return R.string.cfg_lock_sound_import_too_big;
            case "not_wav":
            case "empty":     return R.string.cfg_lock_sound_import_not_wav;
            case "download":  return R.string.cfg_lock_sound_import_download;
            case LockSound.NO_ANSWER: return R.string.cfg_lock_sound_needs_helper;
            default:          return fromLink ? R.string.cfg_lock_sound_import_download
                                              : R.string.cfg_lock_sound_import_failed;
        }
    }

    private static void paintLockSound(Activity activity, LockSoundViews v) {
        paintLockSound(activity, v, v.mode);
        LockSound.mode(activity, now -> {
            v.mode = now;
            paintLockSound(activity, v, now);
        });
    }

    private static void paintLockSound(Activity activity, LockSoundViews v, String now) {
        boolean fartOn = LockSound.FART.equals(now);
        v.fart.setCheckedSilently(fartOn);
        String lit = LockSound.base(activity, now);
        for (int k = 0; k < v.buttons.length; k++) {
            int fill = v.ids[k].equals(lit) ? Style.CARD_ON : Style.CARD;
            v.buttons[k].setBackground(Style.card(fill, activity));
            v.buttons[k].setTextColor(Style.onFill(fill));
        }
        // Under fart mode the pick still shows, dimmed: it is what comes back.
        if (v.supported) v.pick.setAlpha(fartOn ? 0.55f : 1f);
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
