package com.geely.drivemem.ui;

import com.geely.drivemem.sensors.SpeedBuckets;
import com.geely.drivemem.util.Style;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A trip's route as lines over an OpenStreetMap map (issue #19). Each
 * segment between two points gets the colour of their average speed
 * (SpeedBucketColors). North is up. A green dot marks the start, a
 * checkered one the finish, and a bar in the corner gives the scale. The map tiles
 * (MapTiles, TileCache) are drawn under everything; a tile that is not
 * here yet leaves the plain background, so with no network the view is
 * what it was before the map.
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

    /** Told which charge place a touch picked; -1 when it hit none. */
    public interface OnSpotPick { void onSpotPick(int spot); }

    /** Alpha of the other trips while one is selected. */
    private static final int FAINT = 70;

    private double[] xs = new double[0], ys = new double[0], speed = new double[0];
    private double[] rLat = new double[0], rLon = new double[0];
    // Charge spots (ChargeSpots): drawn as pins with the AC or DC icon.
    private double[] spotLat = new double[0], spotLon = new double[0];
    private boolean[] spotDc = new boolean[0];
    // Charging page (setChargePlaces): the count in each pin, and the lit one.
    private String[] spotLabel = new String[0];
    private int selSpot = -1;
    private OnSpotPick onSpotPick;
    private double minSpanM = 200;
    private final Paint pinText = new Paint(Paint.ANTI_ALIAS_FLAG);
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

    // The map under the route. The projection centre is kept so the tiles
    // can be placed (MapTiles.cover); the tile list is built in rebuild().
    private double cLat, cLon;
    private java.util.List<MapTiles.Tile> tiles = java.util.Collections.emptyList();
    private final TileCache tileCache;
    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint credit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint creditBg = new Paint();
    private final android.graphics.RectF tileRect = new android.graphics.RectF();
    private final Runnable tileArrived = this::invalidate;

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

        pinText.setTextAlign(Paint.Align.CENTER);
        pinText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pinText.setTextSize(Style.dp(c, 22));

        tileCache = TileCache.get(c);
        tilePaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(tileMatrix(Style.LIGHT)));
        credit.setColor(Style.LIGHT ? 0xFF333333 : 0xFFCCCCCC);
        credit.setTextSize(Style.dp(c, 15));
        credit.setTextAlign(Paint.Align.RIGHT);
        creditBg.setColor(Style.LIGHT ? 0xB3FFFFFF : 0xB3000000);
        // The map is a rectangle; round its corners like the cards.
        final float radius = Style.dp(c, Style.RADIUS_DP);
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    /** The tiles' colours. Light themes: a little less colour, so the speed
     * colours of the route stand out. Dark themes: much less colour and
     * dimmed to a mid grey, so the map does not glare at night and the
     * roads stay lighter than the blocks between them. */
    static android.graphics.ColorMatrix tileMatrix(boolean light) {
        android.graphics.ColorMatrix m = new android.graphics.ColorMatrix();
        m.setSaturation(light ? 0.6f : 0.25f);
        if (!light) {
            android.graphics.ColorMatrix dim = new android.graphics.ColorMatrix();
            dim.setScale(0.5f, 0.5f, 0.52f, 1f);
            m.postConcat(dim);
        }
        return m;
    }

    /** Where the car charged: pins with the AC (blue plug) or DC (green
     * CCS2) icon, the app's charging style. They count in the fit, so a
     * spot is never off the picture. */
    void setChargeSpots(java.util.List<ChargeSpots.Spot> spots) {
        int n = spots.size();
        spotLat = new double[n]; spotLon = new double[n]; spotDc = new boolean[n];
        spotLabel = new String[n];
        for (int i = 0; i < n; i++) {
            ChargeSpots.Spot sp = spots.get(i);
            spotLat[i] = sp.lat; spotLon[i] = sp.lon; spotDc[i] = sp.dc;
        }
        selSpot = -1;
        project();
        rebuild();
        invalidate();
    }

    /** The charging page's map: one pin per place, with how many charges
     * it had in place of the icon. No route. */
    void setChargePlaces(java.util.List<ChargeSpots.Place> places) {
        int n = places.size();
        spotLat = new double[n]; spotLon = new double[n]; spotDc = new boolean[n];
        spotLabel = new String[n];
        for (int i = 0; i < n; i++) {
            ChargeSpots.Place p = places.get(i);
            spotLat[i] = p.lat; spotLon[i] = p.lon; spotDc[i] = p.dc;
            spotLabel[i] = String.valueOf(p.count());
        }
        selSpot = -1;
        project();
        rebuild();
        invalidate();
    }

    /** Lights one charge place (filled pin, drawn on top); -1 for none. */
    void setSelectedSpot(int i) {
        if (i == selSpot) return;
        selSpot = i;
        invalidate();
    }

    void setOnSpotPick(OnSpotPick l) { onSpotPick = l; }

    /** The least width the map shows, in metres (200 for a route). */
    void setMinSpanM(double m) { minSpanM = m; }

    /** Route points and spots in one frame (one centre). */
    private void project() {
        int n = rLat.length, m = spotLat.length;
        double[] lat = new double[n + m], lon = new double[n + m];
        System.arraycopy(rLat, 0, lat, 0, n); System.arraycopy(spotLat, 0, lat, n, m);
        System.arraycopy(rLon, 0, lon, 0, n); System.arraycopy(spotLon, 0, lon, n, m);
        double[] centre = RouteGeometry.centre(lat, lon);
        cLat = centre[0]; cLon = centre[1];
        double[][] xy = RouteGeometry.project(lat, lon, cLat, cLon);
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
        if (onSpotPick != null) {
            // A pin's head sits above its place (drawPin): test the head.
            if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                float[] hy = new float[spY.length];
                float lift = Style.dp(getContext(), 34);
                for (int i = 0; i < hy.length; i++) hy[i] = spY[i] - lift;
                onSpotPick.onSpotPick(RouteGeometry.nearest(spX, hy, e.getX(), e.getY(), getWidth() / 12f));
            }
            return true;
        }
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

    /** The start dot's green, the same in every theme: a start light. */
    static final int START_GREEN = 0xFF22C55E;
    private final Path checkClip = new Path();

    /** A round checkered flag: 4 x 4 black and white squares in a circle. */
    private void drawChecker(Canvas c, float cx, float cy, float r) {
        checkClip.rewind();
        checkClip.addCircle(cx, cy, r, Path.Direction.CW);
        c.save();
        c.clipPath(checkClip);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(0xFFFFFFFF);
        c.drawCircle(cx, cy, r, dot);
        dot.setColor(0xFF111111);
        float n = 4, side = 2 * r / n, x0 = cx - r, y0 = cy - r;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if ((i + j) % 2 == 1) c.drawRect(x0 + i * side, y0 + j * side,
                    x0 + (i + 1) * side, y0 + (j + 1) * side, dot);
            }
        }
        c.restore();
    }

    /** Marks one point (picked on the height profile); -1 for none. */
    public void setMarker(int index) {
        if (index == marker) return;
        marker = index;
        invalidate();
    }

    // The trip screen's map: its size is kept for MapPreload.
    private boolean tripScreen;
    void setTripScreen(boolean b) { tripScreen = b; }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (tripScreen) MapPreload.rememberSize(getContext(), w, h);
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
        tiles = java.util.Collections.emptyList();
        if ((n == 0 && spXm.length == 0) || w <= 0 || h <= 0) return;

        double[] fx = new double[n + spXm.length], fy = new double[n + spYm.length];
        System.arraycopy(xs, 0, fx, 0, n); System.arraycopy(spXm, 0, fx, n, spXm.length);
        System.arraycopy(ys, 0, fy, 0, n); System.arraycopy(spYm, 0, fy, n, spYm.length);
        RouteGeometry.Fit f = RouteGeometry.fit(fx, fy, w, h, pad, minSpanM);
        for (int i = 0; i < n; i++) { sx[i] = f.x(xs[i]); sy[i] = f.y(ys[i]); }
        for (int i = 0; i < spX.length; i++) { spX[i] = f.x(spXm[i]); spY[i] = f.y(spYm[i]); }
        tiles = MapTiles.cover(cLat, cLon, f.x(0), f.y(0), f.pxPerM, w, h);

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

    private void drawPin(Canvas c, float x, float y, boolean dc, String label, boolean lit) {
        Context ctx = getContext();
        float r = Style.dp(ctx, lit ? 26 : 20), lift = Style.dp(ctx, 34);
        float cy = y - lift;
        int col = dc ? Style.GOOD : Style.ACCENT;
        dot.setStyle(Paint.Style.STROKE);
        dot.setStrokeWidth(Style.dp(ctx, 3));
        dot.setColor(col);
        c.drawLine(x, y, x, cy + r, dot);
        dot.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, Style.dp(ctx, 4), dot);
        dot.setColor(lit ? col : 0xE6000000);
        c.drawCircle(x, cy, r, dot);
        dot.setStyle(Paint.Style.STROKE);
        dot.setColor(lit ? 0xE6000000 : col);
        c.drawCircle(x, cy, r, dot);
        if (label != null) {
            // The number of charges at the place, in place of the icon.
            pinText.setColor(lit ? Style.onFill(col) : col);
            pinText.setTextSize(Style.dp(ctx, lit ? 26 : 22));
            c.drawText(label, x, cy - (pinText.ascent() + pinText.descent()) / 2, pinText);
            return;
        }
        android.graphics.drawable.Drawable d = dc ? dcIcon : acIcon;
        int ir = Style.dp(ctx, 13);
        d.setBounds((int) x - ir, (int) cy - ir, (int) x + ir, (int) cy + ir);
        d.draw(c);
    }

    @Override protected void onDraw(Canvas c) {
        int n = sx.length;
        if (n == 0 && spX.length == 0) return;
        Context ctx = getContext();

        // The map first, under everything. A tile that is not here yet is
        // asked for, and the view redraws when it arrives.
        for (MapTiles.Tile t : tiles) {
            android.graphics.Bitmap b = tileCache.get(t.z, t.x, t.y, tileArrived);
            if (b == null) continue;
            // Half a pixel more on each side, so no seam shows between tiles.
            tileRect.set(t.left - 0.5f, t.top - 0.5f, t.left + t.size + 0.5f, t.top + t.size + 0.5f);
            c.drawBitmap(b, null, tileRect, tilePaint);
        }

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

            // Start: a green dot. Finish: a checkered flag dot. Both with
            // a dark edge, so they read on any map. The finish is drawn
            // last: on a round trip it sits on top of the start.
            float r = Style.dp(ctx, 12);
            dot.setStyle(Paint.Style.FILL);
            dot.setColor(0xCC000000);
            c.drawCircle(sx[markA], sy[markA], r + Style.dp(ctx, 3), dot);
            dot.setColor(START_GREEN);
            c.drawCircle(sx[markA], sy[markA], r, dot);
            dot.setColor(0xCC000000);
            c.drawCircle(sx[markB], sy[markB], r + Style.dp(ctx, 3), dot);
            drawChecker(c, sx[markB], sy[markB], r);
        }

        // Charge spots: a pin above the place, so the trip's start and end
        // marks under it stay visible. AC charges first, DC on top.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < spX.length; i++) {
                if (spotDc[i] != (pass == 1) || i == selSpot) continue;
                drawPin(c, spX[i], spY[i], spotDc[i], spotLabel[i], false);
            }
        }
        // The lit place last, on top of the rest.
        if (selSpot >= 0 && selSpot < spX.length) {
            drawPin(c, spX[selSpot], spY[selSpot], spotDc[selSpot], spotLabel[selSpot], true);
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

        // The map's credit, bottom right: the OpenStreetMap licence asks
        // for it wherever its map is shown.
        if (!tiles.isEmpty()) {
            String s = "© OpenStreetMap";
            float m = Style.dp(ctx, 6);
            float tw = credit.measureText(s);
            float right = getWidth() - m, base = getHeight() - m - Style.dp(ctx, 4);
            c.drawRect(right - tw - 2 * m, base + credit.ascent() - m / 2,
                       getWidth(), getHeight(), creditBg);
            c.drawText(s, right - m, base, credit);
        }
    }
}
