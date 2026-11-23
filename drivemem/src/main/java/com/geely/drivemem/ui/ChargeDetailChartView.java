package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.geely.drivemem.R;
import com.geely.drivemem.util.Style;

import java.util.Locale;

/** The charge page's two charts on one time axis:
 *
 *   top:    power in kW (left scale), battery and outside temperature in °C
 *           (right scale)
 *   bottom: battery %
 *
 * A finger on either chart draws one vertical line through both, with a dot
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
    private final RectF top = new RectF(), bot = new RectF(), rect = new RectF();
    private final DashPathEffect dash;

    private final String lblKw, lblBat, lblOut, lblSoc;

    ChargeDetailChartView(Context c) {
        super(c);
        grid.setStrokeWidth(Style.dp(c, 1));
        axis.setTextSize(Style.dp(c, 17));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Style.dp(c, 3));
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        boxText.setTextSize(Style.dp(c, 20));
        boxText.setFakeBoldText(true);
        dash = new DashPathEffect(new float[]{Style.dp(c, 10), Style.dp(c, 8)}, 0);
        lblKw = "kW";
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
        float padL = Style.dp(ctx, 76), padR = Style.dp(ctx, 60);
        float legendH = Style.dp(ctx, 40), timeH = Style.dp(ctx, 34), gap = Style.dp(ctx, 56);
        float plotH = h - legendH - timeH - gap;
        top.set(padL, legendH, w - padR, legendH + plotH * 0.6f);
        bot.set(padL, top.bottom + gap, w - padR, h - timeH);

        int gridColor = Style.blend(Style.CARD, Style.TEXT_DIM, .25f);
        grid.setColor(gridColor);
        axis.setColor(Style.TEXT_DIM);

        float[] kwr = ChargeDetail.range(kw);
        float kwMax = ChargeDetail.kwAxisMax(kwr == null ? Float.NaN : kwr[1], dc);
        float[] tAx = ChargeDetail.tempAxis(batT, outT);

        // Grid and scales.
        axis.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 4; i++) {
            float v = kwMax * i / 4f;
            float y = top.bottom - top.height() * i / 4f;
            c.drawLine(top.left, y, top.right, y, grid);
            c.drawText(fmt(v) + " kW", top.left - Style.dp(ctx, 10), y + Style.dp(ctx, 6), axis);
        }
        if (tAx != null) {
            axis.setTextAlign(Paint.Align.LEFT);
            for (int i = 0; i <= 4; i++) {
                float v = tAx[0] + (tAx[1] - tAx[0]) * i / 4f;
                float y = top.bottom - top.height() * i / 4f;
                c.drawText(String.format(Locale.getDefault(), "%.0f°", v), top.right + Style.dp(ctx, 8),
                    y + Style.dp(ctx, 6), axis);
            }
        }
        axis.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 2; i++) {
            float y = bot.bottom - bot.height() * i / 2f;
            c.drawLine(bot.left, y, bot.right, y, grid);
            c.drawText((50 * i) + "%", bot.left - Style.dp(ctx, 10), y + Style.dp(ctx, 6), axis);
        }
        axis.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i <= 4; i++) {
            long t = t0 + (t1 - t0) * i / 4;
            c.drawText(SessionText.clock(t), tx(t), h - Style.dp(ctx, 6), axis);
        }

        // Legend over the top chart; the battery % caption over the bottom one.
        float lx = top.left;
        lx = legend(c, lx, kwColor(), lblKw, false);
        if (ChargeDetail.range(batT) != null) lx = legend(c, lx, Style.DANGER, lblBat, false);
        if (ChargeDetail.range(outT) != null) legend(c, lx, Style.HEAT, lblOut, true);
        axis.setTextAlign(Paint.Align.LEFT);
        c.drawText(lblSoc, bot.left, bot.top - Style.dp(ctx, 12), axis);

        // Battery % with a soft area under it.
        int socColor = kwColor();
        series(c, soc, 0, 100, bot, socColor, false, true);
        if (tAx != null) {
            series(c, outT, tAx[0], tAx[1], top, Style.HEAT, true, false);
            series(c, batT, tAx[0], tAx[1], top, Style.DANGER, false, false);
        }
        series(c, kw, 0, kwMax, top, kwColor(), false, false);

        if (crossT >= 0) cross(c, kwMax, tAx);
    }

    private float legend(Canvas c, float x, int color, String label, boolean dashed) {
        Context ctx = getContext();
        float y = Style.dp(ctx, 18);
        line.setColor(color);
        line.setPathEffect(dashed ? dash : null);
        c.drawLine(x, y, x + Style.dp(ctx, 30), y, line);
        line.setPathEffect(null);
        axis.setTextAlign(Paint.Align.LEFT);
        c.drawText(label, x + Style.dp(ctx, 38), y + Style.dp(ctx, 6), axis);
        return x + Style.dp(ctx, 38) + axis.measureText(label) + Style.dp(ctx, 32);
    }

    /** One line; it breaks where two samples are more than GAP_MS apart. */
    private void series(Canvas c, float[] v, float lo, float hi, RectF r, int color, boolean dashed, boolean area) {
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
        line.setPathEffect(dashed ? dash : null);
        c.drawPath(path, line);
        line.setPathEffect(null);
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
            lines.add(new String[]{"", String.format(Locale.getDefault(), "%.1f", kw[i]), " kW"}); colors.add(kwColor());
        }
        if (tAx != null) {
            i = ChargeDetail.nearest(ts, batT, crossT);
            if (i >= 0) {
                dot(c, x, top, batT[i], tAx[0], tAx[1], Style.DANGER);
                lines.add(new String[]{lblBat + " ", String.valueOf(Math.round(batT[i])), "°"}); colors.add(Style.DANGER);
            }
            i = ChargeDetail.nearest(ts, outT, crossT);
            if (i >= 0) {
                dot(c, x, top, outT[i], tAx[0], tAx[1], Style.HEAT);
                lines.add(new String[]{lblOut + " ", String.valueOf(Math.round(outT[i])), "°"}); colors.add(Style.HEAT);
            }
        }
        i = ChargeDetail.nearest(ts, soc, crossT);
        if (i >= 0) {
            dot(c, x, bot, soc[i], 0, 100, kwColor());
            lines.add(new String[]{"", String.valueOf(Math.round(soc[i])), "%"}); colors.add(kwColor());
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
