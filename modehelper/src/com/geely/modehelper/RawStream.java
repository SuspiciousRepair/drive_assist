package com.geely.modehelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** The write-ahead .h264 of one segment: every access unit, Annex-B, as the
 * encoder produced it. See DashRecorder.Seg for why it exists. Plain Java,
 * so it can be tested on a desktop JVM.
 *
 * Two things it does that the FileOutputStream it replaces did not:
 * - It writes the encoder's buffer straight through a FileChannel. The old
 *   path copied every frame into a new byte[] first: ~25 allocations and
 *   ~2 MB of garbage a second, most of the helper's garbage-collector time.
 * - It forces the file to storage at every key frame (once a second). The
 *   stream existed for exactly the case where the process never closes it,
 *   yet unsynced data sits in the page cache for up to ~30 s, and a power
 *   cut loses it: the seconds around an incident. */
final class RawStream {
    private final FileChannel ch;

    RawStream(File f) throws IOException {
        ch = new FileOutputStream(f).getChannel();
    }

    /** SPS/PPS (csd-0, csd-1), before any frame. */
    void header(ByteBuffer csd) throws IOException {
        ByteBuffer d = csd.duplicate();
        while (d.hasRemaining()) ch.write(d);
    }

    /** One access unit: `size` bytes at `offset` of the encoder's buffer.
     * The buffer's own position and limit are left untouched — the muxer
     * reads the same buffer right after. */
    void frame(ByteBuffer buf, int offset, int size, boolean key) throws IOException {
        ByteBuffer d = buf.duplicate();
        d.limit(offset + size);
        d.position(offset);
        while (d.hasRemaining()) ch.write(d);
        if (key) ch.force(false);
    }

    void close() {
        try { ch.close(); } catch (IOException ignored) { }
    }
}
