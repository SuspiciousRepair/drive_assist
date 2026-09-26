package com.geely.drivemem.util;

import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClipRecoveryTest {

    private static void write(File f, String s) throws Exception {
        try (FileWriter w = new FileWriter(f)) { w.write(s); }
    }

    // Recovery used to delete the orphan's .vtt.tmp, so a recovered clip lost
    // its telemetry and showed --:-- for its length.
    @Test public void recoveredClipKeepsItsSubtitles() throws Exception {
        File dir = Files.createTempDirectory("clips").toFile();
        write(new File(dir, "dash_1.vtt.tmp"), "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nx\n\n");
        ClipRecovery.keepSidecar(dir, "dash_1");
        assertFalse(new File(dir, "dash_1.vtt.tmp").exists());
        assertEquals(1, Clips.cueCount(new File(dir, "dash_1.vtt")));
    }

    @Test public void anExistingSidecarIsNotOverwritten() throws Exception {
        File dir = Files.createTempDirectory("clips").toFile();
        write(new File(dir, "dash_1.vtt"), "kept");
        write(new File(dir, "dash_1.vtt.tmp"), "stale");
        ClipRecovery.keepSidecar(dir, "dash_1");
        assertFalse(new File(dir, "dash_1.vtt.tmp").exists());
        assertEquals("kept", new String(Files.readAllBytes(new File(dir, "dash_1.vtt").toPath())));
    }

    @Test public void noSidecarIsFine() throws Exception {
        File dir = Files.createTempDirectory("clips").toFile();
        ClipRecovery.keepSidecar(dir, "dash_1");
        assertTrue(dir.list().length == 0);
    }
}
