package com.geely.modehelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** A fragmented-MP4 writer for one dashcam segment, and the step that
 * finishes such a file. Plain Java, so it is tested on a desktop JVM.
 *
 * WHY. MediaMuxer writes the MP4 index (moov) only when a segment closes,
 * so a crash left an unplayable file, and the recorder had to write every
 * frame twice (the .mp4 and a raw .h264 to recover from): ~14 GB of flash
 * writes per hour of driving. Here the moov goes FIRST, describing no
 * samples, and the video follows as one moof+mdat fragment per key frame
 * (one a second), each forced to storage as it is written. At any moment the
 * file is a valid video up to its last fragment: one write, no sidecar,
 * nothing to recover.
 *
 * SEEKING. Android 9's MPEG4Extractor seeks a fragmented file only through
 * a segment index (sidx) that it meets before the first fragment. The
 * writer therefore reserves room for one right after the moov (a `free` box
 * carrying INDEX_MAGIC), and finish() fills it in by scanning the fragment
 * headers: a few hundred small reads, never a rewrite of the video. The same
 * scan repairs a file whose writer never closed: it cuts a torn last
 * fragment and writes the index, and the result is an ordinary clip.
 *
 * Layout: ftyp | moov (mvhd, trak(avc1/avcC, empty tables), mvex(mehd, trex))
 *         | free (reserved index) | moof mdat | moof mdat | ... */
final class FragmentedMp4 {
    static final int TIMESCALE = 90_000;          // track clock; movie clock is ms
    static final int MAX_FRAGMENTS = 1200;        // 20 min at one fragment a second
    static final byte[] INDEX_MAGIC = "DriveAssistIndex1".getBytes(StandardCharsets.US_ASCII);
    private static final int SIDX_HEADER = 32, SIDX_REF = 12;
    // Room for a full sidx AND the smallest `free` that can follow it.
    static final int RESERVED = SIDX_HEADER + SIDX_REF * MAX_FRAGMENTS + 8 + INDEX_MAGIC.length;
    // A GOP longer than this is flushed early; the encoder asks for 1 s.
    private static final int FLUSH_BYTES = 12 << 20;

    private FragmentedMp4() { }

    // ------------------------------------------------------------ writer

    static final class Writer {
        private final FileChannel ch;
        private final Buf box = new Buf();
        private byte[] data = new byte[4 << 20];
        private int dataLen;
        private byte[] scratch = new byte[1 << 20];
        private int[] sizes = new int[64];
        private long[] pts = new long[64];
        private boolean[] keys = new boolean[64];
        private int n, seq;
        private long lastDelta = TIMESCALE / 25;

        /** sps/pps: the parameter sets WITHOUT a start code. */
        Writer(File f, int width, int height, byte[] sps, byte[] pps) throws IOException {
            ch = new FileOutputStream(f).getChannel();
            box.reset();
            int p = box.start("ftyp");
            box.fourcc("isom"); box.u32(0x200);
            for (String c : new String[] {"isom", "iso6", "avc1", "mp41"}) box.fourcc(c);
            box.end(p);
            moov(box, width, height, sps, pps);
            p = box.start("free");
            box.bytes(INDEX_MAGIC, 0, INDEX_MAGIC.length);
            box.zeros(RESERVED - 8 - INDEX_MAGIC.length);
            box.end(p);
            write(box);
            ch.force(false);
        }

        /** One access unit, Annex-B as MediaCodec produces it, at `offset` of
         * `buf` (whose position and limit are left alone). pts in µs, strictly
         * increasing. A key frame closes the previous fragment first. */
        void sample(ByteBuffer buf, int offset, int size, long ptsUs, boolean key) throws IOException {
            long t = ptsUs * 9 / 100;
            if (n > 0 && (key || dataLen > FLUSH_BYTES)) flush(t);
            if (scratch.length < size) scratch = new byte[Math.max(size, scratch.length * 2)];
            ByteBuffer d = buf.duplicate();
            d.limit(offset + size);
            d.position(offset);
            d.get(scratch, 0, size);
            int before = dataLen;
            appendAvcc(scratch, size);
            if (n == sizes.length) grow();
            sizes[n] = dataLen - before;
            pts[n] = t;
            keys[n] = key;
            n++;
        }

