package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.util.Style;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Daily gross energy: spent below zero, regen followed by AC and DC above zero. */
public final class EnergyBalanceChart extends LinearLayout {
    public static final class Day {
        public final String label;
        public double spent, regen, ac, dc;

        public Day(String label) { this.label = label; }
    }

    private final BarChart chart;
    private final TextView detail;
    private List<Day> days = new ArrayList<>();

    public EnergyBalanceChart(Context c) {
        super(c);
        setOrientation(VERTICAL);
        int pad = Style.dp(c, 16);
        setPadding(pad, pad, pad, pad);
        setBackground(Style.card(Style.cardFillColor(), c));
        TextView title = Style.label(c, c.getString(R.string.charge_balance_title));
        title.setTextSize(14);
        title.setTypeface(null, Typeface.BOLD);
        addView(title);
        chart = new BarChart(c);
        chart.getDescription().setEnabled(false);
        chart.setDrawGridBackground(false);
        chart.setDrawBorders(false);
        chart.setDoubleTapToZoomEnabled(false);
        chart.setScaleEnabled(false);
        chart.setPinchZoom(false);
        chart.setFitBars(true);
        chart.getAxisRight().setEnabled(false);
        YAxis y = chart.getAxisLeft();
        y.setTextColor(Style.TEXT_DIM);
        y.setTextSize(13);
        y.setGridColor(Style.blend(Style.CARD, Style.TEXT_DIM, .25f));
        y.setDrawAxisLine(false);
        y.setDrawZeroLine(true);
        y.setZeroLineColor(Style.TEXT_DIM);
        y.setZeroLineWidth(1.2f);
        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setDrawAxisLine(false);
        x.setTextColor(Style.TEXT_DIM);
        x.setTextSize(13);
        x.setGranularity(1f);
        x.setLabelCount(10);
        chart.getLegend().setTextColor(Style.TEXT_DIM);
        chart.getLegend().setTextSize(14);
        chart.getLegend().setXEntrySpace(18);
        chart.setExtraOffsets(4, 10, 8, 8);
        chart.setOnChartValueSelectedListener(new OnChartValueSelectedListener() {
            @Override public void onValueSelected(Entry e, Highlight h) {
                int i = Math.round(e.getX());
                if (i >= 0 && i < days.size()) showDay(days.get(i));
            }
            @Override public void onNothingSelected() {
                detail.setText(R.string.charge_balance_hint);
            }
        });
        addView(chart, new LayoutParams(LayoutParams.MATCH_PARENT, Style.dp(c, 250)));
        detail = Style.label(c, c.getString(R.string.charge_balance_hint));
        detail.setTextSize(14);
        addView(detail);
    }

    public void setDays(List<Day> values) {
        days = new ArrayList<>(values);
        List<BarEntry> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        float maxIn = 1, maxOut = 1;
        for (int i = 0; i < days.size(); i++) {
            Day d = days.get(i);
            entries.add(new BarEntry(i, new float[]{-(float) d.spent, (float) d.regen,
                    (float) d.ac, (float) d.dc}));
            labels.add(d.label);
            maxIn = Math.max(maxIn, (float) (d.regen + d.ac + d.dc));
            maxOut = Math.max(maxOut, (float) d.spent);
        }
        BarDataSet set = new BarDataSet(entries, "");
        set.setStackLabels(new String[]{getContext().getString(R.string.charge_balance_spent),
                getContext().getString(R.string.charge_balance_regen), "AC", "DC"});
        set.setColors(Style.HEAT, Style.COOL, Style.ACCENT, 0xFFAB8CFF);
        set.setDrawValues(false);
        BarData data = new BarData(set);
        data.setBarWidth(.7f);
        chart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        chart.getAxisLeft().setAxisMinimum(-maxOut * 1.15f);
        chart.getAxisLeft().setAxisMaximum(maxIn * 1.15f);
        chart.setData(data);
        chart.highlightValues(null);
        detail.setText(R.string.charge_balance_hint);
        chart.invalidate();
    }

    private void showDay(Day d) {
        detail.setText(String.format(Locale.getDefault(), "%s  ·  %s −%.2f  ·  %s +%.2f  ·  AC +%.2f  ·  DC +%.2f kWh",
                d.label, getContext().getString(R.string.charge_balance_spent), d.spent,
                getContext().getString(R.string.charge_balance_regen), d.regen, d.ac, d.dc));
    }
}
