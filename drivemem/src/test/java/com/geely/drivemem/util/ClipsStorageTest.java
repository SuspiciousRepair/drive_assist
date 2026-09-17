package com.geely.drivemem.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ClipsStorageTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File write(File directory, String name, String contents) throws Exception {
        File file = new File(directory, name);
        Files.write(file.toPath(), contents.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test public void holdAndReleaseKeepClipAndSidecarsOnOriginalVolume() throws Exception {
        File usb = temporary.newFolder("usb", "dashcam");
        File mp4 = write(usb, "dash_20260917_120000.mp4", "video");
        write(usb, "dash_20260917_120000.vtt", "WEBVTT");
        write(usb, "dash_20260917_120000.jpg", "thumbnail");
        assertTrue(Clips.hold(null, new Clips.Clip(mp4, false, Clips.Kind.DONE), true));
        File keep = new File(usb, Clips.KEEP);
        assertFalse(mp4.exists());
        for (String extension : new String[]{"mp4", "vtt", "jpg"})
            assertTrue(new File(keep, "dash_20260917_120000." + extension).exists());
        File held = new File(keep, mp4.getName());
        assertTrue(Clips.hold(null, new Clips.Clip(held, true, Clips.Kind.DONE), false));
        assertTrue(mp4.exists());
        assertTrue(new File(usb, "dash_20260917_120000.vtt").exists());
        assertTrue(new File(usb, "dash_20260917_120000.jpg").exists());
        assertFalse(held.exists());
    }

    @Test public void recordingMarkerStaysBesideLiveClipUntilItCloses() throws Exception {
        File usb = temporary.newFolder("usb", "dashcam");
        File tmp = write(usb, "dash_20260917_120000.mp4.tmp", "recording");
        Clips.Clip live = new Clips.Clip(tmp, false, Clips.Kind.RECORDING);
        Clips.markPending(null, live);
        File marker = new File(usb, "dash_20260917_120000.hold");
        assertTrue(marker.exists());
        assertTrue(Clips.isPending(null, live));
        assertFalse(Clips.hold(null, live, true));
        assertTrue(tmp.exists());
        Clips.listDirectories(Collections.singletonList(usb));
        assertTrue(marker.exists());
        File completed = new File(usb, "dash_20260917_120000.mp4");
        assertTrue(tmp.renameTo(completed));
        List<Clips.Clip> clips = Clips.listDirectories(Collections.singletonList(usb));
        assertEquals(1, clips.size());
        assertTrue(clips.get(0).held);
        assertEquals(new File(usb, "keep"), clips.get(0).mp4.getParentFile());
        assertFalse(marker.exists());
    }

    @Test public void cancellingPendingHoldOnlyRemovesTheMarker() throws Exception {
        File usb = temporary.newFolder("usb");
        File tmp = write(usb, "dash_20260917_120000.mp4.tmp", "video");
        Clips.Clip live = new Clips.Clip(tmp, false, Clips.Kind.RECORDING);
        Clips.markPending(null, live);
        Clips.clearPending(null, live);
        assertFalse(Clips.isPending(null, live));
        assertTrue(tmp.exists());
    }

    @Test public void failedHoldPreservesBothClipsAndPendingProtection() throws Exception {
        File usb = temporary.newFolder("usb");
        File keep = new File(usb, "keep");
        assertTrue(keep.mkdir());
        String name = "dash_20260917_120000.mp4";
        File original = write(usb, name, "original");
        File protectedClip = write(keep, name, "protected");
        File marker = write(usb, "dash_20260917_120000.hold", "");
        assertFalse(Clips.hold(null, new Clips.Clip(original, false, Clips.Kind.DONE), true));
        assertEquals(2, Clips.listDirectories(Collections.singletonList(usb)).size());
        assertTrue(marker.exists());
        assertEquals("original", new String(Files.readAllBytes(original.toPath()), StandardCharsets.UTF_8));
        assertEquals("protected", new String(Files.readAllBytes(protectedClip.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void listingAndPerVolumeTotalsRetainOldDriveClips() throws Exception {
        File internal = temporary.newFolder("internal");
        File usb = temporary.newFolder("usb");
        File keep = new File(usb, "keep");
        assertTrue(keep.mkdir());
        write(internal, "dash_20260917_110000.mp4", "old");
        write(usb, "dash_20260917_130000.mp4", "newer");
        write(keep, "dash_20260917_120000.mp4", "held");
        List<Clips.Clip> clips = Clips.listDirectories(Arrays.asList(internal, usb));
        assertEquals(3, clips.size());
        assertEquals(usb, clips.get(0).mp4.getParentFile());
        assertTrue(clips.get(1).held);
        assertEquals(internal, clips.get(2).mp4.getParentFile());
        assertEquals(3, Clips.usedBytes(internal));
        assertEquals(9, Clips.usedBytes(usb));
        assertEquals(4, Clips.heldBytes(usb));
    }

    @Test public void readOnlyLibraryScanDoesNotTryToPromotePendingHolds() throws Exception {
        File directory = temporary.newFolder("read-only-library");
        File video = write(directory, "dash_20260917_120000.mp4", "video");
        File marker = write(directory, "dash_20260917_120000.hold", "");
        File readOnly = new File(directory.getAbsolutePath()) {
            @Override public boolean canWrite() {
                return false;
            }
        };
        List<Clips.Clip> clips = Clips.listDirectories(Collections.singletonList(readOnly));
        assertEquals(1, clips.size());
        assertEquals(Clips.Kind.DONE, clips.get(0).kind);
        assertEquals(video.getAbsolutePath(), clips.get(0).mp4.getAbsolutePath());
        assertTrue(video.exists());
        assertTrue(marker.exists());
        assertFalse(new File(directory, Clips.KEEP).exists());
    }

    @Test public void clipsLargerThanFourGiBKeepExactLongSizesInLibraryAndHeldTotals() throws Exception {
        File directory = temporary.newFolder("large-file-library");
        File large = new File(directory, "dash_20260917_120000.mp4");
        long largeBytes = 5L * 1024 * 1024 * 1024 + 257;
        // Sparse fixture: validates metadata arithmetic without writing gigabytes
        // or claiming that this temporary test directory is an exFAT volume.
        try (RandomAccessFile fixture = new RandomAccessFile(large, "rw")) {
            fixture.setLength(largeBytes);
        }
        File small = write(directory, "dash_20260917_110000.mp4", "small");
        List<Clips.Clip> clips = Clips.listDirectories(Collections.singletonList(directory));
        assertEquals(2, clips.size());
        assertEquals(largeBytes, clips.get(0).bytes);
        assertEquals(largeBytes + small.length(), Clips.usedBytes(directory));
        assertEquals("5.0 GB", Clips.mb(clips.get(0).bytes));
        assertTrue(Clips.hold(null, clips.get(0), true));
        assertEquals(largeBytes, Clips.heldBytes(directory));
        assertEquals(largeBytes + small.length(), Clips.usedBytes(directory));
        assertEquals(2, Clips.listDirectories(Collections.singletonList(directory)).size());
    }
}
