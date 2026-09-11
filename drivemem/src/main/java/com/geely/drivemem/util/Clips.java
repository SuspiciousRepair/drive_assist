package com.geely.drivemem.util;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Access to dashcam video clips.
 *
 * Clips are written by modehelper (DashRecorder); this class lists, holds, and deletes them.
 * Clips live in the app's own external files directory, requiring no storage permission.
 * See docs/DASHCAM.md for details.
 */
public final class Clips {

    // Held clips are moved into keep/ directory and never deleted by the ring buffer,
    // though they still count toward the total storage budget.
    public static final String KEEP = "keep";

    // How recently a .mp4.tmp must have been touched to count as "being recorded"
    // rather than "left behind by a crash". The recorder writes several times a
    // second, so anything colder than this is not being written by anyone.
    private static final long LIVE_MS = 15_000;

    private static final SimpleDateFormat STAMP =
        new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private static final SimpleDateFormat SHOWN =
        new SimpleDateFormat("d MMM  HH:mm", Locale.getDefault());

    /** Clip state: DONE (playable), RECORDING (being written), or ORPHAN (incomplete). */
    public enum Kind {
        DONE,       // closed, playable
        RECORDING,  // .mp4.tmp — being written right now, no moov atom yet
        ORPHAN      // .h264 with no .mp4 beside it: a segment that never closed
    }

    /** A dashcam video clip with metadata. */
    public static final class Clip {
        public final File mp4, vtt, thumb;
        public final Kind kind;
        public final boolean held;
        public final long bytes;
        public final long whenMs;
        public final int seconds;      // from the sidecar; -1 when there is none

        Clip(File mp4, boolean held, Kind kind) {
            this.mp4 = mp4;
            this.held = held;
            this.kind = kind;
            this.vtt = new File(mp4.getParentFile(), name(mp4) + ".vtt");
            // Written once by the recorder at segment close, never here: opening a
            // 225 MB container per row is what this whole class avoids.
            this.thumb = new File(mp4.getParentFile(), name(mp4) + ".jpg");
            this.bytes = mp4.length();
            this.whenMs = parseStamp(name(mp4), mp4.lastModified());
            this.seconds = cueCount(this.vtt);
        }

        public String title() { return SHOWN.format(new Date(whenMs)); }

        public boolean playable() { return kind == Kind.DONE; }

        public String subtitle() {
            StringBuilder b = new StringBuilder();
            if (seconds > 0) b.append(seconds / 60).append(':')
                              .append(String.format(Locale.US, "%02d", seconds % 60));
            else b.append("--:--");
            b.append("   ").append(mb(bytes));
            if (kind == Kind.DONE && !vtt.exists()) b.append("   (no data)");
            return b.toString();
        }
    }

    // WHETHER THE RECORDER IS RUNNING, asked of the disk rather than of
    // modehelper. It is a different process with no state to query, but it writes
    // several times a second — so a .mp4.tmp touched within LIVE_MS is proof, and
    // it is the same test the listing already uses to tell recording from debris.
    public static boolean recording(Context c) {
        for (Clip x : list(c)) if (x.kind == Kind.RECORDING) return true;
        return false;
    }

