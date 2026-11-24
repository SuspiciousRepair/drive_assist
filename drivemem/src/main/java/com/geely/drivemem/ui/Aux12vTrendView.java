package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

import com.geely.drivemem.R;
import com.geely.drivemem.sensors.Aux12vMonitor;
import com.geely.drivemem.util.Style;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Minimal 60-day 12V morning resting voltage trend chart for Car Diary.
 *
 * Draws one smooth accent line with a soft area wash, one dashed amber
 * healthy-limit line (12.25 V), an optional vertical marker if the 12V battery
 * was replaced during the window, and a bold endpoint figure with a smaller
 * dimmer "V" unit per docs/STYLE-GUIDE.md. */
public final class Aux12vTrendView extends View {

    private static final double Y_MIN = 11.8;
    private static final double Y_MAX = 13.1;

    private List<Aux12vMonitor.TrendPoint> points = Collections.emptyList();
    private Long replacedAtMs;
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("d MMM", Locale.getDefault());

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint limitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endNumPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endUnitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path areaPath = new Path();

    public Aux12vTrendView(Context c) {
        super(c);
        initPaints(c);
    }

    private void initPaints(Context c) {
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(Style.dp(c, 1));
        gridPaint.setColor(Style.tileStrokeColor());

        limitPaint.setStyle(Paint.Style.STROKE);
        limitPaint.setStrokeWidth(Style.dp(c, 2));
        limitPaint.setColor(Style.WARN);
        limitPaint.setPathEffect(new DashPathEffect(new float[]{Style.dp(c, 6), Style.dp(c, 5)}, 0));

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Style.dp(c, 3));
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(Style.ACCENT);

        areaPaint.setStyle(Paint.Style.FILL);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(Style.ACCENT);

        dotRingPaint.setStyle(Paint.Style.STROKE);
        dotRingPaint.setStrokeWidth(Style.dp(c, 3));
        dotRingPaint.setColor(Style.cardPaint(Style.CARD));

        axisPaint.setColor(Style.TEXT_DIM);
        axisPaint.setTextSize(Style.dp(c, 14));

        endNumPaint.setColor(Style.TEXT);
        endNumPaint.setTextSize(Style.dp(c, 22));
        endNumPaint.setFakeBoldText(true);

        // The unit: the number's size x the row unit scale, dim (STYLE-GUIDE "Numbers and units").
        endUnitPaint.setColor(Style.TEXT_DIM);
        endUnitPaint.setTextSize(Style.dp(c, 22) * Style.UNIT_SCALE_ROW);

