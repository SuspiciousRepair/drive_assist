package com.geely.drivemem.util;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Copies dashcam clips to a USB stick, into {@code Drive Assist/Dashcam/}
 * (issue #18). Each clip goes with its sidecars: the .vtt subtitles (speed,
 * time) that players such as VLC show under the video, and the .jpg thumbnail.
 *
 * Only finished clips: a recording one has no index yet and an orphan needs
 * recovery first, so neither opens on a computer. */
public final class ClipExport {
    public static final String SUBDIR = "Dashcam";

    public enum Result { OK, NO_DRIVE, NO_SPACE, NOTHING, FAILED }

    /** Fires on the export thread; the caller posts to the UI itself. */
    public interface Callback { void onDone(Result r, int clips); }

    private ClipExport() {}

    /** The files to copy for these clips: every playable clip's mp4, plus its
     * vtt and jpg when they exist. */
    static List<File> files(List<Clips.Clip> clips) {
        List<File> out = new ArrayList<>();
        for (Clips.Clip c : clips) {
            if (!c.playable()) continue;
            out.add(c.mp4);
            if (c.vtt.exists()) out.add(c.vtt);
            if (c.thumb.exists()) out.add(c.thumb);
        }
        return out;
    }

    /** Bytes still to write: files already on the stick with the same size
     * are skipped by UsbExport, so they need no space. */
    static long bytesNeeded(List<File> files, File dest) {
        long n = 0;
        for (File f : files) if (!UsbExport.sameSize(f, new File(dest, f.getName()))) n += f.length();
        return n;
    }

    static int playable(List<Clips.Clip> clips) {
        int n = 0;
        for (Clips.Clip c : clips) if (c.playable()) n++;
        return n;
    }

    /** Copies on its own thread. */
    public static void toUsb(Context ctx, List<Clips.Clip> clips, Callback cb) {
        new Thread(() -> cb.onDone(run(clips), playable(clips)), "clip-export").start();
    }

    private static Result run(List<Clips.Clip> clips) {
        List<File> files = files(clips);
        if (files.isEmpty()) return Result.NOTHING;
        File drive = UsbExport.findDrive();
        if (drive == null) return Result.NO_DRIVE;
        long free = UsbExport.freeBytes(drive);
        // A small margin: a stick that ends up 100 % full is a bad surprise.
        if (free >= 0 && bytesNeeded(files, UsbExport.destDir(drive, SUBDIR)) + (16L << 20) > free)
            return Result.NO_SPACE;
        final int[] copied = {0};
        final boolean[] ok = {false};
        UsbExport.exportNow(SUBDIR, (good, d, n) -> { ok[0] = good; copied[0] = n; }, files.toArray(new File[0]));
        return ok[0] && copied[0] == files.size() ? Result.OK : Result.FAILED;
    }
}
