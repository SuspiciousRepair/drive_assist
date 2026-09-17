package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.util.Style;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Vertically scrollable horizontal-bar chart of each session's battery range,
 * drawn top-to-bottom in whatever order setSessions() is given -- ChargeStatsView
 * passes the same newest-first order as its session list, so the two agree. */
public final class ChargeSocChart extends ScrollView {
    public interface Listener { void onSessionSelected(long id); }
    private final Plot plot;
    private Listener listener;

    public ChargeSocChart(Context c) {
        super(c);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        plot = new Plot(c);
        addView(plot, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public void setListener(Listener value) { listener = value; }

    public void setSessions(List<ChargeSession.Summary> values, long selectedId) {
        plot.sessions = new ArrayList<>(values);
        plot.selectedId = selectedId;
        plot.setMinimumHeight(Style.dp(getContext(), 46 + Math.max(1, values.size()) * Plot.ROW));
        plot.requestLayout();
        plot.invalidate();
    }

    public void select(long id, boolean reveal) {
        plot.selectedId = id;
        plot.invalidate();
        if (reveal) {
            int i = plot.indexOf(id);
            if (i >= 0) post(() -> smoothScrollTo(0,
                    Math.max(0, plot.barCenterY(i) - getHeight() / 2)));
        }
    }

    private final class Plot extends View {
        private static final int LEFT = 52, RIGHT = 16, TOP = 34, BOTTOM = 8, ROW = 46;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SimpleDateFormat date = new SimpleDateFormat("d/M", Locale.getDefault());
        private List<ChargeSession.Summary> sessions = new ArrayList<>();
        private long selectedId = -1;

        Plot(Context c) { super(c); setClickable(true); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int height = Style.dp(getContext(), TOP + BOTTOM + Math.max(1, sessions.size()) * ROW);
            setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                    resolveSize(Math.max(getSuggestedMinimumHeight(), height), heightSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            float left = Style.dp(getContext(), LEFT);
            float top = Style.dp(getContext(), TOP);
            float right = getWidth() - Style.dp(getContext(), RIGHT);
            float axisWidth = right - left;
            float bottom = getHeight() - Style.dp(getContext(), BOTTOM);
            paint.setTextSize(Style.dp(getContext(), 12));
            paint.setTypeface(Style.font(getContext()));
            paint.setStrokeWidth(1f);
            for (int pct = 0; pct <= 100; pct += 25) {
                float x = left + axisWidth * pct / 100f;
                paint.setColor(Style.blend(Style.cardFillColor(), Style.TEXT_DIM, .18f));
                canvas.drawLine(x, top, x, bottom, paint);
                paint.setColor(Style.TEXT_DIM);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(pct + "%", x, top - Style.dp(getContext(), 10), paint);
            }
            for (int i = 0; i < sessions.size(); i++) {
                ChargeSession.Summary s = sessions.get(i);
                float cy = barCenterY(i);
                float half = Style.dp(getContext(), s.id == selectedId ? 13 : 11);
                float xStart = left + axisWidth * Math.min(clamp(s.socStart), clamp(s.socEnd)) / 100f;
                float xEnd = left + axisWidth * Math.max(clamp(s.socStart), clamp(s.socEnd)) / 100f;

                // "The battery is always there": a full 0-100% track behind the
                // session's own charged range, so that range reads in context of
                // the whole pack instead of floating on blank space -- what it
                // already had before this session (faint blue) and the headroom
                // left afterward (light grey), same rounded shape as the segment
                // itself. Drawn before the segment below, which paints over the
                // middle third unchanged.
                paint.setColor(Style.blend(Style.cardFillColor(), Style.TEXT_DIM, .18f));
                canvas.drawRoundRect(new RectF(left, cy - half, right, cy + half), 7, 7, paint);
                if (xStart > left) {
                    paint.setColor(Style.blend(Style.cardFillColor(), Style.ACCENT, .18f));
                    canvas.drawRoundRect(new RectF(left, cy - half, xStart, cy + half), 7, 7, paint);
                }

                int barColor = !s.hasChargeVoltage() ? Style.TEXT_DIM : s.isDcfc() ? Style.GOOD : Style.ACCENT;
                paint.setColor(barColor);
                paint.setAlpha(s.id == selectedId ? 255 : 205);
                // Never thinner than tall (same floor Style.chargeRangeBar uses), so
                // a near-zero SoC gain still leaves room for the duration label below.
                RectF bar = new RectF(xStart, cy - half, Math.max(xStart + half * 2, xEnd), cy + half);
                canvas.drawRoundRect(bar, 7, 7, paint);
                paint.setAlpha(255);

                String dur = s.durationLabel();
                if (dur != null && !dur.isEmpty()) {
                    paint.setColor(Style.onFill(barColor));
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTextSize(half * 0.9f);
                    paint.setFakeBoldText(true);
                    float ty = cy - (paint.descent() + paint.ascent()) / 2f;
                    canvas.drawText(dur, (bar.left + bar.right) / 2f, ty, paint);
                    paint.setFakeBoldText(false);
                    paint.setTextSize(Style.dp(getContext(), 12));
                }
                if (s.id == selectedId) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Style.dp(getContext(), 3));
                    paint.setColor(Style.TEXT);
                    canvas.drawRoundRect(new RectF(bar.left - 3, bar.top - 3, bar.right + 3, bar.bottom + 3), 9, 9, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                paint.setColor(Style.TEXT_DIM);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(date.format(new Date(s.startWallMs)), left - Style.dp(getContext(), 8), cy + 4, paint);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP && !sessions.isEmpty()) {
                int i = (int) ((event.getY() - Style.dp(getContext(), TOP))
                        / Style.dp(getContext(), ROW));
                if (i >= 0 && i < sessions.size()) {
                    performClick();
                    if (listener != null) listener.onSessionSelected(sessions.get(i).id);
                }
            }
            return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }
        int indexOf(long id) { for (int i = 0; i < sessions.size(); i++) if (sessions.get(i).id == id) return i; return -1; }
        int barCenterY(int i) { return Style.dp(getContext(), TOP + ROW / 2 + i * ROW); }
        float clamp(int value) { return Math.max(0, Math.min(100, value)); }
    }
}
