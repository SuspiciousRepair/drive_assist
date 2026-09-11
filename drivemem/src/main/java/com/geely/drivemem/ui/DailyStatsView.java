package com.geely.drivemem.ui;

import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.util.Style;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.state.CarState;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Interactive Daily Statistics view featuring:
 * 1. 14-day daily km Bar Chart with active day highlight and floating top labels.
 * 2. Left and right navigation arrows to switch between days.
 * 3. 3-column dashboard grid (Consumo & Eficiência, Bateria & Recargas, Dinâmica & Percurso).
 * 4. Compact 2-line session cards with visual badges for trips and charges.
 */
public class DailyStatsView extends LinearLayout {

    private List<DailyStatsProvider.DayItem> days = new ArrayList<>();
    private int selectedIdx = -1;
    private final Runnable onBack;

    private BarChart chart;
    private TextView navPrevBtn;
    private TextView navNextBtn;
    private TextView navDateLabel;

    private LinearLayout consumptionCard;
    private LinearLayout batteryCard;
    private LinearLayout dynamicsCard;
    private LinearLayout sessionsContainer;

    private final EntityBus.Listener tickListener = (key, reading) -> {
        post(this::onLiveTick);
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        EntityBus.subscribe("telemetry.tick", tickListener);
        EntityBus.subscribe("charge.cost_updated", tickListener);
        EntityBus.subscribe("charge.completed", tickListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EntityBus.unsubscribe("telemetry.tick", tickListener);
        EntityBus.unsubscribe("charge.cost_updated", tickListener);
        EntityBus.unsubscribe("charge.completed", tickListener);
    }

    public DailyStatsView(Context context) {
        this(context, null);
    }

    public DailyStatsView(Context context, Runnable onBack) {
        super(context);
        this.onBack = onBack;
        setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(context, 16);
        setPadding(pad, pad, pad, Style.dp(context, 32));

        buildSkeleton();
        refresh();
    }

    private void buildSkeleton() {
        Context c = getContext();

        // 1. Title row (with Back Button if onBack is provided)
        LinearLayout titleRow = new LinearLayout(c);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        if (onBack != null) {
            titleRow.addView(Style.backButton(c, onBack));
            Style.gap(titleRow, c, 16);
        }
        titleRow.addView(Style.header(c, "Estatísticas Diárias"));
        addView(titleRow);

        // 2. Bar Chart (compact 160dp)
        chart = new BarChart(c);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 160));
        clp.topMargin = Style.dp(c, 8);
        chart.setLayoutParams(clp);

        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setDrawGridBackground(false);
        chart.setDrawBorders(false);
        chart.setDoubleTapToZoomEnabled(false);
        chart.setPinchZoom(false);
        chart.setScaleEnabled(false);
        chart.setTouchEnabled(true);
        chart.setFitBars(true);
        chart.setExtraBottomOffset(Style.dp(c, 4));
        chart.setExtraTopOffset(Style.dp(c, 8));

        chart.setOnChartValueSelectedListener(new OnChartValueSelectedListener() {
            @Override
            public void onValueSelected(Entry e, Highlight h) {
                int idx = (int) e.getX();
                if (idx >= 0 && idx < days.size() && idx != selectedIdx) {
                    selectIndex(idx, false);
                }
            }

            @Override
            public void onNothingSelected() {}
        });

        addView(chart);
        Style.gap(this, c, 14);

