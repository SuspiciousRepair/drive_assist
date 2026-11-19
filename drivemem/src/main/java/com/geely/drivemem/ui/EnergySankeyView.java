package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

import com.geely.drivemem.R;
import com.geely.drivemem.util.EnergyUnits;
import com.geely.drivemem.util.Style;

import java.util.Locale;

/** Where the energy came from and where it went, as a Sankey:
 *
 *   Battery ─┐
 *            ├─ Used ─┬─ Drive
 *   Regen ───┘        ├─ Climate
 *                     └─ Other
 *
 * Battery is what the battery gave (the trip's net). Regen is what the
 * motor sent back while braking, which the car used again. Both are
 * sources: Used = battery + regen. Drive is the motor's whole energy out,
 * so the two sides add up: drive + climate + other = battery + regen.
 *
 * Regen here is the MOTOR's number, not the battery's (the trip's
 * regen_kwh). The motor sends back more than the battery gets: part of it
 * never reaches the battery. With the battery's number, Drive would have
 * to be a made-up figure for the chart to add up (EnergySplit).
 *
 * Every band is to scale (thickness = kWh, one scale for the whole chart).
 * No minimum thickness, on purpose: a small part stays thin. Only the gaps
 * between the right-hand parts and the label positions move, so labels never
 * overlap; a label that moved gets a thin leader line to its band.
 *
 * One number per part, in one unit at a time: kWh, Wh/km (over the km the
 * trips covered) or % of Used. Each label is the name (regular), the
 * number (bold) and the unit (small, dim) right after it. A tap goes to
 * the next unit. The choice is kept (Prefs) and is the same on the trip
 * screen and the stats page. */
final class EnergySankeyView extends View {
    private double used, battery, regen, drive, climate, other, km;
    private boolean hasData;

    static final int UNIT_KWH = 0, UNIT_WH_KM = 1, UNIT_PCT = 2;
    static final String[] UNIT_NAMES = {"kWh", EnergyUnits.WH_PER_KM, "%"};
    private static final String PREF_UNIT = "sankey_unit";
    private int unit;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint name = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final String sUsed, sBattery, sRegen;
    private final String[] partNames;

    // Layout, in px (dp = px on this panel, see CLAUDE.md).
    private final float chartH, top, nodeW, midGap, rightGap, labelGap, labelRow;

