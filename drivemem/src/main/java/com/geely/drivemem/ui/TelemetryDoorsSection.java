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

        addView(Style.header(activity, activity.getString(R.string.cfg_doors_header)));
        addView(Style.toggleRow(activity, activity.getString(R.string.cfg_window_on_door),
            DoorWindow.enabled(activity), on -> {
                Prefs.setWindowOnDoor(activity, on);
                // The watch itself is always registered; the pref only decides
                // whether an event acts. So nothing has to be started or stopped
                // here, and flipping it mid-session cannot make the next door
                // event look like the first one.
                ComfortHub.get(activity);
            }));
        TextView winHint = new TextView(activity);
        winHint.setTextColor(Style.TEXT_DIM); winHint.setTextSize(13);
        winHint.setPadding(0, 0, 0, Style.dp(activity, 4));
        winHint.setText(activity.getString(R.string.cfg_window_on_door_hint));
        addView(winHint);

        // Per-pane crack-all-windows target. See Purge's own comment: a
        // single raw position value doesn't open every pane the same real
        // amount, since each pane's regulator/gearing maps position units
        // to physical travel differently. One field per pane instead of
        // one shared value.
        addView(Style.header(activity, activity.getString(R.string.cfg_purge_open_header)));
        TextView purgeHint = new TextView(activity);
        purgeHint.setTextColor(Style.TEXT_DIM); purgeHint.setTextSize(13);
        purgeHint.setPadding(0, 0, 0, Style.dp(activity, 4));
        purgeHint.setText(activity.getString(R.string.cfg_purge_open_hint));
        addView(purgeHint);
        addView(purgeTargetRow(activity, Purge.AREAS[0], activity.getString(R.string.cfg_purge_open_fl)));
        addView(purgeTargetRow(activity, Purge.AREAS[1], activity.getString(R.string.cfg_purge_open_fr)));
        addView(purgeTargetRow(activity, Purge.AREAS[2], activity.getString(R.string.cfg_purge_open_rl)));
        addView(purgeTargetRow(activity, Purge.AREAS[3], activity.getString(R.string.cfg_purge_open_rr)));
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
        lbl.setTextColor(Style.TEXT); lbl.setTextSize(14);
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
