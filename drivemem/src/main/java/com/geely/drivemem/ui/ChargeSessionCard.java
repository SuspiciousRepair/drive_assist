package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

import java.util.Locale;

/** One finished charge, the same card on the home screen and in Charging
 * stats:
 *
 *   [plug] +22,8 kWh   3h 40m                    |  R$ 18,40 |
 *   [battery] 44% → 100%   [bolt] 6,2 kW avg     | 0,81/kWh  |
 *
 * In a list (withDate) the first line also says when it started:
 * "+22,8 kWh  11 set 13:40 · 3h 40m". The body opens the charge's page; the right tile
 * the cost window. No background or padding of its own: the screen that
 * holds it sets them. */
final class ChargeSessionCard extends LinearLayout {
    private static final java.text.SimpleDateFormat START_FMT =
        new java.text.SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
    private final ImageView icon;
    private final TextView hero, soc, power, costTop, costBottom;
    private final LinearLayout body, costTile;

    ChargeSessionCard(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setBaselineAligned(false);

        body = new LinearLayout(c);
        body.setOrientation(VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout line1 = new LinearLayout(c);
        line1.setGravity(Gravity.CENTER_VERTICAL);
        icon = rowIcon(c, R.drawable.ic_power_plug);
        line1.addView(icon);
        hero = new TextView(c);
        hero.setTextColor(Style.TEXT);
        hero.setTextSize(30);
        hero.setTypeface(null, Typeface.BOLD);
        hero.setSingleLine(true);
        hero.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line1.addView(hero, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(line1);

        LinearLayout line2 = new LinearLayout(c);
        line2.setGravity(Gravity.CENTER_VERTICAL);
        line2.setPadding(0, Style.dp(c, 8), 0, 0);
        line2.addView(rowIcon(c, R.drawable.ic_battery));
        soc = rowValue(c);
        line2.addView(soc);
        line2.addView(new View(c), new LayoutParams(Style.dp(c, 20), 1));
        line2.addView(rowIcon(c, R.drawable.ic_flash));
        power = rowValue(c);
        line2.addView(power);
        body.addView(line2);
        addView(body, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        costTile = new LinearLayout(c);
        costTile.setOrientation(VERTICAL);
        costTile.setGravity(Gravity.CENTER);
        costTile.setPadding(Style.dp(c, 6), Style.dp(c, 8), Style.dp(c, 6), Style.dp(c, 8));
        costTop = tileText(c);
        costTile.addView(costTop);
        costBottom = tileText(c);
        costBottom.setPadding(0, Style.dp(c, 2), 0, 0);
        costTile.addView(costBottom);
        LayoutParams rlp = new LayoutParams(Style.dp(c, 140), ViewGroup.LayoutParams.MATCH_PARENT);
        rlp.leftMargin = Style.dp(c, 16);
        addView(costTile, rlp);
    }

    /** The body (all but the cost tile) opens the charge. */
    void setOnOpen(Runnable r) {
        OnClickListener l = v -> r.run();
        setOnClickListener(l);
        body.setOnClickListener(l);
    }

    void setOnCost(Runnable r) { costTile.setOnClickListener(v -> r.run()); }

    /** Fills the card. gray: Config's grayscale cards. withDate: say when,
     * for a list of charges. */
    void bind(ChargeSession.Summary s, boolean gray, boolean withDate) {
        Context c = getContext();
        boolean dcfc = s.isDcfc();
        boolean hasV = s.hasChargeVoltage();
        int typeColor = (!gray && hasV) ? (dcfc ? Style.GOOD : Style.ACCENT) : Style.TEXT;
        int iconColor = (!gray && hasV) ? (dcfc ? Style.GOOD : Style.ACCENT) : Style.TEXT_DIM;
        icon.setImageResource(dcfc ? R.drawable.ic_ev_plug_ccs2 : R.drawable.ic_power_plug);
        icon.setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_IN);

        SpannableStringBuilder h = new SpannableStringBuilder(Style.valueWithUnit(
            String.format(Locale.getDefault(), "+%.1f", s.kwh),
            (!gray && hasV) ? typeColor : null, "kWh", Style.UNIT_SCALE_ROW));
        String dur = SessionText.duration(s.endWallMs - s.startWallMs);
        Style.appendUnit(h, "   " + (withDate ? START_FMT.format(new java.util.Date(s.startWallMs)) + " · " + dur : dur), 0.6f);
        hero.setText(h);

        if (s.socStart >= 0 && s.socEnd >= 0) {
            soc.setText(Style.percentRange(s.socStart, s.socEnd, Style.UNIT_SCALE_ROW));
        } else if (s.socEnd >= 0) {
            soc.setText(Style.valueWithUnit(String.valueOf(s.socEnd), null, "%", Style.UNIT_SCALE_ROW));
        } else {
            soc.setText(R.string.value_unavailable);
        }

        SpannableStringBuilder p = new SpannableStringBuilder(Style.valueWithUnit(
            String.format(Locale.getDefault(), "%.1f", s.avgPowerW / 1000.0), null, "kW", Style.UNIT_SCALE_ROW));
        Style.appendUnit(p, " " + c.getString(R.string.charge_avg_short), Style.UNIT_SCALE_ROW);
        power.setText(p);

        if (s.cost != null && s.cost >= 0) {
            GradientDrawable g = Style.card(Style.cardFillColor(), c, 18);
            g.setStroke(Style.dp(c, 2), gray ? Style.TEXT_DIM : Style.ACCENT);
            costTile.setBackground(g);
            costTop.setText(Style.moneyWithUnit(s.cost, null, Style.UNIT_SCALE_ROW));
            costTop.setTextColor(gray ? Style.TEXT : Style.ACCENT);
            costTop.setTextSize(19);
            String perKwh = s.costPerKwhLabel();
            costBottom.setText(perKwh != null ? perKwh : c.getString(R.string.charge_cost_label));
            costBottom.setTextColor(Style.TEXT_DIM);
            costBottom.setTextSize(15);
        } else {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Style.dp(c, 18));
            int fill = gray ? Style.CARD_HI : Style.ACCENT;
            g.setColor(fill);
            costTile.setBackground(g);
            int onFill = Style.onFill(fill);
            costTop.setText("+ " + Prefs.getCurrency(c));
            costTop.setTextColor(onFill);
            costTop.setTextSize(22);
            costBottom.setText(c.getString(R.string.charge_cost_label));
            costBottom.setTextColor(onFill);
            costBottom.setTextSize(15);
        }
    }

    private static ImageView rowIcon(Context c, int res) {
        ImageView i = new ImageView(c);
        i.setImageResource(res);
        i.setColorFilter(Style.TEXT_DIM, android.graphics.PorterDuff.Mode.SRC_IN);
        LayoutParams lp = new LayoutParams(Style.dp(c, 24), Style.dp(c, 24));
        lp.rightMargin = Style.dp(c, 8);
        i.setLayoutParams(lp);
        return i;
    }

    private static TextView rowValue(Context c) {
        TextView t = new TextView(c);
        t.setTextColor(Style.TEXT);
        t.setTextSize(22);
        t.setTypeface(null, Typeface.BOLD);
        t.setSingleLine(true);
        return t;
    }

    private static TextView tileText(Context c) {
        TextView t = new TextView(c);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        return t;
    }
}
