package com.geely.modehelper;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Small dependency-free test runner for {@link FragmentedMp4}. Real video
 * was also checked by hand: FFmpeg decodes it frame for frame, and the car's
 * MediaExtractor, MediaMetadataRetriever and MediaPlayer read, seek and
 * thumbnail it (2026-09-26). */
public final class FragmentedMp4Test {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static final byte[] SPS = {0x67, 0x42, 0x00, 0x29, (byte) 0x8d, (byte) 0x8d};
    static final byte[] PPS = {0x68, (byte) 0xca, 0x43, (byte) 0xc8};

    /** Writes `gops` GOPs of 25 frames, 40 ms apart; closes if asked. */
    static FragmentedMp4.Writer write(File f, int gops, boolean close) throws Exception {
        FragmentedMp4.Writer w = new FragmentedMp4.Writer(f, 1920, 800, SPS, PPS);
        Random rnd = new Random(7);
        long pts = 0;
        for (int g = 0; g < gops; g++) {
            for (int i = 0; i < 25; i++) {
                byte[] payload = new byte[200 + rnd.nextInt(800)];
                for (int k = 0; k < payload.length; k++) payload[k] = (byte) (2 + rnd.nextInt(250));
                payload[0] = (byte) (i == 0 ? 0x65 : 0x41);
                ByteBuffer b = ByteBuffer.allocate(payload.length + 7);
                b.put(new byte[] {9, 9, 9, 0, 0, 0, 1}).put(payload);
                w.sample(b, 3, payload.length + 4, pts, i == 0);
                pts += 40_000;
            }
        }
        if (close) check(w.close(f), "close/finish failed");
        return w;
    }

    /** Top-level boxes as "type:size". */
    static List<String> boxes(File f) throws Exception {
        byte[] a = Files.readAllBytes(f.toPath());
        ByteBuffer b = ByteBuffer.wrap(a);
        List<String> out = new ArrayList<>();
        int p = 0;
        while (p + 8 <= a.length) {
            int size = b.getInt(p);
            out.add(new String(a, p + 4, 4, "US-ASCII") + ":" + size);
            if (size < 8) break;
            p += size;
        }
        check(p == a.length, "boxes do not end at the file end: " + p + " of " + a.length);
        return out;
    }

    static int sidxRefs(File f) throws Exception {
        byte[] a = Files.readAllBytes(f.toPath());
        for (int i = 0; i + 4 <= a.length; i++)
            if (a[i] == 's' && a[i + 1] == 'i' && a[i + 2] == 'd' && a[i + 3] == 'x')
                return ByteBuffer.wrap(a).getShort(i + 26) & 0xFFFF;
        return -1;
    }

    static long mehdMs(File f) throws Exception {
        byte[] a = Files.readAllBytes(f.toPath());
        for (int i = 0; i + 4 <= a.length; i++)
            if (a[i] == 'm' && a[i + 1] == 'e' && a[i + 2] == 'h' && a[i + 3] == 'd')
                return ByteBuffer.wrap(a).getLong(i + 8);
        return -1;
    }

    static String types(List<String> boxes) {
        StringBuilder s = new StringBuilder();
        for (String b : boxes) s.append(b, 0, 4).append(' ');
        return s.toString().trim();
    }

    public static void main(String[] args) throws Exception {
        File d = Files.createTempDirectory("fmp4").toFile();

        // A clean close: header, index, one fragment per GOP, duration set.
        File f = new File(d, "a.mp4");
        write(f, 3, true);
        check(types(boxes(f)).equals("ftyp moov sidx free moof mdat moof mdat moof mdat"),
              "layout: " + types(boxes(f)));
        check(sidxRefs(f) == 3, "sidx refs " + sidxRefs(f));
        check(mehdMs(f) == 3000, "duration " + mehdMs(f));

        // finish() again changes nothing.
        byte[] before = Files.readAllBytes(f.toPath());
        check(FragmentedMp4.finish(f), "second finish");
        check(Arrays.equals(before, Files.readAllBytes(f.toPath())), "finish is not idempotent");

        // A writer that never closed, torn in the middle of its second
        // fragment: repaired down to the one whole fragment.
        File t = new File(d, "t.mp4");
        write(t, 3, false);                     // fragments 1 and 2 are on disk, GOP 3 is not
        List<String> raw = boxes(t);
        long firstFragmentEnd = 0;
        // ftyp moov free moof mdat: the end of the first whole fragment.
        for (String b : raw.subList(0, 5)) firstFragmentEnd += Long.parseLong(b.substring(5));
        try (RandomAccessFile r = new RandomAccessFile(t, "rw")) { r.setLength(firstFragmentEnd + 300); }
        check(FragmentedMp4.finish(t), "repair failed");
        check(t.length() == firstFragmentEnd, "torn fragment not cut: " + t.length());
        check(types(boxes(t)).equals("ftyp moov sidx free moof mdat"), "repaired layout " + types(boxes(t)));
        check(mehdMs(t) == 1000, "repaired duration " + mehdMs(t));

        // Not ours: refused and untouched.
        File x = new File(d, "x.mp4");
        byte[] junk = new byte[5000];
        new Random(1).nextBytes(junk);
        Files.write(x.toPath(), junk);
        check(!FragmentedMp4.finish(x), "foreign file accepted");
        check(Arrays.equals(junk, Files.readAllBytes(x.toPath())), "foreign file changed");

        // A header and no whole fragment: refused.
        File e = new File(d, "e.mp4");
        new FragmentedMp4.Writer(e, 1920, 800, SPS, PPS);
        check(!FragmentedMp4.finish(e), "empty file accepted");

        // SegmentFiles.repair: a dead .mp4.tmp becomes a clip with its
        // subtitles; the live segment and a fresh file are left alone.
        File dir = Files.createTempDirectory("seg").toFile();
        long now = System.currentTimeMillis();
        write(new File(dir, "dead.mp4.tmp"), 2, false);
        new File(dir, "dead.mp4.tmp").setLastModified(now - SegmentFiles.REPAIR_COLD_MS - 1000);
        Files.write(new File(dir, "dead.vtt.tmp").toPath(), "WEBVTT\n".getBytes());
        write(new File(dir, "live.mp4.tmp"), 2, false);
        new File(dir, "live.mp4.tmp").setLastModified(now - SegmentFiles.REPAIR_COLD_MS - 1000);
        write(new File(dir, "fresh.mp4.tmp"), 2, false);
        List<String> fixed = SegmentFiles.repair(dir, now, "live");
        check(fixed.equals(Arrays.asList("dead")), "repaired " + fixed);
        check(new File(dir, "dead.mp4").exists() && new File(dir, "dead.vtt").exists(), "dead not renamed");
        check(new File(dir, "live.mp4.tmp").exists() && new File(dir, "fresh.mp4.tmp").exists(), "live/fresh touched");

        // startCode: the stride search agrees with a byte-by-byte one.
        Random rnd = new Random(3);
        for (int round = 0; round < 2000; round++) {
            byte[] s = new byte[rnd.nextInt(40)];
            for (int i = 0; i < s.length; i++) s[i] = (byte) (rnd.nextInt(3) == 0 ? 0 : rnd.nextInt(3));
            int from = s.length == 0 ? 0 : rnd.nextInt(s.length);
            int naive = s.length;
            for (int i = from; i + 2 < s.length; i++) if (s[i] == 0 && s[i + 1] == 0 && s[i + 2] == 1) { naive = i; break; }
            check(FragmentedMp4.startCode(s, from, s.length) == naive, "startCode " + Arrays.toString(s) + " from " + from);
        }

        System.out.println("FragmentedMp4Test OK");
    }
}
