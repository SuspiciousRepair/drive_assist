package com.geely.modehelper;

/** The dashcam's subtitle line, one a second (see DashRecorder.sample and
 * docs/DASHCAM.md):
 *
 *     17:11:07 · 62 km/h ↓↓ · D · max 60
 *
 * Only what tells how the car was driven: the time, the speed and whether
 * it is rising or falling, the gear, and the speed limit when the car knows
 * one. The date is the clip's name. The outside temperature and the compass
 * heading used to be here and said nothing about the driving.
 *
 * The arrow is the change of the speed itself, not a pedal: the helper has
 * no brake or accelerator signal (the brake switch is on the CAN bus, which
 * Drive Assist reads, not this process). So ↓ also shows a lift-off that
 * slows the car by regeneration, which is what the car did.
 *
 * Plain Java, so it is tested on a desktop JVM. */
final class SubtitleLine {
    private SubtitleLine() { }

    // km/h per second. Under TREND the speed is steady: the reading moves
    // by less than 1 km/h a second at a constant speed. HARD is about
    // 0.28 g, a firm stop; gentle driving stays well under it.
    static final float TREND_KMH_S = 2f;
    static final float HARD_KMH_S = 10f;
    // Samples are about a second apart. Closer, one step of the reading
    // looks like a big change; further apart, they are not neighbours.
    static final long MIN_DT_MS = 500L, MAX_DT_MS = 3_000L;

    /** "↑" or "↓" for a speed that is rising or falling, doubled when it is
     * hard, "" when steady or unknown. */
    static String trend(Float beforeKmh, Float nowKmh, long dtMs) {
        if (beforeKmh == null || nowKmh == null || dtMs < MIN_DT_MS || dtMs > MAX_DT_MS) return "";
        float rate = (nowKmh - beforeKmh) * 1000f / dtMs;
        float size = Math.abs(rate);
        if (size < TREND_KMH_S) return "";
        String one = rate > 0 ? "↑" : "↓";
        return size >= HARD_KMH_S ? one + one : one;
    }

    static String gear(int g) {
        switch (g) { case 1: return "N"; case 2: return "R"; case 4: return "P"; case 8: return "D"; }
        return "g" + g;
    }

    /** The first plausible speed limit of the candidates, in order, or -1.
     * A real limit is a small round number; anything else is an enum, a
     * sentinel or noise, and putting it on screen would be a lie. */
    static int limit(Integer... candidates) {
        for (Integer v : candidates) if (v != null && v >= 5 && v <= 200) return v;
        return -1;
    }

    /** `time` is HH:mm:ss; `kmh` and `gear` null when unknown; `limit` -1
     * when there is none. */
    static String line(String time, Float kmh, String trend, Integer gear, int limit) {
        StringBuilder b = new StringBuilder(48);
        b.append(time).append(" · ")
         .append(kmh == null ? "?" : String.valueOf(Math.round(kmh))).append(" km/h");
        if (trend != null && !trend.isEmpty()) b.append(' ').append(trend);
        if (gear != null) b.append(" · ").append(gear(gear));
        if (limit > 0) b.append(" · max ").append(limit);
        return b.toString();
    }
}
