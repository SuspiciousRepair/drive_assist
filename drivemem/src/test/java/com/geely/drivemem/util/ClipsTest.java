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

    @Test public void holdMarkerWaitingOnASegmentIsNotStale() throws Exception {
        for (String ext : new String[] {".mp4", ".mp4.tmp", ".h264"}) {
            File dir = Files.createTempDirectory("clips").toFile();
            assertTrue(new File(dir, "dash_1" + ext).createNewFile());
            assertFalse(ext, Clips.stale(dir, "dash_1"));
        }
    }
}
