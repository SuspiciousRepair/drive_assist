package com.geely.drivemem.util;

import java.util.ArrayList;
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
