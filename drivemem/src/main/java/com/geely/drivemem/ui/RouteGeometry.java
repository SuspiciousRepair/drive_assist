package com.geely.drivemem.ui;

import com.geely.drivemem.sensors.SpeedBuckets;

/** Pure maths for drawing a trip route (issue #19). Kept out of the View
 * so it can be unit tested.
 *
 * Projection: Web Mercator around the route's centre, scaled to metres at
 * the centre: the projection of the OpenStreetMap tiles under the route
 * (MapTiles), so the line sits on its roads. North is up. */
public final class RouteGeometry {
    private RouteGeometry() {}

    static final double EARTH_M = 6371000.0;

    /** Speed band of a speed in km/h: the stats page's speed bucket
     * (SpeedBuckets), so a trip has the same bands and colours on both
     * screens. Fixed for every trip, so two trips compare at a glance. */
    public static int band(double kmh) {
        return SpeedBuckets.of(kmh);
    }

    /** Band of the segment from point i to point i + 1: the band of the
     * mean of their speeds. */
    public static int segmentBand(double[] speed, int i) {
        return band((speed[i] + speed[i + 1]) / 2);
    }

    /** Cuts a route into runs of segments with the same speed band, so a
     * view can draw one path per run and not one line per segment. Returns
     * the point indices where a run starts, plus the last point: run j goes
     * from point r[j] to point r[j + 1] and has the band of segment r[j].
     * Empty with fewer than two points. */
    public static int[] bandRuns(double[] speed) {
        int n = speed.length;
        if (n < 2) return new int[0];
        int[] r = new int[n];
        int k = 0;
        r[k++] = 0;
        int cur = segmentBand(speed, 0);
        for (int i = 1; i < n - 1; i++) {
            int b = segmentBand(speed, i);
            if (b != cur) { r[k++] = i; cur = b; }
        }
        r[k++] = n - 1;
        return java.util.Arrays.copyOf(r, k);
    }

    /** Same as {@link #bandRuns(double[])} for the points from..to (both
     * included) of a longer array: one trip out of many. The indices
     * returned are indices into the whole array. */
    public static int[] bandRuns(double[] speed, int from, int to) {
        if (to <= from) return new int[0];
        int[] r = bandRuns(java.util.Arrays.copyOfRange(speed, from, to + 1));
        for (int i = 0; i < r.length; i++) r[i] += from;
        return r;
    }

    /** Trip of a point, when many trips are in one array. starts holds the
     * first point of each trip, in rising order; trip t goes from
     * starts[t] to the point before starts[t + 1] (or to the last point).
     * -1 when there is no such point. */
    public static int tripOf(int[] starts, int point) {
        if (starts.length == 0 || point < starts[0]) return -1;
        int lo = 0, hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= point) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** The screen point nearest to (px, py), or -1 when none is within
     * maxDist: the point a finger picked. */
    public static int nearest(float[] x, float[] y, float px, float py, float maxDist) {
        int best = -1;
        float bestD = maxDist * maxDist;
        for (int i = 0; i < x.length; i++) {
            float dx = x[i] - px, dy = y[i] - py, d = dx * dx + dy * dy;
            if (d <= bestD) { bestD = d; best = i; }
        }
        return best;
    }

    /** Thins the screen points from..to (both kept): a point is kept only
     * when it is minPx or more from the last kept one. A route has far more
     * points than the panel has pixels; the line drawn through the kept
     * points is the same picture. Returns the kept indices, in order. */
    public static int[] decimate(float[] x, float[] y, int from, int to, float minPx) {
        if (to <= from) return new int[]{from};
        int[] keep = new int[to - from + 1];
        int k = 0;
        keep[k++] = from;
        float lx = x[from], ly = y[from], min2 = minPx * minPx;
        for (int i = from + 1; i < to; i++) {
            float dx = x[i] - lx, dy = y[i] - ly;
            if (dx * dx + dy * dy >= min2) { keep[k++] = i; lx = x[i]; ly = y[i]; }
        }
        keep[k++] = to;
        return java.util.Arrays.copyOf(keep, k);
    }

