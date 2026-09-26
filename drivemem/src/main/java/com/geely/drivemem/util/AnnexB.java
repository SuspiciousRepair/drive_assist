package com.geely.drivemem.util;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Splits a raw H.264 Annex-B elementary stream (the format DashRecorder
 * writes to `.h264` -- see its own comment on why: a crash mid-segment
 * leaves an unplayable `.mp4.tmp` with no moov atom, but the raw stream is
 * valid at every byte) into NAL unit byte ranges, each one still carrying
 * its own start code.
 *
 * Pure logic, no Android/file dependency, so it can be unit tested with a
 * plain byte[] -- {@link ClipRecovery} adapts a memory-mapped file to the
 * same {@link Source} interface for the real recovery path. */
public final class AnnexB {
    private AnnexB() {}

    /** Minimal random-access view AnnexB needs -- a byte[] test double or a
     * MappedByteBuffer both satisfy this without a copy. */
    public interface Source {
        int length();
        byte get(int i);
    }

    public static Source of(byte[] data) {
        return new Source() {
            @Override public int length() { return data.length; }
            @Override public byte get(int i) { return data[i]; }
        };
    }

    /** One NAL unit's byte range [start, end), start code included -- exactly
     * the bytes DashRecorder originally wrote for that unit, since the raw
     * file is the ByteBuffer dump of each MediaCodec output buffer verbatim. */
    public static final class Nal {
        public final int start, end;
        public Nal(int start, int end) { this.start = start; this.end = end; }
        public int length() { return end - start; }
    }

    /** nal_unit_type of the NAL beginning at `nal.start` -- low 5 bits of the
     * byte right after the start code. Type 7 = SPS, 8 = PPS, 5 = IDR slice
     * (a keyframe), 1 = non-IDR slice.
     *
     * Always +3, never +4: split() below finds the *core* `00 00 01` of a
     * start code, whether the stream actually wrote it 3-byte or 4-byte --
     * a leading extra zero from a 4-byte code lands as a harmless trailing
     * byte on the PREVIOUS unit instead (legal per the Annex-B spec's own
     * trailing_zero_8bits, and every decoder already tolerates it), so
     * `nal.start` is never the position of that extra zero. */
    public static int type(Source s, Nal nal) {
        int hdr = nal.start + 3;
        return hdr < nal.end ? (s.get(hdr) & 0x1F) : -1;
    }

    /** Same type helper for streaming recovery NALs. Reader always normalizes
     * start codes to three bytes, so the header is byte three. */
    public static int type(byte[] nal) {
        return nal.length > 3 ? (nal[3] & 0x1F) : -1;
    }

    /** first_mb_in_slice, or -1 when a crash cut the slice before its header.
     * It is the first unsigned Exp-Golomb value in RBSP after the NAL header. */
    public static int firstMbInSlice(byte[] nal) {
        int leading = 0, payload = 0, remaining = -1;
        int zeros = 0;
        for (int i = 4; i < nal.length; i++) {
            int b = nal[i] & 0xFF;
            if (zeros >= 2 && b == 3) { zeros = 0; continue; }
            zeros = b == 0 ? zeros + 1 : 0;
            for (int bit = 7; bit >= 0; bit--) {
                int v = (b >>> bit) & 1;
                if (remaining < 0) { if (v == 0) leading++; else { remaining = leading; if (remaining == 0) return 0; } }
                else { payload = (payload << 1) | v; if (--remaining == 0) return ((1 << leading) - 1) + payload; }
            }
        }
        return -1;
    }

    /** Incremental Annex-B reader. It never maps or retains the full video:
     * at most one NAL unit is resident, making 600 MB interrupted clips safe
     * on the head unit.
     *
     * Scans its own buffer rather than calling InputStream.read() per byte:
     * that synchronized call per byte held recovery to ~0.4 MB/s on the car,
     * a quarter of an hour per segment and past the UI's five-minute wait. */
    public static final class Reader implements Closeable {
        private final InputStream in;
        private final byte[] buf = new byte[256 * 1024];
        private int pos, lim;
        private boolean started, prefixNext;
        private byte[] nal = new byte[64 * 1024];
        private int n;

        public Reader(File file) throws IOException { in = new FileInputStream(file); }

        /** The next NAL unit, always led by a three-byte `00 00 01`; null at
         * the end. A four-byte code's extra zero ends the PREVIOUS unit. */
        public byte[] next() throws IOException {
            n = 0;
            if (prefixNext) { prefixNext = false; put(0); put(0); put(1); }
            int zeroes = 0;
            for (;;) {
                if (pos == lim) {
                    lim = in.read(buf, 0, buf.length);
                    pos = 0;
                    if (lim <= 0) {
                        lim = 0;
                        if (!started) return null;
                        while (zeroes-- > 0) put(0);
                        return n <= 3 ? null : Arrays.copyOf(nal, n);
                    }
                }
                // Fast path: a start code needs two zeros in a row, so looking
                // at every SECOND byte finds any place one could begin, and
                // everything before it is copied in one arraycopy. The byte-
                // by-byte machine below then handles only the zeros. This is
                // what makes recovery fast on a unit with the JIT off, where
                // each byte examined in Java is an interpreted step.
                if (started && zeroes == 0 && buf[pos] != 0) {
                    int i = pos + 1;
                    while (i < lim && buf[i] != 0) i += 2;
                    int end = i < lim ? (buf[i - 1] == 0 ? i - 1 : i)
                                      : (buf[lim - 1] == 0 ? lim - 1 : lim);
                    putAll(buf, pos, end - pos);
                    pos = end;
                    continue;
                }
                int b = buf[pos++] & 0xFF;
                if (b == 0) { zeroes++; continue; }
                if (b == 1 && zeroes >= 2) {
                    if (started) {
                        while (zeroes-- > 2) put(0);
                        prefixNext = true;
                        return Arrays.copyOf(nal, n);
                    }
                    started = true; zeroes = 0; put(0); put(0); put(1); continue;
                }
                if (started) { while (zeroes-- > 0) put(0); put(b); }
                zeroes = 0;
            }
        }

        private void putAll(byte[] src, int off, int len) {
            if (n + len > nal.length) nal = Arrays.copyOf(nal, Math.max(nal.length * 2, n + len));
            System.arraycopy(src, off, nal, n, len);
            n += len;
        }

        private void put(int b) {
            if (n == nal.length) nal = Arrays.copyOf(nal, n * 2);
            nal[n++] = (byte) b;
        }

        @Override public void close() throws IOException { in.close(); }
    }

    /** All NAL units in order, each spanning its own start code through the
     * byte before the next start code (or end of stream for the last one).
     * Malformed input (no start code at all) yields an empty list rather
     * than throwing -- the caller decides what "nothing recoverable" means. */
    public static List<Nal> split(Source s) {
        List<Integer> starts = new ArrayList<>();
        int n = s.length();
        for (int i = 0; i + 2 < n; i++) {
            if (s.get(i) == 0 && s.get(i + 1) == 0 && s.get(i + 2) == 1) {
                starts.add(i);
                i += 2; // the shortest valid code is 3 bytes; a 4-byte one still
                        // matches here at its own byte 1 (00 00 01), which is fine
                        // since we only need the position, not which width it was
            }
        }
        List<Nal> out = new ArrayList<>(starts.size());
        for (int k = 0; k < starts.size(); k++) {
            int start = starts.get(k);
            int end = (k + 1 < starts.size()) ? starts.get(k + 1) : n;
            out.add(new Nal(start, end));
        }
        return out;
    }
}
