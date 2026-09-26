package com.geely.modehelper;

import java.io.File;
import java.nio.file.Files;

/** Small dependency-free test runner for {@link Vtt}. */
public final class VttTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        // A segment that never closes (power cut) never calls close(): every
        // cue must already be on disk, not in the 8 KB writer buffer.
        File f = File.createTempFile("vtt", ".vtt.tmp");
        try {
            Vtt v = new Vtt(f);
            v.cue(0, 1_000_000, "12 km/h");
            v.cue(1_000_000, 2_000_000, "13 km/h");
            String s = new String(Files.readAllBytes(f.toPath()), "UTF-8");
            check(s.contains("12 km/h") && s.contains("13 km/h"), "cues not on disk before close: " + s);
            v.close();
        } finally { f.delete(); }
        System.out.println("VttTest OK");
    }
}
