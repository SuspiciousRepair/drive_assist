package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import com.geely.drivemem.R;
import com.geely.drivemem.util.Style;

import java.util.Locale;

/** The charge page's charts, stacked on one time axis, each with its own
 * single scale (never two scales on one chart):
 *
 *   top:    power in kW, in the charge type's colour (AC blue, DC green,
 *           as in Charging stats)
 *   middle: battery temperature and the outside temperature, in °C; left
 *           out when no temperature was read. The battery line takes the
 *           colour of its own value on the home screen's temperature scale
 *           (TempRangeView: green fine, amber warm, red hot), so a hot
 *           battery shows at a glance; outside is a thin grey context line
 *   bottom: battery %, grey (as in the charge-curve window)
 *
 * A finger on any chart draws one vertical line through all of them, with a dot
 * where it crosses each line and a box with every value at that time. The
 * line stays where the finger left it. Values are the nearest sample within
 * ChargeDetail.GAP_MS; a line breaks where samples are further apart. The
 * line goes away CROSS_HIDE_MS after the finger leaves the screen. */
final class ChargeDetailChartView extends View {
    private long[] ts = new long[0];
    private float[] kw = new float[0], batT = new float[0], outT = new float[0], soc = new float[0];
    private long t0, t1;
    private boolean dc;
    private long crossT = -1;
    static final long CROSS_HIDE_MS = 4_000L;
    private final Runnable hideCross = () -> { crossT = -1; invalidate(); };

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF top = new RectF(), mid = new RectF(), bot = new RectF(), rect = new RectF();
    private final float lineW, thinW;

    private final String lblPower, lblBat, lblOut, lblSoc;

    ChargeDetailChartView(Context c) {
        super(c);
        grid.setStrokeWidth(Style.dp(c, 1));
        axis.setTextSize(Style.dp(c, 17));
        line.setStyle(Paint.Style.STROKE);
        lineW = Style.dp(c, 3);
        thinW = Style.dp(c, 2);
        line.setStrokeWidth(lineW);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        boxText.setTextSize(Style.dp(c, 20));
        boxText.setFakeBoldText(true);
        lblPower = c.getString(R.string.charge_detail_power);
        lblBat = c.getString(R.string.charge_detail_bat_temp);
        lblOut = c.getString(R.string.charge_detail_out_temp);
        lblSoc = c.getString(R.string.charge_detail_soc);
    }

    /** The samples, oldest first, NaN where a value is missing; t0..t1 is
     * the time axis. */
    void setData(long[] ts, float[] kw, float[] batT, float[] outT, float[] soc, long t0, long t1, boolean dc) {
        this.ts = ts; this.kw = kw; this.batT = batT; this.outT = outT; this.soc = soc;
        this.t0 = t0; this.t1 = Math.max(t1, t0 + 60_000L); this.dc = dc;
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(hideCross);
        super.onDetachedFromWindow();
    }

    private int kwColor() { return dc ? Style.GOOD : Style.ACCENT; }
    private static int socColor() { return Style.blend(Style.CARD, Style.TEXT, .45f); }
    private static int outColor() { return Style.TEXT_DIM; }
    // Set while a series is drawn with a colour per value (battery temperature).
    private Shader shade;

