package com.geely.modehelper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/** Small dependency-free test runner for {@link SegmentFiles}. */
public final class SegmentFilesTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static File file(File dir, String name, int bytes) throws Exception {
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(new byte[bytes]); }
        return f;
    }

    private static void age(File f, long modifiedMs) {
        check(f.setLastModified(modifiedMs), "could not age " + f);
    }

    public static void main(String[] args) throws Exception {
        // Clean close: the clip gets its name, the sidecar follows, the
        // write-ahead stream goes.
        File d = Files.createTempDirectory("seg").toFile();
        File tmp = file(d, "s.mp4.tmp", 10), vttTmp = file(d, "s.vtt.tmp", 5), raw = file(d, "s.h264", 10);
        File mp4 = new File(d, "s.mp4"), vtt = new File(d, "s.vtt");
        check(SegmentFiles.finish(tmp, mp4, vttTmp, vtt, raw, true), "clean close must be playable");
        check(mp4.exists() && vtt.exists() && !raw.exists() && !tmp.exists(), "clean close files");

        // Failed close (no moov): must NOT be named .mp4, and the .h264 and
        // .vtt.tmp must survive for recovery.
        d = Files.createTempDirectory("seg").toFile();
        tmp = file(d, "s.mp4.tmp", 10); vttTmp = file(d, "s.vtt.tmp", 5); raw = file(d, "s.h264", 10);
        mp4 = new File(d, "s.mp4"); vtt = new File(d, "s.vtt");
        check(!SegmentFiles.finish(tmp, mp4, vttTmp, vtt, raw, false), "failed close is not playable");
        check(!mp4.exists(), "failed close was named .mp4");
        check(raw.exists() && vttTmp.exists(), "failed close lost the recoverable files");
        check(!tmp.exists(), "moov-less .mp4.tmp kept beside a full .h264");

        // Failed close with no write-ahead stream: the .mp4.tmp is all there is.
        d = Files.createTempDirectory("seg").toFile();
        tmp = file(d, "s.mp4.tmp", 10); vttTmp = file(d, "s.vtt.tmp", 5); raw = new File(d, "s.h264");
        check(!SegmentFiles.finish(tmp, new File(d, "s.mp4"), vttTmp, new File(d, "s.vtt"), raw, false), "no raw");
        check(tmp.exists(), "the only copy was deleted");

        // A held clip moves into keep/ as it closes, with its sidecars, and
        // the marker goes: the ring buffer never sees it.
        d = Files.createTempDirectory("seg").toFile();
        File keep = new File(d, "keep"); keep.mkdirs();
        file(d, "s.mp4", 10); file(d, "s.vtt", 5); file(d, "s.jpg", 5); file(d, "s.hold", 0);
        check(SegmentFiles.held(d, "s"), "marker not seen");
        SegmentFiles.keepIfHeld(d, keep, "s");
        check(new File(keep, "s.mp4").exists() && new File(keep, "s.vtt").exists()
              && new File(keep, "s.jpg").exists(), "held clip not moved to keep/");
        check(!new File(d, "s.mp4").exists() && !new File(d, "s.hold").exists(), "held clip left behind");

        // Not held: nothing moves.
        d = Files.createTempDirectory("seg").toFile();
        keep = new File(d, "keep"); keep.mkdirs();
        file(d, "s.mp4", 10);
        SegmentFiles.keepIfHeld(d, keep, "s");
        check(new File(d, "s.mp4").exists() && !new File(keep, "s.mp4").exists(), "unheld clip moved");

        // Ring buffer. Times are relative to a fixed "now"; cold = older than COLD_MS.
        long now = 1_000_000_000_000L, old = now - SegmentFiles.COLD_MS - 60_000;
        d = Files.createTempDirectory("seg").toFile();
        keep = new File(d, "keep"); keep.mkdirs();
        // An orphan from a crash, oldest of all: .h264 + moov-less .mp4.tmp + .vtt.tmp.
        age(file(d, "a.h264", 100), old - 3000); age(file(d, "a.mp4.tmp", 100), old - 3000);
        age(file(d, "a.vtt.tmp", 1), old - 3000);
        age(file(d, "b.mp4", 100), old - 2000); age(file(d, "b.vtt", 1), old - 2000);
        age(file(d, "c.mp4", 100), old - 1000); file(d, "c.hold", 0);           // held
        age(file(d, "e.mp4", 100), old);

        // Under budget: only the crash's worthless .mp4.tmp goes.
        java.util.List<String> dropped = SegmentFiles.evict(d, keep, 10_000, now);
        check(dropped.equals(java.util.Arrays.asList("a.mp4.tmp")), "under budget dropped " + dropped);
        check(new File(d, "a.h264").exists(), "orphan .h264 must stay while there is room");

        // Over budget (4 x 100 bytes + 1 + 1 used, 250 allowed): the orphan
        // goes first as a whole, then the oldest clip; the held one never.
        dropped = SegmentFiles.evict(d, keep, 250, now);
        check(dropped.equals(java.util.Arrays.asList("a.h264", "b.mp4")), "over budget dropped " + dropped);
        check(!new File(d, "a.vtt.tmp").exists() && !new File(d, "b.vtt").exists(), "sidecars left behind");
        check(new File(d, "c.mp4").exists() && new File(d, "e.mp4").exists(), "wrong clips evicted");

        // A .h264 still being written (fresh) is never a candidate.
        d = Files.createTempDirectory("seg").toFile();
        keep = new File(d, "keep"); keep.mkdirs();
        file(d, "live.h264", 100); file(d, "live.mp4.tmp", 100);
        dropped = SegmentFiles.evict(d, keep, 10, System.currentTimeMillis());
        check(dropped.isEmpty() && new File(d, "live.h264").exists()
              && new File(d, "live.mp4.tmp").exists(), "live segment touched: " + dropped);

        // Same second, same name: the second segment gets -2, before "_valet".
        d = Files.createTempDirectory("seg").toFile();
        check(SegmentFiles.freeStem(d, "dash_1", "_valet").equals("dash_1_valet"), "free name changed");
        file(d, "dash_1_valet.mp4.tmp", 1);
        check(SegmentFiles.freeStem(d, "dash_1", "_valet").equals("dash_1-2_valet"), "collision not avoided");
        file(d, "dash_1-2_valet.mp4", 1);
        check(SegmentFiles.freeStem(d, "dash_1", "_valet").equals("dash_1-3_valet"), "second collision");
        new File(d, "keep").mkdirs();
        file(new File(d, "keep"), "dash_2.mp4", 1);
        check(SegmentFiles.freeStem(d, "dash_2", "").equals("dash_2-2"), "held clip overwritten");

        System.out.println("SegmentFilesTest OK");
    }
}
