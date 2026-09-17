package com.geely.modehelper;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/** Prunes completed, unprotected clips on one actual recording volume only. */
final class DashcamBudget {
    private DashcamBudget() { }

    static void enforce(File directory, long budgetBytes) throws IOException {
        File[] all = directory.listFiles();
        if (all == null) return;
        long used = bytes(all);
        File keep = new File(directory, "keep");
        if (DashcamStorage.directChild(directory, keep)) used += bytes(keep.listFiles());
        Arrays.sort(all, Comparator.comparingLong(File::lastModified));
        for (File clip : all) {
            if (used <= budgetBytes) return;
            if (!clip.isFile() || !clip.getName().endsWith(".mp4")
                    || !DashcamStorage.directChild(directory, clip)) continue;
            String stem = clip.getName().substring(0, clip.getName().length() - 4);
            // An event/current-clip Hold marker protects footage even before the
            // gallery has moved it into keep/. Never evict that pending hold.
            if (new File(directory, stem + ".hold").exists()) continue;
            long videoBytes = Math.max(0, clip.length());
            if (!clip.delete()) continue;
            used -= videoBytes;
            for (String suffix : new String[]{".vtt", ".jpg"}) {
                File sidecar = new File(directory, stem + suffix);
                if (!DashcamStorage.directChild(directory, sidecar)) continue;
                long sideBytes = Math.max(0, sidecar.length());
                if (sidecar.delete()) used -= sideBytes;
            }
        }
    }

    private static long bytes(File[] files) {
        long used = 0;
        if (files != null)
            for (File file : files) if (file.isFile()) used += Math.max(0, file.length());
        return used;
    }
}