        /** Writes the last fragment, then the index. Returns what finish()
         * returns. The channel is closed either way. */
        boolean close(File f) throws IOException {
            try {
                if (n > 0) flush(pts[n - 1] + lastDelta);
                ch.force(false);
            } finally {
                ch.close();
            }
            return finish(f);
        }

        private void flush(long nextPts) throws IOException {
            box.reset();
            int moof = box.start("moof");
            int mfhd = box.start("mfhd"); box.u32(0); box.u32(++seq); box.end(mfhd);
            int traf = box.start("traf");
            int tfhd = box.start("tfhd"); box.u32(0x020000); box.u32(1); box.end(tfhd);
            int tfdt = box.start("tfdt"); box.u32(0x01000000); box.u64(pts[0]); box.end(tfdt);
            int trun = box.start("trun");
            box.u32(0x000701);                  // data offset, duration, size, flags
            box.u32(n);
            int dataOffsetAt = box.len;
            box.u32(0);
            for (int i = 0; i < n; i++) {
                long next = i + 1 < n ? pts[i + 1] : nextPts;
                long dur = Math.max(1, next - pts[i]);
                lastDelta = dur;
                box.u32((int) dur);
                box.u32(sizes[i]);
                box.u32(keys[i] ? 0x02000000 : 0x01010000);
            }
            box.end(trun);
            box.end(traf);
            box.end(moof);
            box.putU32(dataOffsetAt, box.len + 8);
            box.u32(8 + dataLen);
            box.fourcc("mdat");
            write(box);
            ByteBuffer payload = ByteBuffer.wrap(data, 0, dataLen);
            while (payload.hasRemaining()) ch.write(payload);
            ch.force(false);
            dataLen = 0;
            n = 0;
        }

        // Annex-B (start codes) to AVCC (a 4-byte length before each NAL
        // unit), which is what an avc1 sample holds.
        private void appendAvcc(byte[] s, int len) {
            int nalStart = -1, from = 0;
            for (;;) {
                int sc = startCode(s, from, len);
                if (nalStart >= 0) {
                    int end = sc;
                    while (end > nalStart && s[end - 1] == 0) end--;
                    if (end > nalStart) nal(s, nalStart, end - nalStart);
                }
                if (sc >= len) break;
                nalStart = sc + 3;
                from = nalStart;
            }
            if (nalStart < 0 && len > 0) nal(s, 0, len);
        }

        private void nal(byte[] s, int off, int len) {
            if (dataLen + 4 + len > data.length) {
                byte[] bigger = new byte[Math.max(data.length * 2, dataLen + 4 + len)];
                System.arraycopy(data, 0, bigger, 0, dataLen);
                data = bigger;
            }
            data[dataLen] = (byte) (len >>> 24);
            data[dataLen + 1] = (byte) (len >>> 16);
            data[dataLen + 2] = (byte) (len >>> 8);
            data[dataLen + 3] = (byte) len;
            System.arraycopy(s, off, data, dataLen + 4, len);
            dataLen += 4 + len;
        }

        private void grow() {
            int m = sizes.length * 2;
            sizes = java.util.Arrays.copyOf(sizes, m);
            pts = java.util.Arrays.copyOf(pts, m);
            keys = java.util.Arrays.copyOf(keys, m);
        }

        private void write(Buf b) throws IOException {
            ByteBuffer w = ByteBuffer.wrap(b.a, 0, b.len);
            while (w.hasRemaining()) ch.write(w);
        }
    }

