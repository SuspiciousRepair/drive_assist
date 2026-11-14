package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.geely.drivemem.state.TempTrail;
import com.geely.drivemem.util.Style;

/**
 * Outside temperature over the stay in Park, as a sparkline.
 *
 * The line breaks where the head unit was asleep (a NaN bucket in the
 * TempTrail) and those stretches are drawn as an empty band with a dashed
 * floor, so a night asleep reads as "no data here", not as a straight line
 * from the evening reading to the morning one.
 *
 * Drawn from the hub's {@code parking.temp_trail}; this view never reads
 * the car or the database itself.
 */
final class TempSparkView extends View {
    private TempTrail trail = TempTrail.EMPTY;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sleep = new Paint();
    private final Paint floor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    TempSparkView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Style.dp(c, 3));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        dot.setStyle(Paint.Style.FILL);
        sleep.setStyle(Paint.Style.FILL);
        floor.setStyle(Paint.Style.STROKE);
        floor.setStrokeWidth(Style.dp(c, 2));
        floor.setPathEffect(new DashPathEffect(new float[]{Style.dp(c, 6), Style.dp(c, 6)}, 0));
    }

    void setTrail(TempTrail t) {
        TempTrail next = t == null ? TempTrail.EMPTY : t;
        if (next.equals(trail)) return;
        trail = next;
        invalidate();
    }

    private static int withAlpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0x00FFFFFF);
    }

    @Override protected void onDraw(Canvas canvas) {
        int n = trail.size();
        if (n == 0) return;
        float w = getWidth(), h = getHeight();
        float pad = Style.dp(getContext(), 4);
        float min = trail.min(), max = trail.max();
        if (Float.isNaN(min)) return;
        // A flat stay still gets a visible line: at least 2 °C of vertical room.
        float span = Math.max(2f, max - min);
        float mid = (min + max) / 2f;
        float lo = mid - span / 2f;
        float slot = w / n;

        line.setColor(Style.TEXT);
        dot.setColor(Style.TEXT);
        sleep.setColor(withAlpha(Style.TEXT_DIM, 0.12f));
        floor.setColor(withAlpha(Style.TEXT_DIM, 0.6f));

        // Asleep stretches first, under the line.
        int i = 0;
        while (i < n) {
            if (!Float.isNaN(trail.get(i))) { i++; continue; }
            int j = i;
            while (j < n && Float.isNaN(trail.get(j))) j++;
            float x0 = i * slot, x1 = j * slot;
            canvas.drawRect(x0, 0, x1, h, sleep);
            canvas.drawLine(x0, h - pad, x1, h - pad, floor);
            i = j;
        }

        // Awake runs: one path per run, never joined across a gap. A run of
        // a single bucket is a dot, so a short wake-up still shows.
        i = 0;
        while (i < n) {
            if (Float.isNaN(trail.get(i))) { i++; continue; }
            int j = i;
            path.reset();
            while (j < n && !Float.isNaN(trail.get(j))) {
                float x = (j + 0.5f) * slot;
                float y = pad + (h - 2 * pad) * (1f - (trail.get(j) - lo) / span);
                if (j == i) path.moveTo(x, y); else path.lineTo(x, y);
                j++;
            }
            if (j - i == 1) {
                float x = (i + 0.5f) * slot;
                float y = pad + (h - 2 * pad) * (1f - (trail.get(i) - lo) / span);
                canvas.drawCircle(x, y, line.getStrokeWidth(), dot);
            } else {
                canvas.drawPath(path, line);
            }
            i = j;
        }
    }
}
