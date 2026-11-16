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
 * easy to spot. Touching it marks that place on the route. */
public class ElevationView extends View {
    public interface OnPick { void onPick(int index); }

    private double[] km = new double[0], alt = new double[0], speed = new double[0];
    private int marked = -1;
    private OnPick onPick;
    private final Paint fill = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        invalidate();
    }

    public void setOnPick(OnPick p) { onPick = p; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (km.length < 2) return false;
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
            float l = left(), r = getWidth() - right();
            double target = (Math.max(l, Math.min(r, e.getX())) - l) / Math.max(1, r - l) * total();
            marked = TripRouteStats.indexAtKm(km, target);
            if (onPick != null) onPick.onPick(marked);
            invalidate();
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        return act == MotionEvent.ACTION_UP;
    }

    private float left() { return Style.dp(getContext(), 90); }
    private float right() { return Style.dp(getContext(), 20); }
    private double total() { return km.length == 0 ? 0 : Math.max(km[km.length - 1], 0.01); }

    @Override protected void onDraw(Canvas c) {
        int n = km.length;
        if (n < 2) return;
        Context ctx = getContext();
        float l = left(), r = getWidth() - right();
        float top = Style.dp(ctx, 12), bottom = getHeight() - Style.dp(ctx, 26);
        double lo = TripRouteStats.min(alt), hi = TripRouteStats.max(alt);
        // At least 20 m of height on screen, so flat roads look flat.
        double span = Math.max(hi - lo, 20), mid = (lo + hi) / 2;
        lo = mid - span / 2; hi = mid + span / 2;
        double tot = total();

        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = (float) (l + km[i] / tot * (r - l));
            ys[i] = (float) (bottom - (alt[i] - lo) / (hi - lo) * (bottom - top));
        }
        for (int i = 1; i < n; i++) {
            Path p = new Path();
            p.moveTo(xs[i - 1], bottom);
            p.lineTo(xs[i - 1], ys[i - 1]);
            p.lineTo(xs[i], ys[i]);
            p.lineTo(xs[i], bottom);
            p.close();
            int col = RouteGeometry.SPEED_COLORS[RouteGeometry.band((speed[i - 1] + speed[i]) / 2)];
            fill.setColor((col & 0x00FFFFFF) | 0x99000000);
            c.drawPath(p, fill);
        }
        Path top2 = new Path();
        top2.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) top2.lineTo(xs[i], ys[i]);
        c.drawPath(top2, line);

        // Height labels on the left (top and bottom of the drawn range),
        // distance under the right end.
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(String.format(Locale.US, "%.0f m", hi), l - Style.dp(ctx, 10), top + Style.dp(ctx, 14), text);
        c.drawText(String.format(Locale.US, "%.0f m", lo), l - Style.dp(ctx, 10), bottom, text);
        c.drawText(String.format(Locale.US, "%.1f km", km[n - 1]), r, getHeight() - Style.dp(ctx, 4), text);
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