    /** Index of the first `00 00 01` at or after `from`, or `len`. Looks at
     * every second byte until it meets a zero: a start code holds two zeros
     * in a row, so none can be skipped. */
    static int startCode(byte[] s, int from, int len) {
        int j = from + 1;
        while (j < len) {
            if (s[j] != 0) { j += 2; continue; }
            if (j - 1 >= from && s[j - 1] == 0 && j + 1 < len && s[j + 1] == 1) return j - 1;
            if (j + 2 < len && s[j + 1] == 0 && s[j + 2] == 1) return j;
            j++;
        }
        return len;
    }

    private static void moov(Buf b, int w, int h, byte[] sps, byte[] pps) {
        int moov = b.start("moov");
        int mvhd = b.start("mvhd");
        b.u32(0); b.u32(0); b.u32(0); b.u32(1000); b.u32(0);
        b.u32(0x00010000); b.u16(0x0100); b.zeros(10); matrix(b); b.zeros(24); b.u32(2);
        b.end(mvhd);
        int trak = b.start("trak");
        int tkhd = b.start("tkhd");
        b.u32(0x000003); b.u32(0); b.u32(0); b.u32(1); b.u32(0); b.u32(0); b.zeros(8);
        b.u16(0); b.u16(0); b.u16(0); b.u16(0); matrix(b); b.u32(w << 16); b.u32(h << 16);
        b.end(tkhd);
        int mdia = b.start("mdia");
        int mdhd = b.start("mdhd");
        b.u32(0); b.u32(0); b.u32(0); b.u32(TIMESCALE); b.u32(0); b.u16(0x55C4); b.u16(0);
        b.end(mdhd);
        int hdlr = b.start("hdlr");
        b.u32(0); b.u32(0); b.fourcc("vide"); b.zeros(12);
        byte[] name = "VideoHandler\0".getBytes(StandardCharsets.US_ASCII);
        b.bytes(name, 0, name.length);
        b.end(hdlr);
        int minf = b.start("minf");
        int vmhd = b.start("vmhd"); b.u32(1); b.zeros(8); b.end(vmhd);
        int dinf = b.start("dinf");
        int dref = b.start("dref"); b.u32(0); b.u32(1);
        int url = b.start("url "); b.u32(1); b.end(url);
        b.end(dref);
        b.end(dinf);
        int stbl = b.start("stbl");
        int stsd = b.start("stsd"); b.u32(0); b.u32(1);
        int avc1 = b.start("avc1");
        b.zeros(6); b.u16(1); b.zeros(16); b.u16(w); b.u16(h);
        b.u32(0x00480000); b.u32(0x00480000); b.u32(0); b.u16(1); b.zeros(32);
        b.u16(0x0018); b.u16(0xFFFF);
        int avcC = b.start("avcC");
        b.u8(1); b.u8(sps[1]); b.u8(sps[2]); b.u8(sps[3]); b.u8(0xFF); b.u8(0xE1);
        b.u16(sps.length); b.bytes(sps, 0, sps.length);
        b.u8(1); b.u16(pps.length); b.bytes(pps, 0, pps.length);
        b.end(avcC);
        b.end(avc1);
        b.end(stsd);
        for (String t : new String[] {"stts", "stsc", "stco"}) { int x = b.start(t); b.u32(0); b.u32(0); b.end(x); }
        int stsz = b.start("stsz"); b.u32(0); b.u32(0); b.u32(0); b.end(stsz);
        b.end(stbl);
        b.end(minf);
        b.end(mdia);
        b.end(trak);
        int mvex = b.start("mvex");
        int mehd = b.start("mehd"); b.u32(0x01000000); b.u64(0); b.end(mehd);
        int trex = b.start("trex"); b.u32(0); b.u32(1); b.u32(1); b.u32(0); b.u32(0); b.u32(0); b.end(trex);
        b.end(mvex);
        b.end(moov);
    }

    private static void matrix(Buf b) {
        int[] m = {0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000};
        for (int v : m) b.u32(v);
    }

