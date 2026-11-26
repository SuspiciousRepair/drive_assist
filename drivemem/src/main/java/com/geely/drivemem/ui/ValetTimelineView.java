package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.geely.drivemem.util.Style;

/** The whole Valet interval as one bar: parked time is the plain track, each
 * move a block in the accent colour, at the time it happened. Optional amber
 * ticks mark hard bumps; tapping a tick invokes {@link OnTickPick}. */
final class ValetTimelineView extends View {
    interface OnTickPick { void onTickPick(int index); }

    private long vs, ve;
    private long[] starts = new long[0], ends = new long[0];
    private long[] ticks = new long[0];
    private float[] tickX = new float[0];
    private OnTickPick onTickPick;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    ValetTimelineView(Context c) {
        super(c);
        text.setTextSize(Style.dp(c, 17));
        text.setColor(Style.TEXT_DIM);
    }

    void setData(long vs, long ve, long[] starts, long[] ends) {
        this.vs = vs; this.ve = ve; this.starts = starts; this.ends = ends;
        invalidate();
    }

    void setTicks(long[] tickMs) {
        this.ticks = tickMs != null ? tickMs : new long[0];
        invalidate();
    }

    void setOnTickPick(OnTickPick l) { onTickPick = l; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (onTickPick == null || tickX.length == 0) return super.onTouchEvent(e);
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float x = e.getX();
            float best = Style.dp(getContext(), 40);
            int hit = -1;
            for (int i = 0; i < tickX.length; i++) {
                float d = Math.abs(tickX[i] - x);
                if (d < best) { best = d; hit = i; }
            }
            if (hit >= 0) onTickPick.onTickPick(hit);
        }
        return true;
    }

    @Override protected void onDraw(Canvas cv) {
        if (ve <= vs) return;
        Context c = getContext();
        float barH = Style.dp(c, 22), top = Style.dp(c, 8), rad = barH / 2;
        float w = getWidth();
        r.set(0, top, w, top + barH);
        fill.setColor(Style.CARD_HI);
        cv.drawRoundRect(r, rad, rad, fill);
        fill.setColor(Style.ACCENT);
        float[] b = ValetDetail.blocks(vs, ve, starts, ends, w, Style.dp(c, 6));
        for (int i = 0; i + 1 < b.length; i += 2) {
            r.set(b[i], top, b[i + 1], top + barH);
            cv.drawRoundRect(r, Style.dp(c, 3), Style.dp(c, 3), fill);
        }
        if (ticks.length > 0) {
            tickX = new float[ticks.length];
            float tw = Style.dp(c, 4), th = Style.dp(c, 30), tr = Style.dp(c, 2);
            float tTop = top - Style.dp(c, 4);
            double span = Math.max(1, ve - vs);
            fill.setColor(Style.WARN);
            for (int i = 0; i < ticks.length; i++) {
                float cx = (float) (Math.max(0, Math.min(span, ticks[i] - vs)) / span * w);
                tickX[i] = cx;
                r.set(cx - tw / 2, tTop, cx + tw / 2, tTop + th);
                cv.drawRoundRect(r, tr, tr, fill);
            }
        } else {
            tickX = new float[0];
        }
        float base = top + barH + Style.dp(c, 24);
        text.setTextAlign(Paint.Align.LEFT);
        cv.drawText(SessionText.clock(vs), 0, base, text);
        text.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(SessionText.clock(ve), w, base, text);
    }
}
