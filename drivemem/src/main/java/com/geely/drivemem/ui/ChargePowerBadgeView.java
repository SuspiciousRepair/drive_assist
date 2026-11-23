package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import com.geely.drivemem.util.Style;

import java.util.Arrays;
import java.util.Locale;

/**
 * Live charging power as a two-line-tall tile on the right of the active
 * charging card, paired with a mini power-curve sparkline along the bottom:
 *
 *     ┌───────────┐
 *     │  6,4 kW   │   live kW in AC blue (Style.ACCENT) or DC green (Style.GOOD),
 *     │ ╭───────╮ │   number and unit on one baseline; the session power curve
 *     └───────────┘   so far under them
 */
final class ChargePowerBadgeView extends View {
    private static final float AXIS_MAX_KW_AC = 8f;
    private static final float AXIS_MAX_KW_DC = 80f;
    static final String NO_VALUE = "—";

    private double powerKw = Double.NaN;
    private boolean isDcfc;
    private boolean hasVoltage;
    private boolean grayscale;
    private float[] curve = new float[0];

    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    private String numberText = NO_VALUE;

    ChargePowerBadgeView(Context c) {
        super(c);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Style.dp(c, 2));
        number.setTextSize(Style.dp(c, 34));
        number.setFakeBoldText(true);
        number.setTextAlign(Paint.Align.LEFT);
        unit.setTextSize(Style.dp(c, 18));
        unit.setTextAlign(Paint.Align.LEFT);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Style.dp(c, 2));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    static String formatKw(double kw, Locale locale) {
        if (Double.isNaN(kw) || kw <= 0) return NO_VALUE;
        return String.format(locale, "%.1f", kw);
    }

    void setPower(double kw, boolean isDcfc, boolean hasVoltage, float[] curveKw) {
        double rounded = (!Double.isNaN(kw) && kw > 0) ? Math.round(kw * 10.0) / 10.0 : Double.NaN;
        float[] nextCurve = curveKw != null ? curveKw : new float[0];
        boolean sameKw = Double.isNaN(rounded) ? Double.isNaN(powerKw) : rounded == powerKw;
        if (sameKw && this.isDcfc == isDcfc && this.hasVoltage == hasVoltage && Arrays.equals(this.curve, nextCurve)) {
            return;
        }
        this.powerKw = rounded;
        this.isDcfc = isDcfc;
        this.hasVoltage = hasVoltage;
        this.curve = nextCurve.clone();
        this.numberText = formatKw(rounded, Locale.getDefault());
        invalidate();
    }

    void setGrayscale(boolean on) {
        if (on == grayscale) return;
        grayscale = on;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        boolean hasPower = !Double.isNaN(powerKw) && powerKw > 0;
        int accent = (!grayscale && (hasVoltage || hasPower))
            ? (isDcfc ? Style.GOOD : Style.ACCENT)
            : Style.TEXT_DIM;

        float sw = border.getStrokeWidth();
        rect.set(sw / 2f, sw / 2f, w - sw / 2f, h - sw / 2f);
        border.setColor(accent);
        float rad = Style.dp(c, 18);
        canvas.drawRoundRect(rect, rad, rad, border);

        boolean hasCurve = curve.length > 0 || hasPower;
        float padX = Style.dp(c, 16);
        float sparkH = Style.dp(c, 22);
        float sparkGap = Style.dp(c, 8);
        float unitGap = Style.dp(c, 6);

        // "6,4 kW" on one baseline, centred as one group. A wide DC value
        // shrinks the number so the group never touches the border.
        float baseNum = Style.dp(c, 34);
        number.setTextSize(baseNum);
        String unitText = hasPower ? "kW" : "";
        float unitW = unitText.isEmpty() ? 0 : unit.measureText(unitText);
        float gap = unitText.isEmpty() ? 0 : unitGap;
        float numW = number.measureText(numberText);
        float room = w - 2 * padX;
        if (numW + gap + unitW > room && numW > 0) {
            number.setTextSize(baseNum * Math.max(0.6f, (room - gap - unitW) / numW));
            numW = number.measureText(numberText);
        }
        float numH = -number.ascent();
        float descHalf = number.descent() * 0.5f;   // room for the comma's tail
        float total = numH + (hasCurve ? descHalf + sparkGap + sparkH : 0);
        float top = (h - total) / 2f;
        float baseline = top + numH;
        float textX = (w - (numW + gap + unitW)) / 2f;

        number.setColor(hasPower ? (grayscale ? Style.TEXT : accent) : Style.TEXT_DIM);
        canvas.drawText(numberText, textX, baseline, number);
        if (!unitText.isEmpty()) {
            unit.setColor(Style.TEXT_DIM);
            canvas.drawText(unitText, textX + numW + gap, baseline, unit);
        }

        if (!hasCurve) return;

        float left = padX, right = w - padX;
        if (right <= left) return;
        float sparkTop = baseline + descHalf + sparkGap;
        float sparkBottom = sparkTop + sparkH;

        float ceiling = isDcfc ? AXIS_MAX_KW_DC : AXIS_MAX_KW_AC;
        for (float v : curve) {
            if (v > ceiling) ceiling = v;
        }
        if (hasPower && (float) powerKw > ceiling) ceiling = (float) powerKw;

        linePath.reset();
        fillPath.reset();

        int n = curve.length;
        if (n <= 1) {
            float val = n == 1 ? curve[0] : (float) powerKw;
            float frac = Math.max(0.08f, Math.min(1f, val / ceiling));
            float y = sparkBottom - frac * sparkH;
            linePath.moveTo(left, y);
            linePath.lineTo(right, y);
            fillPath.moveTo(left, sparkBottom);
            fillPath.lineTo(left, y);
            fillPath.lineTo(right, y);
            fillPath.lineTo(right, sparkBottom);
            fillPath.close();
        } else {
            for (int i = 0; i < n; i++) {
                float x = left + (right - left) * i / (n - 1f);
                float frac = Math.max(0.08f, Math.min(1f, curve[i] / ceiling));
                float y = sparkBottom - frac * sparkH;
                if (i == 0) {
                    linePath.moveTo(x, y);
                    fillPath.moveTo(x, sparkBottom);
                    fillPath.lineTo(x, y);
                } else {
                    linePath.lineTo(x, y);
                    fillPath.lineTo(x, y);
                }
            }
            fillPath.lineTo(right, sparkBottom);
            fillPath.close();
        }

        fillPaint.setColor((accent & 0x00FFFFFF) | 0x26000000);
        canvas.drawPath(fillPath, fillPaint);
        linePaint.setColor(accent);
        canvas.drawPath(linePath, linePaint);
    }
}
