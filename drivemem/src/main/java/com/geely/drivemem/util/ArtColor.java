package com.geely.drivemem.util;

/** The colour an album cover shows most of, for the lyrics card's
 * background, and the text colour that reads on it. Pure Java on an ARGB
 * pixel array, so the unit tests run it directly; the caller scales the
 * bitmap down first (a few hundred pixels are plenty).
 *
 * "Most of": pixels are grouped by colour, 4 bits per channel (4096
 * groups), and the biggest group wins; the answer is that group's average,
 * not its corner, so it is a colour the cover really has. Transparent
 * pixels do not count. */
public final class ArtColor {
    private ArtColor() {}

    /** The predominant colour, opaque; fallback when there is no pixel. */
    public static int dominant(int[] argb, int fallback) {
        if (argb == null || argb.length == 0) return fallback;
        int[] count = new int[4096];
        long[] r = new long[4096], g = new long[4096], b = new long[4096];
        int best = -1;
        for (int p : argb) {
            if ((p >>> 24) < 128) continue;
            int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
            int k = ((pr >> 4) << 8) | ((pg >> 4) << 4) | (pb >> 4);
            count[k]++; r[k] += pr; g[k] += pg; b[k] += pb;
            if (best < 0 || count[k] > count[best]) best = k;
        }
        if (best < 0) return fallback;
        int n = count[best];
        return 0xFF000000 | ((int) (r[best] / n) << 16) | ((int) (g[best] / n) << 8) | (int) (b[best] / n);
    }

    /** Near-black or white, whichever has the higher contrast on bg (WCAG
     * relative luminance; the two contrasts are equal near 0.179). */
    public static int textOn(int bg) {
        return luminance(bg) > 0.179 ? 0xFF111111 : 0xFFFFFFFF;
    }

    static double luminance(int c) {
        return 0.2126 * lin((c >> 16) & 0xFF) + 0.7152 * lin((c >> 8) & 0xFF) + 0.0722 * lin(c & 0xFF);
    }

    private static double lin(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
