package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

import java.util.Locale;

/**
 * Shared charging-event row used in both the Daily Stats session list and the
 * Charging Stats list (the Home Screen keeps its own compact ChargeSessionCard).
 *
 * Line 1: AC/DC plug icon + time range · duration.
 * Line 2: 4 equal columns — Battery | Energy added | Average power | Cost (or ETA when live).
 */
final class ChargeEventCard {
    private static final int STAT_COLUMNS = 4;

    private ChargeEventCard() { }

    /** Builds a standalone charging event card for Charging Stats. */
    static LinearLayout forSummary(Context c, ChargeSession.Summary s,
                                   Runnable onOpen, Runnable onCost) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        long durMs = Math.max(0L, s.endWallMs - s.startWallMs);
        populate(card, s.isDcfc(), s.hasChargeVoltage(), s.title(), durMs,
            s.socStart, s.socEnd, s.kwh, s.avgPowerW / 1000.0,
            false, null, s.id, s.cost, onOpen, onCost);
        return card;
    }

    /** Populates a session card in Daily Stats (matches Trip and Valet rows). */
    static void populateDaily(LinearLayout card, DailyStatsProvider.ChargeSessionItem ch,
                              long dayStartMs, CharSequence liveEta,
                              Runnable onOpen, Runnable onCost) {
        Context c = card.getContext();
        boolean live = ch.endMs <= 0;
        String yesterday = c.getString(R.string.session_yesterday);
        String range = live
            ? c.getString(R.string.session_since, SessionText.startLabel(ch.startMs, dayStartMs, yesterday))
            : SessionText.range(ch.startMs, ch.endMs, dayStartMs, yesterday);
        long durMs = Math.max(0L, (live ? System.currentTimeMillis() : ch.endMs) - ch.startMs);
        populate(card, ch.isDcfc, true, range, durMs,
            ch.socStart, ch.socEnd, ch.kwh, ch.avgPowerKw,
            live, liveEta, ch.chargeId, ch.cost, onOpen, onCost);
    }

    private static void populate(LinearLayout card, boolean isDcfc, boolean hasVoltage,
                                 String rangeText, long durationMs,
                                 int socStart, int socEnd, double kwh, double avgPowerKw,
                                 boolean live, CharSequence liveEta,
                                 long chargeId, Double cost,
                                 Runnable onOpen, Runnable onCost) {
        Context c = card.getContext();
        // App-wide charging color code: AC = blue + plug, DC = green + CCS.
        int iconColor = hasVoltage ? (isDcfc ? Style.GOOD : Style.ACCENT) : Style.TEXT_DIM;
        Integer energyColor = hasVoltage ? iconColor : null;
        int iconRes = isDcfc ? R.drawable.ic_ev_plug_ccs2 : R.drawable.ic_power_plug;

        card.addView(titleRow(c, iconRes, iconColor, rangeText, durationMs));

        View battery = stat(c, R.string.session_stat_battery, socValue(socStart, socEnd), null);
        View energy = stat(c, R.string.session_stat_charged_energy,
            Style.valueWithUnit(String.format(Locale.US, "+%.1f", kwh),
                energyColor, "kWh", Style.UNIT_SCALE_ROW), null);
        View power = stat(c, R.string.session_stat_avg_power,
            Style.valueWithUnit(String.format(Locale.US, "%.1f", avgPowerKw),
                null, "kW", Style.UNIT_SCALE_ROW), null);

        View fourth;
        if (live) {
            fourth = stat(c, R.string.session_stat_eta, liveEta, null);
        } else if (cost != null && cost >= 0) {
            CharSequence costValue = Style.moneyWithUnit(cost, Style.ACCENT, Style.UNIT_SCALE_ROW);
            CharSequence perKwh = kwh > 0 ? Prefs.formatMoneyPerKwh(cost / kwh) : null;
            fourth = stat(c, R.string.session_stat_cost, costValue, perKwh);
            if (onCost != null) {
                fourth.setOnClickListener(v -> onCost.run());
            }
        } else if (chargeId > 0) {
            SpannableStringBuilder tap = new SpannableStringBuilder(c.getString(R.string.session_cost_tap));
            tap.setSpan(new ForegroundColorSpan(Style.TEXT_DIM), 0, tap.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tap.setSpan(new StyleSpan(Typeface.NORMAL), 0, tap.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            fourth = stat(c, R.string.session_stat_cost, tap, null);
            if (onCost != null) {
                fourth.setOnClickListener(v -> onCost.run());
            }
        } else {
            fourth = null;
        }

        card.addView(statsRow(c, battery, energy, power, fourth));

        if (onOpen != null) {
            card.setOnClickListener(v -> onOpen.run());
        }
    }

    private static LinearLayout titleRow(Context c, int iconRes, int iconColor,
                                         String rangeText, long durationMs) {
        LinearLayout line1 = new LinearLayout(c);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);

        ImageView badge = new ImageView(c);
        badge.setImageResource(iconRes);
        badge.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
        line1.addView(badge, new LinearLayout.LayoutParams(Style.dp(c, 20), Style.dp(c, 20)));

        SpannableStringBuilder time = new SpannableStringBuilder(rangeText);
        Style.appendUnit(time, "  ·  " + SessionText.duration(durationMs), 0.92f);

        TextView timeView = new TextView(c);
        timeView.setTextColor(Style.TEXT);
        timeView.setTextSize(17f);
        timeView.setTypeface(null, Typeface.BOLD);
        timeView.setSingleLine(true);
        timeView.setEllipsize(TextUtils.TruncateAt.END);
        timeView.setText(time);
        timeView.setPadding(Style.dp(c, 10), 0, 0, 0);
        line1.addView(timeView, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return line1;
    }

    private static LinearLayout statsRow(Context c, View... stats) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.setPadding(Style.dp(c, 30), Style.dp(c, 10), 0, 0);
        for (int i = 0; i < STAT_COLUMNS; i++) {
            View v = i < stats.length && stats[i] != null ? stats[i] : new View(c);
            row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private static View stat(Context c, int labelRes, CharSequence value, CharSequence note) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, Style.dp(c, 12), 0);

        TextView label = new TextView(c);
        label.setText(labelRes);
        label.setTextColor(Style.TEXT_DIM);
        label.setTextSize(15f);
        col.addView(label);

        TextView v = new TextView(c);
        v.setText(value);
        v.setTextColor(Style.TEXT);
        v.setTextSize(18f);
        v.setTypeface(null, Typeface.BOLD);
        col.addView(v);

        if (note != null) {
            TextView n = new TextView(c);
            n.setText(note);
            n.setTextColor(Style.TEXT_DIM);
            n.setTextSize(15f);
            col.addView(n);
        }
        return col;
    }

    private static CharSequence socValue(int from, int to) {
        if (from >= 0 && to >= 0) {
            return Style.percentRange(from, to, Style.UNIT_SCALE_ROW);
        }
        if (from >= 0) {
            return Style.valueWithUnit(String.valueOf(from), null, "%", Style.UNIT_SCALE_ROW);
        }
        if (to >= 0) {
            return Style.valueWithUnit(String.valueOf(to), null, "%", Style.UNIT_SCALE_ROW);
        }
        return "—";
    }
}