    /** Metres east (x) and north (y) of the bounding box centre. */
    public static double[][] project(double[] lat, double[] lon) {
        double[] c = centre(lat, lon);
        return project(lat, lon, c[0], c[1]);
    }

    /** The centre of the bounding box: {lat, lon}. {0, 0} when empty. */
    public static double[] centre(double[] lat, double[] lon) {
        int n = lat.length;
        if (n == 0) return new double[]{0, 0};
        double minLat = lat[0], maxLat = lat[0], minLon = lon[0], maxLon = lon[0];
        for (int i = 1; i < n; i++) {
            minLat = Math.min(minLat, lat[i]); maxLat = Math.max(maxLat, lat[i]);
            minLon = Math.min(minLon, lon[i]); maxLon = Math.max(maxLon, lon[i]);
        }
        return new double[]{(minLat + maxLat) / 2, (minLon + maxLon) / 2};
    }

    /** Metres east (x) and north (y) of the given centre, in Web Mercator
     * scaled to be true metres at the centre: the projection of the map
     * tiles (MapTiles), so the route sits on the roads under it. Near the
     * centre this is the same as plain metres; it differs only by the
     * Mercator stretch towards the poles. */
    public static double[][] project(double[] lat, double[] lon, double cLat, double cLon) {
        int n = lat.length;
        double[][] xy = new double[2][n];
        double k = Math.cos(Math.toRadians(cLat)) * EARTH_M;
        double cy = mercY(cLat);
        for (int i = 0; i < n; i++) {
            xy[0][i] = Math.toRadians(lon[i] - cLon) * k;
            xy[1][i] = (mercY(lat[i]) - cy) * k;
        }
        return xy;
    }

    /** Web Mercator y of a latitude, in radians of the unit sphere. Kept
     * inside the tiles' +-85.05 degrees. */
    static double mercY(double latDeg) {
        double r = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, latDeg)));
        return Math.log(Math.tan(Math.PI / 4 + r / 2));
    }

    /** Pixels per metre and the screen centre, so the whole route fits in
     * w x h with pad on every side, keeping the aspect ratio. */
    public static final class Fit {
        public final double pxPerM, cx, cy;
        Fit(double pxPerM, double cx, double cy) { this.pxPerM = pxPerM; this.cx = cx; this.cy = cy; }
        public float x(double mEast) { return (float) (cx + mEast * pxPerM); }
        /** Screen y grows down, north grows up. */
        public float y(double mNorth) { return (float) (cy - mNorth * pxPerM); }
    }

    public static Fit fit(double[] xs, double[] ys, int w, int h, int pad) {
        return fit(xs, ys, w, h, pad, 200);
    }

    /** Same, with at least minSpanM across: a map of one charge place
     * wants the streets around it, not a 200 m close-up. */
    public static Fit fit(double[] xs, double[] ys, int w, int h, int pad, double minSpanM) {
        double minX = 0, maxX = 0, minY = 0, maxY = 0;
        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]);
        }
        // At least minSpanM (200 m for a route) across, so a car that barely
        // moved is not a smear blown up to the whole screen.
        double spanX = Math.max(maxX - minX, minSpanM), spanY = Math.max(maxY - minY, minSpanM);
        double availW = Math.max(1, w - 2.0 * pad), availH = Math.max(1, h - 2.0 * pad);
        double s = Math.min(availW / spanX, availH / spanY);
        double midX = (minX + maxX) / 2, midY = (minY + maxY) / 2;
        return new Fit(s, w / 2.0 - midX * s, h / 2.0 + midY * s);
    }

    /** A round length (1, 2 or 5 x 10^n metres) no longer than maxM. */
    public static double niceLengthM(double maxM) {
        if (!(maxM > 0)) return 0;
        double p = Math.pow(10, Math.floor(Math.log10(maxM)));
        for (double m : new double[]{5, 2, 1}) if (m * p <= maxM) return m * p;
        return p;
    }

    /** "500 m", "2 km". */
    public static String lengthLabel(double m) {
        return m >= 1000 ? String.format(java.util.Locale.US, "%.0f km", m / 1000)
                         : String.format(java.util.Locale.US, "%.0f m", m);
    }
}