        // 3. Day Navigator [ ◀ Ontem ]   [ Data ]   [ Amanhã ▶ ]
        LinearLayout navRow = new LinearLayout(c);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER_VERTICAL);

        navPrevBtn = Style.cardButton(c, c.getString(R.string.daily_stats_prev_day), false, () -> {
            if (selectedIdx > 0) selectIndex(selectedIdx - 1, true);
        });
        setNavIcon(navPrevBtn, R.drawable.ic_chevron_left, true);

        navDateLabel = new TextView(c);
        navDateLabel.setTextColor(Style.TEXT);
        navDateLabel.setTextSize(16);
        navDateLabel.setTypeface(null, Typeface.BOLD);
        navDateLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        navDateLabel.setLayoutParams(nlp);

        navNextBtn = Style.cardButton(c, c.getString(R.string.daily_stats_next_day), false, () -> {
            if (selectedIdx < days.size() - 1) selectIndex(selectedIdx + 1, true);
        });
        setNavIcon(navNextBtn, R.drawable.ic_chevron_right, false);

        navRow.addView(navPrevBtn);
        navRow.addView(navDateLabel);
        navRow.addView(navNextBtn);
        addView(navRow);
        Style.gap(this, c, 18);

        // 4. Aggregated Daily Metric Cards (3-Column Grid)
        LinearLayout dashboardRow = new LinearLayout(c);
        dashboardRow.setOrientation(LinearLayout.HORIZONTAL);
        dashboardRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int cardPad = Style.dp(c, 16);

        consumptionCard = new LinearLayout(c);
        consumptionCard.setOrientation(LinearLayout.VERTICAL);
        consumptionCard.setPadding(cardPad, cardPad, cardPad, cardPad);
        consumptionCard.setBackground(Style.card(Style.cardFillColor(), c));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp1.rightMargin = Style.dp(c, 14);
        consumptionCard.setLayoutParams(lp1);
        dashboardRow.addView(consumptionCard);

        batteryCard = new LinearLayout(c);
        batteryCard.setOrientation(LinearLayout.VERTICAL);
        batteryCard.setPadding(cardPad, cardPad, cardPad, cardPad);
        batteryCard.setBackground(Style.card(Style.cardFillColor(), c));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp2.rightMargin = Style.dp(c, 14);
        batteryCard.setLayoutParams(lp2);
        dashboardRow.addView(batteryCard);

        dynamicsCard = new LinearLayout(c);
        dynamicsCard.setOrientation(LinearLayout.VERTICAL);
        dynamicsCard.setPadding(cardPad, cardPad, cardPad, cardPad);
        dynamicsCard.setBackground(Style.card(Style.cardFillColor(), c));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        dynamicsCard.setLayoutParams(lp3);
        dashboardRow.addView(dynamicsCard);

        addView(dashboardRow);
        Style.gap(this, c, 20);

        // 5. Sessões do Dia (Lean Session List)
        addView(Style.header(c, "Sessões do Dia"));
        sessionsContainer = new LinearLayout(c);
        sessionsContainer.setOrientation(LinearLayout.VERTICAL);
        addView(sessionsContainer);
    }

    /** Reloads days from database and renders selected day. */
    public void refresh() {
        days = DailyStatsProvider.getRecentDays(getContext(), 14);
        if (days.isEmpty()) return;

        // Default to latest day (today) if not set or out of bounds
        if (selectedIdx < 0 || selectedIdx >= days.size()) {
            selectedIdx = days.size() - 1;
        }

        updateChartData();
        selectIndex(selectedIdx, true);
    }

    /** Live tick update (~15s) while screen is actively displayed. */
    private void onLiveTick() {
        if (!isShown() || days == null || days.isEmpty()) return;
        String today = DailyStatsProvider.todayDateStr();
        int todayIdx = days.size() - 1;
        if (todayIdx < 0) return;

        // If the date rolled over midnight, re-pull all 14 days
        if (!days.get(todayIdx).date.equals(today)) {
            refresh();
            return;
        }

        DailyStatsProvider.DayOverview ov = DailyStatsProvider.getDayOverview(getContext(), today);

        // Update today's entry in days list
        DailyStatsProvider.DayItem lastItem = days.get(todayIdx);
        days.set(todayIdx, new DailyStatsProvider.DayItem(lastItem.date, lastItem.shortLabel, ov.distanceKm));

        // Live update the last bar on the chart
        if (chart != null && chart.getData() != null) {
            BarDataSet ds = (BarDataSet) chart.getData().getDataSetByIndex(0);
            if (ds != null && todayIdx < ds.getEntryCount()) {
                BarEntry entry = ds.getEntryForIndex(todayIdx);
                if (entry != null && Math.abs(entry.getY() - (float) ov.distanceKm) > 0.001f) {
                    entry.setY((float) ov.distanceKm);
                    chart.getData().notifyDataChanged();
                    chart.notifyDataSetChanged();
                    chart.invalidate();
                }
            }
        }

        // If currently viewing today, update cards and session log
        if (selectedIdx == todayIdx) {
            renderOverview(ov);
        }
    }

    private void updateChartData() {
        Context c = getContext();
        List<BarEntry> entries = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        String[] labels = new String[days.size()];

        for (int i = 0; i < days.size(); i++) {
            DailyStatsProvider.DayItem item = days.get(i);
            entries.add(new BarEntry(i, (float) item.km));
            labels[i] = item.shortLabel;
            colors.add(i == selectedIdx ? Style.ACCENT : tileBarColor());
        }

        BarDataSet set = new BarDataSet(entries, "km");
        set.setColors(colors);
        set.setDrawValues(true);
        set.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                if (value <= 0) return "";
                return value >= 10
                        ? String.format(Locale.US, "%.0f km", value)
                        : String.format(Locale.US, "%.1f km", value);
            }

            @Override
            public String getBarLabel(BarEntry entry) {
                if (entry == null || entry.getY() <= 0) return "";
                return entry.getY() >= 10
                        ? String.format(Locale.US, "%.0f km", entry.getY())
                        : String.format(Locale.US, "%.1f km", entry.getY());
            }
        });
        set.setValueTextColor(Style.TEXT);
        set.setValueTextSize(11f);
        set.setHighLightAlpha(0);

        BarData data = new BarData(set);
        data.setBarWidth(0.68f);
        chart.setData(data);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setTextColor(Style.TEXT_DIM);
        xAxis.setTextSize(11f);
        xAxis.setGranularity(1f);
        xAxis.setLabelCount(labels.length);
        xAxis.setValueFormatter(new IndexAxisValueFormatter(labels));

        // Remove all Y-axis labels, ticks, and horizontal grid lines
        chart.getAxisLeft().setEnabled(false);
        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getAxisLeft().setSpaceTop(25f);
        chart.getAxisRight().setEnabled(false);

        chart.invalidate();
    }

    private int tileBarColor() {
        return Style.blend(Style.CARD_HI, Style.ACCENT, 0.25f);
    }

    private void selectIndex(int idx, boolean updateChartHighlight) {
        if (idx < 0 || idx >= days.size()) return;
        selectedIdx = idx;
        DailyStatsProvider.DayItem dayItem = days.get(idx);

        // Update nav buttons enable/disable state
        navPrevBtn.setEnabled(selectedIdx > 0);
        navPrevBtn.setAlpha(selectedIdx > 0 ? 1.0f : 0.35f);
        navNextBtn.setEnabled(selectedIdx < days.size() - 1);
        navNextBtn.setAlpha(selectedIdx < days.size() - 1 ? 1.0f : 0.35f);

        if (chart.getData() != null) {
            BarDataSet ds = (BarDataSet) chart.getData().getDataSetByIndex(0);
            if (ds != null) {
                List<Integer> colors = new ArrayList<>();
                for (int i = 0; i < days.size(); i++) {
                    colors.add(i == selectedIdx ? Style.ACCENT : tileBarColor());
                }
                ds.setColors(colors);
                if (updateChartHighlight) {
                    chart.highlightValue(selectedIdx, 0);
                }
                chart.invalidate();
            }
        }

        // Fetch day details
        DailyStatsProvider.DayOverview ov = DailyStatsProvider.getDayOverview(getContext(), dayItem.date);
        renderOverview(ov);
    }

    private void renderOverview(DailyStatsProvider.DayOverview ov) {
        // Capitalize the first letter of display date (e.g. "Quinta-feira, 10 de setembro")
        if (ov.displayDate != null && !ov.displayDate.isEmpty()) {
            String capDate = Character.toUpperCase(ov.displayDate.charAt(0)) + ov.displayDate.substring(1);
            navDateLabel.setText(capDate);
        } else {
            navDateLabel.setText("");
        }

        // 3-Column Middle Section
        renderConsumptionCard(ov);
        renderBatteryCard(ov);
        renderDynamicsCard(ov);

        // Bottom Section: Sessions
        renderSessions(ov);
    }

    private void renderConsumptionCard(DailyStatsProvider.DayOverview ov) {
        Context c = getContext();
        consumptionCard.removeAllViews();

        // Header: "Consumo & Eficiência"
        TextView title = new TextView(c);
        title.setText("Consumo & Eficiência");
        title.setTextColor(Style.TEXT_DIM);
        title.setTextSize(14);
        title.setTypeface(null, Typeface.BOLD);
        consumptionCard.addView(title);
        Style.gap(consumptionCard, c, 8);

        // Hero Metric: 12.8 kWh/100 km (or — if efficiency <= 0)
        CharSequence heroVal = ov.efficiencyKwh100km > 0
                ? valueWithUnit(String.format(Locale.US, "%.1f", ov.efficiencyKwh100km), null, "kWh/100 km", HERO_UNIT_SCALE)
                : "—";
        consumptionCard.addView(createHeroView(c, heroVal, "Eficiência Média"));
        Style.gap(consumptionCard, c, 14);

        // Every secondary number gets its own chip, distributed across the
        // card instead of stacked one composited row per line.
        boolean hasDischarge = ov.dischargeKwh > 0;
        boolean hasRegen = ov.regenKwh > 0;

        CharSequence netVal;
        if (ov.netKwh > 0) {
            netVal = valueWithUnit(String.format(Locale.US, "%.1f", ov.netKwh), null, "kWh", ROW_UNIT_SCALE);
        } else if (hasDischarge) {
            netVal = valueWithUnit(String.format(Locale.US, "%.1f", ov.dischargeKwh), null, "kWh", ROW_UNIT_SCALE);
        } else if (hasRegen) {
            netVal = valueWithUnit(String.format(Locale.US, "%.1f", ov.netKwh), null, "kWh", ROW_UNIT_SCALE);
        } else {
            netVal = "—";
        }
        CharSequence gastoVal = hasDischarge
                ? valueWithUnit(String.format(Locale.US, "%.1f", ov.dischargeKwh), null, "kWh", ROW_UNIT_SCALE)
                : "—";
        CharSequence regenVal = hasRegen
                ? valueWithUnit(String.format(Locale.US, "+%.1f", ov.regenKwh), Style.COOL, "kWh", ROW_UNIT_SCALE)
                : "—";

        List<View> chips = new ArrayList<>();
        chips.add(metricChip(c, "Líquido", netVal));
        chips.add(metricChip(c, "Gasto", gastoVal));
        chips.add(metricChip(c, "Regen", regenVal));
        distributeInPairs(c, consumptionCard, chips, 10);
    }

    private void renderBatteryCard(DailyStatsProvider.DayOverview ov) {
        Context c = getContext();
        batteryCard.removeAllViews();

        // Header: "Bateria & Recargas"
        TextView title = new TextView(c);
        title.setText("Bateria & Recargas");
        title.setTextColor(Style.TEXT_DIM);
        title.setTextSize(14);
        title.setTypeface(null, Typeface.BOLD);
        batteryCard.addView(title);
        Style.gap(batteryCard, c, 8);

        // Hero Metric: 59% → 96% (or —)
        CharSequence socSwing = (ov.firstBatteryPct >= 0 && ov.lastBatteryPct >= 0)
                ? percentRange(ov.firstBatteryPct, ov.lastBatteryPct, HERO_UNIT_SCALE)
                : "—";
        batteryCard.addView(createHeroView(c, socSwing, "Variação de SoC"));
        Style.gap(batteryCard, c, 14);

        // Energia Carregada used to fold the recharge count into the same
        // value ("+14.2 kWh (1 recarga)"); Temp. Exterior folded in the
        // minimum SoC the same way. Each number now gets its own chip.
        CharSequence chargeVal = ov.chargeKwh > 0
                ? valueWithUnit(String.format(Locale.US, "+%.1f", ov.chargeKwh), null, "kWh", ROW_UNIT_SCALE)
                : "0.0 kWh";
        CharSequence countVal = String.valueOf(ov.chargeCount);
        CharSequence tempVal = valueWithUnit(String.format(Locale.US, "%.1f", ov.avgTempC), null, "°C", ROW_UNIT_SCALE);
        CharSequence minSocVal = ov.minBatteryPct >= 0
                ? valueWithUnit(String.valueOf(ov.minBatteryPct), null, "%", ROW_UNIT_SCALE)
                : "—";

        List<View> chips = new ArrayList<>();
        chips.add(metricChip(c, "Energia Carregada", chargeVal));
        chips.add(metricChip(c, "Recargas", countVal));
        chips.add(metricChip(c, "Temp. Exterior", tempVal));
        chips.add(metricChip(c, "SoC Mínimo", minSocVal));
        distributeInPairs(c, batteryCard, chips, 10);
    }

    private void renderDynamicsCard(DailyStatsProvider.DayOverview ov) {
        Context c = getContext();
        dynamicsCard.removeAllViews();

        // Header: "Dinâmica & Percurso"
        TextView title = new TextView(c);
        title.setText("Dinâmica & Percurso");
        title.setTextColor(Style.TEXT_DIM);
        title.setTextSize(14);
        title.setTypeface(null, Typeface.BOLD);
        dynamicsCard.addView(title);
        Style.gap(dynamicsCard, c, 8);

        // Hero Metric: 11.1 km (Distância Total)
        CharSequence distStr = valueWithUnit(String.format(Locale.US, "%.1f", ov.distanceKm), null, "km", HERO_UNIT_SCALE);
        dynamicsCard.addView(createHeroView(c, distStr, "Distância Total"));
        Style.gap(dynamicsCard, c, 14);

        // Altimetria used to glue three numbers into one value
        // ("▲ +173 m / ▼ -211 m (Saldo: -38 m)"); Subida, Descida and Saldo
        // now each get their own chip, same as Tempo and Vel. Média.
        long dMin = Math.round(ov.drivingMinutes);
        CharSequence tempoVal = String.format(Locale.US, "%dh %02dm", dMin / 60, dMin % 60);
        CharSequence velVal = valueWithUnit(String.format(Locale.US, "%.0f", ov.avgSpeedKmh), null, "km/h", ROW_UNIT_SCALE);
        CharSequence subidaVal = valueWithUnit(String.format(Locale.US, "+%.0f", ov.ascentDPlusM), Style.HEAT, "m", ROW_UNIT_SCALE);
        CharSequence descidaVal = valueWithUnit(String.format(Locale.US, "-%.0f", ov.descentDMinusM), Style.COOL, "m", ROW_UNIT_SCALE);
        String signNet = ov.netElevationM >= 0 ? "+" : "";
        CharSequence saldoVal = valueWithUnit(String.format(Locale.US, "%s%.0f", signNet, ov.netElevationM), null, "m", ROW_UNIT_SCALE);

        List<View> chips = new ArrayList<>();
        chips.add(metricChip(c, "Tempo", tempoVal));
        chips.add(metricChip(c, "Vel. Média", velVal));
        chips.add(metricChip(c, "Subida", subidaVal));
        chips.add(metricChip(c, "Descida", descidaVal));
        chips.add(metricChip(c, "Saldo", saldoVal));
        distributeInPairs(c, dynamicsCard, chips, 10);
    }

    // Unit text (kWh, km, %, ...) always renders smaller, lighter-weight and
    // dimmer than the number it follows, so the number is what the eye lands
    // on first. Hero numbers are big enough that their unit can shrink more;
    // sub-metric rows are already small, so their unit shrinks less.
    private static final float HERO_UNIT_SCALE = 0.55f;
    private static final float ROW_UNIT_SCALE = 0.78f;

    private View createHeroView(Context c, CharSequence value, String subCaption) {
        LinearLayout hero = new LinearLayout(c);
        hero.setOrientation(LinearLayout.VERTICAL);

        TextView valTv = new TextView(c);
        valTv.setText(value);
        valTv.setTextColor(Style.TEXT);
        valTv.setTextSize(26);
        valTv.setTypeface(null, Typeface.BOLD);
        hero.addView(valTv);

        TextView capTv = new TextView(c);
        capTv.setText(subCaption);
        capTv.setTextColor(Style.TEXT_DIM);
        capTv.setTextSize(12);
        capTv.setPadding(0, Style.dp(c, 2), 0, 0);
        hero.addView(capTv);

        return hero;
    }

    /**
     * A single metric as its own small tile: dim label on top, bold value
     * below. Faint fill/stroke instead of a full bordered card — several of
     * these sit together inside one metric card without fighting each other
     * for attention.
     */
    private View metricChip(Context c, String label, CharSequence value) {
        LinearLayout chip = new LinearLayout(c);
        chip.setOrientation(LinearLayout.VERTICAL);
        int padH = Style.dp(c, 10), padV = Style.dp(c, 8);
        chip.setPadding(padH, padV, padH, padV);
        chip.setBackground(Style.tile(c));

        TextView labelTv = new TextView(c);
        labelTv.setText(label);
        labelTv.setTextColor(Style.TEXT_DIM);
        labelTv.setTextSize(11.5f);
        chip.addView(labelTv);

        TextView valueTv = new TextView(c);
        valueTv.setTextColor(Style.TEXT);
        valueTv.setTextSize(16);
        valueTv.setTypeface(null, Typeface.BOLD);
        valueTv.setText(value);
        valueTv.setPadding(0, Style.dp(c, 2), 0, 0);
        chip.addView(valueTv);

        return chip;
    }

    /** Puts an MDI chevron on a nav button (left of text if onLeft, else right), tinted to match the label. */
    private void setNavIcon(TextView btn, int drawableRes, boolean onLeft) {
        Context c = btn.getContext();
        android.graphics.drawable.Drawable icon = c.getDrawable(drawableRes).mutate();
        int size = Style.dp(c, 18);
        icon.setBounds(0, 0, size, size);
        icon.setTint(btn.getCurrentTextColor());
        btn.setCompoundDrawables(onLeft ? icon : null, null, onLeft ? null : icon, null);
        btn.setCompoundDrawablePadding(Style.dp(c, 8));
    }

    /** Lays views out two per row, equal width, with a small gap — used to spread metric chips across a card. */
    private static void distributeInPairs(Context c, LinearLayout container, List<View> items, int gapDp) {
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = Style.dp(c, gapDp);
                row.setLayoutParams(rlp);
                container.addView(row);
            }
            View v = items.get(i);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 2 == 0) vlp.rightMargin = Style.dp(c, gapDp);
            v.setLayoutParams(vlp);
            row.addView(v);

            boolean rowEnd = (i % 2 == 1) || (i == items.size() - 1);
            if (rowEnd && row.getChildCount() == 1) {
                // Odd one out: an invisible spacer keeps it half-width like the rest.
                View spacer = new View(c);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
                row.addView(spacer);
            }
        }
    }

    /** number, optionally colored, followed by a smaller/lighter/dimmer unit. */
    private static CharSequence valueWithUnit(String number, Integer numberColor, String unit, float unitScale) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int vStart = sb.length();
        sb.append(number);
        if (numberColor != null) {
            sb.setSpan(new ForegroundColorSpan(numberColor), vStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (unit != null && !unit.isEmpty()) {
            appendUnit(sb, " " + unit, unitScale);
        }
        return sb;
    }

    /** "59% → 95%" with both % signs de-emphasized the same way as a trailing unit. */
    private static CharSequence percentRange(int from, int to, float unitScale) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(String.valueOf(from));
        appendUnit(sb, "%", unitScale);
        sb.append(" → ").append(String.valueOf(to));
        appendUnit(sb, "%", unitScale);
        return sb;
    }

    /** Appends text as smaller, normal-weight and dim — used for units and parenthetical asides. */
    private static void appendUnit(SpannableStringBuilder sb, String text, float scale) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new RelativeSizeSpan(scale), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.NORMAL), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(Style.TEXT_DIM), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void renderSessions(DailyStatsProvider.DayOverview ov) {
        Context c = getContext();
        sessionsContainer.removeAllViews();
        if (ov.sessions.isEmpty()) {
            TextView empty = Style.label(c, "Nenhuma viagem ou recarga registrada nesta data.");
            empty.setPadding(0, Style.dp(c, 8), 0, 0);
            sessionsContainer.addView(empty);
            return;
        }

        // Newest (and any in-progress) session first, not last.
        List<DailyStatsProvider.DaySession> ordered = new ArrayList<>(ov.sessions);
        Collections.reverse(ordered);

        // A single column, capped at a comfortable reading width (one third
        // of the screen) instead of stretching each row edge to edge.
        int colWidth = Style.dp(c, 640);
        for (int i = 0; i < ordered.size(); i++) {
            DailyStatsProvider.DaySession s = ordered.get(i);
            View card = buildSessionCard(s);
            card.setLayoutParams(new LinearLayout.LayoutParams(colWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
            sessionsContainer.addView(card);

            // Parked time between this session's start and the next older
            // session's end — a plain "skip" row, not another card.
            if (i < ordered.size() - 1) {
                long gapMs = s.startMs - ordered.get(i + 1).endMs;
                View gapRow = buildGapRow(gapMs, colWidth);
                if (gapRow != null) sessionsContainer.addView(gapRow);
            }
            Style.gap(sessionsContainer, c, 10);
        }
    }

    /**
     * A plain time-elapsed row for the parked gap between two sessions.
     * Sessions come from the DB already correctly segmented (trip/charge
     * qualification and park debounce happened upstream when they were
     * recorded), so the gap between two consecutive sessions' timestamps is
     * trustworthy as-is — no threshold or filtering of our own to apply here.
     */
    private View buildGapRow(long gapMs, int width) {
        if (gapMs <= 0) return null;
        Context c = getContext();

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setPadding(Style.dp(c, 4), Style.dp(c, 4), Style.dp(c, 4), Style.dp(c, 4));

        android.widget.ImageView icon = new android.widget.ImageView(c);
        icon.setImageResource(R.drawable.ic_parking);
        icon.setColorFilter(Style.TEXT_DIM, android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Style.dp(c, 16), Style.dp(c, 16));
        ilp.rightMargin = Style.dp(c, 8);
        icon.setLayoutParams(ilp);
        row.addView(icon);

        TextView tv = new TextView(c);
        tv.setTextColor(Style.TEXT_DIM);
        tv.setTextSize(12.5f);
        tv.setText("Estacionado " + formatGapDuration(gapMs));
        row.addView(tv);

        return row;
    }

    private static String formatGapDuration(long ms) {
        long totalMin = ms / 60_000L;
        long h = totalMin / 60;
        long m = totalMin % 60;
        return h > 0 ? String.format(Locale.US, "%dh %02dmin", h, m) : String.format(Locale.US, "%dmin", m);
    }

    private View buildSessionCard(DailyStatsProvider.DaySession session) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(c, 14);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Style.card(Style.cardFillColor(), c));

        if (session.isTrip()) {
            buildDriveSessionCard(card, (DailyStatsProvider.DriveSession) session);
        } else {
            buildChargeSessionCard(card, (DailyStatsProvider.ChargeSessionItem) session);
        }

        return card;
    }

    private void buildDriveSessionCard(LinearLayout card, DailyStatsProvider.DriveSession t) {
        Context c = getContext();

        // Line 1: Left [Badge pill + time]   Right [10.0 km • 10.5 kWh/100km]
        LinearLayout line1 = new LinearLayout(c);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);

        // Left container (Badge + Time)
        LinearLayout leftBox = new LinearLayout(c);
        leftBox.setOrientation(LinearLayout.HORIZONTAL);
        leftBox.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftBox.setLayoutParams(leftLp);

        // Trip marker: an MDI vector icon (readable and tintable, unlike the
        // emoji it replaces). Whether it's still going is already said by
        // the time text below ("... – em andamento").
        android.widget.ImageView badge = new android.widget.ImageView(c);
        badge.setImageResource(R.drawable.ic_car);
        badge.setColorFilter(Style.TEXT_DIM, android.graphics.PorterDuff.Mode.SRC_IN);
        badge.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(c, 20), Style.dp(c, 20)));
        leftBox.addView(badge);

        // Time range: 08:18 – 09:05 (48m)
        TextView timeView = new TextView(c);
        timeView.setTextColor(Style.TEXT_DIM);
        timeView.setTextSize(13);
        timeView.setText(t.timeLabel + " (" + t.durationLabel + ")");
        timeView.setPadding(Style.dp(c, 10), 0, 0, 0);
        leftBox.addView(timeView);

        line1.addView(leftBox);

        // Right metric: 10.0 km • 10.5 kWh/100km (bold, Style.TEXT, dim/small units)
        TextView rightMetric = new TextView(c);
        rightMetric.setTextColor(Style.TEXT);
        rightMetric.setTextSize(14);
        rightMetric.setTypeface(null, Typeface.BOLD);
        SpannableStringBuilder rightSb = new SpannableStringBuilder();
        rightSb.append(valueWithUnit(String.format(Locale.US, "%.1f", t.distanceKm), null, "km", ROW_UNIT_SCALE));
        rightSb.append("  •  ");
        if (t.efficiencyKwh100km > 0) {
            rightSb.append(valueWithUnit(String.format(Locale.US, "%.1f", t.efficiencyKwh100km), null, "kWh/100km", ROW_UNIT_SCALE));
        } else {
            rightSb.append("—");
        }
        rightMetric.setText(rightSb);
        line1.addView(rightMetric);

        card.addView(line1);

        // Line 2: SoC: 100% → 97% | ▲+163m ▼-189m | Consumo: 1.0 kWh (Regen +0.7)
        TextView line2 = new TextView(c);
        line2.setTextSize(12.5f);
        line2.setPadding(0, Style.dp(c, 6), 0, 0);

        SpannableStringBuilder l2Sb = new SpannableStringBuilder();

        // SoC: 100% → 97%
        String socLabel = (t.socStart >= 0 && t.socEnd >= 0)
                ? (t.socStart + "% → " + t.socEnd + "%")
                : (t.socStart >= 0 ? (t.socStart + "%") : "—");
        appendMetadata(l2Sb, "SoC: ", socLabel, Style.TEXT);

        // Divider
        appendDivider(l2Sb);

        // Altimetria: ▲+163m ▼-189m
        int altStart = l2Sb.length();
        l2Sb.append(String.format(Locale.US, "▲+%.0fm", t.ascentDPlusM));
        l2Sb.setSpan(new ForegroundColorSpan(Style.HEAT), altStart, l2Sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        l2Sb.append(" ");

        int descStart = l2Sb.length();
        l2Sb.append(String.format(Locale.US, "▼-%.0fm", t.descentDMinusM));
        l2Sb.setSpan(new ForegroundColorSpan(Style.COOL), descStart, l2Sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Divider
        appendDivider(l2Sb);

        // Consumo: 1.0 kWh (Regen +0.7)
        String netStr = (t.spentKwh > 0 || t.regenKwh > 0)
                ? String.format(Locale.US, "%.1f kWh", t.energyKwh)
                : (t.distanceKm > 0 ? "0.0 kWh" : "—");
        appendMetadata(l2Sb, "Consumo: ", netStr, Style.TEXT);

        if (t.regenKwh > 0) {
            l2Sb.append(" (Regen ");
            int rStart = l2Sb.length();
            l2Sb.append(String.format(Locale.US, "+%.1f", t.regenKwh));
            l2Sb.setSpan(new ForegroundColorSpan(Style.COOL), rStart, l2Sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            l2Sb.append(")");
        }

        line2.setTextColor(Style.TEXT_DIM);
        line2.setText(l2Sb);
        card.addView(line2);
    }

    private void buildChargeSessionCard(LinearLayout card, DailyStatsProvider.ChargeSessionItem ch) {
        Context c = getContext();

        // Line 1: Left [Badge pill + time]   Right [+14.2 kWh]
        LinearLayout line1 = new LinearLayout(c);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);

        // Left container
        LinearLayout leftBox = new LinearLayout(c);
        leftBox.setOrientation(LinearLayout.HORIZONTAL);
        leftBox.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftBox.setLayoutParams(leftLp);

        // Charge marker: an MDI vector icon (station bolt for DC fast
        // charge, plug for AC) instead of an emoji that doesn't render
        // cleanly on this display. Whether it's still going is already
        // said by the time text below ("... – em andamento").
        android.widget.ImageView badge = new android.widget.ImageView(c);
        badge.setImageResource(ch.isDcfc ? R.drawable.ic_ev_station : R.drawable.ic_power_plug);
        badge.setColorFilter(ch.isDcfc ? Style.HEAT : 0xFF4CAF50, android.graphics.PorterDuff.Mode.SRC_IN);
        badge.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(c, 20), Style.dp(c, 20)));
        leftBox.addView(badge);

        // Time range: 01:20 – 04:42 (3h 22m)
        TextView timeView = new TextView(c);
        timeView.setTextColor(Style.TEXT_DIM);
        timeView.setTextSize(13);
        timeView.setText(ch.timeLabel + " (" + ch.durationLabel + ")");
        timeView.setPadding(Style.dp(c, 10), 0, 0, 0);
        leftBox.addView(timeView);

        line1.addView(leftBox);

        // Right: +14.2 kWh (bold green/cool or heat text, dim/small unit)
        TextView rightMetric = new TextView(c);
        int metricColor = ch.isDcfc ? Style.HEAT : (Style.LIGHT ? 0xFF2E7D32 : 0xFF4CAF50);
        rightMetric.setTextSize(15);
        rightMetric.setTypeface(null, Typeface.BOLD);
        rightMetric.setText(valueWithUnit(String.format(Locale.US, "+%.1f", ch.kwh), metricColor, "kWh", ROW_UNIT_SCALE));
        line1.addView(rightMetric);

        card.addView(line1);

        // Line 2: SoC: 66% → 100% | Potência Méd: 4.2 kW | Custo: R$ 0,00 (or Custo não informado)
        TextView line2 = new TextView(c);
        line2.setTextSize(12.5f);
        line2.setPadding(0, Style.dp(c, 6), 0, 0);

        SpannableStringBuilder l2Sb = new SpannableStringBuilder();

        // SoC: 66% → 100%
        String socLabel = (ch.socStart >= 0 && ch.socEnd >= 0)
                ? (ch.socStart + "% → " + ch.socEnd + "%")
                : (ch.socStart >= 0 ? (ch.socStart + "%") : "—");
        appendMetadata(l2Sb, "SoC: ", socLabel, Style.TEXT);

        // Divider
        appendDivider(l2Sb);

        // Potência Méd: 4.2 kW
        String pwrStr = String.format(Locale.US, "%.1f kW", ch.avgPowerKw);
        appendMetadata(l2Sb, "Potência Méd: ", pwrStr, Style.TEXT);

        // Divider
        appendDivider(l2Sb);

        // Custo: R$ 0,00 or Custo não informado
        if (ch.cost != null && ch.cost >= 0) {
            String costStr = String.format(Locale.getDefault(), "R$ %.2f", ch.cost);
            appendMetadata(l2Sb, "Custo: ", costStr, Style.ACCENT);
        } else {
            int cStart = l2Sb.length();
            l2Sb.append("Custo não informado");
            l2Sb.setSpan(new ForegroundColorSpan(Style.TEXT_DIM), cStart, l2Sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        line2.setTextColor(Style.TEXT_DIM);
        line2.setText(l2Sb);
        card.addView(line2);

        // Retain click listener on completed charging session card to open ChargeCostDialog,
        // preserving the Park-only safety check (CarState.isParked()).
        if (ch.endMs > 0 && ch.chargeId > 0) {
            card.setOnClickListener(v -> {
                if (!CarState.isParked()) {
                    Toast.makeText(getContext(), R.string.charge_cost_parked_only, Toast.LENGTH_SHORT).show();
                    return;
                }
                ChargeCostDialog.show(
                        getContext(), ch.chargeId, ch.kwh, ch.socStart, ch.socEnd, ch.cost, this::refresh);
            });
        }
    }

    private static void appendMetadata(SpannableStringBuilder sb, String label, String value, int valueColor) {
        int lStart = sb.length();
        sb.append(label);
        sb.setSpan(new ForegroundColorSpan(Style.TEXT_DIM), lStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        int vStart = sb.length();
        sb.append(value);
        sb.setSpan(new ForegroundColorSpan(valueColor), vStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void appendDivider(SpannableStringBuilder sb) {
        int dStart = sb.length();
        sb.append("  ·  ");
        sb.setSpan(new ForegroundColorSpan(Style.TEXT_DIM), dStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
