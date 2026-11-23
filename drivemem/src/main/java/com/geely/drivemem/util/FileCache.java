package com.geely.drivemem.util;

import java.io.File;

/** A folder that keeps only its newest files (by last change). A cache hit
 * sets the file's time to now, so songs heard often stay. */
final class FileCache {
    private FileCache() {}

    /** Keeps the newest max files in dir. */
    static void trim(File dir, int max) {
        File[] all = dir.listFiles();
        if (all == null || all.length <= max) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = max; i < all.length; i++) //noinspection ResultOfMethodCallIgnored
            all[i].delete();
    }
}
