package com.geely.drivemem.ui;

import android.app.Dialog;
import android.content.Context;
import android.database.Cursor;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.util.Style;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.util.ArrayList;
import java.util.List;

/** Parked-only detail showing measured charging power (and outside/battery
 * temperature, where available) over one historical session. Dismiss by
 * tapping outside -- no OK button, per the 2026-09-13 style pass (a
 * full-width button here was disproportionate to what this dialog needs). */
public final class ChargeCurrentCurveDialog {
    // AC and DC have very different real ceilings (AC onboard charger: 6.6kW;
    // DC fast charge: 70kW) -- one shared 80kW axis made every AC session's
    // curve a flat sliver along the bottom. Each type gets its own fixed
    // axis instead, still comparable session-to-session within its own type.
    private static final float POWER_AXIS_MAX_KW_AC = 8f;
    private static final float POWER_AXIS_MAX_KW_DC = 80f;

    public static void show(Context c, ChargeSession.Summary session) {
        Dialog dialog = new Dialog(c);
        dialog.setCanceledOnTouchOutside(true);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(c, 24);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(Style.card(Style.cardFillColor(), c));
        TextView title = Style.title(c, c.getString(R.string.charge_curve_title));
        root.addView(title);

        Curves curves = read(c, session);
        if (curves.power.isEmpty()) {
            TextView empty = Style.label(c, c.getString(R.string.charge_curve_unavailable));
            empty.setPadding(0, Style.dp(c, 36), 0, Style.dp(c, 36));
            root.addView(empty);
        } else {
            boolean hasTemp = !curves.outsideTemp.isEmpty() || !curves.batteryTemp.isEmpty();
            LineChart chart = new LineChart(c);
            chart.getXAxis().setTypeface(Style.font(c));
            chart.getAxisLeft().setTypeface(Style.font(c));
            chart.getAxisRight().setTypeface(Style.font(c));
            chart.getLegend().setTypeface(Style.font(c));
            chart.getDescription().setEnabled(false);
            chart.setScaleEnabled(false);
            chart.setDoubleTapToZoomEnabled(false);

            Legend legend = chart.getLegend();
            legend.setEnabled(hasTemp); // one series needs no legend to identify it
            legend.setTextColor(Style.TEXT_DIM);

            YAxis left = chart.getAxisLeft();
            left.setAxisMinimum(0);
            left.setAxisMaximum(session.isDcfc() ? POWER_AXIS_MAX_KW_DC : POWER_AXIS_MAX_KW_AC);
            left.setTextColor(Style.TEXT_DIM);
            left.setGridColor(Style.blend(Style.CARD, Style.TEXT_DIM, .2f));
            left.setValueFormatter(new ValueFormatter() {
                @Override public String getFormattedValue(float v) { return (int) v + " kW"; }
            });

            YAxis right = chart.getAxisRight();
            right.setEnabled(hasTemp);
            right.setTextColor(Style.TEXT_DIM);
            right.setDrawGridLines(false);
            if (hasTemp) {
                // No fixed range here, unlike power: the car's 70kW ceiling is a
                // real, universal reference to compare sessions against, but
                // outside temperature has no such fixed ceiling and this app
                // isn't Brazil-only, so a hardcoded range would be arbitrary.
                //
                // The floor is the coldest outside temperature this car's own
                // history has ever recorded, not this session's own minimum --
                // a session-relative floor stretched a narrow in-session swing
                // (often under 1 degree) to fill the whole chart height, which
                // read as far more dramatic than it was. Anchoring to a stable,
                // real, data-driven cold reference fixes that directly: a mild
                // session sits near the top of a tall axis instead of owning
                // the whole plot. The ceiling stays session-relative (this
                // session's own max + headroom) so *this* session's peak is
                // still clearly legible, not flattened by an all-time hot day.
                float tMin = Float.MAX_VALUE, tMax = -Float.MAX_VALUE;
                for (Entry e : curves.outsideTemp) {
                    tMin = Math.min(tMin, e.getY());
                    tMax = Math.max(tMax, e.getY());
                }
                for (Entry e : curves.batteryTemp) {
                    tMin = Math.min(tMin, e.getY());
                    tMax = Math.max(tMax, e.getY());
                }
                float floor = Math.min(tMin, globalMinOutsideTempC(c, tMin));
                float topPad = Math.max(1f, (tMax - tMin) * 0.10f);
                right.setAxisMinimum(floor - 1f);
                right.setAxisMaximum(tMax + topPad);
            }
            // One decimal place: outside temperature barely moves across one
            // charging session (a couple of degrees at most), and integer
            // labels on a range that narrow used to round several consecutive
            // gridlines to the same number (e.g. six ticks all reading "29°C").
            right.setLabelCount(5, false);
            right.setValueFormatter(new ValueFormatter() {
                @Override public String getFormattedValue(float v) { return String.format(java.util.Locale.US, "%.1f°C", v); }
            });

            XAxis x = chart.getXAxis();
            x.setPosition(XAxis.XAxisPosition.BOTTOM);
            x.setTextColor(Style.TEXT_DIM);
            x.setDrawGridLines(false);
            x.setValueFormatter(new ValueFormatter() {
                @Override public String getFormattedValue(float v) { return (int) v + " min"; }
            });

            LineDataSet powerSet = new LineDataSet(curves.power, c.getString(R.string.charge_curve_power_label));
            powerSet.setColor(Style.ACCENT);
            powerSet.setLineWidth(2.5f);
            powerSet.setDrawCircles(false);
            powerSet.setDrawValues(false);
            powerSet.setMode(LineDataSet.Mode.LINEAR);
            powerSet.setAxisDependency(YAxis.AxisDependency.LEFT);

            List<ILineDataSet> sets = new ArrayList<>();
            sets.add(powerSet);
            if (!curves.outsideTemp.isEmpty()) {
                LineDataSet tempSet = new LineDataSet(curves.outsideTemp, c.getString(R.string.charge_curve_temp_label));
                tempSet.setColor(Style.HEAT);
                tempSet.setLineWidth(2f);
                tempSet.setDrawCircles(false);
                tempSet.setDrawValues(false);
                tempSet.setMode(LineDataSet.Mode.LINEAR);
                tempSet.setAxisDependency(YAxis.AxisDependency.RIGHT);
                sets.add(tempSet);
            }
            if (!curves.batteryTemp.isEmpty()) {
                LineDataSet battTempSet = new LineDataSet(curves.batteryTemp, c.getString(R.string.charge_curve_batt_temp_label));
                battTempSet.setColor(Style.GOOD);
                battTempSet.setLineWidth(2f);
                battTempSet.setDrawCircles(false);
                battTempSet.setDrawValues(false);
                battTempSet.setMode(LineDataSet.Mode.LINEAR);
                battTempSet.setAxisDependency(YAxis.AxisDependency.RIGHT);
                sets.add(battTempSet);
            }
            chart.setData(new LineData(sets));
            root.addView(chart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 330)));
        }
        dialog.setContentView(root);
        android.view.Window window = dialog.getWindow();
        if (window != null) window.setLayout(Style.dp(c, 1000), ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();
        if (window != null) window.setLayout(Style.dp(c, 1000), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Coldest outside temperature ever recorded, across all history -- not
     * scoped to any one session. Falls back to the given value (the current
     * session's own minimum) if the table has nothing, which only happens if
     * this session's own points were also somehow absent. */
    private static float globalMinOutsideTempC(Context c, float fallback) {
        Cursor cursor = CarDb.get(c).db().rawQuery(
            "SELECT MIN(outside_temp_c) FROM telemetry_sample WHERE outside_temp_c IS NOT NULL", null);
        try {
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getFloat(0);
        } finally { cursor.close(); }
        return fallback;
    }

    private static final class Curves {
        final List<Entry> power = new ArrayList<>();
        final List<Entry> outsideTemp = new ArrayList<>();
        // Only populated for sessions recorded after the CarDb v16 migration
        // added telemetry_sample.battery_temp_c (2026-09-13) -- older
        // sessions simply have nothing here, same as any other column that
        // didn't exist yet when they were recorded. Also empty whenever the
        // OBD2 dongle wasn't connected/enabled during charging, since that's
        // the only source for this value (see TelemetrySampler).
        final List<Entry> batteryTemp = new ArrayList<>();
    }

    private static Curves read(Context c, ChargeSession.Summary s) {
        Curves out = new Curves();
        Cursor cursor = CarDb.get(c).db().rawQuery(
            "SELECT ts_ms, charge_a, charge_v, outside_temp_c, battery_temp_c FROM telemetry_sample "
          + "WHERE ts_ms BETWEEN ? AND ? ORDER BY ts_ms ASC",
            new String[]{String.valueOf(s.startWallMs), String.valueOf(s.endWallMs)});
        try {
            while (cursor.moveToNext()) {
                float minutes = (cursor.getLong(0) - s.startWallMs) / 60_000f;
                if (!cursor.isNull(1) && !cursor.isNull(2) && cursor.getFloat(1) >= 0) {
                    float kw = cursor.getFloat(1) * cursor.getFloat(2) / 1000f;
                    out.power.add(new Entry(minutes, kw));
                }
                if (!cursor.isNull(3)) {
                    out.outsideTemp.add(new Entry(minutes, cursor.getFloat(3)));
                }
                if (!cursor.isNull(4)) {
                    out.batteryTemp.add(new Entry(minutes, cursor.getFloat(4)));
                }
            }
        } finally { cursor.close(); }
        return out;
    }

    private ChargeCurrentCurveDialog() {}
}
