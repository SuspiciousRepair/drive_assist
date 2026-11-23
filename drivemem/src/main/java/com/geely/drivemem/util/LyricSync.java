package com.geely.drivemem.util;

import java.util.ArrayList;
import java.util.List;

/** The owner's own timing fix for one song's lyrics: points that say "the
 * line timed X in the lyrics is sung at Y in the song", one per line the
 * owner tapped while it was sung. Pure Java, no Android, so the unit tests
 * run it directly. Immutable.
 *
 * One point moves every line by the same amount. Two or more also fix
 * lyrics that drift (timed for another version of the song): between two
 * points a line moves in proportion, so each point stays exact. Before the
 * first point and after the last, lines move like the nearest point. */
public final class LyricSync {
    public static final LyricSync NONE = new LyricSync(new long[0], new long[0]);

    private final long[] at;   // the line's time in the lyrics (ms), rising
    private final long[] to;   // the song's position when it was sung (ms), rising

    private LyricSync(long[] at, long[] to) { this.at = at; this.to = to; }

    public boolean isEmpty() { return at.length == 0; }
    public int size() { return at.length; }

    /** True when the line timed lrcMs has a point of its own. */
    public boolean has(long lrcMs) {
        for (long a : at) if (a == lrcMs) return true;
        return false;
    }

    /** This sync plus one point: the line timed lrcMs is sung at songMs. A
     * point on the same line is replaced. A point that disagrees with the
     * new one (an earlier line marked later, or a later line marked earlier)
     * is dropped: the newest tap wins, and the times keep rising. */
    public LyricSync with(long lrcMs, long songMs) {
        List<long[]> keep = new ArrayList<>();
        boolean added = false;
        for (int i = 0; i < at.length; i++) {
            boolean before = at[i] < lrcMs && to[i] < songMs;
            boolean after = at[i] > lrcMs && to[i] > songMs;
            if (!before && !after) continue;
            if (after && !added) { keep.add(new long[]{lrcMs, songMs}); added = true; }
            keep.add(new long[]{at[i], to[i]});
        }
        if (!added) keep.add(new long[]{lrcMs, songMs});
        return of(keep);
    }

    /** When the line timed lrcMs is sung in the song. */
    public long map(long lrcMs) {
        int n = at.length;
        if (n == 0) return lrcMs;
        if (lrcMs <= at[0]) return lrcMs + (to[0] - at[0]);
        if (lrcMs >= at[n - 1]) return lrcMs + (to[n - 1] - at[n - 1]);
        int i = 0;
        while (at[i + 1] < lrcMs) i++;
        double f = (lrcMs - at[i]) / (double) (at[i + 1] - at[i]);
        return to[i] + Math.round(f * (to[i + 1] - to[i]));
    }

    /** "lyricsMs:songMs" pairs, comma separated: what is stored. */
    public String encode() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < at.length; i++) {
            if (i > 0) b.append(',');
            b.append(at[i]).append(':').append(to[i]);
        }
        return b.toString();
    }

    /** Reads encode()'s text. Bad pairs are skipped; null or empty: NONE. */
    public static LyricSync decode(String s) {
        if (s == null || s.isEmpty()) return NONE;
        LyricSync out = NONE;
        for (String pair : s.split(",")) {
            String[] p = pair.split(":");
            if (p.length != 2) continue;
            try { out = out.with(Long.parseLong(p[0].trim()), Long.parseLong(p[1].trim())); }
            catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private static LyricSync of(List<long[]> points) {
        long[] a = new long[points.size()], t = new long[points.size()];
        for (int i = 0; i < a.length; i++) { a[i] = points.get(i)[0]; t[i] = points.get(i)[1]; }
        return new LyricSync(a, t);
    }
}