        emptyPaint.setColor(Style.TEXT_DIM);
        emptyPaint.setTextSize(Style.dp(c, 18));
        emptyPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(List<Aux12vMonitor.TrendPoint> pts, Long replacedMs) {
        this.points = (pts != null) ? pts : Collections.emptyList();
        this.replacedAtMs = replacedMs;
        initPaints(getContext());
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Context c = getContext();
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float padL = Style.dp(c, 46);
        float padR = Style.dp(c, 92);   // room for the end figure ("12.6 V") right of the last point
        float padT = Style.dp(c, 14);
        float padB = Style.dp(c, 30);
        float plotW = Math.max(1f, w - padL - padR);
        float plotH = Math.max(1f, h - padT - padB);

        // Horizontal grid & ticks: 12.0, 12.4, 12.8
        axisPaint.setTextAlign(Paint.Align.RIGHT);
        double[] ticks = {12.0, 12.4, 12.8};
        for (double v : ticks) {
            float y = yOf(v, padT, plotH);
            canvas.drawLine(padL, y, padL + plotW, y, gridPaint);
            canvas.drawText(String.format(Locale.US, "%.1f", v),
                padL - Style.dp(c, 8), y + Style.dp(c, 5), axisPaint);
        }

        // Healthy limit dashed amber line (12.25 V)
        float limY = yOf(Aux12vMonitor.HEALTHY_RESTING_LIMIT_V, padT, plotH);
        canvas.drawLine(padL, limY, padL + plotW, limY, limitPaint);

        if (points.isEmpty()) {
            canvas.drawText(c.getString(R.string.diary_12v_no_trend),
                padL + plotW / 2f, padT + plotH / 2f + Style.dp(c, 6), emptyPaint);
            return;
        }

        long tMin = points.get(0).tsMs;
        long tMax = points.get(points.size() - 1).tsMs;
        long span = Math.max(1L, tMax - tMin);

        // Optional vertical marker when 12V battery was replaced within the window
        if (replacedAtMs != null && replacedAtMs >= tMin && replacedAtMs <= tMax && points.size() > 1) {
            float rx = padL + (float) ((replacedAtMs - tMin) * plotW / (double) span);
            canvas.drawLine(rx, padT, rx, padT + plotH, gridPaint);
        }

        if (points.size() == 1) {
            float cx = padL + plotW;
            float cy = yOf(points.get(0).voltageV, padT, plotH);
            drawEndpoint(canvas, c, cx, cy, points.get(0).voltageV);
            axisPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(dayFmt.format(new Date(tMax)), padL + plotW, h - Style.dp(c, 6), axisPaint);
            return;
        }

        linePath.reset();
        areaPath.reset();
        float firstX = 0f, lastX = 0f, lastY = 0f;
        for (int i = 0; i < points.size(); i++) {
            Aux12vMonitor.TrendPoint p = points.get(i);
            float x = padL + (float) ((p.tsMs - tMin) * plotW / (double) span);
            float y = yOf(p.voltageV, padT, plotH);
            if (i == 0) {
                firstX = x;
                linePath.moveTo(x, y);
                areaPath.moveTo(x, y);
            } else {
                linePath.lineTo(x, y);
                areaPath.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        areaPath.lineTo(lastX, padT + plotH);
        areaPath.lineTo(firstX, padT + plotH);
        areaPath.close();

        int topCol = (Style.ACCENT & 0x00FFFFFF) | 0x38000000;
        int botCol = (Style.ACCENT & 0x00FFFFFF) | 0x04000000;
        areaPaint.setShader(new LinearGradient(0, padT, 0, padT + plotH, topCol, botCol, Shader.TileMode.CLAMP));
        canvas.drawPath(areaPath, areaPaint);
        canvas.drawPath(linePath, linePaint);

        drawEndpoint(canvas, c, lastX, lastY, points.get(points.size() - 1).voltageV);

        // X-axis date ticks (start, mid, end)
        float labelY = h - Style.dp(c, 6);
        axisPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(dayFmt.format(new Date(tMin)), padL, labelY, axisPaint);
        axisPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(dayFmt.format(new Date(tMin + span / 2L)), padL + plotW / 2f, labelY, axisPaint);
        axisPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(dayFmt.format(new Date(tMax)), padL + plotW, labelY, axisPaint);
    }

    private void drawEndpoint(Canvas canvas, Context c, float x, float y, double v) {
        float r = Style.dp(c, 5);
        canvas.drawCircle(x, y, r + Style.dp(c, 1), dotRingPaint);
        canvas.drawCircle(x, y, r, dotPaint);
        String num = String.format(Locale.US, "%.1f", v);
        float tx = x + Style.dp(c, 12);
        float ty = y + Style.dp(c, 7);
        canvas.drawText(num, tx, ty, endNumPaint);
        float nw = endNumPaint.measureText(num);
        canvas.drawText(" V", tx + nw, ty, endUnitPaint);
    }

    private static float yOf(double v, float padT, float plotH) {
        double clamped = Math.max(Y_MIN, Math.min(Y_MAX, v));
        double frac = (clamped - Y_MIN) / (Y_MAX - Y_MIN);
        return padT + (float) (plotH * (1.0 - frac));
    }
}
