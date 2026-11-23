package com.geely.drivemem.ui;

import com.geely.drivemem.util.Style;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/** Height against distance, under the route (issue #19). The area under
 * the line has the same speed colours as the route, so a slow climb is
 * easy to spot. Touching it marks that place on the route.
 *
 * The geometry is built once ({@link #rebuild}: on new data and on a size
 * change), not in onDraw: a finger scrubbing the profile redraws on every
 * move, and each of those frames only replays the cached paths and moves
 * the mark. */
public class ElevationView extends View {
    public interface OnPick { void onPick(int index); }

    private double[] km = new double[0], alt = new double[0], speed = new double[0];
    private int marked = -1;
    private OnPick onPick;
    private final Paint fill = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Built by rebuild(); screen pixels.
    private float[] xs = new float[0], ys = new float[0];
    private Path[] fillPaths = new Path[0];
    private int[] fillColors = new int[0];
    private final Path topPath = new Path();
    private float top, bottom;
    private String hiLabel = "", loLabel = "", kmLabel = "";

    public ElevationView(Context c) {
        super(c);
        fill.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Style.dp(c, 3));
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(Style.TEXT);
        text.setColor(Style.TEXT_DIM);
        text.setTextSize(Style.dp(c, 18));
        mark.setColor(Style.TEXT);
        mark.setStrokeWidth(Style.dp(c, 2));
    }

    /** km from the start, smoothed height in m, speed km/h; one per point. */
    public void setProfile(double[] km, double[] alt, double[] speed) {
        this.km = km; this.alt = alt; this.speed = speed;
        marked = -1;   // the old mark points into the old profile
        rebuild();
        invalidate();
    }

    public void setOnPick(OnPick p) { onPick = p; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (km.length < 2) return false;
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
            float l = left(), r = getWidth() - right();
            double target = (Math.max(l, Math.min(r, e.getX())) - l) / Math.max(1, r - l) * total();
            int pick = TripRouteStats.indexAtKm(km, target);
            if (pick != marked) {   // many move events land on the same point
                marked = pick;
                if (onPick != null) onPick.onPick(marked);
                invalidate();
            }
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        return act == MotionEvent.ACTION_UP;
    }

    private float left() { return Style.dp(getContext(), 90); }
    private float right() { return Style.dp(getContext(), 20); }
    private double total() { return km.length == 0 ? 0 : Math.max(km[km.length - 1], 0.01); }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        rebuild();
    }

    /** Screen coordinates, one filled shape per run of one colour, the
     * line on top and the labels. Runs on new data and on a size change
     * only. */
    private void rebuild() {
        int n = km.length;
        topPath.rewind();
        fillPaths = new Path[0];
        fillColors = new int[0];
        xs = new float[0]; ys = new float[0];
        if (n < 2 || getWidth() <= 0 || getHeight() <= 0) return;
        Context ctx = getContext();
        float l = left(), r = getWidth() - right();
        top = Style.dp(ctx, 12); bottom = getHeight() - Style.dp(ctx, 26);
        double[] range = range(TripRouteStats.min(alt), TripRouteStats.max(alt));
        double lo = range[0], hi = range[1];
        double tot = total();

        xs = new float[n]; ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = (float) (l + km[i] / tot * (r - l));
            ys[i] = (float) (bottom - (alt[i] - lo) / (hi - lo) * (bottom - top));
        }
        int[] runs = RouteGeometry.bandRuns(speed);
        int[] colors = SpeedBucketColors.get();
        int count = Math.max(0, runs.length - 1);
        fillPaths = new Path[count];
        fillColors = new int[count];
        for (int j = 0; j < count; j++) {
            int a = runs[j], b = runs[j + 1];
            Path p = new Path();
            p.moveTo(xs[a], bottom);
            for (int i = a; i <= b; i++) p.lineTo(xs[i], ys[i]);
            p.lineTo(xs[b], bottom);
            p.close();
            fillPaths[j] = p;
            int col = colors[RouteGeometry.segmentBand(speed, a)];
            fillColors[j] = (col & 0x00FFFFFF) | 0x99000000;
        }
        topPath.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) topPath.lineTo(xs[i], ys[i]);

        hiLabel = String.format(Locale.US, "%.0f m", hi);
        loLabel = String.format(Locale.US, "%.0f m", lo);
        kmLabel = String.format(Locale.US, "%.1f km", km[n - 1]);
    }

    /** At least this much height on screen. A city trip climbs some tens
     * of metres; drawn over the whole chart that looked like a roller
     * coaster. With 200 m, 50 m of hills fill a quarter of the height and
     * a mountain road still uses all of it. */
    static final double MIN_SPAN_M = 200;

    /** The two labels are multiples of this: "600 m", not "639 m". */
    static final double STEP_M = 50;

    /** The heights at the bottom and the top of the chart: the trip's
     * lowest and highest, widened around their middle to MIN_SPAN_M, then
     * moved out to round numbers (STEP_M), so nothing is cut off.
     *
     * The widening never takes the bottom under sea level: a trip by the
     * coast (5..40 m) had a chart that started at "-78 m", a height nobody
     * measured. The bottom goes under 0 only when the trip's own lowest
     * point does, to the round number just under that point. */
    static double[] range(double lo, double hi) {
        double span = Math.max(hi - lo, MIN_SPAN_M), mid = (lo + hi) / 2;
        double bottom = Math.floor(Math.max(mid - span / 2, Math.min(0, lo)) / STEP_M) * STEP_M;
        double top = Math.ceil(Math.max(bottom + span, hi) / STEP_M) * STEP_M;
        return new double[]{bottom, top};
    }

    @Override protected void onDraw(Canvas c) {
        int n = xs.length;
        if (n < 2) return;
        Context ctx = getContext();
        float l = left(), r = getWidth() - right();

        for (int j = 0; j < fillPaths.length; j++) {
            fill.setColor(fillColors[j]);
            c.drawPath(fillPaths[j], fill);
        }
        c.drawPath(topPath, line);

        // Height labels on the left (top and bottom of the drawn range),
        // distance under the right end.
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(hiLabel, l - Style.dp(ctx, 10), top + Style.dp(ctx, 14), text);
        c.drawText(loLabel, l - Style.dp(ctx, 10), bottom, text);
        c.drawText(kmLabel, r, getHeight() - Style.dp(ctx, 4), text);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText("0", l, getHeight() - Style.dp(ctx, 4), text);

        if (marked >= 0 && marked < n) {
            c.drawLine(xs[marked], top, xs[marked], bottom, mark);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(Style.TEXT);
            c.drawText(String.format(Locale.US, "%.0f m · %.0f km/h", alt[marked], speed[marked]),
                Math.max(l + Style.dp(ctx, 90), Math.min(r - Style.dp(ctx, 90), xs[marked])),
                getHeight() - Style.dp(ctx, 4), text);
            text.setColor(Style.TEXT_DIM);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }
}
