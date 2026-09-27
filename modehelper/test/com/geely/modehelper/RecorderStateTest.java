package com.geely.modehelper;

import java.io.File;
import java.nio.file.Files;

/** Small dependency-free test runner for {@link RecorderState}. The format
 * is a contract with Drive Assist's Clips.liveStem (ClipsTest writes the
 * same text), so it is pinned byte for byte. */
public final class RecorderStateTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File d = Files.createTempDirectory("state").toFile();
        RecorderState.write(d, "recording", "dash_1", 1234, null);
        String s = new String(Files.readAllBytes(new File(d, "recorder.state").toPath()), "UTF-8");
        check(s.equals("state=recording\nstem=dash_1\nbeat=1234\nerror=\n"), "format: " + s);
        check(!new File(d, "recorder.state.tmp").exists(), "temp file left behind");

        RecorderState.write(d, "stopped", "", 5678, "codec\nerror");
        s = new String(Files.readAllBytes(new File(d, "recorder.state").toPath()), "UTF-8");
        check(s.equals("state=stopped\nstem=\nbeat=5678\nerror=codec error\n"), "error not one line: " + s);

        System.out.println("RecorderStateTest OK");
    }
}
