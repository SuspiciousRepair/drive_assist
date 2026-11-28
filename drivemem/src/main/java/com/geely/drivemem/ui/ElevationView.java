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

    /** What a vertical event line marks (issue #210). */
    public static final int EVENT_STOP = 0, EVENT_PARK = 1, EVENT_CHARGE = 2, EVENT_TOLL = 3;
    /** Point index, kind and label of each event line: tolls, stops, the
     * parking between joined trips and charges on the way. */
    private int[] evIdx = new int[0], evKind = new int[0];
    private String[] evLabel = new String[0];
    /** Split mode: the two parts are tinted, cut at points cutA..cutB. */
    private int cutA = -1, cutB = -1;
    private OnPick onPick;
    private final Paint fill = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint();

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
        tick.setColor((Style.ACCENT & 0x00FFFFFF) | 0xB0000000);
        tick.setStrokeWidth(Style.dp(c, 2));
        tick.setPathEffect(new android.graphics.DashPathEffect(
            new float[]{Style.dp(c, 6), Style.dp(c, 5)}, 0));
        chipText.setTextSize(Style.dp(c, 17));
        chipText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        chipText.setTextAlign(Paint.Align.LEFT);
    }

    /** km from the start, smoothed height in m, speed km/h; one per point. */
    public void setProfile(double[] km, double[] alt, double[] speed) {
        this.km = km; this.alt = alt; this.speed = speed;
        marked = -1;   // the old mark points into the old profile
        rebuild();
        invalidate();
    }

    public void setOnPick(OnPick p) { onPick = p; }

    /** A vertical line with a label on top at each point index: a toll
     * plaza or Free Flow gantry (so a slow-down lines up with its toll), a
     * stop, the parking between joined trips, a charge on the way.
     * Indexes outside the profile are skipped. Labels may be null. */
    public void setEvents(int[] pointIndexes, int[] kinds, String[] labels) {
        int n = pointIndexes == null ? 0 : pointIndexes.length;
        evIdx = n == 0 ? new int[0] : pointIndexes.clone();
        evKind = new int[n];
        evLabel = new String[n];
        for (int i = 0; i < n; i++) {
            evKind[i] = kinds != null && i < kinds.length ? kinds[i] : EVENT_TOLL;
            evLabel[i] = labels != null && i < labels.length ? labels[i] : null;
        }
        rebuild();
        invalidate();
    }

    /** The point the finger last picked, or -1. */
    public int marked() { return marked; }

    /** Moves the mark to point i (the split picker snapping to a stop). */
    public void setMarked(int i) {
        marked = i;
        invalidate();
    }

    /** Split mode: tints the part before point a and the part after point
     * b. -1 clears it. */
    public void setCut(int a, int b) {
        cutA = a; cutB = b;
        invalidate();
    }

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
        // Room for the event labels above the chart when there are any.
        top = Style.dp(ctx, evIdx.length > 0 ? 50 : 12); bottom = getHeight() - Style.dp(ctx, 26);
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

    /** The event lines, dashed, each in its kind's colour, with a small
     * label on top. A label that would overlap one already drawn is left
     * out; its line stays. Charges and stops get their label first: a trip
     * has few of them, and a toll beside one must not hide it. */
    private void drawEvents(Canvas c, int n) {
        Context ctx = getContext();
        java.util.List<float[]> taken = new java.util.ArrayList<>();   // {left, right} of each label
        float h = Style.dp(ctx, 32), padX = Style.dp(ctx, 8), icon = Style.dp(ctx, 20), gap = Style.dp(ctx, 4);
        Integer[] order = new Integer[evIdx.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            int ta = evKind[a] == EVENT_TOLL ? 1 : 0, tb = evKind[b] == EVENT_TOLL ? 1 : 0;
            return ta != tb ? Integer.compare(ta, tb) : Integer.compare(evIdx[a], evIdx[b]);
        });
        for (int k : order) {
            int i = evIdx[k];
            if (i < 0 || i >= n) continue;
            int color = eventColor(evKind[k]);
            tick.setColor(evKind[k] == EVENT_TOLL ? (color & 0x00FFFFFF) | 0xB0000000 : color);
            c.drawLine(xs[i], top, xs[i], bottom, tick);

            String label = evLabel[k];
            int iconRes = eventIcon(evKind[k]);
            float textW = label == null ? 0 : chipText.measureText(label);
            float w = padX * 2 + (iconRes != 0 ? icon : 0) + (iconRes != 0 && label != null ? gap : 0) + textW;
            float left = Math.max(0, Math.min(getWidth() - w, xs[i] - w / 2));
            boolean free = true;
            for (float[] t : taken) if (left < t[1] + gap && left + w > t[0] - gap) { free = false; break; }
            if (!free) continue;
            taken.add(new float[]{left, left + w});
            float y0 = Style.dp(ctx, 6);
            // A stop's line is in the text colour, but its chip is not: text
            // on a chip of the text colour does not show (a blank white box).
            int fill = evKind[k] == EVENT_PARK ? Style.CARD_HI : color;
            chip.setColor(fill);
            c.drawRoundRect(left, y0, left + w, y0 + h, Style.dp(ctx, 8), Style.dp(ctx, 8), chip);
            int fg = Style.onFill(fill);
            float x = left + padX;
            if (iconRes != 0) {
                android.graphics.drawable.Drawable d = ctx.getDrawable(iconRes);
                if (d != null) {
                    d = d.mutate();
                    d.setTint(fg);
                    int iy = (int) (y0 + (h - icon) / 2);
                    d.setBounds((int) x, iy, (int) (x + icon), (int) (iy + icon));
                    d.draw(c);
                }
                x += icon + gap;
            }
            if (label != null) {
                chipText.setColor(fg);
                c.drawText(label, x, y0 + h / 2 - (chipText.ascent() + chipText.descent()) / 2, chipText);
            }
        }
    }

    private static int eventColor(int kind) {
        switch (kind) {
            case EVENT_PARK: return Style.TEXT;
            case EVENT_CHARGE: return Style.GOOD;
            case EVENT_TOLL: return Style.ACCENT;
            default: return Style.CARD_HI;
        }
    }

    private static int eventIcon(int kind) {
        switch (kind) {
            case EVENT_PARK: return com.geely.drivemem.R.drawable.ic_parking;
            case EVENT_CHARGE: return com.geely.drivemem.R.drawable.ic_flash;
            case EVENT_TOLL: return com.geely.drivemem.R.drawable.ic_toll;
            default: return 0;
        }
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
        if (cutA > 0 && cutA < n && cutB >= cutA && cutB < n) {
            tint.setColor((Style.ACCENT & 0x00FFFFFF) | 0x40000000);
            c.drawRect(l, top, xs[cutA], bottom, tint);
            tint.setColor((Style.HEAT & 0x00FFFFFF) | 0x40000000);
            c.drawRect(xs[cutB], top, r, bottom, tint);
        }
        drawEvents(c, n);
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
            mark.setStrokeWidth(Style.dp(ctx, cutA >= 0 ? 5 : 2));
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
