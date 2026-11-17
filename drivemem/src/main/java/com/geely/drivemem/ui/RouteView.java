package com.geely.drivemem.ui;

import com.geely.drivemem.sensors.SpeedBuckets;
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
 * It can also hold many trips in one picture ({@link #setRoutes}). Then
 * every trip is a thin line, and one trip can be selected: it is drawn
 * thick, with its start and end marks, over the others made faint. A touch
 * on the picture picks the trip under the finger.
 *
 * The geometry is built once ({@link #rebuild}: on new data and on a size
 * change), not in onDraw: the marker moves on every touch event while a
 * finger scrubs the height profile, and each of those frames only replays
 * the cached paths. */
public class RouteView extends View {
    /** Points closer than this on screen are drawn as one. */
    static final float DECIMATE_PX = 2f;

    /** Told which trip a touch picked; -1 when the touch hit no route. */
    public interface OnTripPick { void onTripPick(int trip); }

    /** Alpha of the other trips while one is selected. */
    private static final int FAINT = 70;

    private double[] xs = new double[0], ys = new double[0], speed = new double[0];
    private double[] rLat = new double[0], rLon = new double[0];
    // Charge spots (ChargeSpots): drawn as pins with the AC or DC icon.
    private double[] spotLat = new double[0], spotLon = new double[0];
    private boolean[] spotDc = new boolean[0];
    private double[] spXm = new double[0], spYm = new double[0];   // metres, same frame as xs/ys
    private float[] spX = new float[0], spY = new float[0];
    private final android.graphics.drawable.Drawable acIcon, dcIcon;
    /** First point of each trip; one trip is {0}. */
    private int[] starts = {0};
    /** Many trips in one picture: thin lines, a trip can be selected. */
    private boolean many;
    private int selected = -1;
    private OnTripPick onPick;
    private int marker = -1;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint casing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int pad;

    // Built by rebuild(); screen pixels.
    private float[] sx = new float[0], sy = new float[0];
    // The thick layer: the one trip, or the selected one. One path per
    // speed bucket, so a frame costs the same with one trip or a thousand.
    private final Path casingPath = new Path();
    private final Path[] bucketPaths = newPaths();
    // The thin layer: every trip, in many-trips mode only.
    private final Path[] allPaths = newPaths();
    private final int[] colors;
    private final float thick, thin;
    private int markA = -1, markB = -1;   // start and end point of the thick trip
    private float barPx;
    private String barLabel;

    public RouteView(Context c) {
        super(c);
        for (Paint p : new Paint[]{line, casing}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        thick = Style.dp(c, 6);
        thin = Style.dp(c, 3);
        colors = SpeedBucketColors.get();
        // A dark edge under the colour, so the line reads on any theme.
        casing.setStrokeWidth(Style.dp(c, 10));
        casing.setColor(0x99000000);
        text.setColor(Style.TEXT_DIM);
        text.setTextSize(Style.dp(c, 18));
        bar.setColor(Style.TEXT_DIM);
        bar.setStrokeWidth(Style.dp(c, 3));
        pad = Style.dp(c, 40);
        acIcon = c.getDrawable(com.geely.drivemem.R.drawable.ic_power_plug).mutate();
        acIcon.setTint(Style.ACCENT);
        dcIcon = c.getDrawable(com.geely.drivemem.R.drawable.ic_ev_plug_ccs2).mutate();
        dcIcon.setTint(Style.GOOD);
    }

    /** Where the car charged: pins with the AC (blue plug) or DC (green
     * CCS2) icon, the app's charging style. They count in the fit, so a
     * spot is never off the picture. */
    void setChargeSpots(java.util.List<ChargeSpots.Spot> spots) {
        int n = spots.size();
        spotLat = new double[n]; spotLon = new double[n]; spotDc = new boolean[n];
        for (int i = 0; i < n; i++) {
            ChargeSpots.Spot sp = spots.get(i);
            spotLat[i] = sp.lat; spotLon[i] = sp.lon; spotDc[i] = sp.dc;
        }
        project();
        rebuild();
        invalidate();
    }

    /** Route points and spots in one frame (one centre). */
    private void project() {
        int n = rLat.length, m = spotLat.length;
        double[] lat = new double[n + m], lon = new double[n + m];
        System.arraycopy(rLat, 0, lat, 0, n); System.arraycopy(spotLat, 0, lat, n, m);
        System.arraycopy(rLon, 0, lon, 0, n); System.arraycopy(spotLon, 0, lon, n, m);
        double[][] xy = RouteGeometry.project(lat, lon);
        xs = java.util.Arrays.copyOf(xy[0], n); ys = java.util.Arrays.copyOf(xy[1], n);
        spXm = java.util.Arrays.copyOfRange(xy[0], n, n + m);
        spYm = java.util.Arrays.copyOfRange(xy[1], n, n + m);
    }

    private static Path[] newPaths() {
        Path[] p = new Path[SpeedBuckets.COUNT];
        for (int i = 0; i < p.length; i++) p[i] = new Path();
        return p;
    }

    /** lat, lon in degrees, speed in km/h; same length, oldest first. */
    public void setRoute(double[] lat, double[] lon, double[] speedKmh) {
        set(lat, lon, speedKmh, new int[]{0}, false);
    }

    /** Many trips in one picture. The arrays hold the points of every
     * trip, one trip after the other; tripStarts holds the index of the
     * first point of each trip. */
    public void setRoutes(double[] lat, double[] lon, double[] speedKmh, int[] tripStarts) {
        set(lat, lon, speedKmh, tripStarts, true);
    }

    private void set(double[] lat, double[] lon, double[] speedKmh, int[] tripStarts, boolean manyTrips) {
        rLat = lat; rLon = lon; speed = speedKmh;
        project();
        starts = tripStarts; many = manyTrips; selected = -1;
        rebuild();
        invalidate();
    }

    /** Selects one trip of a many-trips picture; -1 for none. */
    public void setSelected(int trip) {
        if (trip == selected) return;
        selected = trip;
        buildThick();
        invalidate();
    }

    public void setOnTripPick(OnTripPick l) { onPick = l; }

    // A touch picks the trip of the nearest point. The reach is a part of
    // the view, not a dp value: on this panel 1 dp is 1 px.
    @Override public boolean onTouchEvent(android.view.MotionEvent e) {
        if (onPick == null || !many) return super.onTouchEvent(e);
        if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
            int i = RouteGeometry.nearest(sx, sy, e.getX(), e.getY(), getWidth() / 30f);
            onPick.onTripPick(i < 0 ? -1 : RouteGeometry.tripOf(starts, i));
        }
        return true;
    }

    private int tripEnd(int trip) {
        return trip + 1 < starts.length ? starts[trip + 1] - 1 : xs.length - 1;
    }

    /** Adds the points a..b (one trip) to one path per speed bucket, and
     * to casing when it is not null. */
    private void addTrip(int a, int b, Path[] byBucket, Path casingTo) {
        int[] runs = RouteGeometry.bandRuns(speed, a, b);
        for (int j = 0; j + 1 < runs.length; j++) {
            int[] keep = RouteGeometry.decimate(sx, sy, runs[j], runs[j + 1], DECIMATE_PX);
            Path p = byBucket[RouteGeometry.segmentBand(speed, runs[j])];
            p.moveTo(sx[keep[0]], sy[keep[0]]);
            if (j == 0 && casingTo != null) casingTo.moveTo(sx[keep[0]], sy[keep[0]]);
            for (int k = 1; k < keep.length; k++) {
                p.lineTo(sx[keep[k]], sy[keep[k]]);
                if (casingTo != null) casingTo.lineTo(sx[keep[k]], sy[keep[k]]);
            }
        }
    }

    /** The thick layer: the one trip, or the selected trip of many. */
    private void buildThick() {
        casingPath.rewind();
        for (Path p : bucketPaths) p.rewind();
        markA = markB = -1;
        int trip = many ? selected : 0;
        if (sx.length == 0 || trip < 0 || trip >= starts.length) return;
        markA = starts[trip]; markB = tripEnd(trip);
        addTrip(markA, markB, bucketPaths, casingPath);
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

    /** Screen coordinates, the paths, and the scale bar. Runs on new data
     * and on a size change only. */
    private void rebuild() {
        int n = xs.length, w = getWidth(), h = getHeight();
        casingPath.rewind();
        for (Path p : bucketPaths) p.rewind();
        for (Path p : allPaths) p.rewind();
        markA = markB = -1;
        sx = new float[n]; sy = new float[n];
        spX = new float[spXm.length]; spY = new float[spYm.length];
        barPx = 0; barLabel = null;
        if (n == 0 || w <= 0 || h <= 0) return;

        double[] fx = new double[n + spXm.length], fy = new double[n + spYm.length];
        System.arraycopy(xs, 0, fx, 0, n); System.arraycopy(spXm, 0, fx, n, spXm.length);
        System.arraycopy(ys, 0, fy, 0, n); System.arraycopy(spYm, 0, fy, n, spYm.length);
        RouteGeometry.Fit f = RouteGeometry.fit(fx, fy, w, h, pad);
        for (int i = 0; i < n; i++) { sx[i] = f.x(xs[i]); sy[i] = f.y(ys[i]); }
        for (int i = 0; i < spX.length; i++) { spX[i] = f.x(spXm[i]); spY[i] = f.y(spYm[i]); }

        if (many) {
            for (int t = 0; t < starts.length; t++) addTrip(starts[t], tripEnd(t), allPaths, null);
        }
        buildThick();

        // Scale bar: a round length up to a quarter of the width.
        double barM = RouteGeometry.niceLengthM(w / 4.0 / f.pxPerM);
        if (barM > 0) {
            barPx = (float) (barM * f.pxPerM);
            barLabel = RouteGeometry.lengthLabel(barM);
        }
    }

    private void drawPin(Canvas c, float x, float y, boolean dc) {
        Context ctx = getContext();
        float r = Style.dp(ctx, 20), lift = Style.dp(ctx, 34);
        float cy = y - lift;
        int col = dc ? Style.GOOD : Style.ACCENT;
        dot.setStyle(Paint.Style.STROKE);
        dot.setStrokeWidth(Style.dp(ctx, 3));
        dot.setColor(col);
        c.drawLine(x, y, x, cy + r, dot);
        dot.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, Style.dp(ctx, 4), dot);
        dot.setColor(0xE6000000);
        c.drawCircle(x, cy, r, dot);
        dot.setStyle(Paint.Style.STROKE);
        dot.setColor(col);
        c.drawCircle(x, cy, r, dot);
        android.graphics.drawable.Drawable d = dc ? dcIcon : acIcon;
        int ir = Style.dp(ctx, 13);
        d.setBounds((int) x - ir, (int) cy - ir, (int) x + ir, (int) cy + ir);
        d.draw(c);
    }

    @Override protected void onDraw(Canvas c) {
        int n = sx.length;
        if (n == 0) return;
        Context ctx = getContext();

        if (many) {
            line.setStrokeWidth(thin);
            for (int j = 0; j < allPaths.length; j++) {
                line.setColor(colors[j]);
                if (markA >= 0) line.setAlpha(FAINT);
                c.drawPath(allPaths[j], line);
            }
        }
        if (markA >= 0) {
            c.drawPath(casingPath, casing);
            line.setStrokeWidth(thick);
            for (int j = 0; j < bucketPaths.length; j++) {
                line.setColor(colors[j]);
                c.drawPath(bucketPaths[j], line);
            }

            // Start: a filled dot. End: a ring. Both white with a dark edge.
            float r = Style.dp(ctx, 11);
            dot.setStyle(Paint.Style.FILL);
            dot.setColor(0xCC000000);
            c.drawCircle(sx[markA], sy[markA], r + Style.dp(ctx, 3), dot);
            c.drawCircle(sx[markB], sy[markB], r + Style.dp(ctx, 3), dot);
            dot.setColor(0xFFFFFFFF);
            c.drawCircle(sx[markA], sy[markA], r, dot);
            dot.setStyle(Paint.Style.STROKE);
            dot.setStrokeWidth(Style.dp(ctx, 4));
            c.drawCircle(sx[markB], sy[markB], r - Style.dp(ctx, 2), dot);
        }

        // Charge spots: a pin above the place, so the trip's start and end
        // marks under it stay visible. AC charges first, DC on top.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < spX.length; i++) {
                if (spotDc[i] != (pass == 1)) continue;
                drawPin(c, spX[i], spY[i], spotDc[i]);
            }
        }

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
