package com.geely.drivemem.ui;

import java.util.Arrays;
import java.util.List;

/** Many trip routes put one after the other, for the All routes screen:
 * one set of point arrays for the picture, and one height profile with the
 * distance running on from trip to trip. Pure maths, unit tested. */
final class RouteSet {
    /** The points of one trip, oldest first. alt is NaN where unknown. */
    static final class Trip {
        final double[] lat, lon, speed, alt;
        Trip(double[] lat, double[] lon, double[] speed, double[] alt) {
            this.lat = lat; this.lon = lon; this.speed = speed; this.alt = alt;
        }
    }

    /** Every point of every trip, in the order of the trips. */
    final double[] lat, lon, speed;
    /** First point of each trip. */
    final int[] starts;
    /** The height profile. A trip with no height at all is left out, so
     * each profile point says which point of the picture it is (point). */
    final double[] profKm, profAlt, profSpeed;
    final int[] point;

    private RouteSet(double[] lat, double[] lon, double[] speed, int[] starts,
                     double[] profKm, double[] profAlt, double[] profSpeed, int[] point) {
        this.lat = lat; this.lon = lon; this.speed = speed; this.starts = starts;
        this.profKm = profKm; this.profAlt = profAlt; this.profSpeed = profSpeed; this.point = point;
    }

    /** All the trips, in this order. smooth is the window of the moving
     * median on each trip's height. */
    static RouteSet of(List<Trip> trips, int smooth) {
        int n = 0;
        for (Trip t : trips) n += t.lat.length;
        double[] lat = new double[n], lon = new double[n], speed = new double[n];
        double[] km = new double[n], alt = new double[n], ps = new double[n];
        int[] starts = new int[trips.size()], point = new int[n];
        int k = 0, p = 0;
        double kmBase = 0;
        for (int t = 0; t < trips.size(); t++) {
            Trip tr = trips.get(t);
            starts[t] = k;
            int m = tr.lat.length;
            System.arraycopy(tr.lat, 0, lat, k, m);
            System.arraycopy(tr.lon, 0, lon, k, m);
            System.arraycopy(tr.speed, 0, speed, k, m);
            double[] filled = TripRouteStats.fillGaps(tr.alt);
            if (filled != null && m > 0) {
                double[] h = TripRouteStats.medianSmooth(filled, smooth);
                double[] tk = TripRouteStats.cumulativeKm(tr.lat, tr.lon);
                for (int i = 0; i < m; i++) {
                    km[p] = kmBase + tk[i]; alt[p] = h[i]; ps[p] = tr.speed[i]; point[p] = k + i;
                    p++;
                }
                kmBase += tk[m - 1];
            }
            k += m;
        }
        return new RouteSet(lat, lon, speed, starts,
            Arrays.copyOf(km, p), Arrays.copyOf(alt, p), Arrays.copyOf(ps, p), Arrays.copyOf(point, p));
    }
}
