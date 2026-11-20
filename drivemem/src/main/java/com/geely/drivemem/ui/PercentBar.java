package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import com.geely.drivemem.util.Style;

/** Darkest mode's darkness (#130): one line from 0 % to 100 % with one
 * handle, in steps of 10, the value written above the handle. Same look and
 * size as HoursBar, the line above it on the Config card.
 *
 * Replaces five buttons (50 to 90 %); the owner wanted the whole range,
 * with 100 % turning the panel off (ExtraDark.OFF_PCT).
 *
 * A tap moves the handle there; the value is saved on finger up. */
public final class PercentBar extends View {
    /** Called on finger up with the new value, 0..100. */
    public interface OnChange { void changed(int pct); }

    static final int STEP = 10;

    private int value;
    private boolean dragging;
    private final OnChange onChange;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float pad, radius, lineY, stroke;

    public PercentBar(Context c, int pct, OnChange onChange) {
        super(c);
        this.onChange = onChange;
        value = snap(pct);
        pad = Style.dp(c, 40);
        radius = Style.dp(c, 13);
        stroke = Style.dp(c, 6);
        lineY = Style.dp(c, 52);
        line.setColor(Style.CARD_HI); line.setStrokeWidth(stroke); line.setStrokeCap(Paint.Cap.ROUND);
        on.setColor(Style.ACCENT); on.setStrokeWidth(stroke); on.setStrokeCap(Paint.Cap.ROUND);
        knob.setColor(Style.TEXT);
        text.setColor(Style.TEXT); text.setTextSize(Style.dp(c, 20)); text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        tick.setColor(Style.TEXT_DIM); tick.setTextSize(Style.dp(c, 16)); tick.setTextAlign(Paint.Align.CENTER);
        tick.setStrokeWidth(Style.dp(c, 2));
        setMinimumHeight((int) Style.dp(c, 100));
    }

    /** Any value to the nearest step, kept in 0..100. */
    static int snap(float pct) {
        int p = Math.round(pct / STEP) * STEP;
        return Math.max(0, Math.min(100, p));
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) Style.dp(getContext(), 100));
    }

    private float xOf(int pct) { return pad + (getWidth() - 2 * pad) * pct / 100f; }

    private float pctAt(float x) { return (x - pad) / (getWidth() - 2 * pad) * 100f; }

    @Override protected void onDraw(Canvas c) {
        float x0 = xOf(0), x1 = xOf(100), xv = xOf(value);
        c.drawLine(x0, lineY, x1, lineY, line);
        for (int p = 0; p <= 100; p += 50) {
            float x = xOf(p);
            c.drawLine(x, lineY + stroke, x, lineY + stroke + Style.dp(getContext(), 8), tick);
            c.drawText(p + " %", x, lineY + Style.dp(getContext(), 38), tick);
        }
        c.drawLine(x0, lineY, xv, lineY, on);
        c.drawCircle(xv, lineY, radius, knob);
        float half = text.measureText("100 %") / 2f;
        float tx = Math.max(half, Math.min(getWidth() - half, xv));
        c.drawText(value + " %", tx, lineY - radius - Style.dp(getContext(), 10), text);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through: a tap moves the handle there
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return true;
                int v = snap(pctAt(e.getX()));
                if (v != value) { value = v; invalidate(); }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging && onChange != null) onChange.changed(value);
                dragging = false;
                return true;
            default:
                return true;
        }
    }
}
