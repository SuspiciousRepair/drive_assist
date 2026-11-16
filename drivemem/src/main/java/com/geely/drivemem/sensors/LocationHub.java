package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarActor;

import android.content.Context;

/** The car's GPS position as a hub key (CLAUDE.md rule 7). GpsReader is the
 * source; every consumer (MQTT, ABRP, the 30 s sampler, trips, charges)
 * reads {@link #FIX} from the hub cache instead.
 *
 * Polled every 2 s. The cache always holds the newest fix, but EntityBus
 * only publishes when the car moved to another ~5 m grid cell
 * ({@link Fix#equals}), so a parked car with a wobbling fix stays quiet. */
public final class LocationHub {
    public static final String FIX = "location.fix";
    static final int POLL_MS = 2000;
    /** About 5.5 m of latitude; less of longitude away from the equator. */
    static final double GRID_DEG = 0.00005;

    private LocationHub() {}

    /** One GPS fix. Raw values are kept; only equality uses the grid. */
    public static final class Fix {
        public final double lat, lon, altM, bearing, speedKmh, accuracyM;

        public Fix(double lat, double lon, double altM, double bearing, double speedKmh, double accuracyM) {
            this.lat = lat; this.lon = lon; this.altM = altM;
            this.bearing = bearing; this.speedKmh = speedKmh; this.accuracyM = accuracyM;
        }

        /** From GpsReader's array: [lat, lon, alt, bearing, speed m/s, accuracy]. */
        static Fix of(double[] l) {
            return new Fix(l[0], l[1], l[2], l[3], l[4] * 3.6, l[5]);
        }

        /** Same layout as GpsReader's array, for the older consumers. */
        public double[] toArray() {
            return new double[]{lat, lon, altM, bearing, speedKmh / 3.6, accuracyM};
        }

        long cellLat() { return (long) Math.floor(lat / GRID_DEG); }
        long cellLon() { return (long) Math.floor(lon / GRID_DEG); }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Fix)) return false;
            Fix f = (Fix) o;
            return cellLat() == f.cellLat() && cellLon() == f.cellLon();
        }

        @Override public int hashCode() { return Long.hashCode(cellLat() * 31 + cellLon()); }
    }

    /** Idempotent (registerPoll replaces by key). */
    public static void register(Context ctx, CarActor a) {
        if (ctx == null || a == null) return;
        final Context app = ctx.getApplicationContext();
        a.registerPoll(FIX, POLL_MS, car -> {
            double[] l = GpsReader.read(app);
            return l == null ? CarActor.Reading.NOT_AVAILABLE : CarActor.Reading.ok(Fix.of(l));
        });
    }

    /** The newest fix from the hub cache, or null. */
    public static Fix get(Context ctx) {
        if (ctx == null) return null;
        CarActor.Reading r = CarActor.get(ctx).get(FIX);
        return (r.status == CarActor.Reading.Status.OK && r.value instanceof Fix) ? (Fix) r.value : null;
    }

    /** {@link #get} in GpsReader's array layout, or null. */
    public static double[] read(Context ctx) {
        Fix f = get(ctx);
        return f == null ? null : f.toArray();
    }

    /** Great-circle distance in metres. */
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
