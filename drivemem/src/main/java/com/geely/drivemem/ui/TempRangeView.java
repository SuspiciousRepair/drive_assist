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
        invalidate();
    }

    /** Config > Elements "Grayscale cards": a plain bar, no colour scale. */
    void setGrayscale(boolean on) {
        if (on == grayscale) return;
        grayscale = on;
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
        float gap = Style.dp(c, 10);
        float r = Style.dp(c, 8);

        String sMin = String.format(Locale.US, "%.0f°", min);
        String sMax = String.format(Locale.US, "%.0f°", max);
        float baseline = cy - (text.descent() + text.ascent()) / 2f;
        text.setColor(Style.TEXT);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(sMin, 0, baseline, text);
        if (Math.round(min) == Math.round(max)) return;   // a flat stay: one number, no bar
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(sMax, w, baseline, text);

        float left = text.measureText(sMin) + gap + r;
        float right = w - text.measureText(sMax) - gap - r;
        if (right <= left) return;

        // The bar's colours are sampled from the absolute scale at the
        // range's own temperatures, so only the part of the scale the car
        // actually saw is painted.
        int n = 6;
        int[] colors = new int[n];
        for (int i = 0; i < n; i++) colors[i] = grayscale ? Style.TEXT_DIM : colorFor(min + (max - min) * i / (n - 1));
        bar.setStrokeWidth(Style.dp(c, 10));
        bar.setShader(new LinearGradient(left, 0, right, 0, colors, null, Shader.TileMode.CLAMP));
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
