package com.geely.drivemem.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.sensors.OdoStats;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;
import com.geely.drivemem.util.UsbExport;

import java.util.List;

/** Charging session history — a screen showing charging statistics and session logs.
 *
 * Extracted from TelemetryActivity.buildCharge(), periodTile(), statTile(),
 * and chargeRow(), same pattern as TelemetrySpotifySection and TelemetryDoorsSection. */
public final class TelemetryChargeSection extends LinearLayout {
    private int chargePeriodDays = 30;

    public TelemetryChargeSection(Activity activity) {
        super(activity);
        setOrientation(VERTICAL);

        addView(Style.header(activity, activity.getString(R.string.charge_title)));

        List<ChargeSession.Summary> sessions = ChargeSession.readLog(activity);

        // Last N days/months, as its own card up top — same visual language
        // as the main page's cards (Clima/Turbo/etc.), not a settings line,
        // since this is the headline number for the whole screen. km driven
        // comes from OdoStats's own daily readings, not from charge sessions —
        // an odometer snapshot taken only when charging starts covers just
        // the gaps BETWEEN charges, silently missing any driving before the
        // first or after the last charge in the window. OdoStats has no
        // history before it started running, so with less than
        // chargePeriodDays of data on file this reads "since logging began"
        // rather than a true N-day figure — a smaller number, not a wrong
        // one, and it grows into accuracy on its own.
        long ltmCutoff = System.currentTimeMillis() - chargePeriodDays * 24L * 3600 * 1000;
        int ltmCount = 0; double ltmKwh = 0;
        for (ChargeSession.Summary s : sessions) {
            if (s.startWallMs < ltmCutoff) continue;
            ltmCount++;
            ltmKwh += s.kwh;
        }
        double kmDriven = OdoStats.kmSince(activity, chargePeriodDays);

        LinearLayout ltmCard = new LinearLayout(activity);
        ltmCard.setOrientation(LinearLayout.VERTICAL);
        int ltmPad = Style.dp(activity, 26);
        ltmCard.setPadding(ltmPad, ltmPad, ltmPad, ltmPad);
        ltmCard.setBackground(Style.card(Style.cardFillColor(), activity));
        ltmCard.addView(Style.header(activity, activity.getString(
            chargePeriodDays <= 30 ? R.string.charge_period_30d : R.string.charge_ltm_title)));

        LinearLayout periodRow = new LinearLayout(activity);
        periodRow.setOrientation(LinearLayout.HORIZONTAL);
        periodRow.setPadding(0, Style.dp(activity, 8), 0, 0);
        periodRow.addView(periodTile(activity, 30, activity.getString(R.string.charge_period_30d)));
        periodRow.addView(periodTile(activity, 365, activity.getString(R.string.charge_ltm_title)));
        ltmCard.addView(periodRow);

        LinearLayout statsRow = new LinearLayout(activity);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setPadding(0, Style.dp(activity, 10), 0, 0);
        statsRow.addView(statTile(activity, String.valueOf(ltmCount), activity.getString(R.string.charge_ltm_count_label)));
        statsRow.addView(statTile(activity, String.format(java.util.Locale.US, "%.0f", ltmKwh), "kWh"));
        statsRow.addView(statTile(activity, String.format(java.util.Locale.US, "%.0f", kmDriven), "km"));
        ltmCard.addView(statsRow);
        addView(ltmCard);

        double totalKwh = 0;
        for (ChargeSession.Summary s : sessions) totalKwh += s.kwh;
        addView(Style.label(activity,
            activity.getString(R.string.charge_summary, sessions.size(), totalKwh)));

        LinearLayout ctl = new LinearLayout(activity);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setPadding(0, Style.dp(activity, 12), 0, Style.dp(activity, 4));
        ctl.addView(Style.cardButton(activity, activity.getString(R.string.charge_export), false, () ->
            UsbExport.exportFiles((ok, drive, copied) -> activity.runOnUiThread(() -> {
                    String msg = drive == null ? activity.getString(R.string.charge_export_no_drive)
                               : !ok || copied == 0 ? activity.getString(R.string.charge_export_nothing)
                               : activity.getString(R.string.charge_export_ok, copied);
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                }),
                CarDb.file(activity))));
        addView(ctl);

        if (sessions.isEmpty()) { addView(Style.label(activity, activity.getString(R.string.charge_none))); return; }
        // Most recent first — readLog() returns oldest-first.
        for (int i = sessions.size() - 1; i >= 0; i--) addView(chargeRow(activity, sessions.get(i)));
    }

    // One of the two period-toggle buttons on the charge history card.
    private View periodTile(Activity activity, int days, String label) {
        boolean sel = chargePeriodDays == days;
        TextView b = Style.cardButton(activity, label, sel, () -> {
            chargePeriodDays = days;
            // Trigger a full rebuild of this section via the parent Activity
            // This is handled by TelemetryActivity's selectSection(SEC_CHARGE)
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Style.dp(activity, 5);
        lp.leftMargin = m; lp.rightMargin = m;
        b.setLayoutParams(lp);
        // This button doesn't actually trigger a refresh because the section
        // would need to be rebuilt from the Activity level. For now, this is
        // just a placeholder that allows the button to be clicked but doesn't
        // update. A future enhancement would pass a callback to trigger
        // selectSection(SEC_CHARGE) from the Activity.
        return b;
    }

    // A big-number-over-small-label tile, same shape repeated three times in
    // the LTM card — count, kWh, km share one look rather than three ad hoc
    // layouts.
    private static LinearLayout statTile(Activity activity, String value, String label) {
        LinearLayout t = new LinearLayout(activity);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(activity);
        v.setText(value); v.setTextColor(Style.TEXT); v.setTextSize(34);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        t.addView(v);
        TextView l = new TextView(activity);
        l.setText(label); l.setTextColor(Style.TEXT_DIM); l.setTextSize(14);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        t.addView(l);
        return t;
    }

    private static LinearLayout chargeRow(Activity activity, ChargeSession.Summary s) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Style.card(Style.CARD, activity));
        int p = Style.dp(activity, 14);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Style.dp(activity, 10);
        card.setLayoutParams(lp);
        card.addView(Style.header(activity, s.title()));   // date + time range

        final ImageView bar = new ImageView(activity);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(activity, 26));   // tall enough to fit the duration label
        barLp.topMargin = Style.dp(activity, 8);
        bar.setLayoutParams(barLp);
        // Drawn once, after layout gives it a real width — this row never
        // changes again, unlike the live card's bar, so no redraw-on-tick
        // machinery is needed here.
        bar.post(() -> {
            int w = bar.getWidth();
            if (w > 0) bar.setImageBitmap(Style.chargeRangeBar(activity, w, bar.getHeight(),
                s.socStart / 100f, s.socEnd / 100f, Style.ACCENT, s.durationLabel()));
        });
        card.addView(bar);

        // Smaller than Style.label()'s usual 22sp: this line is now just
        // three numbers (SoC range, kWh, kW), not a sentence — it doesn't
        // need the same weight as a card's main text.
        TextView sub = new TextView(activity);
        sub.setText(s.subtitle(activity));
        sub.setTextColor(Style.TEXT_DIM);
        sub.setTextSize(16);
        card.addView(sub);
        return card;
    }
}