    // ------------------------------------------------------------ finish

    /** Writes the segment index and the total duration into a file this
     * class wrote, whether or not its writer closed. A torn last fragment
     * (the process died mid-write) is cut off first. Idempotent.
     *
     * Returns false, touching nothing, when the file is not one of ours or
     * holds no complete fragment. */
    static boolean finish(File f) throws IOException {
        try (FileChannel ch = FileChannel.open(f.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long size = ch.size();
            long[] h = header(ch, 0, size);
            if (h == null || h[1] != type("ftyp")) return false;
            long pos = h[0];
            h = header(ch, pos, size);
            if (h == null || h[1] != type("moov")) return false;
            long moovPos = pos, moovSize = h[0];
            pos += h[0];
            long idxPos = pos;
            if (!reserved(ch, idxPos, size)) return false;
            pos += RESERVED;

            List<long[]> refs = new ArrayList<>();   // {size, duration, startsWithKey}
            long earliest = -1, total = 0;
            while (pos + 8 <= size) {
                h = header(ch, pos, size);
                if (h == null || h[1] != type("moof") || pos + h[0] > size) break;
                long[] m = moof(read(ch, pos, (int) h[0]));
                long mdatPos = pos + h[0];
                long[] d = header(ch, mdatPos, size);
                if (m == null || d == null || d[1] != type("mdat") || mdatPos + d[0] > size) break;
                refs.add(new long[] {h[0] + d[0], m[1], m[2]});
                if (earliest < 0) earliest = m[0];
                total += m[1];
                pos = mdatPos + d[0];
            }
            if (refs.isEmpty()) return false;
            if (pos < size) ch.truncate(pos);

            Buf b = new Buf();
            if (refs.size() <= MAX_FRAGMENTS) {
                int rest = RESERVED - SIDX_HEADER - SIDX_REF * refs.size();
                int sidx = b.start("sidx");
                b.u32(0); b.u32(1); b.u32(TIMESCALE); b.u32((int) earliest); b.u32(rest);
                b.u16(0); b.u16(refs.size());
                for (long[] r : refs) {
                    b.u32((int) r[0]);
                    b.u32((int) r[1]);
                    b.u32(r[2] == 1 ? 0x90000000 : 0);   // starts_with_SAP, SAP type 1
                }
                b.end(sidx);
                int free = b.start("free");
                b.bytes(INDEX_MAGIC, 0, INDEX_MAGIC.length);
                b.zeros(rest - 8 - INDEX_MAGIC.length);
                b.end(free);
                ch.write(ByteBuffer.wrap(b.a, 0, b.len), idxPos);
            }

            ByteBuffer moov = read(ch, moovPos, (int) moovSize);
            int at = indexOf(moov, type("mehd"));
            if (at >= 0) {
                ByteBuffer v = ByteBuffer.allocate(8);
                v.putLong(0, total * 1000 / TIMESCALE);
                ch.write(v, moovPos + at + 12);   // after size, type, version/flags
            }
            ch.force(true);
            return true;
        }
    }

    /** The reserved index area: either still the `free` box the writer left,
     * or a sidx this method wrote followed by a `free` of the rest. */
    private static boolean reserved(FileChannel ch, long pos, long size) throws IOException {
        long[] h = header(ch, pos, size);
        if (h == null) return false;
        long rest = pos;
        if (h[1] == type("sidx")) {
            rest = pos + h[0];
            h = header(ch, rest, size);
            if (h == null) return false;
        }
        if (h[1] != type("free") || rest + h[0] != pos + RESERVED || h[0] < 8 + INDEX_MAGIC.length) return false;
        ByteBuffer m = read(ch, rest + 8, INDEX_MAGIC.length);
        for (byte c : INDEX_MAGIC) if (m.get() != c) return false;
        return true;
    }

    /** {baseMediaDecodeTime, sum of sample durations, first sample is key 1/0}. */
    private static long[] moof(ByteBuffer m) {
        int end = m.limit();
        for (int p = 8; p + 8 <= end; ) {
            int sz = m.getInt(p);
            if (sz < 8 || p + sz > end) return null;
            if (m.getInt(p + 4) == type("traf")) return traf(m, p + 8, p + sz);
            p += sz;
        }
        return null;
    }

    private static long[] traf(ByteBuffer m, int p, int end) {
        long base = 0, dur = 0, key = 0;
        boolean sawTrun = false;
        while (p + 8 <= end) {
            int sz = m.getInt(p), t = m.getInt(p + 4);
            if (sz < 8 || p + sz > end) return null;
            if (t == type("tfdt")) {
                base = (m.get(p + 8) == 1) ? m.getLong(p + 12) : (m.getInt(p + 12) & 0xFFFFFFFFL);
            } else if (t == type("trun")) {
                int flags = m.getInt(p + 8) & 0xFFFFFF, count = m.getInt(p + 12), q = p + 16;
                if ((flags & 0x1) != 0) q += 4;
                int firstFlags = -1;
                if ((flags & 0x4) != 0) { firstFlags = m.getInt(q); q += 4; }
                for (int i = 0; i < count; i++) {
                    if ((flags & 0x100) != 0) { dur += m.getInt(q) & 0xFFFFFFFFL; q += 4; }
                    if ((flags & 0x200) != 0) q += 4;
                    if ((flags & 0x400) != 0) { if (i == 0 && firstFlags < 0) firstFlags = m.getInt(q); q += 4; }
                    if ((flags & 0x800) != 0) q += 4;
                }
                key = (firstFlags >= 0 && (firstFlags & 0x00010000) == 0) ? 1 : 0;
                sawTrun = true;
            }
            p += sz;
        }
        return sawTrun ? new long[] {base, dur, key} : null;
    }

    private static long[] header(FileChannel ch, long pos, long size) throws IOException {
        if (pos + 8 > size) return null;
        ByteBuffer b = read(ch, pos, 8);
        long sz = b.getInt(0) & 0xFFFFFFFFL;
        return sz < 8 ? null : new long[] {sz, b.getInt(4)};
    }

    private static ByteBuffer read(FileChannel ch, long pos, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(len);
        while (b.hasRemaining()) if (ch.read(b, pos + b.position()) < 0) break;
        b.flip();
        return b;
    }

    private static int indexOf(ByteBuffer b, int fourcc) {
        for (int i = 0; i + 4 <= b.limit(); i++) if (b.getInt(i) == fourcc) return i - 4;
        return -1;
    }

    static int type(String s) {
        return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
    }

    /** A growable big-endian byte buffer with nested box sizes. */
    static final class Buf {
        byte[] a = new byte[64 * 1024];
        int len;

        void reset() { len = 0; }
        int start(String type) { int p = len; u32(0); fourcc(type); return p; }
        void end(int p) { putU32(p, len - p); }
        void putU32(int at, int v) {
            a[at] = (byte) (v >>> 24); a[at + 1] = (byte) (v >>> 16);
            a[at + 2] = (byte) (v >>> 8); a[at + 3] = (byte) v;
        }
        private void room(int k) { if (len + k > a.length) a = java.util.Arrays.copyOf(a, Math.max(a.length * 2, len + k)); }
        void u8(int v) { room(1); a[len++] = (byte) v; }
        void u16(int v) { room(2); a[len++] = (byte) (v >>> 8); a[len++] = (byte) v; }
        void u32(int v) { room(4); putU32(len, v); len += 4; }
        void u64(long v) { u32((int) (v >>> 32)); u32((int) v); }
        void fourcc(String s) { u32(type(s)); }
        void zeros(int k) { room(k); java.util.Arrays.fill(a, len, len + k, (byte) 0); len += k; }
        void bytes(byte[] s, int off, int k) { room(k); System.arraycopy(s, off, a, len, k); len += k; }
    }
}
