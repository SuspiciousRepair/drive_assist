package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.art.Skyline;
import com.geely.drivemem.util.Style;

import java.util.ArrayList;
import java.util.List;

/** Appearance and theme selection panel of the Config screen. Theme picker,
 * appearance mode (light/dark/auto), and skyline art background settings
 * (enabled, seed, random-per-drive toggle).
 *
 * Extracted from TelemetryActivity.buildLook() so that 2800+ line file
 * stops growing with every new Config section -- one View subclass per
 * section, same pattern ChargeStatsView/DailyStatsView already use. */
public final class TelemetryLookSection extends LinearLayout {
    private final Activity activity;
    private final SharedPreferences prefs;
    private EditText fSkylineSeed;

    public TelemetryLookSection(Activity activity) {
        super(activity);
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("drivemem", Activity.MODE_PRIVATE);
        setOrientation(VERTICAL);

        int pageWidth = Style.dp(activity, 960);

        // Noturno's own scene (VaporArtView) is a different art path entirely
        // from the skyline (SkylineArtView) every other theme uses -- this
        // toggle only ever affects the skyline, so Noturno always shows its
        // own art regardless of it. See ComfortActivity's art selection.
        //
        // Flipping this recreates the screen (not just saves the pref):
        // the seed config below must appear/disappear with it, not just sit
        // there disabled -- "the config for it" goes away along with the
        // skyline itself, not just the art.
        addView(Style.header(activity, activity.getString(R.string.cfg_skyline_header)));
        boolean skylineEnabled = prefs.getBoolean("skyline_enabled", true);
        LinearLayout skylineToggle = toggleRow(activity, activity.getString(R.string.cfg_skyline_label),
            skylineEnabled,
            on -> {
                prefs.edit().putBoolean("skyline_enabled", on).apply();
                // Recreate is handled by TelemetryActivity
            });
        skylineToggle.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(skylineToggle);

        if (skylineEnabled) {
            addView(Style.header(activity, activity.getString(R.string.cfg_skyline_seed_header)));

            boolean randomPerDrive = prefs.getBoolean("skyline_random_per_drive", false);

            fSkylineSeed = field(activity, activity.getString(R.string.cfg_skyline_seed_label),
                String.valueOf(prefs.getLong("skyline_seed", Skyline.DEFAULT_SEED)),
                InputType.TYPE_CLASS_NUMBER);
            fSkylineSeed.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
            // Locked while "random every drive" is on: that toggle is the one
            // writing skyline_seed now, on every P->D, so a value typed here
            // would just be overwritten by the next drive anyway.
            fSkylineSeed.setEnabled(!randomPerDrive);
            addView(fSkylineSeed);

            LinearLayout skylineBtnRow = new LinearLayout(activity);
            skylineBtnRow.setOrientation(LinearLayout.HORIZONTAL);
            skylineBtnRow.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
            skylineBtnRow.addView(action(activity, activity.getString(R.string.cfg_skyline_seed_save), Style.ACCENT, () -> {
                if (!fSkylineSeed.isEnabled()) return;   // random-per-drive owns the seed right now
                long seed;
                try { seed = Long.parseLong(fSkylineSeed.getText().toString().trim()); }
                catch (NumberFormatException e) { seed = Skyline.DEFAULT_SEED; }
                prefs.edit().putLong("skyline_seed", seed).apply();
                fSkylineSeed.setText(String.valueOf(seed));
                Toast.makeText(activity, activity.getString(R.string.cfg_saved), Toast.LENGTH_SHORT).show();
            }));
            skylineBtnRow.addView(action(activity, activity.getString(R.string.cfg_skyline_cycle), 0xFF6A4CFF, () -> {
                if (fSkylineSeed != null && !fSkylineSeed.isEnabled()) return;
                long seed = new java.util.Random().nextLong() & Long.MAX_VALUE;
                prefs.edit().putLong("skyline_seed", seed).apply();
                if (fSkylineSeed != null) fSkylineSeed.setText(String.valueOf(seed));
                Toast.makeText(activity, activity.getString(R.string.cfg_saved), Toast.LENGTH_SHORT).show();
            }));
            addView(skylineBtnRow);

            LinearLayout randomToggle = toggleRow(activity, activity.getString(R.string.cfg_skyline_random_per_drive_label),
                randomPerDrive,
                on -> {
                    prefs.edit().putBoolean("skyline_random_per_drive", on).apply();
                    // Recreate is handled by TelemetryActivity
                });
            randomToggle.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(randomToggle);
        }

        addView(Style.header(activity, activity.getString(R.string.cfg_theme_header)));
        addView(sectionLabel(activity, activity.getString(R.string.cfg_dark_mode_header)));
        LinearLayout appRow = new LinearLayout(activity);
        appRow.setOrientation(LinearLayout.HORIZONTAL);
        appRow.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        appRow.addView(appearanceTile(activity, Style.APPEARANCE_LIGHT, activity.getString(R.string.cfg_appearance_light)));
        appRow.addView(appearanceTile(activity, Style.APPEARANCE_DARK,  activity.getString(R.string.cfg_appearance_dark)));
        appRow.addView(appearanceTile(activity, Style.APPEARANCE_AUTO,  activity.getString(R.string.cfg_appearance_auto)));
        addView(appRow);
        TextView appNote = new TextView(activity);
        appNote.setTextColor(Style.TEXT_DIM); appNote.setTextSize(13);
        appNote.setPadding(0, Style.dp(activity, 8), 0, Style.dp(activity, 12));
        appNote.setText(activity.getString(R.string.cfg_appearance_note));
        addView(appNote);

        addView(sectionLabel(activity, activity.getString(R.string.cfg_theme_selection_header)));
        TextView sub = new TextView(activity);
        sub.setTextColor(Style.TEXT_DIM); sub.setTextSize(14);
        sub.setText(activity.getString(R.string.cfg_theme_sub));
        addView(sub);

        // A secret theme still gets a tile while it is the SAVED choice. That is
        // not a loophole in the secret: without it, someone who chose Noturno
        // before it was hidden would open this screen and find nothing selected,
        // with no way to describe the theme they are looking at. BORROWED for the
        // trip it stays hidden — the highlight belongs to the saved choice, which
        // is what comes back when the process dies.
        List<Style.Theme> shown = new ArrayList<>();
        for (Style.Theme t : Style.THEMES)
            if (!Style.secret(t.id) || t.id.equals(Style.savedId(activity))) shown.add(t);

        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                addView(row);
            }
            row.addView(themeTile(activity, shown.get(i)));
        }
        // odd number of themes: fill the empty column so the last one is not stretched
        if (shown.size() % 2 == 1 && row != null) {
            View filler = new View(activity);
            filler.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(filler);
        }

        TextView note = new TextView(activity);
        note.setTextColor(Style.TEXT_DIM); note.setTextSize(13);
        note.setPadding(0, Style.dp(activity, 14), 0, 0);
        note.setText(activity.getString(Style.FOLLOW_AMBIENT
            ? R.string.cfg_theme_ambient_on : R.string.cfg_theme_ambient_off));
        addView(note);
    }

    private View themeTile(Activity activity, final Style.Theme t) {
        // SAVED, not current(). During a transient Noturno current() is Noturno,
        // which has no tile — so the grid would highlight nothing. The grid
        // describes what is written to disk; the borrowed theme is what you are
        // looking at, and those are allowed to disagree for one trip.
        final boolean sel = t.id.equals(Style.savedId(activity));
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(sel ? Style.outlinedCard(Style.ACCENT, activity) : Style.card(Style.CARD, activity));
        int p = Style.dp(activity, 10);
        col.setPadding(p, p, p, p);

        // thumbnail drawn at the slot's real size (no stretching)
        final ImageView sw = new ImageView(activity);
        sw.setScaleType(ImageView.ScaleType.FIT_XY);
        sw.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(activity, 104)));
        sw.post(() -> sw.setImageBitmap(Style.themeSwatch(activity, t, sw.getWidth(), sw.getHeight())));
        col.addView(sw);

        TextView name = new TextView(activity);
        // t.name is a proper name ("Geely", "Noturno"…): not translated, it only gets the selected marker
        name.setText(sel ? activity.getString(R.string.cfg_theme_selected, t.name) : t.name);
        name.setTextColor(sel ? Style.ACCENT : Style.TEXT); name.setTextSize(18);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setPadding(0, Style.dp(activity, 8), 0, 0);
        col.addView(name);

        TextView blurb = new TextView(activity);
        blurb.setText(activity.getString(t.blurbRes));
        blurb.setTextColor(Style.TEXT_DIM); blurb.setTextSize(13);
        col.addView(blurb);

        col.setOnClickListener(v -> pickTheme(activity, t));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Style.dp(activity, 5);
        lp.setMargins(m, Style.dp(activity, 10), m, 0);
        col.setLayoutParams(lp);
        return col;
    }

    private void pickTheme(Activity activity, Style.Theme t) {
        // Compare against the SAVED theme, not the current one. While the Konami
        // code has us transiently in Noturno, Style.current() IS Noturno — so
        // picking Noturno here would return early and neither persist the choice
        // nor clear the override, leaving the user stuck in an easter egg with no
        // way out through the UI.
        if (t.id.equals(Style.savedId(activity)) && !Style.isTransient()) return;
        Style.save(activity, t.id);
        activity.getIntent().putExtra("section", 2); // SEC_LOOK = 2
        activity.recreate();
    }

    private View appearanceTile(Activity activity, String mode, String label) {
        boolean sel = mode.equals(Style.appearance(activity));
        TextView b = Style.cardButton(activity, label, sel, () -> {
            Style.setAppearance(activity, mode);
            activity.getIntent().putExtra("section", 2); // SEC_LOOK = 2
            activity.recreate();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Style.dp(activity, 5);
        lp.setMargins(m, Style.dp(activity, 10), m, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout toggleRow(Activity activity, String label, boolean on, com.geely.drivemem.controls.GeelySwitch.OnToggle cb) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Style.dp(activity, 8), 0, Style.dp(activity, 8));
        com.geely.drivemem.controls.GeelySwitch sw = new com.geely.drivemem.controls.GeelySwitch(activity);
        sw.setLockSeconds(prefs.getInt("switch_lock_s", com.geely.drivemem.controls.GeelySwitch.DEFAULT_LOCK_S));
        sw.setCheckedSilently(on);
        sw.setOnToggle(cb);
        row.addView(sw);
        TextView lbl = Style.label(activity, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Style.dp(activity, 14);
        lbl.setLayoutParams(lp);
        row.addView(lbl);
        return row;
    }

    private EditText field(Activity activity, String label, String val, int type) {
        TextView l = new TextView(activity);
        l.setText(label); l.setTextColor(Style.TEXT_DIM); l.setTextSize(14);
        l.setPadding(0, Style.dp(activity, 10), 0, Style.dp(activity, 2));
        addView(l);
        EditText e = new EditText(activity);
        e.setText(val); e.setInputType(type);
        e.setTextColor(Style.TEXT); e.setTextSize(17);
        e.setBackground(Style.card(Style.CARD, activity));
        int p = Style.dp(activity, 12);
        e.setPadding(p, p, p, p);
        addView(e);
        return e;
    }

    private TextView action(Activity activity, String label, int color, Runnable onClick) {
        TextView t = new TextView(activity);
        t.setText(label);
        t.setTextColor(Style.onFill(color));
        t.setTextSize(18);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Style.card(color, activity));
        int pv = Style.dp(activity, 16);
        t.setPadding(pv, pv, pv, pv);
        t.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Style.dp(activity, 4);
        lp.setMargins(m, Style.dp(activity, 8), m, 0);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView sectionLabel(Activity activity, String label) {
        TextView t = new TextView(activity);
        t.setText(label);
        t.setTextColor(Style.TEXT);
        t.setTextSize(16);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Style.dp(activity, 14);
        lp.bottomMargin = Style.dp(activity, 8);
        t.setLayoutParams(lp);
        return t;
    }
}
