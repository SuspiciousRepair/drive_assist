package com.geely.modehelper;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Copies an APK with one entry replaced. Used by {@link OemText} to put a
 * patched resources.arsc into a copy of a car app.
 *
 * Every other entry is copied byte for byte, still compressed: no
 * re-compression, so a 100 MB app copies in seconds, and classes.dex keeps
 * its CRC, which the app's existing odex/vdex checks.
 *
 * The new entry is STORED (not compressed). Every STORED entry starts on a
 * 4-byte boundary (a .so on a 4096-byte one), padded in the local extra
 * field like zipalign does, so Android can map it.
 *
 * The APK signing block is not copied, and the v1 signature no longer
 * matches resources.arsc. That is fine for this use only: the copy is bind
 * mounted after boot, over a /system app the package manager already
 * scanned, and nothing verifies it again. It could never be installed.
 *
 * Pure Java, no Android, so tools/verify.sh can test it on the desktop. */
final class ApkRewrite {
    private ApkRewrite() {}

    static final int LOCAL_SIG = 0x04034b50, CENTRAL_SIG = 0x02014b50, END_SIG = 0x06054b50;
    static final int STORED = 0, FLAG_DESCRIPTOR = 0x08;

    /** Reads entry {@code name}, uncompressed, or null when it is absent. */
    static byte[] read(File apk, String name) throws IOException {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(apk)) {
            java.util.zip.ZipEntry e = z.getEntry(name);
            if (e == null) return null;
            try (java.io.InputStream in = z.getInputStream(e)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, e.getSize()));
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toByteArray();
            }
        }
    }

    /** Writes {@code dst}: {@code src} with entry {@code name}'s data replaced by {@code data}. */
    static void replace(File src, File dst, String name, byte[] data) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(src, "r");
             Counting out = new Counting(new BufferedOutputStream(new FileOutputStream(dst), 1 << 16))) {
            long len = in.length();
            // End of central directory: the last 22 bytes, plus a comment of up to 64 KB.
            int tail = (int) Math.min(len, 22 + 65535);
            byte[] t = new byte[tail];
            in.seek(len - tail);
            in.readFully(t);
            int eocd = -1;
            for (int i = tail - 22; i >= 0; i--) if (ArscPatch.u32(t, i) == END_SIG) { eocd = i; break; }
            if (eocd < 0) throw new IOException("no end of central directory");
            int count = ArscPatch.u16(t, eocd + 10);
            long cdSize = ArscPatch.u32(t, eocd + 12) & 0xffffffffL;
            long cdOff = ArscPatch.u32(t, eocd + 16) & 0xffffffffL;
            if (count == 0xffff || cdOff == 0xffffffffL) throw new IOException("zip64 is not supported");
            byte[] cd = new byte[(int) cdSize];
            in.seek(cdOff);
            in.readFully(cd);

            ByteArrayOutputStream newCd = new ByteArrayOutputStream(cd.length + 1024);
            boolean found = false;
            CRC32 crc = new CRC32();
            crc.update(data, 0, data.length);
            byte[] buf = new byte[1 << 16];
            int p = 0;
            for (int i = 0; i < count; i++) {
                if (ArscPatch.u32(cd, p) != CENTRAL_SIG) throw new IOException("bad central directory");
                int nlen = ArscPatch.u16(cd, p + 28), xlen = ArscPatch.u16(cd, p + 30), clen = ArscPatch.u16(cd, p + 32);
                int centralLen = 46 + nlen + xlen + clen;
                byte[] c = new byte[centralLen];
                System.arraycopy(cd, p, c, 0, centralLen);
                p += centralLen;
                String ename = new String(c, 46, nlen, StandardCharsets.UTF_8);
                long localOff = ArscPatch.u32(c, 42) & 0xffffffffL;

                byte[] lh = new byte[30];
                in.seek(localOff);
                in.readFully(lh);
                if (ArscPatch.u32(lh, 0) != LOCAL_SIG) throw new IOException("bad local header: " + ename);
                long dataAt = localOff + 30 + ArscPatch.u16(lh, 26) + ArscPatch.u16(lh, 28);

                boolean mine = ename.equals(name);
                if (mine) {
                    found = true;
                    ArscPatch.p16(c, 10, STORED);
                    ArscPatch.p32(c, 16, (int) crc.getValue());
                    ArscPatch.p32(c, 20, data.length);
                    ArscPatch.p32(c, 24, data.length);
                }
                int flags = ArscPatch.u16(c, 8) & ~FLAG_DESCRIPTOR;
                ArscPatch.p16(c, 8, flags);
                int method = ArscPatch.u16(c, 10);
                long csize = ArscPatch.u32(c, 20) & 0xffffffffL;

                // New local header from the central record: sizes always in
                // it, so no data descriptor is needed after the data.
                long here = out.n;
                int pad = 0;
                if (method == STORED) {
                    int align = ename.endsWith(".so") ? 4096 : 4;
                    pad = (int) ((align - (here + 30 + nlen) % align) % align);
                }
                byte[] l = new byte[30 + nlen + pad];
                ArscPatch.p32(l, 0, LOCAL_SIG);
                System.arraycopy(c, 6, l, 4, 2);                 // version needed
                ArscPatch.p16(l, 6, flags);
                System.arraycopy(c, 10, l, 8, 18);               // method, time, date, crc, sizes
                ArscPatch.p16(l, 26, nlen);
                ArscPatch.p16(l, 28, pad);
                System.arraycopy(c, 46, l, 30, nlen);
                out.write(l);
                if (mine) {
                    out.write(data);
                } else {
                    in.seek(dataAt);
                    for (long left = csize; left > 0; ) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new IOException("short entry: " + ename);
                        out.write(buf, 0, n);
                        left -= n;
                    }
                }
                ArscPatch.p32(c, 42, (int) here);
                newCd.write(c, 0, c.length);
            }
            if (!found) throw new IOException("no " + name + " in " + src);
            long newCdOff = out.n;
            byte[] ncd = newCd.toByteArray();
            out.write(ncd);
            byte[] end = new byte[tail - eocd];
            System.arraycopy(t, eocd, end, 0, end.length);
            ArscPatch.p32(end, 12, ncd.length);
            ArscPatch.p32(end, 16, (int) newCdOff);
            out.write(end);
        }
    }

    /** Counts the bytes written, for the offsets in the central directory. */
    static final class Counting extends OutputStream {
        private final OutputStream o;
        long n;
        Counting(OutputStream o) { this.o = o; }
        @Override public void write(int b) throws IOException { o.write(b); n++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { o.write(b, off, len); n += len; }
        @Override public void flush() throws IOException { o.flush(); }
        @Override public void close() throws IOException { o.close(); }
    }
}