    /** A vertical gradient over r so a line drawn in r has, at each height,
     * the colour of the temperature there. */
    private static Shader tempShader(RectF r, float lo, float hi) {
        int n = 12;
        int[] colors = new int[n];
        float[] pos = new float[n];
        for (int i = 0; i < n; i++) {
            pos[i] = i / (float) (n - 1);
            colors[i] = TempRangeView.colorFor(lo + (hi - lo) * pos[i]);
        }
        return new LinearGradient(0, r.bottom, 0, r.top, colors, pos, Shader.TileMode.CLAMP);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            removeCallbacks(hideCross);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            float x = Math.max(top.left, Math.min(top.right, e.getX()));
            if (top.width() > 0) {
                crossT = t0 + (long) ((x - top.left) / top.width() * (t1 - t0));
                invalidate();
            }
            return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            postDelayed(hideCross, CROSS_HIDE_MS);
            return true;
        }
        return super.onTouchEvent(e);
    }

    private float tx(long t) { return top.left + top.width() * (t - t0) / (float) (t1 - t0); }

    @Override protected void onDraw(Canvas c) {
        Context ctx = getContext();
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float[] kwr = ChargeDetail.range(kw);
        float kwMax = ChargeDetail.kwAxisMax(kwr == null ? Float.NaN : kwr[1], dc);
        float[] tAx = ChargeDetail.tempAxis(batT, outT);

        // Each chart has a caption row above it; one time row under the last.
        float padL = Style.dp(ctx, 76), padR = Style.dp(ctx, 24);
        float capH = Style.dp(ctx, 40), timeH = Style.dp(ctx, 34), gap = Style.dp(ctx, 40);
        int panels = tAx != null ? 3 : 2;
        float plotH = h - timeH - panels * capH - (panels - 1) * gap;
        float powerH = plotH * (tAx != null ? .38f : .5f);
        float tempH = tAx != null ? plotH * .24f : 0f;
        float y = capH;
        top.set(padL, y, w - padR, y + powerH);
        y = top.bottom;
        if (tAx != null) {
            y += gap + capH;
            mid.set(padL, y, w - padR, y + tempH);
            y = mid.bottom;
        } else {
            mid.setEmpty();
        }
        y += gap + capH;
        bot.set(padL, y, w - padR, h - timeH);

        int gridColor = Style.blend(Style.CARD, Style.TEXT_DIM, .25f);
        grid.setColor(gridColor);
        axis.setColor(Style.TEXT_DIM);

        // Grids and scales, all on the left.
        axis.setTextAlign(Paint.Align.RIGHT);
        float lx = top.left - Style.dp(ctx, 10), ly = Style.dp(ctx, 6);
        for (int i = 0; i <= 4; i++) {
            float gy = top.bottom - top.height() * i / 4f;
            c.drawLine(top.left, gy, top.right, gy, grid);
            c.drawText(fmt(kwMax * i / 4f) + " kW", lx, gy + ly, axis);
        }
        if (tAx != null) {
            for (int i = 0; i <= 2; i++) {
                float gy = mid.bottom - mid.height() * i / 2f;
                c.drawLine(mid.left, gy, mid.right, gy, grid);
                c.drawText(String.format(Locale.getDefault(), "%.0f°", tAx[0] + (tAx[1] - tAx[0]) * i / 2f),
                    lx, gy + ly, axis);
            }
        }
        for (int i = 0; i <= 2; i++) {
            float gy = bot.bottom - bot.height() * i / 2f;
            c.drawLine(bot.left, gy, bot.right, gy, grid);
            c.drawText((50 * i) + "%", lx, gy + ly, axis);
        }
        axis.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i <= 4; i++) {
            long t = t0 + (t1 - t0) * i / 4;
            c.drawText(SessionText.clock(t), tx(t), h - Style.dp(ctx, 6), axis);
        }

        // Captions: one name per single-line chart, a legend where there are two.
        axis.setTextAlign(Paint.Align.LEFT);
        float capY = Style.dp(ctx, 14);
        c.drawText(lblPower, top.left, top.top - capY, axis);
        if (tAx != null) {
            float cx = mid.left;
            if (ChargeDetail.range(batT) != null) {
                // Its legend line runs green to red: the line's colour is its value.
                float ly0 = mid.top - capY - Style.dp(ctx, 6);
                shade = new LinearGradient(cx, ly0, cx + Style.dp(ctx, 30), ly0,
                    new int[]{TempRangeView.colorFor(20f), TempRangeView.colorFor(30f), TempRangeView.colorFor(40f)},
                    null, Shader.TileMode.CLAMP);
                cx = legend(c, cx, mid.top - capY, Style.TEXT, lineW, lblBat);
                shade = null;
            }
            if (ChargeDetail.range(outT) != null) legend(c, cx, mid.top - capY, outColor(), thinW, lblOut);
        }
        c.drawText(lblSoc, bot.left, bot.top - capY, axis);

        series(c, kw, 0, kwMax, top, kwColor(), lineW, false);
        if (tAx != null) {
            series(c, outT, tAx[0], tAx[1], mid, outColor(), thinW, false);
            shade = tempShader(mid, tAx[0], tAx[1]);
            series(c, batT, tAx[0], tAx[1], mid, Style.TEXT, lineW, false);
            shade = null;
        }
        // Battery % with a soft area under it.
        series(c, soc, 0, 100, bot, socColor(), lineW, true);

        if (crossT >= 0) cross(c, kwMax, tAx);
    }

    /** A short line in the series' colour and its name; returns the x after it. */
    private float legend(Canvas c, float x, float baseline, int color, float width, String label) {
        Context ctx = getContext();
        float y = baseline - Style.dp(ctx, 6);
        line.setColor(color);
        line.setStrokeWidth(width);
        line.setShader(shade);
        c.drawLine(x, y, x + Style.dp(ctx, 30), y, line);
        line.setShader(null);
        line.setStrokeWidth(lineW);
        axis.setTextAlign(Paint.Align.LEFT);
        c.drawText(label, x + Style.dp(ctx, 40), baseline, axis);
        return x + Style.dp(ctx, 40) + axis.measureText(label) + Style.dp(ctx, 36);
    }

    /** One line; it breaks where two samples are more than GAP_MS apart. */
    private void series(Canvas c, float[] v, float lo, float hi, RectF r, int color, float width, boolean area) {
        if (v.length == 0 || hi <= lo) return;
        path.rewind();
        Path areaPath = area ? new Path() : null;
        boolean open = false;
        long lastT = 0;
        float lastX = 0, firstX = 0;
        for (int i = 0; i < ts.length; i++) {
            if (Float.isNaN(v[i])) continue;
            float x = tx(ts[i]);
            float y = r.bottom - r.height() * Math.max(0f, Math.min(1f, (v[i] - lo) / (hi - lo)));
            if (open && ts[i] - lastT > ChargeDetail.GAP_MS) {
                if (area) { areaPath.lineTo(lastX, r.bottom); areaPath.lineTo(firstX, r.bottom); areaPath.close(); }
                open = false;
            }
            if (!open) {
                path.moveTo(x, y);
                if (area) areaPath.moveTo(x, y);
                firstX = x; open = true;
            } else {
                path.lineTo(x, y);
                if (area) areaPath.lineTo(x, y);
            }
            lastT = ts[i]; lastX = x;
        }
        if (area && open) { areaPath.lineTo(lastX, r.bottom); areaPath.lineTo(firstX, r.bottom); areaPath.close(); }
        if (area) {
            fill.setColor((color & 0x00FFFFFF) | 0x2E000000);
            c.drawPath(areaPath, fill);
        }
        line.setColor(color);
        line.setStrokeWidth(width);
        line.setShader(shade);
        c.drawPath(path, line);
        line.setShader(null);
        line.setStrokeWidth(lineW);
    }

    private void cross(Canvas c, float kwMax, float[] tAx) {
        Context ctx = getContext();
        float x = tx(crossT);
        grid.setColor(Style.TEXT);
        grid.setStrokeWidth(Style.dp(ctx, 2));
        c.drawLine(x, top.top, x, bot.bottom, grid);
        grid.setStrokeWidth(Style.dp(ctx, 1));

        // Each line: label (dim, may be empty), number, unit (smaller and
        // dimmer than the number, as everywhere: docs/STYLE-GUIDE.md).
        java.util.List<String[]> lines = new java.util.ArrayList<>();
        java.util.List<Integer> colors = new java.util.ArrayList<>();
        lines.add(new String[]{"", SessionText.clock(crossT), ""}); colors.add(Style.TEXT);
        int i = ChargeDetail.nearest(ts, kw, crossT);
        if (i >= 0) {
            dot(c, x, top, kw[i], 0, kwMax, kwColor());
            lines.add(new String[]{"", String.format(Locale.getDefault(), "%.1f", kw[i]), " kW"}); colors.add(Style.TEXT);
        }
        if (tAx != null) {
            i = ChargeDetail.nearest(ts, batT, crossT);
            if (i >= 0) {
                dot(c, x, mid, batT[i], tAx[0], tAx[1], TempRangeView.colorFor(batT[i]));
                lines.add(new String[]{lblBat + " ", String.valueOf(Math.round(batT[i])), "°"}); colors.add(Style.TEXT);
            }
            i = ChargeDetail.nearest(ts, outT, crossT);
            if (i >= 0) {
                dot(c, x, mid, outT[i], tAx[0], tAx[1], outColor());
                lines.add(new String[]{lblOut + " ", String.valueOf(Math.round(outT[i])), "°"}); colors.add(Style.TEXT);
            }
        }
        i = ChargeDetail.nearest(ts, soc, crossT);
        if (i >= 0) {
            dot(c, x, bot, soc[i], 0, 100, socColor());
            lines.add(new String[]{"", String.valueOf(Math.round(soc[i])), "%"}); colors.add(Style.TEXT);
        }

        unitText.setTextSize(boxText.getTextSize() * Style.UNIT_SCALE_ROW);
        unitText.setColor(Style.TEXT_DIM);
        float lh = Style.dp(ctx, 30), pad = Style.dp(ctx, 16);
        float bw = 0;
        for (String[] l : lines) {
            bw = Math.max(bw, unitText.measureText(l[0]) + boxText.measureText(l[1]) + unitText.measureText(l[2]));
        }
        bw += 2 * pad;
        float bh = lines.size() * lh + pad;
        float bx = x + Style.dp(ctx, 18);
        if (bx + bw > top.right) bx = x - Style.dp(ctx, 18) - bw;   // flip near the right edge
        float by = top.top + Style.dp(ctx, 8);
        rect.set(bx, by, bx + bw, by + bh);
        box.setColor((Style.BG_BOTTOM & 0x00FFFFFF) | 0xEE000000);
        float r = Style.dp(ctx, 12);
        c.drawRoundRect(rect, r, r, box);
        for (int k = 0; k < lines.size(); k++) {
            String[] l = lines.get(k);
            float y = by + pad / 2f + lh * (k + 1) - Style.dp(ctx, 8);
            float tx = bx + pad;
            c.drawText(l[0], tx, y, unitText);
            tx += unitText.measureText(l[0]);
            boxText.setColor(colors.get(k));
            c.drawText(l[1], tx, y, boxText);
            tx += boxText.measureText(l[1]);
            c.drawText(l[2], tx, y, unitText);
        }
    }

    private void dot(Canvas c, float x, RectF r, float v, float lo, float hi, int color) {
        Context ctx = getContext();
        float y = r.bottom - r.height() * Math.max(0f, Math.min(1f, (v - lo) / (hi - lo)));
        dot.setColor(Style.TEXT);
        c.drawCircle(x, y, Style.dp(ctx, 9), dot);
        dot.setColor(color);
        c.drawCircle(x, y, Style.dp(ctx, 6), dot);
    }

    private static String fmt(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format(Locale.getDefault(), "%.1f", v);
    }
}
