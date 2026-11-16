package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import com.geely.drivemem.R;
import com.geely.drivemem.util.Style;

import java.util.Locale;

/**
 * The live trip's consumption as a two-line-tall tile on the right of the
 * driving card:
 *
 *     ┌──────────┐
 *     │   8,7    │   number in the scale colour
 *     │kWh/100 km│
 *     │ ━●━━━━━━ │   where it sits on the scale
 *     └──────────┘
 *
 * The scale is FIXED (not relative to the driver's own average), so the same
 * consumption always has the same colour: 12 kWh/100 km and below is green,
 * 20 and above is red.
 */
final class EfficiencyBadgeView extends View {
    static final float[] STOPS = {12f, 14f, 16f, 18f, 20f};
    static final int[] STOP_COLORS = {
        0xFF43A047, // 12 green
        0xFFC0CA33, // 14 yellow-green
        0xFFFBC02D, // 16 yellow
        0xFFF57C00, // 18 orange
        0xFFC62828, // 20 red
    };
    // The small bar under the number spans a little past both ends.
    static final float BAR_MIN = 10f, BAR_MAX = 22f;

    private double value = Double.NaN;
    private boolean estimated;
    private boolean grayscale;
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    // Built when a value, the grayscale switch or the size changes, never in
    // onDraw: the card redraws often, and each draw made a new int[], a new
    // LinearGradient and a new formatted String.
    private static final int BAR_COLORS = 7;
    private final String unitText;
    private String numberText = NO_VALUE;
    private Shader barShader;
    private float barLeft, barRight;

    EfficiencyBadgeView(Context c) {
        super(c);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Style.dp(c, 2));
        number.setTextSize(Style.dp(c, 34));
        number.setFakeBoldText(true);
        number.setTextAlign(Paint.Align.CENTER);
        unit.setTextSize(Style.dp(c, 14));
        unit.setTextAlign(Paint.Align.CENTER);
        bar.setStrokeCap(Paint.Cap.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        unitText = c.getString(R.string.unit_kwh_per_100km);
    }

    static final String NO_VALUE = "—";

    /** The number as drawn: one decimal, "~" after an estimate, a dash for none. */
    static String numberText(double value, boolean estimated, Locale locale) {
        if (Double.isNaN(value)) return NO_VALUE;
        String s = String.format(locale, "%.1f", value);
        return estimated ? s + "~" : s;
    }

    /** The small bar's colours, left to right: the scale from BAR_MIN to
     * BAR_MAX, or one plain colour for grayscale cards. */
    static int[] barColors(boolean grayscale, int plain) {
        int[] colors = new int[BAR_COLORS];
        for (int i = 0; i < BAR_COLORS; i++) {
            colors[i] = grayscale ? plain : colorFor(BAR_MIN + (BAR_MAX - BAR_MIN) * i / (BAR_COLORS - 1));
        }
        return colors;
    }

    // The bar's place depends on the width only; its colours on grayscale.
    private void rebuild() {
        numberText = numberText(value, estimated, Locale.getDefault());
        Context c = getContext();
        float w = getWidth();
        if (w <= 0) { barShader = null; return; }
        float barW = Math.min(w - Style.dp(c, 40), Style.dp(c, 100));
        barLeft = (w - barW) / 2f;
        barRight = barLeft + barW;
        barShader = new LinearGradient(barLeft, 0, barRight, 0,
            barColors(grayscale, Style.TEXT_DIM), null, Shader.TileMode.CLAMP);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild();
    }

    /** kWh/100 km, or 0/NaN for "not yet" (under 200 m). */
    void setValue(double kwh100, boolean estimated) {
        double v = kwh100 > 0 ? Math.round(kwh100 * 10) / 10.0 : Double.NaN;
        boolean same = Double.isNaN(v) ? Double.isNaN(value) : v == value;
        if (same && estimated == this.estimated) return;
        value = v;
        this.estimated = estimated;
        rebuild();
        invalidate();
    }

    /** Config > Elements "Grayscale cards": theme text colours, no scale. */
    void setGrayscale(boolean on) {
        if (on == grayscale) return;
        grayscale = on;
        rebuild();
        invalidate();
    }

    /** Colour of a consumption on the fixed scale (clamped at both ends). */
    static int colorFor(double kwh100) {
        if (kwh100 <= STOPS[0]) return STOP_COLORS[0];
        for (int i = 1; i < STOPS.length; i++) {
            if (kwh100 <= STOPS[i]) {
                float t = (float) ((kwh100 - STOPS[i - 1]) / (STOPS[i] - STOPS[i - 1]));
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
        Context c = getContext();
        float w = getWidth(), h = getHeight();
        boolean has = !Double.isNaN(value);
        int color = has && !grayscale ? colorFor(value) : Style.TEXT_DIM;

        float sw = border.getStrokeWidth();
        rect.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2);
        border.setColor(color);
        float rad = Style.dp(c, 18);
        canvas.drawRoundRect(rect, rad, rad, border);

        float barH = Style.dp(c, 6);
        float unitGap = Style.dp(c, 4), barGap = Style.dp(c, 8);
        float numH = -number.ascent();
        float unitH = -unit.ascent() + unit.descent();
        float total = numH + unitGap + unitH + (has ? barGap + barH : 0);
        float top = (h - total) / 2f;

        number.setColor(has && grayscale ? Style.TEXT : color);
        canvas.drawText(numberText, w / 2f, top + numH, number);
        unit.setColor(Style.TEXT_DIM);
        canvas.drawText(unitText, w / 2f, top + numH + unitGap - unit.ascent(), unit);
        if (!has || barShader == null) return;

        float cy = top + numH + unitGap + unitH + barGap + barH / 2f;
        float left = barLeft, right = barRight, barW = right - left;
        bar.setStrokeWidth(barH);
        bar.setShader(barShader);
        canvas.drawLine(left, cy, right, cy, bar);

        float t = (float) Math.max(0, Math.min(1, (value - BAR_MIN) / (BAR_MAX - BAR_MIN)));
        float x = left + barW * t;
        float r = Style.dp(c, 6);
        dot.setColor(Style.TEXT);
        ring.setColor(Style.cardFillColor());
        ring.setStrokeWidth(Style.dp(c, 2));
        canvas.drawCircle(x, cy, r, dot);
        canvas.drawCircle(x, cy, r, ring);
    }
}
