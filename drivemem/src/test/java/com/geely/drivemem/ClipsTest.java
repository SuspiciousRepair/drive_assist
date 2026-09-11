package com.geely.drivemem;

import com.geely.drivemem.util.Clips;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

public class ClipsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    // ---- mb(long) — public static, no reflection ----

    @Test public void mbZeroBytes() {
        assertEquals("0 MB", Clips.mb(0));
    }

    @Test public void mbUnderOneGb() {
        assertEquals("5 MB", Clips.mb(5L * 1024 * 1024));
    }

    @Test public void mbExactlyOneGb() {
        assertEquals("1.0 GB", Clips.mb(1024L * 1024 * 1024));
    }

    @Test public void mbJustUnderOneGbStaysInMb() {
        assertEquals("1023 MB", Clips.mb(1024L * 1024 * 1024 - 1));
    }

    // ---- name(File) — public static, no reflection ----

    @Test public void nameStripsSingleExtension() {
        assertEquals("clip", Clips.name(new File("clip.mp4")));
    }

    @Test public void nameStripsOnlyLastExtension() {
        assertEquals("clip.mp4", Clips.name(new File("clip.mp4.tmp")));
    }

    @Test public void nameWithNoExtensionIsUnchanged() {
        assertEquals("clipnoext", Clips.name(new File("clipnoext")));
    }

    @Test public void nameOfDotfileIsEmpty() {
        assertEquals("", Clips.name(new File(".hidden")));
    }

    // ---- cueCount(File) — package-private static, via reflection ----

    private static int cueCount(File vtt) throws Exception {
        Method m = Clips.class.getDeclaredMethod("cueCount", File.class);
        m.setAccessible(true);
        return (int) m.invoke(null, vtt);
    }

    @Test public void cueCountOfMissingFileIsMinusOne() throws Exception {
        assertEquals(-1, cueCount(new File(tmp.getRoot(), "missing.vtt")));
    }

    @Test public void cueCountCountsArrowLines() throws Exception {
        File vtt = tmp.newFile("clip.vtt");
        try (FileWriter w = new FileWriter(vtt)) {
            w.write("WEBVTT\n\n");
            w.write("1\n00:00:00.000 --> 00:00:02.000\nfront\n\n");
            w.write("2\n00:00:02.000 --> 00:00:04.000\nrear\n\n");
            w.write("3\n00:00:04.000 --> 00:00:06.000\nleft\n\n");
        }
        assertEquals(3, cueCount(vtt));
    }

    @Test public void cueCountOfEmptyFileIsZero() throws Exception {
        File vtt = tmp.newFile("empty.vtt");
        assertEquals(0, cueCount(vtt));
    }

    // ---- parseStamp(String, long) — private static, via reflection ----

    private static long parseStamp(String stem, long fallback) throws Exception {
        Method m = Clips.class.getDeclaredMethod("parseStamp", String.class, long.class);
        m.setAccessible(true);
        return (long) m.invoke(null, stem, fallback);
    }

    @Test public void parseStampParsesWellFormedStem() throws Exception {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        long expected = fmt.parse("20260101_120000").getTime();
        assertEquals(expected, parseStamp("front_20260101_120000", -999L));
    }

    @Test public void parseStampNoUnderscoreReturnsFallback() throws Exception {
        assertEquals(-999L, parseStamp("nounderscore", -999L));
    }

    @Test public void parseStampMalformedDateReturnsFallback() throws Exception {
        assertEquals(-999L, parseStamp("front_notadate", -999L));
    }

    // ---- collect(File, boolean, List<Clip>) — private static, via reflection ----

    @SuppressWarnings("unchecked")
    private static List<Object> collect(File dir, boolean held) throws Exception {
        Method m = Clips.class.getDeclaredMethod("collect", File.class, boolean.class, List.class);
        m.setAccessible(true);
        List<Object> out = new ArrayList<>();
        m.invoke(null, dir, held, out);
        return out;
    }

    @Test public void collectPlainMp4IsDone() throws Exception {
        tmp.newFile("a.mp4");
        List<Object> out = collect(tmp.getRoot(), false);
        assertEquals(1, out.size());
        Object clip = out.get(0);
        assertEquals("DONE", clip.getClass().getField("kind").get(clip).toString());
    }

    @Test public void collectFreshTmpIsRecording() throws Exception {
        tmp.newFile("b.mp4.tmp");
        List<Object> out = collect(tmp.getRoot(), false);
        assertEquals(1, out.size());
        Object clip = out.get(0);
        assertEquals("RECORDING", clip.getClass().getField("kind").get(clip).toString());
    }

    @Test public void collectOrphanH264WithNoSiblingIsOrphan() throws Exception {
        tmp.newFile("c.h264");
        List<Object> out = collect(tmp.getRoot(), false);
        assertEquals(1, out.size());
        Object clip = out.get(0);
        assertEquals("ORPHAN", clip.getClass().getField("kind").get(clip).toString());
    }
}
