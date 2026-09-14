package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.database.Cursor;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.sensors.OdoStats;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.util.Style;
import com.geely.drivemem.util.UsbExport;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Comprehensive charging history & statistics screen with cost tracking and period rollups. */
public class ChargeStatsView extends LinearLayout {

    private final Runnable onBack;
    private int periodDays = 30;

    private LinearLayout headerContainer;
    private LinearLayout kpiContainer;
    private EnergyBalanceChart energyBalanceChart;
    private LinearLayout listContainer;
    private ScrollView sessionListScroll;
    private ChargeSocChart chargeSocChart;
    private long selectedSessionId = -1;
    private final Map<Long, ChargeSession.Summary> visibleSessions = new LinkedHashMap<>();
    private final Map<Long, View> sessionRows = new LinkedHashMap<>();

    private final EntityBus.Listener chargeListener = (key, reading) -> {
        post(this::refresh);
    };

    public ChargeStatsView(Context context) {
        this(context, null);
    }

    public ChargeStatsView(Context context, Runnable onBack) {
        super(context);
        this.onBack = onBack;
        setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(context, 16);
        setPadding(pad, pad, pad, Style.dp(context, 32));

        buildSkeleton();
        refresh();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        EntityBus.subscribe("charge.completed", chargeListener);
        EntityBus.subscribe("charge.cost_updated", chargeListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EntityBus.unsubscribe("charge.completed", chargeListener);
        EntityBus.unsubscribe("charge.cost_updated", chargeListener);
    }

    private void buildSkeleton() {
        Context c = getContext();

        // 1. Header row
        headerContainer = new LinearLayout(c);
        headerContainer.setOrientation(LinearLayout.HORIZONTAL);
        headerContainer.setGravity(Gravity.CENTER_VERTICAL);
        if (onBack != null) {
            headerContainer.addView(Style.backButton(c, onBack));
            Style.gap(headerContainer, c, 16);
        }
        headerContainer.addView(Style.header(c, c.getString(R.string.charge_stats_title)));
        addView(headerContainer);

        // 2. KPI Summary Container
        kpiContainer = new LinearLayout(c);
        kpiContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams kpiLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kpiLp.topMargin = Style.dp(c, 12);
        kpiContainer.setLayoutParams(kpiLp);
        addView(kpiContainer);

        // 3. Compact session navigator and chronological SoC chart.
        // These are two independent cards, not a matched pair -- neither's
        // height should derive from the other's. Previously both were forced
        // into one shared fixed-height row (390dp), so a short chart reserved
        // the same box as a long session list and vice versa; whichever had
        // more content than that box could hold got clipped instead of
        // scrolling on its own. Each card now sizes to (or caps) its own
        // content independently.
        LinearLayout overview = new LinearLayout(c);
        overview.setOrientation(LinearLayout.HORIZONTAL);
        overview.setBaselineAligned(false);
        LinearLayout.LayoutParams overviewLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        overviewLp.topMargin = Style.dp(c, 12);
        overview.setLayoutParams(overviewLp);

        LinearLayout listCard = new LinearLayout(c);
        listCard.setOrientation(LinearLayout.VERTICAL);
        int cardPad = Style.dp(c, 14);
        listCard.setPadding(cardPad, cardPad, cardPad, cardPad);
        listCard.setBackground(Style.card(Style.cardFillColor(), c));
        TextView listTitle = Style.label(c, c.getString(R.string.charge_sessions_title));
        listTitle.setTextSize(14);
        listTitle.setTypeface(null, Typeface.BOLD);
        listCard.addView(listTitle);

        sessionListScroll = new ScrollView(c);
        sessionListScroll.setVerticalScrollBarEnabled(false);
        sessionListScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listContainer = new LinearLayout(c);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        sessionListScroll.addView(listContainer);
        // Own fixed cap (not tied to the chart card): enough room for a
        // couple of session cards before this list scrolls internally,
        // regardless of how tall the chart beside it ends up being.
        listCard.addView(sessionListScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 340)));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 46f);
        listLp.rightMargin = Style.dp(c, 12);
        overview.addView(listCard, listLp);

        LinearLayout chartCard = new LinearLayout(c);
        chartCard.setOrientation(LinearLayout.VERTICAL);
        chartCard.setPadding(cardPad, cardPad, cardPad, Style.dp(c, 8));
        chartCard.setBackground(Style.card(Style.cardFillColor(), c));
        LinearLayout chartHeading = new LinearLayout(c);
        chartHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView chartTitle = Style.label(c, c.getString(R.string.charge_soc_chart_title));
        chartTitle.setTextSize(14);
        chartTitle.setTypeface(null, Typeface.BOLD);
        chartHeading.addView(chartTitle, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        chartHeading.addView(legend(c, "AC", Style.ACCENT));
        chartHeading.addView(legend(c, "DC", Style.GOOD));
        chartCard.addView(chartHeading);
        chargeSocChart = new ChargeSocChart(c);
        chargeSocChart.setListener(id -> selectSession(id, true));
        // WRAP_CONTENT, not a shared weighted fill: ChargeSocChart already
        // computes its own ideal height from its session count (see its
        // setMinimumHeight call), so this card is exactly as tall as its own
        // bars need, independent of the list card beside it.
        chartCard.addView(chargeSocChart, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Energy Balance sits below the SoC chart, in the same column at the
        // same width -- both are per-day charts, so they read as a pair
        // (2026-09-13; previously Balanço de Energia spanned the full page
        // width below both columns, which orphaned it from the chart it's
        // most related to).
        LinearLayout rightColumn = new LinearLayout(c);
        rightColumn.setOrientation(LinearLayout.VERTICAL);
        rightColumn.addView(chartCard, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        energyBalanceChart = new EnergyBalanceChart(c);
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chartLp.topMargin = Style.dp(c, 12);
        energyBalanceChart.setLayoutParams(chartLp);
        rightColumn.addView(energyBalanceChart);

        overview.addView(rightColumn, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 54f));
        addView(overview);
    }

    private static final SimpleDateFormat DAY_FMT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat SHORT_DAY_FMT =
            new SimpleDateFormat("d/M", Locale.US);

    public void refresh() {
        Context c = getContext();
        List<ChargeSession.Summary> allSessions = ChargeSession.readLog(c);

        long cutoff = System.currentTimeMillis() - periodDays * 24L * 3600 * 1000;
        int count = 0;
        double totalKwh = 0;
        double totalCost = 0;
        int costSessionCount = 0;

        for (ChargeSession.Summary s : allSessions) {
            if (s.startWallMs < cutoff) continue;
            count++;
            totalKwh += s.kwh;
            if (s.cost != null && s.cost >= 0) {
                totalCost += s.cost;
                costSessionCount++;
            }
        }
        double kmDriven = OdoStats.kmSince(c, periodDays);

        // Rebuild KPI row: a standalone metrics card (three columns, two rows,
        // same as before) beside a narrow controls column (period toggle +
        // export) -- 2026-09-13, replacing the previous stack of controls
        // above metrics. The metrics are the primary content here; the
        // period/export controls are secondary, so they get a slim side
        // column (~1/8 of the row) instead of a full-width band of their own.
        kpiContainer.removeAllViews();
        LinearLayout topRowSection = new LinearLayout(c);
        topRowSection.setOrientation(LinearLayout.HORIZONTAL);
        topRowSection.setBaselineAligned(false);

        LinearLayout statsCard = new LinearLayout(c);
        statsCard.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(c, 20);
        statsCard.setPadding(pad, pad, pad, pad);
        statsCard.setBackground(Style.card(Style.CARD, c));

        // Metric row 1: Count, Energy, Cost
        LinearLayout statsRow1 = new LinearLayout(c);
        statsRow1.setOrientation(LinearLayout.HORIZONTAL);
        statsRow1.addView(statTile(String.valueOf(count), c.getString(R.string.charge_ltm_count_label), Style.TEXT));
        // Unit style guide (2026-09-13): the unit renders smaller/dimmer than
        // its value everywhere -- Style.valueWithUnit, not a plain concatenated string.
        statsRow1.addView(statTile(Style.valueWithUnit(String.format(Locale.US, "%.1f", totalKwh),
            Style.COOL, "kWh", Style.UNIT_SCALE_HERO), "Energia", Style.COOL));
        String costStr = costSessionCount > 0 ? String.format(Locale.getDefault(), "R$ %.2f", totalCost) : "—";
        statsRow1.addView(statTile(costStr, c.getString(R.string.charge_stat_total_cost), Style.TEXT));
        statsCard.addView(statsRow1);

        // Metric row 2: Cost/kWh, Cost/100km, km driven
        LinearLayout statsRow2 = new LinearLayout(c);
        statsRow2.setOrientation(LinearLayout.HORIZONTAL);
        statsRow2.setPadding(0, Style.dp(c, 12), 0, 0);

        String costPerKwhStr = (costSessionCount > 0 && totalKwh > 0)
            ? String.format(Locale.getDefault(), "R$ %.2f", totalCost / totalKwh) : "—";
        statsRow2.addView(statTile(costPerKwhStr, c.getString(R.string.charge_stat_avg_cost_kwh), Style.TEXT));

        String costPer100kmStr = (costSessionCount > 0 && kmDriven > 0)
            ? String.format(Locale.getDefault(), "R$ %.2f", (totalCost / kmDriven) * 100) : "—";
        statsRow2.addView(statTile(costPer100kmStr, c.getString(R.string.charge_stat_cost_100km), Style.TEXT));
        statsRow2.addView(statTile(Style.valueWithUnit(String.format(Locale.US, "%.0f", kmDriven),
            Style.TEXT_DIM, "km", Style.UNIT_SCALE_HERO), "Distância", Style.TEXT_DIM));
        statsCard.addView(statsRow2);

        LinearLayout.LayoutParams statsCardLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 7f);
        statsCardLp.rightMargin = Style.dp(c, 12);
        topRowSection.addView(statsCard, statsCardLp);

        LinearLayout controlsCol = new LinearLayout(c);
        controlsCol.setOrientation(LinearLayout.VERTICAL);
        controlsCol.addView(sideButton(30, c.getString(R.string.charge_period_30d)));
        Style.gap(controlsCol, c, 10);
        controlsCol.addView(sideButton(365, c.getString(R.string.charge_ltm_title)));
        Style.gap(controlsCol, c, 10);
        TextView exportBtn = Style.cardButton(c, c.getString(R.string.charge_export), false, () ->
            UsbExport.exportFiles((ok, drive, copied) -> post(() -> {
                String msg = drive == null ? c.getString(R.string.charge_export_no_drive)
                           : !ok || copied == 0 ? c.getString(R.string.charge_export_nothing)
                           : c.getString(R.string.charge_export_ok, copied);
                Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
            }), CarDb.file(c)));
        exportBtn.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        controlsCol.addView(exportBtn);
        topRowSection.addView(controlsCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        kpiContainer.addView(topRowSection);

        // Always render the complete rolling calendar, including days with
        // driving energy but no recharge. Overnight sessions are apportioned
        // by their overlap with each calendar day.
        final int chartPeriodDays = 30;
        long chartCutoff = System.currentTimeMillis() - chartPeriodDays * 24L * 3600 * 1000;
        List<EnergyBalanceChart.Day> chartDays = buildBalanceDays(c, allSessions, chartCutoff, chartPeriodDays);
        energyBalanceChart.setDays(chartDays);
        energyBalanceChart.setVisibility(VISIBLE);

        // Rebuild session navigator and chart in the same order (newest
        // first) -- they used to disagree (list newest-first, chart
        // chronological), which read as two different sort orders for the
        // same data sitting side by side.
        listContainer.removeAllViews();
        sessionRows.clear();
        visibleSessions.clear();
        List<ChargeSession.Summary> periodSessions = new ArrayList<>();
        for (ChargeSession.Summary s : allSessions) {
            if (s.startWallMs >= cutoff) {
                periodSessions.add(s);
                visibleSessions.put(s.id, s);
            }
        }
        if (periodSessions.isEmpty()) {
            listContainer.addView(Style.label(c, c.getString(R.string.charge_none)));
            chargeSocChart.setSessions(periodSessions, -1);
            return;
        }
        if (!visibleSessions.containsKey(selectedSessionId)) {
            selectedSessionId = periodSessions.get(periodSessions.size() - 1).id;
        }
        List<ChargeSession.Summary> rev = new ArrayList<>(periodSessions);
        Collections.reverse(rev);
        for (ChargeSession.Summary s : rev) {
            View row = chargeRow(s);
            sessionRows.put(s.id, row);
            listContainer.addView(row);
        }
        chargeSocChart.setSessions(rev, selectedSessionId);
        chargeSocChart.select(selectedSessionId, true);
    }

    private List<EnergyBalanceChart.Day> buildBalanceDays(Context c,
            List<ChargeSession.Summary> sessions, long cutoff, int days) {
        List<EnergyBalanceChart.Day> out = new ArrayList<>();
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(cutoff);
        day.set(Calendar.HOUR_OF_DAY, 0); day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < days; i++) {
            long start = day.getTimeInMillis();
            long end = start + 24L * 3600_000L;
            String key = DAY_FMT.format(new Date(start));
            EnergyBalanceChart.Day item = new EnergyBalanceChart.Day(SHORT_DAY_FMT.format(new Date(start)));
            double[] energy = rawEnergyForDay(c, key);
            item.spent = energy[0]; item.regen = energy[1];
            for (ChargeSession.Summary s : sessions) {
                long overlapStart = Math.max(start, s.startWallMs);
                long overlapEnd = Math.min(end, s.endWallMs);
                if (overlapEnd <= overlapStart || s.endWallMs <= s.startWallMs) continue;
                double share = (overlapEnd - overlapStart) / (double) (s.endWallMs - s.startWallMs);
                if (s.isDcfc()) item.dc += s.kwh * share;
                else item.ac += s.kwh * share;
            }
            out.add(item);
            day.add(Calendar.DATE, 1);
        }
        return out;
    }

    /** Chart deliberately includes parked HVAC use; this is separate from driving efficiency. */
    private double[] rawEnergyForDay(Context c, String date) {
        Cursor cursor = CarDb.get(c).db().rawQuery(
                "SELECT COALESCE(SUM(energy_spent_kwh),0), COALESCE(SUM(energy_regen_kwh),0) "
              + "FROM telemetry_sample WHERE date(ts_ms/1000,'unixepoch','localtime') = ? "
              + "AND (is_charging IS NULL OR is_charging = 0)", new String[]{date});
        try {
            return cursor.moveToFirst() ? new double[]{cursor.getDouble(0), cursor.getDouble(1)} : new double[]{0, 0};
        } finally { cursor.close(); }
    }

    /** A period-toggle button sized for the narrow controls column -- full
     * width, stacked vertically with its sibling, rather than splitting a
     * row's width with it (2026-09-13). */
    private View sideButton(int days, String label) {
        boolean sel = periodDays == days;
        TextView b = Style.cardButton(getContext(), label, sel, () -> {
            periodDays = days;
            refresh();
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private LinearLayout statTile(CharSequence value, String label, int color) {
        LinearLayout t = new LinearLayout(getContext());
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(getContext());
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(26);
        v.setTypeface(null, Typeface.BOLD);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        t.addView(v);

        TextView l = new TextView(getContext());
        l.setText(label);
        l.setTextColor(Style.TEXT_DIM);
        l.setTextSize(13);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        t.addView(l);
        return t;
    }

    private View chargeRow(ChargeSession.Summary s) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        // More horizontal padding than vertical: the icon and the SoC/Cost
        // corner text both used to sit flush against the card's left/right
        // edge. Vertical stays tight (each corner is one line now). Right
        // gets extra: the right corners are bold, variable-width numbers
        // (energy, cost) that came closer to the border than the left side
        // did at the same padding value.
        int pL = Style.dp(c, 16);
        int pR = Style.dp(c, 24);
        int pV = Style.dp(c, 10);
        card.setPadding(pL, pV, pR, pV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Style.dp(c, 16);
        card.setLayoutParams(lp);

        boolean isDcfc = s.isDcfc();
        int typeColor = !s.hasChargeVoltage() ? Style.TEXT_DIM : isDcfc ? Style.GOOD : Style.ACCENT;

        // Four corners, one per dimension (2026-09-13 v2, replacing the
        // previous four-stacked-row layout -- that version was too tall for
        // what it showed, with too much vertical gap between rows and hero
        // numbers sized the same as secondary text). Top-left Time,
        // top-right SoC/Energy, bottom-left Power (+ chart link),
        // bottom-right Cost -- each quadrant anchored to its own corner
        // instead of stacked full-width lines.
        LinearLayout topRow = new LinearLayout(c);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.TOP);

        // Top-left: Time.
        LinearLayout timeCell = new LinearLayout(c);
        timeCell.setOrientation(LinearLayout.HORIZONTAL);
        timeCell.setGravity(Gravity.CENTER_VERTICAL);
        timeCell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // App-wide charging style guide (2026-09-13): AC = blue + plug icon,
        // DC = green + ev-plug-ccs2 icon, color/icon alone carry the meaning
        // -- no "AC"/"DC" text on a session card, that's reserved for the
        // chart legend (the one place two colors side by side need naming).
        ImageView typeIcon = new ImageView(c);
        typeIcon.setImageResource(isDcfc ? R.drawable.ic_ev_plug_ccs2 : R.drawable.ic_power_plug);
        typeIcon.setColorFilter(typeColor, android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(Style.dp(c, 20), Style.dp(c, 20));
        iconLp.rightMargin = Style.dp(c, 8);
        typeIcon.setLayoutParams(iconLp);
        timeCell.addView(typeIcon);

        // Time and duration share one line -- "11 set 13:40 -> 14:39  0:58"
        // -- rather than duration sitting on its own line below (2026-09-13:
        // that made the cell taller than it needed to be for one related fact).
        LinearLayout timeText = new LinearLayout(c);
        timeText.setOrientation(LinearLayout.HORIZONTAL);
        timeText.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(c);
        title.setText(s.title()); // "11 set  13:40 → 14:39"
        title.setTextColor(Style.TEXT);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        timeText.addView(title);
        TextView durationText = new TextView(c);
        durationText.setText(s.durationLabel());
        durationText.setTextColor(Style.TEXT_DIM);
        durationText.setTextSize(13);
        LinearLayout.LayoutParams durationLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        durationLp.leftMargin = Style.dp(c, 8);
        durationText.setLayoutParams(durationLp);
        timeText.addView(durationText);
        timeCell.addView(timeText);
        topRow.addView(timeCell);

        // Top-right: SoC and the energy it added share one line -- these are
        // two views of the same fact (how much the battery gained), not two
        // separate ones, so they read better side by side than stacked.
        LinearLayout socCell = new LinearLayout(c);
        socCell.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        socCell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView socEnergyText = new TextView(c);
        SpannableStringBuilder socEnergy = new SpannableStringBuilder();
        socEnergy.append(Style.percentRange(s.socStart, s.socEnd, Style.UNIT_SCALE_ROW));
        socEnergy.append("  ");
        // Normal weight, not bold: the SoC range is the primary fact on this
        // corner, the energy added is secondary -- two bold numbers side by
        // side on one line fought for attention instead of reading as
        // primary/secondary (2026-09-13).
        socEnergy.append(Style.valueWithUnit(String.format(Locale.getDefault(), "+%.1f", s.kwh),
            null, "kWh", Style.UNIT_SCALE_ROW, false));
        socEnergyText.setText(socEnergy);
        socEnergyText.setTextColor(Style.TEXT);
        socEnergyText.setTextSize(19);
        socEnergyText.setTypeface(null, Typeface.BOLD);
        socCell.addView(socEnergyText);
        topRow.addView(socCell);
        card.addView(topRow);

        LinearLayout bottomRow = new LinearLayout(c);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomLp.topMargin = Style.dp(c, 4);
        bottomRow.setLayoutParams(bottomLp);

        // Bottom-left: Power, with the link to its detail chart right next to
        // it (the chart it opens *is* the power curve, so it belongs with
        // this number rather than floating between two unrelated ones).
        // A spacer matching the icon's own width (20dp) + its margin (8dp)
        // comes first, so Power's text starts at the same x as Time's text
        // above it -- the icon stays where it is (top-left only), but the
        // two corners' text still lines up into one visual column.
        LinearLayout powerCell = new LinearLayout(c);
        powerCell.setOrientation(LinearLayout.HORIZONTAL);
        powerCell.setGravity(Gravity.CENTER_VERTICAL);
        powerCell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View iconAlignSpacer = new View(c);
        iconAlignSpacer.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(c, 28), 0));
        powerCell.addView(iconAlignSpacer);

        TextView powerText = new TextView(c);
        SpannableStringBuilder power = new SpannableStringBuilder();
        power.append(Style.valueWithUnit(String.format(Locale.getDefault(), "%.1f", s.avgPowerW / 1000.0),
            Style.TEXT, "kW", Style.UNIT_SCALE_ROW));
        Style.appendUnit(power, " méd.", Style.UNIT_SCALE_ROW);
        powerText.setText(power);
        powerText.setTextSize(19);
        powerText.setTypeface(null, Typeface.BOLD);
        powerCell.addView(powerText);

        // A small icon, not a full button -- a labeled button sitting in the
        // middle of a data row read as an odd, heavy element.
        ImageView curveBtn = new ImageView(c);
        curveBtn.setImageResource(R.drawable.ic_chart_line);
        curveBtn.setColorFilter(Style.TEXT_DIM, android.graphics.PorterDuff.Mode.SRC_IN);
        curveBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int curvePad = Style.dp(c, 6);
        curveBtn.setPadding(curvePad, curvePad, curvePad, curvePad);
        // 44dp touch target around a 24dp glyph -- a hand-sized tap area, not
        // just a visually-sized one (see CLAUDE.md's note on touch targets).
        curveBtn.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(c, 44), Style.dp(c, 44)));
        curveBtn.setOnClickListener(v -> {
            if (!com.geely.drivemem.state.CarState.isParked()) {
                Toast.makeText(c, R.string.charge_cost_parked_only, Toast.LENGTH_SHORT).show();
                return;
            }
            ChargeCurrentCurveDialog.show(c, s);
        });
        powerCell.addView(curveBtn);
        bottomRow.addView(powerCell);

        // Bottom-right: Cost, anchored to that corner. Total and its per-kWh
        // rate share one line, same as SoC/Energy above -- the rate is
        // de-emphasized inline (smaller/dimmer) rather than stacked on its
        // own line, per the unit style guide's "value then smaller/dimmer
        // detail" idea applied to a rate instead of a suffix unit.
        LinearLayout costCell = new LinearLayout(c);
        costCell.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        costCell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (s.cost != null && s.cost >= 0) {
            TextView costText = new TextView(c);
            SpannableStringBuilder costSb = new SpannableStringBuilder();
            costSb.append(s.costLabel());
            if (s.costPerKwhLabel() != null) {
                Style.appendUnit(costSb, "  " + s.costPerKwhLabel(), Style.UNIT_SCALE_ROW);
            }
            costText.setText(costSb);
            costText.setTextColor(Style.ACCENT);
            costText.setTextSize(19);
            costText.setTypeface(null, Typeface.BOLD);
            costText.setOnClickListener(v -> {
                if (!com.geely.drivemem.state.CarState.isParked()) {
                    Toast.makeText(c, R.string.charge_cost_parked_only, Toast.LENGTH_SHORT).show();
                    return;
                }
                ChargeCostDialog.show(c, s.id, s.kwh, s.socStart, s.socEnd, s.cost, this::refresh);
            });
            costCell.addView(costText);
        } else {
            TextView costBtn = Style.cardButton(c, "+ " + c.getString(R.string.charge_cost_label), false, () -> {
                if (!com.geely.drivemem.state.CarState.isParked()) {
                    Toast.makeText(c, R.string.charge_cost_parked_only, Toast.LENGTH_SHORT).show();
                    return;
                }
                ChargeCostDialog.show(c, s.id, s.kwh, s.socStart, s.socEnd, null, this::refresh);
            });
            costCell.addView(costBtn);
        }
        bottomRow.addView(costCell);
        card.addView(bottomRow);
        card.setOnClickListener(v -> selectSession(s.id, false));
        card.setBackground(s.id == selectedSessionId
            ? Style.outlinedCard(isDcfc ? Style.GOOD : Style.ACCENT, c)
            : Style.card(Style.CARD, c));
        return card;
    }

    private void selectSession(long id, boolean fromChart) {
        selectedSessionId = id;
        for (Map.Entry<Long, View> entry : sessionRows.entrySet()) {
            ChargeSession.Summary s = visibleSessions.get(entry.getKey());
            int color = s != null && s.isDcfc() ? Style.GOOD : Style.ACCENT;
            entry.getValue().setBackground(entry.getKey() == id
                ? Style.outlinedCard(color, getContext())
                : Style.card(Style.CARD, getContext()));
        }
        chargeSocChart.select(id, !fromChart);
        View row = sessionRows.get(id);
        if (fromChart && row != null) sessionListScroll.smoothScrollTo(0, row.getTop());
    }

    private View legend(Context c, String text, int color) {
        TextView v = new TextView(c);
        v.setText("● " + text);
        v.setTextColor(color);
        v.setTextSize(13);
        v.setPadding(Style.dp(c, 12), 0, 0, 0);
        return v;
    }
}
