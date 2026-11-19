package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import com.geely.drivemem.util.Style;

/** The whole Valet interval as one bar: parked time is the plain track, each
 * move a block in the accent colour, at the time it happened. The start and
 * end clock times sit under the two ends. */
final class ValetTimelineView extends View {
    private long vs, ve;
    private long[] starts = new long[0], ends = new long[0];
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
        float base = top + barH + Style.dp(c, 24);
        text.setTextAlign(Paint.Align.LEFT);
        cv.drawText(SessionText.clock(vs), 0, base, text);
        text.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(SessionText.clock(ve), w, base, text);
    }
}
