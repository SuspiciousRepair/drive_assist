package com.geely.drivemem.sensors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The rules for trips joined or split by hand (issue #210), kept free of
 * Android and of the database so they are unit-tested. The trip rows never
 * change: {@code trip_edit} (CarDb v39) only says how to show them.
 *
 * <p>Order: cuts first, then joins. A cut splits one recorded trip into
 * parts. A join then shows every trip (or part) that starts inside its time
 * window as one trip. */
public final class TripEdits {
    private TripEdits() {}

    public static final String KIND_JOIN = "join";
    public static final String KIND_CUT = "cut";

    /** The parts of one trip [start, end] after its cuts. Each cut is
     * {a, b}: the part before ends at a, the next starts at b (a == b for a
     * cut while moving, a &lt; b for a cut at a stop). Cuts outside the trip,
     * or that would leave an empty part, are skipped. One window {start, end}
     * when there is no usable cut. */
    public static List<long[]> windows(long start, long end, List<long[]> cuts) {
        List<long[]> out = new ArrayList<>();
        List<long[]> sorted = new ArrayList<>();
        if (cuts != null) for (long[] c : cuts) if (c != null && c.length == 2 && c[0] <= c[1]) sorted.add(c);
        sorted.sort((x, y) -> Long.compare(x[0], y[0]));
        long from = start;
        for (long[] c : sorted) {
            if (c[0] <= from || c[1] >= end) continue;
            out.add(new long[]{from, c[0]});
            from = c[1];
        }
        out.add(new long[]{from, end});
        return out;
    }

    /** One join window: {id, start, end}. */
    public static final class Join {
        public final long id, startMs, endMs;
        public Join(long id, long startMs, long endMs) { this.id = id; this.startMs = startMs; this.endMs = endMs; }
    }

    /** Which trips each join takes. starts/ends: the trips (or parts),
     * oldest first. A trip is in a join when it starts at or after the
     * join's start and ends at or before its end. A wider join wins over one
     * inside it, and a trip goes to one join only. A join with fewer than
     * two trips left is no join. Returns {joinId, first index, last index}
     * per group, oldest first. */
    public static List<long[]> groups(long[] starts, long[] ends, List<Join> joins) {
        List<long[]> out = new ArrayList<>();
        if (joins == null || joins.isEmpty() || starts.length < 2) return out;
        List<Join> order = new ArrayList<>(joins);
        order.sort((a, b) -> a.startMs != b.startMs ? Long.compare(a.startMs, b.startMs)
                                                    : Long.compare(b.endMs, a.endMs));
        boolean[] taken = new boolean[starts.length];
        for (Join j : order) {
            int first = -1, last = -1;
            for (int i = 0; i < starts.length; i++) {
                if (taken[i] || starts[i] < j.startMs || ends[i] <= 0 || ends[i] > j.endMs) continue;
                if (first < 0) first = i;
                else if (i != last + 1) break;   // trips in a time window are next to each other
                last = i;
            }
            if (first < 0 || last <= first) continue;
            Arrays.fill(taken, first, last + 1, true);
            out.add(new long[]{j.id, first, last});
        }
        out.sort((a, b) -> Long.compare(a[1], b[1]));
        return out;
    }

    /** One trip made of several, with the numbers the cards showed added up:
     * km, energy, climb and tolls are sums; battery and odometer run from
     * the first trip's start to the last one's end. So the joined card says
     * exactly what its trips said together. */
    public static DailyStatsProvider.DriveSession join(List<DailyStatsProvider.DriveSession> m, long joinId) {
        DailyStatsProvider.DriveSession first = m.get(0), last = m.get(m.size() - 1);
        double km = 0, up = 0, down = 0, regen = 0, spent = 0, net = 0, tollCost = 0;
        int tollCount = 0, tollUnpriced = 0;
        List<EnergySource> sources = new ArrayList<>();
        List<EnergySplit> splits = new ArrayList<>();
        boolean estimated = false;
        for (DailyStatsProvider.DriveSession t : m) {
            km += t.distanceKm;
            up += t.ascentDPlusM;
            down += t.descentDMinusM;
            regen += t.regenKwh;
            spent += t.spentKwh;
            net += t.energyKwh;
            sources.add(t.energySource);
            splits.add(t.split);
            tollCount += t.tollCount;
            tollCost += t.tollCost;
            tollUnpriced += t.tollUnpriced;
            if (t.energySource == EnergySource.ESTIMATED) estimated = true;
        }
        EnergySource src = EnergySource.combine(sources);
        // An estimated trip has a net but no spent/regen: the spent-based
        // formula would leave its energy out.
        double eff = estimated
            ? (km > 0.2 ? Math.max(0, net) * 100 / km : 0)
            : DrivingConsumption.efficiencyKwh100km(km, spent, regen);
        DailyStatsProvider.DriveSession j = new DailyStatsProvider.DriveSession(first.tripId,
            first.startMs, last.endMs, km, first.socStart, last.socEnd, up, down,
            regen, spent, net, eff, src);
        j.split = estimated ? null : EnergySplit.sum(splits);
        j.tollCount = tollCount;
        j.tollCost = tollCost;
        j.tollUnpriced = tollUnpriced;
        j.odoStartKm = first.odoStartKm;
        j.odoEndKm = last.odoEndKm;
        j.members = new ArrayList<>(m);
        j.joinId = joinId;
        return j;
    }

    /** Applies the joins to trips (oldest first, cut parts already in). */
    public static List<DailyStatsProvider.DriveSession> applyJoins(
            List<DailyStatsProvider.DriveSession> trips, List<Join> joins) {
        int n = trips.size();
        long[] s = new long[n], e = new long[n];
        for (int i = 0; i < n; i++) { s[i] = trips.get(i).startMs; e[i] = trips.get(i).endMs; }
        List<long[]> g = groups(s, e, joins);
        if (g.isEmpty()) return trips;
        List<DailyStatsProvider.DriveSession> out = new ArrayList<>();
        int i = 0;
        for (long[] grp : g) {
            int a = (int) grp[1], b = (int) grp[2];
            while (i < a) out.add(trips.get(i++));
            out.add(join(trips.subList(a, b + 1), grp[0]));
            i = b + 1;
        }
        while (i < n) out.add(trips.get(i++));
        return out;
    }

    /** The edit window a new join saves when the picked trips run from
     * firstStart to lastEnd: the same, widened to cover any join it
     * overlaps (those are then replaced). Returns {start, end}. */
    public static long[] joinWindow(long firstStart, long lastEnd, List<Join> existing) {
        long s = Math.min(firstStart, lastEnd), e = Math.max(firstStart, lastEnd);
        boolean grew = true;
        while (grew && existing != null) {
            grew = false;
            for (Join j : existing) {
                if (j.startMs <= e && j.endMs >= s && (j.startMs < s || j.endMs > e)) {
                    s = Math.min(s, j.startMs);
                    e = Math.max(e, j.endMs);
                    grew = true;
                }
            }
        }
        return new long[]{s, e};
    }

    /** One shift to P inside a trip: the only place a trip can be split.
     * startMs/endMs: the park_session row; from/to: the route points just
     * before and just after it (the car makes no points while it stands). */
    public static final class Stop {
        public final int from, to;
        public final long startMs, endMs;
        public Stop(int from, int to, long startMs, long endMs) {
            this.from = from; this.to = to; this.startMs = startMs; this.endMs = endMs;
        }
    }

    /** The shifts to P that lie fully inside one of the trip's windows
     * (one window, or a joined trip's trips), as stops on its points. The
     * P that ends a trip runs past its end and is never one. parks and
     * windows are {start, end} ms; ts: the points' times, ascending. */
    public static List<Stop> parkStops(List<long[]> parks, List<long[]> windows, long[] ts) {
        List<Stop> out = new ArrayList<>();
        int n = ts.length;
        if (n < 2 || parks == null || windows == null) return out;
        for (long[] p : parks) {
            if (p == null || p.length < 2 || p[1] <= p[0]) continue;
            boolean inside = false;
            for (long[] w : windows) if (p[0] > w[0] && p[1] < w[1]) { inside = true; break; }
            if (!inside) continue;
            int from = 0, to = n - 1;
            for (int i = 0; i < n; i++) if (ts[i] <= p[0]) from = i;
            for (int i = n - 1; i >= 0; i--) if (ts[i] >= p[1]) to = i;
            if (to < from) to = from;
            out.add(new Stop(from, to, p[0], p[1]));
        }
        return out;
    }

    /** The stop nearest to the finger at point index pick (0 km when the
     * finger is on it). Null when there is no stop. km: distance from the
     * start, per point. */
    public static Stop snap(int pick, double[] km, List<Stop> stops) {
        if (stops == null || stops.isEmpty() || km.length == 0) return null;
        int i = Math.max(0, Math.min(km.length - 1, pick));
        Stop best = null;
        double bestD = Double.MAX_VALUE;
        for (Stop s : stops) {
            double d = i < s.from ? km[s.from] - km[i] : i > s.to ? km[i] - km[s.to] : 0;
            if (d < bestD) { bestD = d; best = s; }
        }
        return best;
    }

    /** How a recorded trip's CAN split and other shared totals are shared
     * between its parts: each part's own non-negative measure (energy used,
     * else km) over the whole, capped so the shares never pass 1, and the
     * last part gets what is left. So the parts always add up to the trip.
     * own: each part's measure (the last one is not read). */
    public static double[] shares(double[] own, double whole) {
        int n = own.length;
        double[] f = new double[n];
        double used = 0;
        for (int i = 0; i < n - 1; i++) {
            double x = whole > 0 ? Math.max(0, own[i]) / whole : 0;
            f[i] = Math.min(x, 1 - used);
            used += f[i];
        }
        if (n > 0) f[n - 1] = Math.max(0, 1 - used);
        return f;
    }
}
