package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.geely.drivemem.util.Style;

import java.util.Locale;

/** Darkest mode's hours (#130): one line for the 24 hours, from noon to
 * noon, so midnight is in the middle and a night window (21:00 to 06:00) is
 * one piece of line. Two handles, start and end, dragged along it in
 * 30-minute steps. The time of each handle is written above it.
 *
 * Replaces two -/+ rows whose buttons clipped on the Config card. A finger
 * grabs the nearer handle; the handles cannot cross and stay at least one
 * step apart, so the window never wraps over noon.
 *
 * Sized for the 1920 x 1080 panel read from ~75 cm (CLAUDE.md, display):
 * the handle is 26 px across and the hit area is the whole height. */
public final class HoursBar extends View {
    /** Called on finger up with the new start and end, minutes after midnight. */
    public interface OnChange { void changed(int startMin, int endMin); }

    static final int DAY = 24 * 60;
    static final int STEP = 30;
    /** Where the line starts: noon. */
    static final int ORIGIN = 12 * 60;

    private int startPos, endPos;   // minutes after noon, 0..DAY
    private int dragging = -1;      // 0 start, 1 end
    private final OnChange onChange;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float pad, radius, lineY, stroke;

    public HoursBar(Context c, int startMin, int endMin, OnChange onChange) {
        super(c);
        this.onChange = onChange;
        int[] p = positions(startMin, endMin);
        startPos = p[0];
        endPos = p[1];
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

    /** The two handles for stored hours. Start equal to end is the whole
     * day (ExtraDark.inHours): both handles at the ends of the line, which
     * is how it was picked, not the default (review, 2026-11-20). Anything
     * the line cannot show (the window wraps over noon) falls back to the
     * default 21:00 to 06:00. */
    static int[] positions(int startMin, int endMin) {
        if (((startMin - endMin) % DAY + DAY) % DAY == 0) return new int[]{0, DAY};
        int s = posOf(startMin), e = posOf(endMin);
        if (e - s < STEP) return new int[]{posOf(21 * 60), posOf(6 * 60)};
        return new int[]{s, e};
    }

        /** Minutes after midnight -> minutes after noon (the line's position). */
    static int posOf(int min) { return ((min - ORIGIN) % DAY + DAY) % DAY; }

    /** Minutes after noon -> minutes after midnight. */
    static int minOf(int pos) { return (pos + ORIGIN) % DAY; }

    /** A raw position snapped to the step and kept inside the line. */
    static int snap(float pos) {
        int p = Math.round(pos / STEP) * STEP;
        return Math.max(0, Math.min(DAY, p));
    }

    /** Moves one handle; it cannot pass the other and stays a step away. */
    static int[] move(int start, int end, int which, int pos) {
        if (which == 0) start = Math.max(0, Math.min(pos, end - STEP));
        else end = Math.min(DAY, Math.max(pos, start + STEP));
        return new int[]{start, end};
    }

    static String hhmm(int min) {
        min = ((min % DAY) + DAY) % DAY;
        return String.format(Locale.US, "%02d:%02d", min / 60, min % 60);
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) Style.dp(getContext(), 100));
    }

    private float xOf(int pos) { return pad + (getWidth() - 2 * pad) * pos / (float) DAY; }

    private float posAt(float x) { return (x - pad) / (getWidth() - 2 * pad) * DAY; }

    @Override protected void onDraw(Canvas c) {
        float x0 = xOf(0), x1 = xOf(DAY);
        c.drawLine(x0, lineY, x1, lineY, line);
        // ticks every 6 hours: 12:00 18:00 00:00 06:00 12:00
        for (int p = 0; p <= DAY; p += 6 * 60) {
            float x = xOf(p);
            c.drawLine(x, lineY + stroke, x, lineY + stroke + Style.dp(getContext(), 8), tick);
            c.drawText(hhmm(minOf(p)), x, lineY + Style.dp(getContext(), 38), tick);
        }
        float xs = xOf(startPos), xe = xOf(endPos);
        c.drawLine(xs, lineY, xe, lineY, on);
        c.drawCircle(xs, lineY, radius, knob);
        c.drawCircle(xe, lineY, radius, knob);
        // times above the handles, pushed apart when the handles are close
        float half = text.measureText("00:00") / 2f + Style.dp(getContext(), 4);
        float ts = xs, te = xe;
        if (te - ts < 2 * half) { float mid = (ts + te) / 2f; ts = mid - half; te = mid + half; }
        ts = Math.max(half, ts); te = Math.min(getWidth() - half, te);
        float ty = lineY - radius - Style.dp(getContext(), 10);
        c.drawText(hhmm(minOf(startPos)), ts, ty, text);
        c.drawText(hhmm(minOf(endPos)), te, ty, text);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float pos = posAt(e.getX());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = Math.abs(pos - startPos) <= Math.abs(pos - endPos) ? 0 : 1;
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through: a tap moves the nearer handle there
            case MotionEvent.ACTION_MOVE: {
                if (dragging < 0) return true;
                int[] r = move(startPos, endPos, dragging, snap(pos));
                if (r[0] != startPos || r[1] != endPos) { startPos = r[0]; endPos = r[1]; invalidate(); }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging >= 0 && onChange != null) onChange.changed(minOf(startPos), minOf(endPos));
                dragging = -1;
                return true;
            default:
                return true;
        }
    }
}
