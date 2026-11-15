package com.geely.drivemem.util;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Issue #18: what goes to the USB stick, and how much space it needs. */
public class ClipExportTest {

    private static File write(File dir, String name, int bytes) throws Exception {
        File f = new File(dir, name);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(new byte[bytes]); }
        return f;
    }

    @Test public void aFinishedClipGoesWithItsSubtitlesAndThumbnail() throws Exception {
        File dir = Files.createTempDirectory("clips").toFile();
        File mp4 = write(dir, "dash_20261114_101500.mp4", 1000);
        write(dir, "dash_20261114_101500.vtt", 50);
        write(dir, "dash_20261114_101500.jpg", 20);
        File bare = write(dir, "dash_20261114_102500.mp4", 500);   // no sidecars

        List<File> files = ClipExport.files(Arrays.asList(
            new Clips.Clip(mp4, false, Clips.Kind.DONE),
            new Clips.Clip(bare, true, Clips.Kind.DONE)));

        assertEquals(4, files.size());
        assertEquals("dash_20261114_101500.mp4", files.get(0).getName());
        assertEquals("dash_20261114_101500.vtt", files.get(1).getName());
        assertEquals("dash_20261114_101500.jpg", files.get(2).getName());
        assertEquals("dash_20261114_102500.mp4", files.get(3).getName());
    }

    @Test public void recordingAndOrphanClipsAreNotExported() throws Exception {
        File dir = Files.createTempDirectory("clips").toFile();
        File live = write(dir, "dash_20261114_103000.mp4.tmp", 100);
        File orphan = write(dir, "dash_20261114_090000.h264", 100);
        List<Clips.Clip> clips = Arrays.asList(
            new Clips.Clip(live, false, Clips.Kind.RECORDING),
            new Clips.Clip(orphan, false, Clips.Kind.ORPHAN));
        assertTrue(ClipExport.files(clips).isEmpty());
        assertEquals(0, ClipExport.playable(clips));
    }

    @Test public void filesAlreadyOnTheStickNeedNoSpace() throws Exception {
        File dir = Files.createTempDirectory("clips").toFile();
        File stick = Files.createTempDirectory("usb").toFile();
        File a = write(dir, "a.mp4", 1000);
        File b = write(dir, "b.mp4", 300);
        File c = write(dir, "c.mp4", 200);
        write(stick, "a.mp4", 1000);   // same size: already exported
        write(stick, "b.mp4", 10);     // cut short (stick pulled): copy again

        assertEquals(300 + 200, ClipExport.bytesNeeded(Arrays.asList(a, b, c), stick));
    }
}
