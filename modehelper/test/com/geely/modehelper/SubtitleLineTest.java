package com.geely.modehelper;

/** Small dependency-free test runner for {@link SubtitleLine}. */
public final class SubtitleLineTest {
    private static void eq(String got, String want) {
        if (!want.equals(got)) throw new AssertionError("want [" + want + "] got [" + got + "]");
    }

    public static void main(String[] args) {
        final String up = "\u2191", down = "\u2193";

        // Steady, rising, falling; a second apart.
        eq(SubtitleLine.trend(60f, 60.9f, 1000), "");
        eq(SubtitleLine.trend(60f, 58.5f, 1000), "");
        eq(SubtitleLine.trend(60f, 62f, 1000), up);
        eq(SubtitleLine.trend(60f, 57f, 1000), down);
        // Hard: 10 km/h in a second or more.
        eq(SubtitleLine.trend(60f, 50f, 1000), down + down);
        eq(SubtitleLine.trend(60f, 51f, 1000), down);
        eq(SubtitleLine.trend(0f, 12f, 1000), up + up);
        // The rate, not the step: the sampler is not exactly a second.
        eq(SubtitleLine.trend(60f, 57f, 2000), "");
        eq(SubtitleLine.trend(60f, 45f, 1500), down + down);
        // Standing still, in any gear.
        eq(SubtitleLine.trend(0f, 0f, 1000), "");
        // Unknown, or samples that are not neighbours: no claim.
        eq(SubtitleLine.trend(null, 60f, 1000), "");
        eq(SubtitleLine.trend(60f, null, 1000), "");
        eq(SubtitleLine.trend(60f, 0f, 0), "");
        eq(SubtitleLine.trend(60f, 0f, 499), "");
        eq(SubtitleLine.trend(60f, 0f, 3001), "");
        eq(SubtitleLine.trend(60f, 0f, -5), "");

        // The limit: camera sign, then navigation, then navigation speed.
        if (SubtitleLine.limit(60, 80, 90) != 60) throw new AssertionError("first plausible");
        if (SubtitleLine.limit(null, 80, 90) != 80) throw new AssertionError("skips unknown");
        if (SubtitleLine.limit(0, 255, 50) != 50) throw new AssertionError("skips sentinels");
        if (SubtitleLine.limit(null, 0, 4) != -1) throw new AssertionError("none");

        eq(SubtitleLine.line("17:11:07", 61.6f, down + down, 8, false, 60),
           "17:11:07 \u00B7 62 km/h " + down + down + " \u00B7 D \u00B7 max 60");
        eq(SubtitleLine.line("17:11:07", 61.6f, down + down, 8, true, 60),
           "17:11:07 \u00B7 62 km/h " + down + down + " \u00B7 D \u00B7 BRAKE \u00B7 max 60");
        // Held at a stop: the pedal with no arrow.
        eq(SubtitleLine.line("17:11:07", 0f, "", 8, true, -1), "17:11:07 \u00B7 0 km/h \u00B7 D \u00B7 BRAKE");
        eq(SubtitleLine.line("17:11:07", 0f, "", 4, false, -1), "17:11:07 \u00B7 0 km/h \u00B7 P");
        eq(SubtitleLine.line("17:11:07", 5f, up, 2, false, -1), "17:11:07 \u00B7 5 km/h " + up + " \u00B7 R");
        // Nothing known: still a line, so the clip's length (one cue a
        // second) stays right.
        eq(SubtitleLine.line("17:11:07", null, "", null, false, -1), "17:11:07 \u00B7 ? km/h");
        eq(SubtitleLine.line("17:11:07", 30f, null, 7, false, -1), "17:11:07 \u00B7 30 km/h \u00B7 g7");
        System.out.println("SubtitleLineTest OK");
    }
}
