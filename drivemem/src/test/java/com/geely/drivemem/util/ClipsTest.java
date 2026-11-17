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

    // The bug: in Park with Park monitoring on, the recorder is on but has no
    // segment open, so the Record switch read "off" and flipped back.
    @Test public void watchingCountsAsOn() throws Exception {
        long now = 1_790_000_000_000L;
        org.junit.Assert.assertEquals(Boolean.TRUE, Clips.recorderOn(state("watching", "", now - 4_000), now));
        org.junit.Assert.assertEquals(Boolean.TRUE, Clips.recorderOn(state("recording", "dash_1", now - 4_000), now));
    }

    @Test public void stoppedIsOffAndStaleOrMissingSaysNothing() throws Exception {
        long now = 1_790_000_000_000L;
        org.junit.Assert.assertEquals(Boolean.FALSE, Clips.recorderOn(state("stopped", "", now - 3_600_000), now));
        org.junit.Assert.assertNull(Clips.recorderOn(state("watching", "", now - Clips.BEAT_MS - 1), now));
        org.junit.Assert.assertNull(Clips.recorderOn(Files.createTempDirectory("clips").toFile(), now));
    }

    // A tap holds the switch where it was put while the recorder starts up.
    @Test public void switchKeepsTheRequestDuringGrace() {
        long tap = 1_790_000_000_000L;
        assertTrue(Clips.shown(tap, true, tap + 1_500, () -> false));
        assertFalse(Clips.shown(tap, false, tap + 1_500, () -> true));
        assertFalse(Clips.shown(tap, true, tap + Clips.ASK_GRACE_MS, () -> false));
        assertTrue(Clips.shown(0, false, tap, () -> true));
    }

    // The one re-check after a tap comes after the grace, so it shows the
    // recorder's real state and not the request again.
    @Test public void recheckAfterATapShowsTheRealState() {
        long tap = 1_790_000_000_000L;
        assertTrue(Clips.RECHECK_MS > Clips.ASK_GRACE_MS);
        assertFalse(Clips.shown(tap, true, tap + Clips.RECHECK_MS, () -> false));
        assertTrue(Clips.shown(tap, false, tap + Clips.RECHECK_MS, () -> true));
    }
}
