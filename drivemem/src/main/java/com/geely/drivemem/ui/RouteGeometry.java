package com.geely.drivemem.ui;

/** Pure maths for drawing a trip route on a plain background (issue #19):
 * no map, no tiles. Kept out of the View so it can be unit tested.
 *
 * Projection: equirectangular around the route's centre, in metres. Fine at
 * city and region scale (a trip of a few hundred km bends less than a
 * line's width on this panel). North is up. */
public final class RouteGeometry {
    private RouteGeometry() {}

    /** Upper limits of the speed bands, km/h. Fixed for every trip, so two
     * trips compare at a glance. */
    public static final double[] SPEED_STEPS = {20, 50, 80, 110};
    /** One colour per band: stop-and-go red, orange, green, blue, purple. */
    public static final int[] SPEED_COLORS = {0xFFE53935, 0xFFFF9800, 0xFF43A047, 0xFF1E88E5, 0xFFAB47BC};

    static final double EARTH_M = 6371000.0;

    /** Index into SPEED_COLORS. A band's upper limit belongs to it (20 is red). */
    public static int band(double kmh) {
        for (int i = 0; i < SPEED_STEPS.length; i++) if (kmh <= SPEED_STEPS[i]) return i;
        return SPEED_STEPS.length;
    }

    /** Metres east (x) and north (y) of the bounding box centre. */
    public static double[][] project(double[] lat, double[] lon) {
        int n = lat.length;
        double[][] xy = new double[2][n];
        if (n == 0) return xy;
        double minLat = lat[0], maxLat = lat[0], minLon = lon[0], maxLon = lon[0];
        for (int i = 1; i < n; i++) {
            minLat = Math.min(minLat, lat[i]); maxLat = Math.max(maxLat, lat[i]);
            minLon = Math.min(minLon, lon[i]); maxLon = Math.max(maxLon, lon[i]);
        }
        double cLat = (minLat + maxLat) / 2, cLon = (minLon + maxLon) / 2;
        double k = Math.cos(Math.toRadians(cLat));
        for (int i = 0; i < n; i++) {
            xy[0][i] = Math.toRadians(lon[i] - cLon) * k * EARTH_M;
            xy[1][i] = Math.toRadians(lat[i] - cLat) * EARTH_M;
        }
        return xy;
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
        double minX = 0, maxX = 0, minY = 0, maxY = 0;
        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]);
        }
        // At least 200 m across, so a car that barely moved is not a smear
        // blown up to the whole screen.
        double spanX = Math.max(maxX - minX, 200), spanY = Math.max(maxY - minY, 200);
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
