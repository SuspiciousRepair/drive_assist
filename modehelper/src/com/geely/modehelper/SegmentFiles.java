package com.geely.modehelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    /** A segment name nothing is using yet: `base + suffix`, or with -2, -3
     * ... before the suffix. Two segments started in the same wall-clock
     * second (an event right after a rotation) used to share a name, and the
     * second one's close overwrote the first clip. The counter goes before
     * the suffix, so "_valet" stays at the end where Clips looks for it. */
    static String freeStem(File dir, String base, String suffix) {
        String stem = base + suffix;
        for (int k = 2; taken(dir, stem); k++) stem = base + "-" + k + suffix;
        return stem;
    }

    private static boolean taken(File dir, String stem) {
        for (String ext : new String[] {".mp4", ".mp4.tmp", ".h264", ".vtt", ".vtt.tmp"})
            if (new File(dir, stem + ext).exists()) return true;
        return new File(new File(dir, "keep"), stem + ".mp4").exists();
    }

    /** Marked for keeping: a `<stem>.hold` beside the clip, dropped by a
     * parked-monitoring event or by the Clips screen while the segment was
     * still recording. */
    static boolean held(File dir, String stem) {
        return new File(dir, stem + ".hold").exists();
    }

    /** Moves a held, closed clip into keep/ the moment it closes. Only
     * Drive Assist's Clips screen used to do this, and only when someone
     * opened it — so the ring buffer usually evicted the event clip first,
     * leaving the marker behind with nothing to keep. */
    static void keepIfHeld(File dir, File keep, String stem) {
        File mp4 = new File(dir, stem + ".mp4");
        if (!held(dir, stem) || !mp4.exists()) return;
        if (!mp4.renameTo(new File(keep, mp4.getName()))) return;
        for (String ext : new String[] {".vtt", ".jpg"}) {
            File f = new File(dir, stem + ext);
            if (f.exists()) f.renameTo(new File(keep, f.getName()));
        }
        new File(dir, stem + ".hold").delete();
    }

    // The recorder forces a fragment to storage every second, so a
    // .mp4.tmp untouched for a minute that is not the live segment is one
    // whose writer died.
    static final long REPAIR_COLD_MS = 60_000L;

    /** Turns every dead fragmented .mp4.tmp into a clip: FragmentedMp4.finish
     * cuts a torn last fragment and writes the index, then it is renamed like
     * a clean close. Never the live segment; never a legacy MediaMuxer file
     * (finish refuses those), nor one with a .h264 beside it (a legacy orphan
     * for ClipRecovery). Returns the stems repaired. */
    static List<String> repair(File dir, long nowMs, String liveStem) {
        List<String> done = new ArrayList<>();
        File[] all = dir.listFiles();
        if (all == null) return done;
        for (File f : all) {
            String n = f.getName();
            if (!n.endsWith(".mp4.tmp")) continue;
            String stem = n.substring(0, n.length() - 8);
            if (stem.equals(liveStem) || nowMs - f.lastModified() <= REPAIR_COLD_MS) continue;
            if (new File(dir, stem + ".h264").exists() || new File(dir, stem + ".mp4").exists()) continue;
            try {
                if (!FragmentedMp4.finish(f) || !f.renameTo(new File(dir, stem + ".mp4"))) continue;
            } catch (java.io.IOException e) { continue; }
            File vttTmp = new File(dir, stem + ".vtt.tmp");
            if (vttTmp.exists()) vttTmp.renameTo(new File(dir, stem + ".vtt"));
            done.add(stem);
        }
        return done;
    }

    // Untouched this long means nobody is writing it. A recovery in progress
    // writes its .mp4.tmp continuously, so it is never this cold.
    static final long COLD_MS = 10 * 60_000L;

    private static final String[] SEGMENT_FILES =
        {".mp4", ".h264", ".mp4.tmp", ".vtt", ".vtt.tmp", ".jpg"};

    /** The ring buffer: oldest first against a byte budget, one whole
     * segment at a time. Returns what was dropped, for the log.
     *
     * Orphans (.h264 with no .mp4) used to count against the budget and
     * never be evicted: every crash left ~0.6-1.2 GB that pushed real clips
     * out sooner, forever. They now age out in the same queue. And a
     * crash's moov-less .mp4.tmp is dropped as soon as it is cold: the .h264
     * beside it holds the same frames in a form that can be recovered.
     *
     * Held clips are never candidates: keep/ is not listed, and a clip
     * with a .hold beside it is waiting for keepIfHeld. They still count
     * against the budget, so holding more leaves less room for recording. */
    static List<String> evict(File dir, File keep, long budget, long nowMs) {
        List<String> dropped = new ArrayList<>();
        File[] all = dir.listFiles();
        if (all == null) return dropped;
        for (File f : all) {
            String n = f.getName();
            if (!n.endsWith(".mp4.tmp") || !cold(f, nowMs)) continue;
            File raw = new File(dir, n.substring(0, n.length() - 8) + ".h264");
            if (raw.length() > 0 && f.delete()) dropped.add(n);
        }
        all = dir.listFiles();
        if (all == null) return dropped;
        long used = size(all) + size(keep.listFiles());
        if (used <= budget) return dropped;
        List<File> heads = new ArrayList<>();
        for (File f : all) {
            String n = f.getName();
            if (!f.isFile()) continue;
            boolean orphan = (n.endsWith(".h264") || n.endsWith(".mp4.tmp")) && cold(f, nowMs)
                && !new File(dir, stem(n) + ".mp4").exists();
            if (n.endsWith(".h264") && !orphan) continue;
            // A cold .mp4.tmp repair() could not finish counts as an orphan
            // too, or it would sit in the budget forever.
            if (n.endsWith(".mp4.tmp") && (!orphan || new File(dir, stem(n) + ".h264").exists())) continue;
            if ((n.endsWith(".mp4") || orphan) && !held(dir, stem(n))) heads.add(f);
        }
        Collections.sort(heads, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File head : heads) {
            if (used <= budget) break;
            if (!head.exists()) continue;
            String stem = stem(head.getName());
            for (String ext : SEGMENT_FILES) {
                File f = new File(dir, stem + ext);
                long len = Math.max(0, f.length());
                if (f.exists() && f.delete()) used -= len;
            }
            dropped.add(head.getName());
        }
        return dropped;
    }

    private static boolean cold(File f, long nowMs) { return nowMs - f.lastModified() > COLD_MS; }

    private static String stem(String name) {
        int i = name.indexOf('.');
        return i < 0 ? name : name.substring(0, i);
    }

    private static long size(File[] fs) {
        long n = 0;
        if (fs != null) for (File f : fs) if (f.isFile()) n += Math.max(0, f.length());
        return n;
    }
}
