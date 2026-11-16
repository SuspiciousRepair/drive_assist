package com.geely.drivemem.ui;

import com.geely.drivemem.util.Style;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A trip's route as lines on a plain background (issue #19): no map. Each
 * segment between two points gets the colour of their average speed
 * (RouteGeometry.SPEED_COLORS). North is up. A dot marks the start, a ring
 * the end, and a bar in the corner gives the scale. */
public class RouteView extends View {
    private double[] xs = new double[0], ys = new double[0], speed = new double[0];
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint casing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

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
    }

    /** lat, lon in degrees, speed in km/h; same length, oldest first. */
    public void setRoute(double[] lat, double[] lon, double[] speedKmh) {
        double[][] xy = RouteGeometry.project(lat, lon);
        xs = xy[0]; ys = xy[1]; speed = speedKmh;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        int n = xs.length;
        if (n == 0) return;
        Context ctx = getContext();
        int pad = Style.dp(ctx, 40);
        RouteGeometry.Fit f = RouteGeometry.fit(xs, ys, getWidth(), getHeight(), pad);

        for (int i = 1; i < n; i++) {
            c.drawLine(f.x(xs[i - 1]), f.y(ys[i - 1]), f.x(xs[i]), f.y(ys[i]), casing);
        }
        for (int i = 1; i < n; i++) {
            double avg = (speed[i - 1] + speed[i]) / 2;
            line.setColor(RouteGeometry.SPEED_COLORS[RouteGeometry.band(avg)]);
            c.drawLine(f.x(xs[i - 1]), f.y(ys[i - 1]), f.x(xs[i]), f.y(ys[i]), line);
        }

        // Start: a filled dot. End: a ring. Both white with a dark edge.
        float r = Style.dp(ctx, 11);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(0xCC000000);
        c.drawCircle(f.x(xs[0]), f.y(ys[0]), r + Style.dp(ctx, 3), dot);
        c.drawCircle(f.x(xs[n - 1]), f.y(ys[n - 1]), r + Style.dp(ctx, 3), dot);
        dot.setColor(0xFFFFFFFF);
        c.drawCircle(f.x(xs[0]), f.y(ys[0]), r, dot);
        dot.setStyle(Paint.Style.STROKE);
        dot.setStrokeWidth(Style.dp(ctx, 4));
        c.drawCircle(f.x(xs[n - 1]), f.y(ys[n - 1]), r - Style.dp(ctx, 2), dot);

        // Scale bar, bottom left: a round length up to a quarter of the width.
        double barM = RouteGeometry.niceLengthM(getWidth() / 4.0 / f.pxPerM);
        if (barM > 0) {
            float x0 = pad, yb = getHeight() - Style.dp(ctx, 16);
            float x1 = x0 + (float) (barM * f.pxPerM);
            Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
            bar.setColor(Style.TEXT_DIM);
            bar.setStrokeWidth(Style.dp(ctx, 3));
            c.drawLine(x0, yb, x1, yb, bar);
            c.drawLine(x0, yb - Style.dp(ctx, 8), x0, yb, bar);
            c.drawLine(x1, yb - Style.dp(ctx, 8), x1, yb, bar);
            c.drawText(RouteGeometry.lengthLabel(barM), x1 + Style.dp(ctx, 10), yb, text);
        }
        // North arrow, top right.
        float nx = getWidth() - pad, ny = Style.dp(ctx, 30);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText("N ↑", nx, ny, text);
        text.setTextAlign(Paint.Align.LEFT);
    }
}
