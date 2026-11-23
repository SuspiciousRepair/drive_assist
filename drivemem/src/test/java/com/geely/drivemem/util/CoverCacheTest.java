package com.geely.drivemem.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

public class CoverCacheTest {
    @Test public void aLargeCoverIsKeptSmallerInTheSameShape() {
        assertArrayEquals(new int[]{300, 300}, CoverCache.scaled(300, 300, 512));
        assertArrayEquals(new int[]{512, 512}, CoverCache.scaled(1000, 1000, 512));
        assertArrayEquals(new int[]{512, 256}, CoverCache.scaled(1000, 500, 512));
        assertArrayEquals(new int[]{256, 512}, CoverCache.scaled(500, 1000, 512));
    }

    @Test public void aRemovedCoverIsGoneAndTheOthersStay() throws Exception {
        File cache = Files.createTempDirectory("cache").toFile();
        try {
            String a = CoverCache.key("Artist", "Album A", "t1");
            String b = CoverCache.key("Artist", "Album B", "t2");
            for (String k : new String[]{a, b}) {
                File f = CoverCache.file(cache, k);
                assertTrue(f.getParentFile().isDirectory() || f.getParentFile().mkdirs());
                Files.write(f.toPath(), new byte[]{1});
            }
            assertTrue(CoverCache.remove(cache, a));
            assertFalse(CoverCache.file(cache, a).exists());
            assertTrue(CoverCache.file(cache, b).exists());
            assertFalse(CoverCache.remove(cache, a));      // nothing left to forget
            assertFalse(CoverCache.remove(cache, null));
        } finally {
            for (File f : new File(cache, "covers").listFiles()) f.delete();
            new File(cache, "covers").delete();
            cache.delete();
        }
    }

    @Test public void theFolderKeepsTheSongsHeardLast() throws Exception {
        File dir = Files.createTempDirectory("covers").toFile();
        try {
            File[] f = new File[5];
            for (int i = 0; i < f.length; i++) {
                f[i] = new File(dir, i + ".jpg");
                Files.write(f[i].toPath(), new byte[]{1});
                assertTrue(f[i].setLastModified(1_000_000L + i * 1000L));
            }
            // File 0 is old, but was just heard again.
            assertTrue(f[0].setLastModified(2_000_000L));
            FileCache.trim(dir, 3);
            assertEquals(3, dir.listFiles().length);
            assertTrue(f[0].exists());
            assertFalse(f[1].exists());
            assertFalse(f[2].exists());
            assertTrue(f[3].exists());
            assertTrue(f[4].exists());
        } finally {
            File[] left = dir.listFiles();
            if (left != null) for (File x : left) x.delete();
            dir.delete();
        }
    }

    @Test public void songsOfOneAlbumShareOneCover() {
        String a = CoverCache.key("Queen", "A Night at the Opera", "s-1");
        assertEquals(a, CoverCache.key("queen ", "a night at the opera", "s-2"));
        assertFalse(a.equals(CoverCache.key("Queen", "Jazz", "s-1")));
        assertFalse(a.equals(CoverCache.key("Other", "A Night at the Opera", "s-1")));
        // No album name: the song's own key.
        assertEquals("s-3", CoverCache.key("Queen", "", "s-3"));
        assertEquals("s-3", CoverCache.key("Queen", null, "s-3"));
    }
}
