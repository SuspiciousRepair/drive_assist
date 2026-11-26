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

    /** One stop inside a trip: a run of points with no speed. */
    public static final class Stop {
        public final int from, to;     // point indexes, inclusive
        public Stop(int from, int to) { this.from = from; this.to = to; }
    }

    /** The stops of a trip's points: runs of speed under 1 km/h that last
     * at least minMs. The split picker snaps to these. */
    public static List<Stop> stops(double[] speed, long[] ts, long minMs) {
        List<Stop> out = new ArrayList<>();
        int n = Math.min(speed.length, ts.length);
        int i = 0;
        while (i < n) {
            if (speed[i] >= 1) { i++; continue; }
            int j = i;
            while (j + 1 < n && speed[j + 1] < 1) j++;
            // Not the very start or end: there is nothing to split off.
            if (i > 0 && j < n - 1 && ts[j] - ts[i] >= minMs) out.add(new Stop(i, j));
            i = j + 1;
        }
        return out;
    }

    /** Where a split lands when the finger is at point index pick: the
     * nearest stop within maxKm of it (its first and last point), else the
     * point itself. km: distance from the start, per point. Returns {a, b}
     * point indexes, a &lt;= b; null when pick is the first or last point
     * (no empty part). */
    public static int[] snap(int pick, double[] km, List<Stop> stops, double maxKm) {
        int n = km.length;
        if (pick <= 0 || pick >= n - 1) return null;
        Stop best = null;
        double bestD = maxKm;
        for (Stop s : stops) {
            double d = pick < s.from ? km[s.from] - km[pick]
                     : pick > s.to ? km[pick] - km[s.to] : 0;
            if (d <= bestD) { bestD = d; best = s; }
        }
        return best != null ? new int[]{best.from, best.to} : new int[]{pick, pick};
    }
}