    EnergySankeyView(Context c) {
        super(c);
        chartH = Style.dp(c, 140);
        top = Style.dp(c, 30);
        nodeW = Style.dp(c, 8);
        midGap = Style.dp(c, 32);
        rightGap = Style.dp(c, 12);
        labelGap = Style.dp(c, 26);
        labelRow = Style.dp(c, 26);
        bold.setTextSize(Style.dp(c, 17));
        bold.setTypeface(Typeface.DEFAULT_BOLD);
        dim.setTextSize(Style.dp(c, 14));
        name.setTextSize(Style.dp(c, 17));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Style.dp(c, 1));
        sUsed = c.getString(R.string.sankey_used);
        sBattery = c.getString(R.string.sankey_battery);
        sRegen = c.getString(R.string.sankey_regen);
        partNames = new String[]{c.getString(R.string.stats_part_drive),
            c.getString(R.string.stats_part_climate), c.getString(R.string.stats_part_other)};
        unit = com.geely.drivemem.util.Prefs.file(c).getInt(PREF_UNIT, UNIT_KWH);
        if (unit < 0 || unit >= UNIT_NAMES.length) unit = UNIT_KWH;
        setOnClickListener(v -> {
            unit = nextUnit(unit, km);
            com.geely.drivemem.util.Prefs.file(getContext()).edit().putInt(PREF_UNIT, unit).apply();
            invalidate();
        });
    }

    /** The unit after this one. Wh/km is skipped when there is no distance. */
    static int nextUnit(int unit, double km) {
        int n = (unit + 1) % UNIT_NAMES.length;
        if (n == UNIT_WH_KM && !(km > 0)) n = UNIT_PCT;
        return n;
    }

    /** The unit drawn: Wh/km falls back to kWh when there is no distance. */
    static int shownUnit(int unit, double km) {
        return unit == UNIT_WH_KM && !(km > 0) ? UNIT_KWH : unit;
    }

    /** One part's number in a unit, with no unit after it: "0.52", "124",
     * "37". Percent is of used. */
    static String value(double kwhValue, int unit, double used, double km) {
        switch (unit) {
            case UNIT_WH_KM: {
                double wh = EnergyUnits.whPerKm(kwhValue, km);
                return Double.isNaN(wh) ? "—" : EnergyUnits.wholeWh(wh);
            }
            case UNIT_PCT: {
                if (!(used > 0)) return "—";
                double p = kwhValue / used * 100;
                return p > 0 && p < 1 ? "<1" : String.format(Locale.US, "%.0f", p);
            }
            default:
                return kwh(kwhValue);
        }
    }

    /** kWh for each node, and the km the trips covered (for Wh/km).
     * battery + regen must equal drive + climate + other (EnergySplit's
     * batteryKwh, motorRegenKwh and motorOutKwh do). */
    void setData(double battery, double regen, double drive, double climate, double other, double km) {
        this.battery = Math.max(0, battery); this.regen = Math.max(0, regen);
        this.used = this.battery + this.regen;
        this.drive = drive; this.climate = climate; this.other = other; this.km = km;
        hasData = used > 0;
        requestLayout();
        invalidate();
    }

    static String kwh(double v) { return String.format(Locale.US, Math.abs(v) < 2 ? "%.2f" : "%.1f", v); }

    /** Label centres: each at its band's centre, pushed down until it is at
     * least {@code minGap} below the one above. Never moves a band. */
    static float[] labelYs(float[] centres, float minGap) {
        return labelYs(centres, minGap, -Float.MAX_VALUE);
    }

    /** The same, and no label above {@code minFirst}. */
    static float[] labelYs(float[] centres, float minGap, float minFirst) {
        float[] out = new float[centres.length];
        float prev = minFirst - minGap;
        for (int i = 0; i < centres.length; i++) {
            out[i] = Math.max(centres[i], prev + minGap);
            prev = out[i];
        }
        return out;
    }

    private float k() { return used > 0 ? (float) (chartH / used) : 0; }

    // Used and the parts start at the top. Battery and regen start one label
    // row lower, so Battery's label sits under Used's, never beside it.
    private float yLeft() { return top + labelRow; }
    private float hBattery() { return (float) (battery * k()); }
    private float hRegen() { return (float) (regen * k()); }
    private float yRegen() { return yLeft() + hBattery() + midGap; }

    private float[] partHeights() {
        float k = k();
        return new float[]{(float) Math.max(0, drive) * k, (float) Math.max(0, climate) * k, (float) Math.max(0, other) * k};
    }

    private float[] partCentres() {
        float[] h = partHeights();
        float y = top;
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) { c[i] = y + h[i] / 2; y += h[i] + rightGap; }
        return c;
    }

    // The first part's label stays clear of Used's label above the chart.
    private float[] partLabelYs() { return labelYs(partCentres(), labelGap, top + Style.dp(getContext(), 16)); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h;
        if (!hasData) {
            h = 0;
        } else {
            float[] ys = partLabelYs();
            float leftBottom = hRegen() > 0 ? yRegen() + hRegen() : yLeft() + hBattery();
            float rightBottom = top + chartH + 2 * rightGap;
            h = (int) Math.ceil(Math.max(Math.max(leftBottom, rightBottom), ys[2] + Style.dp(getContext(), 24)));
        }
        setMeasuredDimension(w, h);
    }

    // A smooth band from (x0, y0..y0+h) to (x1, y1..y1+h).
    private void band(Canvas cv, float x0, float y0, float x1, float y1, float h, int color) {
        if (h <= 0) return;
        float m = (x0 + x1) / 2;
        path.reset();
        path.moveTo(x0, y0);
        path.cubicTo(m, y0, m, y1, x1, y1);
        path.lineTo(x1, y1 + h);
        path.cubicTo(m, y1 + h, m, y0 + h, x0, y0 + h);
        path.close();
        fill.setColor((color & 0x00FFFFFF) | 0x8C000000);   // ~55 % opaque
        cv.drawPath(path, fill);
    }

    private void node(Canvas cv, float x, float y, float h, int color) {
        fill.setColor(color);
        cv.drawRect(x, y, x + nodeW, y + Math.max(h, 1f), fill);
    }

    /** "Drive 0.52 kWh": the name regular, the number bold, the unit
     * small and dim right after it. Name and number in color. */
    private void label(Canvas cv, String text, double kwhValue, float x, float y, int color) {
        int u = shownUnit(unit, km);
        name.setColor(color);
        bold.setColor(color);
        String n = text + " ";
        cv.drawText(n, x, y, name);
        x += name.measureText(n);
        String v = value(kwhValue, u, used, km);
        cv.drawText(v, x, y, bold);
        x += bold.measureText(v) + Style.dp(getContext(), 3);
        cv.drawText(UNIT_NAMES[u], x, y, dim);
    }

    @Override protected void onDraw(Canvas cv) {
        if (!hasData) return;
        float w = getWidth();
        float xLeft = 0, xMid = Math.round(w * 0.36f), xRight = Math.round(w * 0.60f);
        float hBat = hBattery(), hReg = hRegen();
        float yBat = yLeft(), yReg = yRegen();
        int[] colors = {Style.ACCENT, Style.HEAT, Style.TEXT_DIM};
        bold.setColor(Style.TEXT);
        dim.setColor(Style.TEXT_DIM);
        float labelUp = Style.dp(getContext(), 10);

        // Battery and regen, into Used: battery on top, regen under it.
        node(cv, xLeft, yBat, hBat, Style.TEXT_DIM);
        band(cv, xLeft + nodeW, yBat, xMid, top, hBat, Style.TEXT_DIM);
        label(cv, sBattery, battery, xLeft, yBat - labelUp, Style.TEXT);
        if (hReg > 0) {
            node(cv, xLeft, yReg, hReg, Style.PURPLE);
            band(cv, xLeft + nodeW, yReg, xMid, top + hBat, hReg, Style.PURPLE);
            label(cv, sRegen, regen, xLeft, yReg - labelUp, Style.PURPLE);
        }

        node(cv, xMid, top, chartH, Style.TEXT_DIM);
        label(cv, sUsed, used, xMid, top - labelUp, Style.TEXT);

        // Drive, climate, other from Used.
        float[] h = partHeights();
        double[] v = {drive, climate, other};
        float ySrc = top, yDst = top;
        float[] centres = new float[3];
        for (int i = 0; i < 3; i++) {
            band(cv, xMid + nodeW, ySrc, xRight, yDst, h[i], colors[i]);
            node(cv, xRight, yDst, h[i], colors[i]);
            centres[i] = yDst + h[i] / 2;
            ySrc += h[i];
            yDst += h[i] + rightGap;
        }
        float[] ly = partLabelYs();
        float lx = xRight + nodeW + Style.dp(getContext(), 10);
        for (int i = 0; i < 3; i++) {
            if (Math.abs(ly[i] - centres[i]) > 1) {
                line.setColor(colors[i]);
                cv.drawLine(xRight + nodeW, centres[i], lx - 3, ly[i] - Style.dp(getContext(), 6), line);
            }
            label(cv, partNames[i], v[i], lx, ly[i], Style.TEXT);
        }
    }
}
