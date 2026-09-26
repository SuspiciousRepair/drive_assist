package com.geely.modehelper;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;

/** Small dependency-free test runner for {@link RawStream}. */
public final class RawStreamTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File f = File.createTempFile("raw", ".h264");
        try {
            RawStream s = new RawStream(f);
            ByteBuffer csd = ByteBuffer.wrap(new byte[] {0, 0, 0, 1, 0x67, 9});
            s.header(csd);
            check(csd.position() == 0, "header moved the format's buffer");

            // Like a MediaCodec output buffer: direct, the frame somewhere
            // inside it, position/limit not describing the frame.
            ByteBuffer out = ByteBuffer.allocateDirect(16);
            out.put(new byte[] {7, 7, 0, 0, 0, 1, 0x65, 1, 2, 7, 7, 7, 7, 7, 7, 7});
            out.position(3).limit(12);
            s.frame(out, 2, 7, true);
            check(out.position() == 3 && out.limit() == 12, "frame moved the encoder's buffer");
            s.frame(out, 2, 7, false);
            s.close();

            byte[] want = {0, 0, 0, 1, 0x67, 9, 0, 0, 0, 1, 0x65, 1, 2, 0, 0, 0, 1, 0x65, 1, 2};
            byte[] got = Files.readAllBytes(f.toPath());
            check(Arrays.equals(want, got), "wrote " + Arrays.toString(got));
        } finally { f.delete(); }
        System.out.println("RawStreamTest OK");
    }
}
