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

/** Where the energy went, as a Sankey:
 *
 *   Used ─┬─ Regen
 *         └─ Net ─┬─ Drive
 *                 ├─ Climate
 *                 └─ Other
 *
 * Every band is to scale (thickness = kWh, one scale for the whole chart).
 * No minimum thickness, on purpose: a small part stays thin. Only the gaps
 * between the right-hand parts and the label positions move, so labels never
 * overlap; a label that moved gets a thin leader line to its band.
 *
 * Used and Regen must come from the same trips as the split, so that
 * drive + climate + other = used - regen (EnergySplit scales them so). */
final class EnergySankeyView extends View {
    private double used, regen, drive, climate, other, km;
    private boolean hasData;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final String sUsed, sNet, sRegen;
    private final String[] partNames;

    // Layout, in px (dp = px on this panel, see CLAUDE.md).
    private final float chartH, top, nodeW, midGap, rightGap, labelGap;

    EnergySankeyView(Context c) {
        super(c);
        chartH = Style.dp(c, 140);
        top = Style.dp(c, 30);
        nodeW = Style.dp(c, 8);
        midGap = Style.dp(c, 32);
        rightGap = Style.dp(c, 12);
        labelGap = Style.dp(c, 36);
        bold.setTextSize(Style.dp(c, 17));
        bold.setTypeface(Typeface.DEFAULT_BOLD);
        dim.setTextSize(Style.dp(c, 14));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Style.dp(c, 1));
        sUsed = c.getString(R.string.sankey_used);
        sNet = c.getString(R.string.sankey_net);
        sRegen = c.getString(R.string.sankey_regen);
        partNames = new String[]{c.getString(R.string.stats_part_drive),
            c.getString(R.string.stats_part_climate), c.getString(R.string.stats_part_other)};
    }

    /** kWh for each node, and the km the trips covered (for Wh/km). */
    void setData(double used, double regen, double drive, double climate, double other, double km) {
        this.used = used; this.regen = Math.max(0, regen);
        this.drive = drive; this.climate = climate; this.other = other; this.km = km;
        hasData = used > 0;
        requestLayout();
        invalidate();
    }

    static String kwh(double v) { return String.format(Locale.US, Math.abs(v) < 2 ? "%.2f" : "%.1f", v); }

    /** Label centres: each at its band's centre, pushed down until it is at
     * least {@code minGap} below the one above. Never moves a band. */
    static float[] labelYs(float[] centres, float minGap) {
        float[] out = new float[centres.length];
        float prev = -Float.MAX_VALUE;
        for (int i = 0; i < centres.length; i++) {
            out[i] = Math.max(centres[i], prev + minGap);
            prev = out[i];
        }
        return out;
    }

    private float k() { return used > 0 ? (float) (chartH / used) : 0; }
    private float yNet() { return top + (float) (regen * k()) + midGap; }

    private float[] partHeights() {
        float k = k();
        return new float[]{(float) Math.max(0, drive) * k, (float) Math.max(0, climate) * k, (float) Math.max(0, other) * k};
    }

    private float[] partCentres() {
        float[] h = partHeights();
        float y = yNet();
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) { c[i] = y + h[i] / 2; y += h[i] + rightGap; }
        return c;
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h;
        if (!hasData) {
            h = 0;
        } else {
            float[] ys = labelYs(partCentres(), labelGap);
            float bottomBands = yNet() + (float) (Math.max(0, used - regen) * k()) + 2 * rightGap;
            h = (int) Math.ceil(Math.max(top + chartH, Math.max(ys[2] + Style.dp(getContext(), 24), bottomBands)));
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

    /** "  152 Wh/km" over the trips' km, or "" without distance. */
    private String perKm(double kwhValue) {
        double wh = EnergyUnits.whPerKm(kwhValue, km);
        return Double.isNaN(wh) ? "" : "  " + EnergyUnits.wholeWh(wh) + " " + EnergyUnits.WH_PER_KM;
    }

    /** A bold value and its dim Wh/km after it, on one line. */
    private void pair(Canvas cv, String value, String perKm, float x, float y) {
        cv.drawText(value, x, y, bold);
        if (!perKm.isEmpty()) cv.drawText(perKm, x + bold.measureText(value), y, dim);
    }

    @Override protected void onDraw(Canvas cv) {
        if (!hasData) return;
        float w = getWidth();
        float k = k();
        float xUsed = 0, xMid = Math.round(w * 0.36f), xRight = Math.round(w * 0.60f);
        double net = used - regen;
        float hReg = (float) (regen * k), hNet = (float) (Math.max(0, net) * k);
        float yReg = top, yNet = yNet();
        int[] colors = {Style.ACCENT, Style.HEAT, Style.TEXT_DIM};
        bold.setColor(Style.TEXT);
        dim.setColor(Style.TEXT_DIM);

        // Used, its top part came back.
        node(cv, xUsed, top, (float) (used * k), Style.TEXT_DIM);
        pair(cv, sUsed + " " + kwh(used) + " kWh", perKm(used), xUsed, top - Style.dp(getContext(), 10));

        // Regen on top, Net under it.
        band(cv, xUsed + nodeW, top, xMid, yReg, hReg, Style.PURPLE);
        band(cv, xUsed + nodeW, top + hReg, xMid, yNet, hNet, Style.TEXT_DIM);
        if (hReg > 0) node(cv, xMid, yReg, hReg, Style.PURPLE);
        node(cv, xMid, yNet, hNet, Style.TEXT_DIM);
        float tx = xMid + nodeW + Style.dp(getContext(), 8);
        bold.setColor(Style.PURPLE);
        String reg = sRegen + " " + kwh(regen);
        float regY = yReg + hReg / 2 + Style.dp(getContext(), 6);
        pair(cv, reg, perKm(regen), tx, regY);
        bold.setColor(Style.TEXT);
        pair(cv, sNet + " " + kwh(net), perKm(net), tx, yNet - Style.dp(getContext(), 9));

        // Drive, climate, other from Net.
        float[] h = partHeights();
        double[] v = {drive, climate, other};
        float ySrc = yNet, yDst = yNet;
        float[] centres = new float[3];
        for (int i = 0; i < 3; i++) {
            band(cv, xMid + nodeW, ySrc, xRight, yDst, h[i], colors[i]);
            node(cv, xRight, yDst, h[i], colors[i]);
            centres[i] = yDst + h[i] / 2;
            ySrc += h[i];
            yDst += h[i] + rightGap;
        }
        float[] ly = labelYs(centres, labelGap);
        float lx = xRight + nodeW + Style.dp(getContext(), 10);
        for (int i = 0; i < 3; i++) {
            if (Math.abs(ly[i] - centres[i]) > 1) {
                line.setColor(colors[i]);
                cv.drawLine(xRight + nodeW, centres[i], lx - 3, ly[i] - Style.dp(getContext(), 6), line);
            }
            cv.drawText(partNames[i] + " " + kwh(v[i]), lx, ly[i], bold);
            double wh = EnergyUnits.whPerKm(v[i], km);
            if (!Double.isNaN(wh)) {
                cv.drawText(EnergyUnits.wholeWh(wh) + " " + EnergyUnits.WH_PER_KM, lx, ly[i] + Style.dp(getContext(), 18), dim);
            }
        }
    }
}
