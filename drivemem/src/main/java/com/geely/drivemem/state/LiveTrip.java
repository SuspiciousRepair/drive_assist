package com.geely.drivemem.state;

import com.geely.drivemem.sensors.DrivingConsumption;
import com.geely.drivemem.sensors.EnergySource;

/**
 * The trip that is open right now, as the hub key {@link TripSession#LIVE_KEY}
 * ("trip.live"). Built on the hub thread on every telemetry.tick, so the home
 * card reads the hub instead of querying car.db.
 *
 * Immutable, with value equality on quantized fields (0.1 km, 0.01 kWh,
 * whole minutes): the hub publishes only when something a driver can see
 * has changed.
 */
public final class LiveTrip {
    /** No open trip. */
    public static final LiveTrip NONE = new LiveTrip(0, 0, 0, 0, 0, -1, -1, EnergySource.NO_DATA);

    public final long startMs;
    private final long drivingMin;
    private final int distanceDm;   // 0.1 km
    private final int spentCwh;     // 0.01 kWh
    private final int regenCwh;     // 0.01 kWh
    public final int socStart, socEnd;
    public final EnergySource source;

    private LiveTrip(long startMs, long drivingMin, int distanceDm, int spentCwh, int regenCwh,
                     int socStart, int socEnd, EnergySource source) {
        this.startMs = startMs;
        this.drivingMin = drivingMin;
        this.distanceDm = distanceDm;
        this.spentCwh = spentCwh;
        this.regenCwh = regenCwh;
        this.socStart = socStart;
        this.socEnd = socEnd;
        this.source = source == null ? EnergySource.NO_DATA : source;
    }

    public static LiveTrip of(long startMs, long drivingMs, double distanceKm, double spentKwh,
                              double regenKwh, int socStart, int socEnd, EnergySource source) {
        return new LiveTrip(startMs, Math.max(0, drivingMs) / 60_000L,
            (int) Math.round(Math.max(0, distanceKm) * 10),
            (int) Math.round(Math.max(0, spentKwh) * 100),
            (int) Math.round(Math.max(0, regenKwh) * 100),
            socStart, socEnd, source);
    }

    public boolean isOpen() { return startMs > 0; }
    public long drivingMs() { return drivingMin * 60_000L; }
    public double distanceKm() { return distanceDm / 10.0; }
    public double spentKwh() { return spentCwh / 100.0; }
    public double regenKwh() { return regenCwh / 100.0; }
    public double netKwh() { return (spentCwh - regenCwh) / 100.0; }

    /** Same formula as every other trip in the app. 0 below 200 m. */
    public double efficiencyKwh100km() {
        return DrivingConsumption.efficiencyKwh100km(distanceKm(), spentKwh(), regenKwh());
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LiveTrip)) return false;
        LiveTrip t = (LiveTrip) o;
        return startMs == t.startMs && drivingMin == t.drivingMin && distanceDm == t.distanceDm
            && spentCwh == t.spentCwh && regenCwh == t.regenCwh && socStart == t.socStart
            && socEnd == t.socEnd && source == t.source;
    }

    @Override public int hashCode() {
        int h = Long.hashCode(startMs);
        h = 31 * h + Long.hashCode(drivingMin);
        h = 31 * h + distanceDm;
        h = 31 * h + spentCwh;
        h = 31 * h + regenCwh;
        h = 31 * h + socStart;
        h = 31 * h + socEnd;
        return 31 * h + source.hashCode();
    }

    @Override public String toString() {
        return "LiveTrip{start=" + startMs + ", km=" + distanceKm() + ", spent=" + spentKwh()
            + ", regen=" + regenKwh() + ", soc=" + socStart + "->" + socEnd + ", " + source + "}";
    }
}
