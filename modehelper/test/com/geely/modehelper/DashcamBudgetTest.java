package com.geely.modehelper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Protected and unfinished footage must survive budget pressure on any volume. */
public final class DashcamBudgetTest {
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("dashcam-budget-test");
        try {
            File usb = Files.createDirectory(temporary.resolve("usb")).toFile();
            File primary = Files.createDirectory(temporary.resolve("internal")).toFile();
            File internalClip = file(primary, "internal.mp4", 100, 1);
            File old = file(usb, "old.mp4", 100, 1);
            File subtitle = file(usb, "old.vtt", 5, 1);
            File thumb = file(usb, "old.jpg", 5, 1);
            File held = file(usb, "event.mp4", 100, 2);
            File marker = file(usb, "event.hold", 0, 2);
            File keep = Files.createDirectory(usb.toPath().resolve("keep")).toFile();
            File kept = file(keep, "saved.mp4", 200, 0);
            File live = file(usb, "current.mp4.tmp", 20, 3);
            File recovery = file(usb, "current.h264", 20, 3);
            DashcamBudget.enforce(usb, 500);
            check(old.exists(), "under-budget archive unchanged");
            DashcamBudget.enforce(usb, 350);
            check(!old.exists() && !subtitle.exists() && !thumb.exists(), "old completed clip and sidecars evicted");
            check(internalClip.exists(), "enforce only actual segment volume");
            check(held.exists() && marker.exists() && kept.exists(), "pending and completed holds preserved");
            check(live.exists() && recovery.exists(), "live and recoverable footage preserved");
            DashcamBudget.enforce(usb, 0);
            check(held.exists() && marker.exists() && kept.exists(), "protected footage survives even impossible budget");

            File order = Files.createDirectory(temporary.resolve("order")).toFile();
            File first = file(order, "first.mp4", 40, 1);
            File second = file(order, "second.mp4", 40, 2);
            File newest = file(order, "newest.mp4", 40, 3);
            DashcamBudget.enforce(order, 80);
            check(!first.exists() && second.exists() && newest.exists(), "oldest footage evicted first");
            Files.createSymbolicLink(order.toPath().resolve("outside.mp4"), internalClip.toPath());
            DashcamBudget.enforce(order, 0);
            check(internalClip.exists() && new File(order, "outside.mp4").exists(), "symlink target never pruned");

            // Continuous recording: each newly completed clip forces the oldest
            // ordinary history out once the ring is full. The same held footage
            // must remain protected through many rotations, not just one prune.
            File loop = Files.createDirectory(temporary.resolve("loop")).toFile();
            File loopKeep = Files.createDirectory(loop.toPath().resolve("keep")).toFile();
            File fixedHeld = file(loopKeep, "saved.mp4", 80, 0);
            File pendingHeld = file(loop, "event.mp4", 40, 0);
            File pendingMarker = file(loop, "event.hold", 0, 0);
            for (int rotation = 0; rotation < 100; rotation++) {
                String stem = "loop_" + rotation;
                File latest = file(loop, stem + ".mp4", 40, rotation + 10);
                file(loop, stem + ".vtt", 4, rotation + 10);
                file(loop, stem + ".jpg", 6, rotation + 10);
                DashcamBudget.enforce(loop, 370); // 120 held bytes + five 50-byte clips.
                check(latest.exists(), "latest completed footage retained throughout loop");
                check(fixedHeld.exists() && pendingHeld.exists() && pendingMarker.exists(),
                    "held footage retained throughout loop");
                if (rotation >= 5) {
                    String evicted = "loop_" + (rotation - 5);
                    check(!new File(loop, evicted + ".mp4").exists()
                            && !new File(loop, evicted + ".vtt").exists()
                            && !new File(loop, evicted + ".jpg").exists(),
                        "oldest video and sidecars replaced as loop fills");
                }
                try (java.util.stream.Stream<Path> paths = Files.walk(loop.toPath())) {
                    long used = paths.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
                    check(used <= 370, "continuous loop remains within configured budget");
                }
            }
            try (java.util.stream.Stream<Path> files = Files.list(loop.toPath())) {
                check(files.filter(p -> p.getFileName().toString().startsWith("loop_")
                        && p.getFileName().toString().endsWith(".mp4")).count() == 5,
                    "ordinary history stays bounded after one hundred rotations");
            }
            for (int rotation = 95; rotation < 100; rotation++)
                check(new File(loop, "loop_" + rotation + ".mp4").exists(),
                    "the newest five completed clips remain");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.delete(path);
            }
        }
    }

    private static File file(File directory, String name, int bytes, long order) throws Exception {
        File result = new File(directory, name);
        Files.write(result.toPath(), new byte[bytes]);
        result.setLastModified(1_000L + order * 1_000L);
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
