package com.geely.modehelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Host-side filesystem checks; mounting is supplied separately from path checks. */
public final class DashcamStorageTest {
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("dashcam-storage-test");
        try {
            File storage = Files.createDirectory(temporary.resolve("storage")).toFile();
            File internal = temporary.resolve("internal/dashcam").toFile();
            check(DashcamStorage.resolve("internal", internal, storage, v -> false).equals(internal),
                "default internal storage");
            check(internal.isDirectory(), "internal directory created");

            File missing = new File(storage, "missing-usb");
            check(DashcamStorage.resolve("missing-usb", internal, storage, v -> {
                throw new AssertionError("missing volume must not reach mounted probe");
            }).equals(internal), "missing USB falls back");
            check(!missing.exists(), "must not create phantom volume");

            File removed = Files.createDirectory(storage.toPath().resolve("removed-usb")).toFile();
            check(DashcamStorage.resolve("removed-usb", internal, storage, v -> {
                check(removed.delete(), "remove volume during mount check");
                return true;
            }).equals(internal), "USB disappearing during setup falls back");
            check(!removed.exists(), "must not recreate a volume removed during setup");

            File volume = Files.createDirectory(storage.toPath().resolve("ABCD-1234")).toFile();
            File appDir = new File(volume, DashcamStorage.APP_DIRECTORY);
            check(DashcamStorage.resolve("ABCD-1234", internal, storage, v -> false).equals(internal),
                "existing but unmounted/read-only volume falls back");
            check(!appDir.exists(), "must not write onto an unmounted directory");
            check(DashcamStorage.resolve("ABCD-1234", internal, storage, v -> true).equals(appDir),
                "mounted removable volume selected");
            check(appDir.isDirectory(), "only app-specific recording directory created");

            File outside = Files.createDirectory(temporary.resolve("outside")).toFile();
            Files.createSymbolicLink(storage.toPath().resolve("escape-volume"), outside.toPath());
            check(DashcamStorage.resolve("escape-volume", internal, storage, v -> true).equals(internal),
                "volume symlink cannot escape storage root");
            check(!new File(outside, "Android").exists(), "escaped volume remains untouched");

            File redirected = Files.createDirectory(storage.toPath().resolve("redirected-usb")).toFile();
            Files.createSymbolicLink(redirected.toPath().resolve("Android"), outside.toPath());
            check(DashcamStorage.resolve("redirected-usb", internal, storage, v -> true).equals(internal),
                "app directory symlink cannot escape selected volume");
            check(!new File(outside, "data").exists(), "escaped app path remains untouched");

            File blocked = Files.createDirectory(storage.toPath().resolve("blocked-usb")).toFile();
            Files.createFile(blocked.toPath().resolve("Android"));
            check(DashcamStorage.resolve("blocked-usb", internal, storage, v -> true).equals(internal),
                "unwritable directory structure falls back");
            check(DashcamStorage.resolve("ABCD-1234", internal, storage, v -> {
                throw new SecurityException("denied");
            }).equals(internal), "permission denied falls back");

            int[] probes = {0};
            check(DashcamStorage.resolve("ABCD-1234", internal, storage, v -> ++probes[0] == 1)
                .equals(internal), "unmounted during setup falls back");
            for (String value : new String[]{"../outside", "/tmp", "emulated", "self", "primary"})
                check(DashcamStorage.resolve(value, internal, storage, v -> {
                    throw new AssertionError("invalid ID must never reach mounted probe");
                }).equals(internal), "invalid path rejected");

            File unavailableInternal = Files.createFile(temporary.resolve("internal-file")).toFile();
            boolean rejected = false;
            try { DashcamStorage.resolve("missing-usb", unavailableInternal, storage, v -> false); }
            catch (IOException expected) { rejected = true; }
            check(rejected, "no writable destination must fail rather than pretend recording");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
