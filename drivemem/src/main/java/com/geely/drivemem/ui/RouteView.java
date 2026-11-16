package com.geely.drivemem.ui;

import com.geely.drivemem.util.Style;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A trip's route as lines on a plain background (issue #19): no map. Each
 * segment between two points gets the colour of their average speed
 * (SpeedBucketColors). North is up. A dot marks the start, a ring
 * the end, and a bar in the corner gives the scale.
 *
 * The geometry is built once ({@link #rebuild}: on new data and on a size
 * change), not in onDraw: the marker moves on every touch event while a
 * finger scrubs the height profile, and each of those frames only replays
 * the cached paths. */
public class RouteView extends View {
    /** Points closer than this on screen are drawn as one. */
    static final float DECIMATE_PX = 2f;

    private double[] xs = new double[0], ys = new double[0], speed = new double[0];
    private int marker = -1;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint casing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int pad;

    // Built by rebuild(); screen pixels.
    private float[] sx = new float[0], sy = new float[0];
    private final Path casingPath = new Path();
    private Path[] runPaths = new Path[0];
    private int[] runColors = new int[0];
    private float barPx;
    private String barLabel;

    public RouteView(Context c) {
        super(c);
        for (Paint p : new Paint[]{line, casing}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        line.setStrokeWidth(Style.dp(c, 6));
        // A dark edge under the colour, so the line reads on any theme.
        casing.setStrokeWidth(Style.dp(c, 10));
        casing.setColor(0x99000000);
        text.setColor(Style.TEXT_DIM);
        text.setTextSize(Style.dp(c, 18));
        bar.setColor(Style.TEXT_DIM);
        bar.setStrokeWidth(Style.dp(c, 3));
        pad = Style.dp(c, 40);
    }

    /** lat, lon in degrees, speed in km/h; same length, oldest first. */
    public void setRoute(double[] lat, double[] lon, double[] speedKmh) {
        double[][] xy = RouteGeometry.project(lat, lon);
        xs = xy[0]; ys = xy[1]; speed = speedKmh;
        rebuild();
        invalidate();
    }

    /** Marks one point (picked on the height profile); -1 for none. */
    public void setMarker(int index) {
        if (index == marker) return;
        marker = index;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        rebuild();
    }

    /** Screen coordinates, one path per run of one colour, and the scale
     * bar. Runs on new data and on a size change only. */
    private void rebuild() {
        int n = xs.length, w = getWidth(), h = getHeight();
        casingPath.rewind();
        runPaths = new Path[0];
        runColors = new int[0];
        sx = new float[n]; sy = new float[n];
        barPx = 0; barLabel = null;
        if (n == 0 || w <= 0 || h <= 0) return;

        RouteGeometry.Fit f = RouteGeometry.fit(xs, ys, w, h, pad);
        for (int i = 0; i < n; i++) { sx[i] = f.x(xs[i]); sy[i] = f.y(ys[i]); }

        int[] runs = RouteGeometry.bandRuns(speed);
        int[] colors = SpeedBucketColors.get();
        int count = Math.max(0, runs.length - 1);
        runPaths = new Path[count];
        runColors = new int[count];
        for (int j = 0; j < count; j++) {
            int[] keep = RouteGeometry.decimate(sx, sy, runs[j], runs[j + 1], DECIMATE_PX);
            Path p = new Path();
            p.moveTo(sx[keep[0]], sy[keep[0]]);
            if (j == 0) casingPath.moveTo(sx[keep[0]], sy[keep[0]]);
            for (int k = 1; k < keep.length; k++) {
                p.lineTo(sx[keep[k]], sy[keep[k]]);
                casingPath.lineTo(sx[keep[k]], sy[keep[k]]);
            }
            runPaths[j] = p;
            runColors[j] = colors[RouteGeometry.segmentBand(speed, runs[j])];
        }

        // Scale bar: a round length up to a quarter of the width.
        double barM = RouteGeometry.niceLengthM(w / 4.0 / f.pxPerM);
        if (barM > 0) {
            barPx = (float) (barM * f.pxPerM);
            barLabel = RouteGeometry.lengthLabel(barM);
        }
    }

    @Override protected void onDraw(Canvas c) {
        int n = sx.length;
        if (n == 0) return;
        Context ctx = getContext();

        c.drawPath(casingPath, casing);
        for (int j = 0; j < runPaths.length; j++) {
            line.setColor(runColors[j]);
            c.drawPath(runPaths[j], line);
        }

        // Start: a filled dot. End: a ring. Both white with a dark edge.
        float r = Style.dp(ctx, 11);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(0xCC000000);
        c.drawCircle(sx[0], sy[0], r + Style.dp(ctx, 3), dot);
        c.drawCircle(sx[n - 1], sy[n - 1], r + Style.dp(ctx, 3), dot);
        dot.setColor(0xFFFFFFFF);
        c.drawCircle(sx[0], sy[0], r, dot);
        dot.setStyle(Paint.Style.STROKE);
        dot.setStrokeWidth(Style.dp(ctx, 4));
        c.drawCircle(sx[n - 1], sy[n - 1], r - Style.dp(ctx, 2), dot);

        if (marker >= 0 && marker < n) {
            float mx = sx[marker], my = sy[marker];
            dot.setStyle(Paint.Style.FILL);
            dot.setColor(0xCC000000);
            c.drawCircle(mx, my, Style.dp(ctx, 15), dot);
            dot.setColor(Style.TEXT);
            c.drawCircle(mx, my, Style.dp(ctx, 9), dot);
        }

        // Scale bar, bottom left.
        if (barLabel != null) {
            float x0 = pad, yb = getHeight() - Style.dp(ctx, 16);
            float x1 = x0 + barPx;
            c.drawLine(x0, yb, x1, yb, bar);
            c.drawLine(x0, yb - Style.dp(ctx, 8), x0, yb, bar);
            c.drawLine(x1, yb - Style.dp(ctx, 8), x1, yb, bar);
            c.drawText(barLabel, x1 + Style.dp(ctx, 10), yb, text);
        }
        // North arrow, top right.
        float nx = getWidth() - pad, ny = Style.dp(ctx, 30);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText("N ↑", nx, ny, text);
        text.setTextAlign(Paint.Align.LEFT);
    }
}