    public static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "dashcam");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File keepDir(Context c) {
        File d = new File(dir(c), KEEP);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // Newest first, held clips mixed in by time rather than pinned to the top:
    // you look for a clip by WHEN it happened, and holding it does not change
    // when it happened.
    public static List<Clip> list(Context c) {
        applyPendingHolds(c);
        List<Clip> out = new ArrayList<>();
        collect(dir(c), false, out);
        collect(keepDir(c), true, out);
        Collections.sort(out, new Comparator<Clip>() {
            @Override public int compare(Clip a, Clip b) { return Long.compare(b.whenMs, a.whenMs); }
        });
        return out;
    }

    // ---- holding a clip that is STILL RECORDING ----
    //
    // hold() itself must not touch a RECORDING clip: its mp4 field is the
    // live .mp4.tmp DashRecorder is still writing several times a second, and
    // moving it out from under that process would not corrupt the write (a
    // rename does not disturb an already-open file descriptor on the same
    // filesystem) but WOULD break the finalize step at segment close —
    // DashRecorder renames .mp4.tmp -> .mp4 using the path it opened the file
    // at, a plain in-memory File/path string, not a live handle, so a move
    // done from over here leaves nothing at the path it goes looking for.
    //
    // So the intent is remembered instead, as an empty sidecar file next to
    // the recording — cheap, and it survives Drive Assist's own process restarting
    // mid-recording, which a purely in-memory flag would not. Every list()
    // call (i.e. every time the Clips screen is looked at) sweeps pending
    // markers and promotes any whose segment has actually closed since.
    private static final String PENDING_SUFFIX = ".hold";

    private static String recordingStem(Clip c) {
        String n = c.mp4.getName();
        return n.endsWith(".mp4.tmp") ? n.substring(0, n.length() - 8) : name(c.mp4);
    }

    public static void markPending(Context c, Clip recording) {
        try { new File(dir(c), recordingStem(recording) + PENDING_SUFFIX).createNewFile(); }
        catch (Exception ignored) {}
    }

    public static void clearPending(Context c, Clip recording) {
        new File(dir(c), recordingStem(recording) + PENDING_SUFFIX).delete();
    }

    public static boolean isPending(Context c, Clip recording) {
        return new File(dir(c), recordingStem(recording) + PENDING_SUFFIX).exists();
    }

    // A marker whose segment has not closed yet (no .mp4 beside it) is left
    // alone for next time — that is the normal case on every call but the
    // last one before the recording actually finishes.
    private static void applyPendingHolds(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (!f.isFile() || !f.getName().endsWith(PENDING_SUFFIX)) continue;
            String stem = f.getName().substring(0, f.getName().length() - PENDING_SUFFIX.length());
            File mp4 = new File(dir(c), stem + ".mp4");
            if (mp4.exists()) {
                hold(c, new Clip(mp4, false, Kind.DONE), true);
                f.delete();
            }
        }
    }

    private static void collect(File d, boolean held, List<Clip> out) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (!f.isFile()) continue;
            String n = f.getName();
            if (n.endsWith(".mp4")) { out.add(new Clip(f, held, Kind.DONE)); continue; }
            // SHOW THE ONE BEING RECORDED. It cannot be played — no moov atom
            // until the segment closes — but hiding it made the screen claim
            // nothing was happening while the disk filled, which is worse than a
            // row that says what it is.
            if (n.endsWith(".mp4.tmp")) {
                // Only if something is actually writing it. A crash leaves a cold
                // .mp4.tmp behind, and the first version of this happily reported
                // it as GRAVANDO forever — while listing the same lost segment a
                // second time as its .h264. A stale one is skipped entirely: the
                // orphan row below is that footage, and it is the row that can
                // actually be recovered.
                if (System.currentTimeMillis() - f.lastModified() < LIVE_MS)
                    out.add(new Clip(f, held, Kind.RECORDING));
                continue;
            }
            // A .h264 outliving its .mp4 means a segment never closed: a crash or
            // a power cut. The footage is intact and remuxable, so it gets a row
            // rather than sitting on disk unmentioned.
            if (n.endsWith(".h264")) {
                // Orphan only if nothing else represents this segment: no closed
                // .mp4, and no LIVE .mp4.tmp either. Checking just the .mp4 listed
                // the write-ahead stream of the segment being recorded right now
                // as if it were crash debris — the same one-segment-two-rows bug
                // as before, wearing a different hat.
                String stem = name(f);
                File done = new File(f.getParentFile(), stem + ".mp4");
                File tmp  = new File(f.getParentFile(), stem + ".mp4.tmp");
                boolean live = tmp.exists()
                            && System.currentTimeMillis() - tmp.lastModified() < LIVE_MS;
                if (!done.exists() && !live) out.add(new Clip(f, held, Kind.ORPHAN));
            }
        }
    }

    public static boolean hold(Context c, Clip clip, boolean held) {
        File target = held ? keepDir(c) : dir(c);
        if (clip.mp4.getParentFile().equals(target)) return true;
        File mp4 = new File(target, clip.mp4.getName());
        // The clip moves first. If the sidecar move fails we lose telemetry, not
        // footage — the same precedence the recorder uses when it renames.
        if (!clip.mp4.renameTo(mp4)) return false;
        if (clip.vtt.exists()) clip.vtt.renameTo(new File(target, clip.vtt.getName()));
        if (clip.thumb.exists()) clip.thumb.renameTo(new File(target, clip.thumb.getName()));
        return true;
    }

    public static void delete(Clip clip) {
        clip.mp4.delete();
        if (clip.vtt.exists()) clip.vtt.delete();
        if (clip.thumb.exists()) clip.thumb.delete();
        // An orphan is one lost segment wearing three filenames. Deleting the row
        // has to take all of them, or the leftovers reappear as a ghost.
        if (clip.kind == Kind.ORPHAN) {
            File d = clip.mp4.getParentFile();
            String stem = name(clip.mp4);
            new File(d, stem + ".mp4.tmp").delete();
            new File(d, stem + ".vtt.tmp").delete();
        }
    }

    public static long usedBytes(Context c) {
        return sum(dir(c)) + sum(keepDir(c));
    }

    public static long heldBytes(Context c) { return sum(keepDir(c)); }

    private static long sum(File d) {
        File[] fs = d.listFiles();
        long n = 0;
        if (fs != null) for (File f : fs) if (f.isFile()) n += Math.max(0, f.length());
        return n;
    }

    public static String mb(long bytes) {
        if (bytes >= 1024L * 1024 * 1024)
            return String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024);
        return String.format(Locale.US, "%d MB", bytes / 1024 / 1024);
    }

    public static String name(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(0, i);
    }

    // Length from the sidecar rather than the container: counting "-->" lines is
    // one cheap pass over ~16 KB, where asking MediaMetadataRetriever means
    // opening and parsing a 225 MB mp4 for every row in the list.
    static int cueCount(File vtt) {
        if (!vtt.exists()) return -1;
        int n = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(vtt))) {
            String line;
            while ((line = r.readLine()) != null) if (line.contains(" --> ")) n++;
        } catch (Exception ignored) { return -1; }
        return n;
    }

    private static long parseStamp(String stem, long fallback) {
        int i = stem.indexOf('_');
        if (i < 0) return fallback;
        try { return STAMP.parse(stem.substring(i + 1)).getTime(); }
        catch (Exception e) { return fallback; }
    }
}
