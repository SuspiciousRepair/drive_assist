package com.geely.modehelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Dependency-free test runner for {@link ApkRewrite}. */
public final class ApkRewriteTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static byte[] fill(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 31 + seed * 7 + (i >> 5));
        return b;
    }

    static void stored(ZipOutputStream z, String name, byte[] data) throws Exception {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        CRC32 c = new CRC32();
        c.update(data);
        e.setCrc(c.getValue());
        z.putNextEntry(e);
        z.write(data);
        z.closeEntry();
    }

    static void deflated(ZipOutputStream z, String name, byte[] data) throws Exception {
        z.putNextEntry(new ZipEntry(name));          // DEFLATED, with a data descriptor
        z.write(data);
        z.closeEntry();
    }

    static byte[] read(ZipFile z, String name) throws Exception {
        try (InputStream in = z.getInputStream(z.getEntry(name))) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("apkrewrite").toFile();
        File src = new File(dir, "in.apk"), dst = new File(dir, "out.apk");
        byte[] dex = fill(5001, 1), png = fill(3000, 2), so = fill(777, 3), oldArsc = fill(999, 4);
        byte[] newArsc = fill(1234, 5);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(src))) {
            z.setComment("kept");
            deflated(z, "AndroidManifest.xml", fill(400, 6));
            stored(z, "classes.dex", dex);           // offsets here are not aligned on purpose
            deflated(z, "res/a.png", png);
            deflated(z, "resources.arsc", oldArsc);
            stored(z, "lib/arm64-v8a/libx.so", so);
        }

        check(java.util.Arrays.equals(ApkRewrite.read(src, "resources.arsc"), oldArsc), "read must give the entry");
        check(ApkRewrite.read(src, "nope") == null, "read of a missing entry must be null");

        ApkRewrite.replace(src, dst, "resources.arsc", newArsc);

        try (ZipFile in = new ZipFile(src); ZipFile out = new ZipFile(dst)) {
            check(in.size() == out.size(), "same number of entries");
            for (Enumeration<? extends ZipEntry> en = in.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                ZipEntry o = out.getEntry(e.getName());
                check(o != null, "entry kept: " + e.getName());
                if (e.getName().equals("resources.arsc")) {
                    check(java.util.Arrays.equals(read(out, e.getName()), newArsc), "resources.arsc replaced");
                    check(o.getMethod() == ZipEntry.STORED, "resources.arsc stored");
                } else {
                    check(java.util.Arrays.equals(read(in, e.getName()), read(out, e.getName())), "same data: " + e.getName());
                    check(e.getMethod() == o.getMethod(), "same method: " + e.getName());
                    check(e.getCrc() == o.getCrc(), "same CRC (the odex checks classes.dex's): " + e.getName());
                }
            }
            check("kept".equals(out.getComment()), "zip comment kept");
        }

        byte[] all = Files.readAllBytes(dst.toPath());
        check(indexOf(all, newArsc) % 4 == 0, "resources.arsc must start on 4 bytes");
        check(indexOf(all, dex) % 4 == 0, "a stored entry must start on 4 bytes");
        check(indexOf(all, so) % 4096 == 0, "a stored .so must start on 4096 bytes");

        boolean threw = false;
        try { ApkRewrite.replace(src, new File(dir, "x.apk"), "missing.bin", newArsc); } catch (java.io.IOException e) { threw = true; }
        check(threw, "replacing an entry that is not there must fail");

        for (File f : dir.listFiles()) f.delete();
        dir.delete();
    }
}
