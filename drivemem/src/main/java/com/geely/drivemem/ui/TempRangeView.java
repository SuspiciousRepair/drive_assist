package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import com.geely.drivemem.util.Style;

import java.util.Locale;

/**
 * Outside temperature during the stay in Park, in one line:
 *
 *     27°  ━━━━━━●━━  33°
 *
 * The bar covers only the range the car sat through (lowest to highest), and
 * is painted from an ABSOLUTE temperature scale, so the colours say where
 * that range sits: a 30-40 °C day is all orange-to-red, a 10-15 °C night is
 * all blue. The dot is the temperature now.
 *
 * Values come from the hub (parking.temp_trail min/max, telemetry.outside_temp).
 */
final class TempRangeView extends View {
    // Absolute scale, °C -> colour. Fixed on purpose (not theme colours): the
    // same temperature must always look the same.
    static final float[] STOPS_C = {0f, 10f, 18f, 24f, 28f, 32f, 36f, 40f};
    static final int[] STOP_COLORS = {
        0xFF1565C0, // 0  deep blue
        0xFF0097A7, // 10 teal
        0xFF43A047, // 18 green
        0xFFC0CA33, // 24 yellow-green
        0xFFFBC02D, // 28 yellow
        0xFFF57C00, // 32 orange
        0xFFE64A19, // 36 dark orange
        0xFFC62828, // 40 red
    };

    private float min = Float.NaN, max = Float.NaN, now = Float.NaN;
    private boolean grayscale;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Built when the values, the grayscale switch or the size change, never
    // in onDraw: each draw made a new int[], a new LinearGradient and two
    // formatted Strings.
    private static final int BAR_COLORS = 6;
    private String sMin = "", sMax = "";
    private Shader barShader;          // null: no bar to draw
    private float barLeft, barRight;

    TempRangeView(Context c) {
        super(c);
        bar.setStrokeCap(Paint.Cap.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        text.setTextSize(Style.dp(c, 20));
        text.setFakeBoldText(true);
    }

    void setValues(float min, float max, float now) {
        if (same(min, this.min) && same(max, this.max) && same(now, this.now)) return;
        this.min = min; this.max = max; this.now = now;
        rebuild();
        invalidate();
    }

    /** "27°": whole degrees. */
    static String label(float c) {
        return String.format(Locale.US, "%.0f°", c);
    }

    /** The bar's colours, left to right: the absolute scale sampled at the
     * range's own temperatures, so only the part of the scale the car
     * actually saw is painted. One plain colour for grayscale cards. */
    static int[] barColors(float min, float max, boolean grayscale, int plain) {
        int[] colors = new int[BAR_COLORS];
        for (int i = 0; i < BAR_COLORS; i++) {
            colors[i] = grayscale ? plain : colorFor(min + (max - min) * i / (BAR_COLORS - 1));
        }
        return colors;
    }

    /** A flat stay: one number, no bar. */
    static boolean flat(float min, float max) {
        return Math.round(min) == Math.round(max);
    }

    private void rebuild() {
        barShader = null;
        if (Float.isNaN(min)) return;
        sMin = label(min);
        sMax = label(max);
        float w = getWidth();
        if (w <= 0 || flat(min, max)) return;
        Context c = getContext();
        float gap = Style.dp(c, 10);
        float r = Style.dp(c, 8);
        barLeft = text.measureText(sMin) + gap + r;
        barRight = w - text.measureText(sMax) - gap - r;
        if (barRight <= barLeft) return;
        barShader = new LinearGradient(barLeft, 0, barRight, 0,
            barColors(min, max, grayscale, Style.TEXT_DIM), null, Shader.TileMode.CLAMP);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild();
    }

    /** Config > Elements "Grayscale cards": a plain bar, no colour scale. */
    void setGrayscale(boolean on) {
        if (on == grayscale) return;
        grayscale = on;
        rebuild();
        invalidate();
    }

    private static boolean same(float a, float b) {
        return Float.isNaN(a) ? Float.isNaN(b) : a == b;
    }

    /** Colour of a temperature on the absolute scale (clamped at both ends). */
    static int colorFor(float c) {
        if (c <= STOPS_C[0]) return STOP_COLORS[0];
        for (int i = 1; i < STOPS_C.length; i++) {
            if (c <= STOPS_C[i]) {
                float t = (c - STOPS_C[i - 1]) / (STOPS_C[i] - STOPS_C[i - 1]);
                return lerp(STOP_COLORS[i - 1], STOP_COLORS[i], t);
            }
        }
        return STOP_COLORS[STOP_COLORS.length - 1];
    }

    private static int lerp(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (Float.isNaN(min)) return;
        Context c = getContext();
        float w = getWidth(), h = getHeight();
        float cy = h / 2f;
        float r = Style.dp(c, 8);

        float baseline = cy - (text.descent() + text.ascent()) / 2f;
        text.setColor(Style.TEXT);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(sMin, 0, baseline, text);
        if (flat(min, max)) return;   // a flat stay: one number, no bar
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(sMax, w, baseline, text);

        if (barShader == null) return;   // no room between the two numbers
        float left = barLeft, right = barRight;
        bar.setStrokeWidth(Style.dp(c, 10));
        bar.setShader(barShader);
        canvas.drawLine(left, cy, right, cy, bar);

        if (!Float.isNaN(now)) {
            float t = Math.max(0f, Math.min(1f, (now - min) / (max - min)));
            float xn = left + (right - left) * t;
            dot.setColor(Style.TEXT);
            ring.setColor(Style.cardFillColor());
            ring.setStrokeWidth(Style.dp(c, 3));
            canvas.drawCircle(xn, cy, r, dot);
            canvas.drawCircle(xn, cy, r, ring);
        }
    }
}
