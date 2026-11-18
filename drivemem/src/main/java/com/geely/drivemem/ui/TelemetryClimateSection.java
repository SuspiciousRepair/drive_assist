package com.geely.drivemem.ui;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.hvac.ComfortHub;
import com.geely.drivemem.hvac.ComfortLearn;
import com.geely.drivemem.hvac.ComfortRuler;
import com.geely.drivemem.hvac.EffortTable;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

import java.util.Map;

/** Climate panel of the Config screen: the comfort ruler's learn mode
 * (hvac.ComfortLearn) -- the switch, what was learned and when, and the
 * reset. Its own page: climate is not driving, and at the bottom of the
 * Drive page it sat below the fold. */
public final class TelemetryClimateSection extends LinearLayout {
    private final Activity activity;
    private final TextView learned;

    public TelemetryClimateSection(Activity activity) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);

        LinearLayout col = ConfigCards.column(activity);
        addView(ConfigCards.columns(activity, col));

        LinearLayout card = ConfigCards.card(activity, activity.getString(R.string.cfg_learn_header));
        card.addView(Style.toggleRow(activity, activity.getString(R.string.cfg_learn_toggle),
            Prefs.getComfortLearn(activity), on -> {
                Prefs.setComfortLearn(activity, on);
                reloadRuler();
            }));
        card.addView(ConfigCards.hint(activity, activity.getString(R.string.cfg_learn_hint)));
        learned = new TextView(activity);
        learned.setTextColor(Style.TEXT);
        learned.setTextSize(17);
        learned.setPadding(0, Style.dp(activity, 10), 0, Style.dp(activity, 4));
        card.addView(learned);
        showLearned();
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(Style.button(activity, activity.getString(R.string.cfg_learn_reset), Style.CARD_HI, () ->
            new android.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.cfg_learn_reset_title))
                .setMessage(activity.getString(R.string.cfg_learn_reset_body))
                .setPositiveButton(activity.getString(R.string.cfg_learn_reset_ok), (d, w) -> {
                    Prefs.setComfortBook(activity, "");
                    com.geely.drivemem.hvac.ComfortTableStore.reset(activity);
                    reloadRuler();
                    showLearned();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show()));
        card.addView(actions);
        ConfigCards.add(col, card);
    }

    private void reloadRuler() {
        ComfortRuler r = ComfortHub.peek();
        if (r != null) r.reloadLearned();
    }

    /** Lists what the ruler learned, one line per column and weather band. */
    private void showLearned() {
        ComfortLearn.Book book = ComfortLearn.Book.decode(Prefs.getComfortBook(activity));
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, ComfortLearn.Patch> e : book.learned().entrySet()) {
            int[] k = ComfortLearn.parseKey(e.getKey());
            if (k == null) continue;
            ComfortLearn.Patch p = e.getValue();
            StringBuilder what = new StringBuilder();
            if (p.fan != null) what.append(activity.getString(R.string.cfg_learn_fan, p.fan));
            if (p.sp != null) sep(what).append(activity.getString(R.string.cfg_learn_sp,
                p.sp % 1f == 0f ? String.valueOf(p.sp.intValue()) : String.valueOf(p.sp)));
            if (p.dir != null) sep(what).append(dirName(p.dir));
            if (p.rec != null) sep(what).append(activity.getString(
                p.rec ? R.string.cfg_learn_rec_on : R.string.cfg_learn_rec_off));
            String level = activity.getString(R.string.ruler_level,
                activity.getString(k[0] > 0 ? R.string.ruler_src_cold : R.string.ruler_src_hot), Math.abs(k[0]));
            if (b.length() > 0) b.append('\n');
            b.append(activity.getString(R.string.cfg_learn_item, level, band(k[0], k[1]), what));
        }
        if (b.length() == 0) b.append(activity.getString(R.string.cfg_learn_none));
        java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
        if (book.lastChangeMs() > 0) {
            b.append('\n').append(activity.getString(R.string.cfg_learn_last, df.format(new java.util.Date(book.lastChangeMs()))));
        }
        if (book.nextDecisionMs() > 0) {
            b.append('\n').append(activity.getString(R.string.cfg_learn_week, book.holdCount(),
                df.format(new java.util.Date(book.nextDecisionMs()))));
        }
        learned.setText(b.toString());
    }

    private static StringBuilder sep(StringBuilder b) { return b.length() > 0 ? b.append(", ") : b; }

    private String band(int level, int free) {
        float[] r = ComfortLearn.band(level, free);
        if (Float.isNaN(r[0])) return activity.getString(R.string.cfg_learn_band_below, (int) r[1]);
        if (Float.isNaN(r[1])) return activity.getString(R.string.cfg_learn_band_above, (int) r[0]);
        return activity.getString(R.string.cfg_learn_band, (int) r[0], (int) r[1]);
    }

    private String dirName(int dir) {
        switch (dir) {
            case EffortTable.DIR_FACE:       return activity.getString(R.string.cfg_learn_dir_face);
            case EffortTable.DIR_FEET:       return activity.getString(R.string.cfg_learn_dir_feet);
            case EffortTable.DIR_FACE_FEET:  return activity.getString(R.string.cfg_learn_dir_face_feet);
            case EffortTable.DIR_GLASS:      return activity.getString(R.string.cfg_learn_dir_glass);
            case EffortTable.DIR_GLASS_FEET: return activity.getString(R.string.cfg_learn_dir_glass_feet);
            default:                         return String.valueOf(dir);
        }
    }
}
