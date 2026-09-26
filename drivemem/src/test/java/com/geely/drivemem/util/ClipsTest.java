package com.geely.drivemem.util;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClipsTest {

    // Three of these sat on the car with their clips long evicted.
    @Test public void holdMarkerWithNothingLeftIsStale() throws Exception {
        File dir = Files.createTempDirectory("clips").toFile();
        assertTrue(Clips.stale(dir, "dash_1"));
    }

    // Exactly what modehelper's RecorderState writes (RecorderStateTest pins
    // the same text on that side).
    private static File state(String state, String stem, long beat) throws Exception {
        File dir = Files.createTempDirectory("clips").toFile();
        try (java.io.FileWriter w = new java.io.FileWriter(new File(dir, "recorder.state"))) {
            w.write("state=" + state + "\nstem=" + stem + "\nbeat=" + beat + "\nerror=\n");
        }
        return dir;
    }

    @Test public void liveStemComesFromAFreshRecordingBeat() throws Exception {
        long now = 1_790_000_000_000L;
        org.junit.Assert.assertEquals("dash_1", Clips.liveStem(state("recording", "dash_1", now - 10_000), now));
    }

    @Test public void noLiveStemWhenStoppedStaleOrMissing() throws Exception {
        long now = 1_790_000_000_000L;
        org.junit.Assert.assertNull(Clips.liveStem(state("stopped", "", now), now));
        org.junit.Assert.assertNull(Clips.liveStem(state("recording", "dash_1", now - Clips.BEAT_MS - 1), now));
        org.junit.Assert.assertNull(Clips.liveStem(Files.createTempDirectory("clips").toFile(), now));
    }

    @Test public void holdMarkerWaitingOnASegmentIsNotStale() throws Exception {
        for (String ext : new String[] {".mp4", ".mp4.tmp", ".h264"}) {
            File dir = Files.createTempDirectory("clips").toFile();
            assertTrue(new File(dir, "dash_1" + ext).createNewFile());
            assertFalse(ext, Clips.stale(dir, "dash_1"));
        }
    }
}
