package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
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

        // 3. Actions Row (Export)
        LinearLayout actRow = new LinearLayout(c);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setPadding(0, Style.dp(c, 14), 0, Style.dp(c, 4));
        actRow.addView(Style.cardButton(c, c.getString(R.string.charge_export), false, () ->
            UsbExport.exportFiles((ok, drive, copied) -> post(() -> {
                String msg = drive == null ? c.getString(R.string.charge_export_no_drive)
                           : !ok || copied == 0 ? c.getString(R.string.charge_export_nothing)
                           : c.getString(R.string.charge_export_ok, copied);
                Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
            }), CarDb.file(c))));
        addView(actRow);

        // 4. Energy Balance Chart (spent vs regen/AC/DC per day)
        energyBalanceChart = new EnergyBalanceChart(c);
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chartLp.topMargin = Style.dp(c, 8);
        energyBalanceChart.setLayoutParams(chartLp);
        addView(energyBalanceChart);

        // 5. Session History List Container
        listContainer = new LinearLayout(c);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = Style.dp(c, 8);
        listContainer.setLayoutParams(listLp);
        addView(listContainer);
    }

    private static final SimpleDateFormat DAY_FMT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat SHORT_DAY_FMT =
            new SimpleDateFormat("d/M", Locale.US);
    /** Existing session classification: 22 kW and above is DC fast. */
    private static final double DCFC_W_THRESHOLD = 22_000;

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

        // Rebuild KPI Card
        kpiContainer.removeAllViews();
        LinearLayout ltmCard = new LinearLayout(c);
        ltmCard.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(c, 20);
        ltmCard.setPadding(pad, pad, pad, pad);
        ltmCard.setBackground(Style.card(Style.CARD, c));

        // Period toggle buttons
        LinearLayout periodRow = new LinearLayout(c);
        periodRow.setOrientation(LinearLayout.HORIZONTAL);
        periodRow.addView(periodTile(30, c.getString(R.string.charge_period_30d)));
        Style.gap(periodRow, c, 10);
        periodRow.addView(periodTile(365, c.getString(R.string.charge_ltm_title)));
        ltmCard.addView(periodRow);

        // Metric row 1: Count, Energy, Cost
        LinearLayout statsRow1 = new LinearLayout(c);
        statsRow1.setOrientation(LinearLayout.HORIZONTAL);
        statsRow1.setPadding(0, Style.dp(c, 16), 0, 0);
        statsRow1.addView(statTile(String.valueOf(count), c.getString(R.string.charge_ltm_count_label), Style.TEXT));
        statsRow1.addView(statTile(String.format(Locale.US, "%.1f kWh", totalKwh), "Energia", Style.COOL));
        String costStr = costSessionCount > 0 ? String.format(Locale.getDefault(), "R$ %.2f", totalCost) : "—";
        statsRow1.addView(statTile(costStr, c.getString(R.string.charge_stat_total_cost), Style.TEXT));
        ltmCard.addView(statsRow1);

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
        statsRow2.addView(statTile(String.format(Locale.US, "%.0f km", kmDriven), "Distância", Style.TEXT_DIM));
        ltmCard.addView(statsRow2);

        kpiContainer.addView(ltmCard);

        // Always render the complete rolling calendar, including days with
        // driving energy but no recharge. Overnight sessions are apportioned
        // by their overlap with each calendar day.
        final int chartPeriodDays = 30;
        long chartCutoff = System.currentTimeMillis() - chartPeriodDays * 24L * 3600 * 1000;
        List<EnergyBalanceChart.Day> chartDays = buildBalanceDays(c, allSessions, chartCutoff, chartPeriodDays);
        energyBalanceChart.setDays(chartDays);
        energyBalanceChart.setVisibility(VISIBLE);

        // Rebuild Session History List (Newest first)
        listContainer.removeAllViews();
        if (allSessions.isEmpty()) {
            listContainer.addView(Style.label(c, c.getString(R.string.charge_none)));
            return;
        }

        List<ChargeSession.Summary> rev = new ArrayList<>(allSessions);
        Collections.reverse(rev);
        for (ChargeSession.Summary s : rev) {
            listContainer.addView(chargeRow(s));
        }
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
                if (s.avgPowerW > DCFC_W_THRESHOLD) item.dc += s.kwh * share;
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

    private View periodTile(int days, String label) {
        boolean sel = periodDays == days;
        TextView b = Style.cardButton(getContext(), label, sel, () -> {
            periodDays = days;
            refresh();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout statTile(String value, String label, int color) {
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
        card.setBackground(Style.card(Style.CARD, c));
        int p = Style.dp(c, 16);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Style.dp(c, 10);
        card.setLayoutParams(lp);

        // Header row with Title and Fast/Slow charging badge
        LinearLayout titleRow = new LinearLayout(c);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        boolean isDcfc = s.avgPowerW >= 22000;

        // MDI vector icon instead of an emoji, same as the daily stats session list.
        ImageView typeIcon = new ImageView(c);
        typeIcon.setImageResource(isDcfc ? R.drawable.ic_ev_station : R.drawable.ic_power_plug);
        typeIcon.setColorFilter(isDcfc ? Style.HEAT : Style.COOL, android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(Style.dp(c, 20), Style.dp(c, 20));
        iconLp.rightMargin = Style.dp(c, 8);
        typeIcon.setLayoutParams(iconLp);
        titleRow.addView(typeIcon);

        TextView title = new TextView(c);
        title.setText(s.title());
        title.setTextColor(Style.TEXT);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView typeBadge = new TextView(c);
        typeBadge.setText(isDcfc ? "DC Fast" : "AC Mains");
        typeBadge.setTextColor(isDcfc ? Style.HEAT : Style.COOL);
        typeBadge.setTextSize(13);
        typeBadge.setTypeface(null, Typeface.BOLD);
        titleRow.addView(typeBadge);
        card.addView(titleRow);

        // Visual charge range bar
        final ImageView bar = new ImageView(c);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 24));
        barLp.topMargin = Style.dp(c, 8);
        bar.setLayoutParams(barLp);
        bar.post(() -> {
            int w = bar.getWidth();
            if (w > 0) {
                int color = isDcfc ? Style.HEAT : Style.ACCENT;
                bar.setImageBitmap(Style.chargeRangeBar(c, w, bar.getHeight(),
                    s.socStart / 100f, s.socEnd / 100f, color, s.durationLabel()));
            }
        });
        card.addView(bar);

        // Metrics & Cost Action Row
        LinearLayout infoRow = new LinearLayout(c);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams irLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        irLp.topMargin = Style.dp(c, 8);
        infoRow.setLayoutParams(irLp);

        TextView sub = new TextView(c);
        sub.setText(s.subtitle(c));
        sub.setTextColor(Style.TEXT_DIM);
        sub.setTextSize(15);
        infoRow.addView(sub, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Cost display & input button
        if (s.cost != null && s.cost >= 0) {
            LinearLayout costCol = new LinearLayout(c);
            costCol.setOrientation(LinearLayout.VERTICAL);
            costCol.setGravity(Gravity.END);

            TextView costVal = new TextView(c);
            costVal.setText(s.costLabel() + " (" + s.costPerKwhLabel() + ")");
            costVal.setTextColor(Style.ACCENT);
            costVal.setTextSize(15);
            costVal.setTypeface(null, Typeface.BOLD);
            costCol.addView(costVal);

            costCol.setOnClickListener(v -> {
                if (!com.geely.drivemem.state.CarState.isParked()) {
                    Toast.makeText(c, R.string.charge_cost_parked_only, Toast.LENGTH_SHORT).show();
                    return;
                }
                ChargeCostDialog.show(c, s.id, s.kwh, s.socStart, s.socEnd, s.cost, this::refresh);
            });
            infoRow.addView(costCol);
        } else {
            TextView costBtn = Style.cardButton(c, "+ " + c.getString(R.string.charge_cost_label), false, () -> {
                if (!com.geely.drivemem.state.CarState.isParked()) {
                    Toast.makeText(c, R.string.charge_cost_parked_only, Toast.LENGTH_SHORT).show();
                    return;
                }
                ChargeCostDialog.show(c, s.id, s.kwh, s.socStart, s.socEnd, null, this::refresh);
            });
            infoRow.addView(costBtn);
        }

        card.addView(infoRow);
        return card;
    }
}
