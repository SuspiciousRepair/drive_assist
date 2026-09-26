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

        System.out.println("SegmentFilesTest OK");
    }
}
