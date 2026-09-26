package com.geely.modehelper;

import java.io.File;

/** The file moves at the end of a dashcam segment, kept free of Android so
 * they can be tested on a desktop JVM. See DashRecorder.Seg. */
final class SegmentFiles {
    private SegmentFiles() { }

    /** Settles one segment's files after its muxer stopped. `closed` is
     * whether MediaMuxer.stop() returned normally, i.e. whether the moov
     * atom was written. Returns true when a playable .mp4 now exists.
     *
     * Only a clean close earns the .mp4 name and costs the .h264. A failed
     * close used to be renamed to .mp4 anyway, listed as playable, with its
     * write-ahead stream deleted — the one copy that could still be
     * recovered. Now it stays an orphan: the .h264 and the .vtt.tmp are
     * kept for ClipRecovery, and the moov-less .mp4.tmp goes when the .h264
     * holds the same frames. */
    static boolean finish(File mp4Tmp, File mp4, File vttTmp, File vtt, File raw, boolean closed) {
        if (closed && mp4Tmp.length() > 0 && mp4Tmp.renameTo(mp4)) {
            // The mp4 is renamed FIRST: a .vtt with no clip beside it is
            // litter, but a clip with no subtitles is still footage.
            if (vttTmp.exists()) vttTmp.renameTo(vtt);
            raw.delete();
            return true;
        }
        if (raw.length() > 0 || mp4Tmp.length() == 0) mp4Tmp.delete();
        return false;
    }
}
