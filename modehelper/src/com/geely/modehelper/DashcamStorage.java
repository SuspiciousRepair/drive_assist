package com.geely.modehelper;

import java.io.File;
import java.io.IOException;

/** Resolves only our app's recording directory, never an arbitrary broadcast path. */
final class DashcamStorage {
    static final String APP_DIRECTORY = "Android/data/com.geely.drivemem/files/dashcam";

    interface MountedVolume {
        boolean writableRemovable(File volume);
    }

    private DashcamStorage() { }

    static File resolve(String preference, File internal, File storageRoot,
                        MountedVolume mounted) throws IOException {
        String id = DashcamOptions.storage(preference);
        if (!DashcamOptions.INTERNAL.equals(id)) {
            File volume = new File(storageRoot, id);
            try {
                // Check before mkdirs: /storage/<missing USB> must never become an
                // ordinary directory that silently records onto internal storage.
                if (volume.isDirectory() && directChild(storageRoot, volume)
                        && mounted.writableRemovable(volume)) {
                    File destination = new File(volume, APP_DIRECTORY);
                    if (confined(volume, destination)) {
                        prepareRemovable(volume, mounted);
                        // Recheck after creation in case the drive was removed or
                        // a path component changed while the directory was made.
                        if (mounted.writableRemovable(volume) && confined(volume, destination))
                            return destination;
                    }
                }
            } catch (IOException | SecurityException unavailable) {
                // A missing, read-only or inaccessible USB is a preference, not a
                // reason to lose recording when internal app storage is available.
            }
        }
        ensureWritable(internal);
        return internal;
    }

    static boolean directChild(File parent, File child) throws IOException {
        return new File(parent.getCanonicalFile(), child.getName()).getAbsoluteFile()
            .equals(child.getCanonicalFile());
    }

    static boolean confined(File volume, File destination) throws IOException {
        return new File(volume.getCanonicalFile(), APP_DIRECTORY).getAbsoluteFile()
            .equals(destination.getCanonicalFile());
    }

    private static void prepareRemovable(File volume, MountedVolume mounted) throws IOException {
        File parent = volume;
        for (String component : APP_DIRECTORY.split("/")) {
            if (!volume.isDirectory() || !mounted.writableRemovable(volume))
                throw new IOException("Removable volume disappeared");
            File directory = new File(parent, component);
            if (!directChild(parent, directory)) throw new IOException("Redirected dashcam path");
            // mkdir (not mkdirs) cannot recreate the missing volume or its parents
            // if the USB disappears between the mounted check and this operation.
            if (!directory.isDirectory() && !directory.mkdir())
                throw new IOException("Cannot create removable dashcam directory");
            if (!directory.isDirectory() || !directory.canWrite())
                throw new IOException("Removable dashcam directory is not writable");
            parent = directory;
        }
    }

    private static void ensureWritable(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create dashcam directory");
        if (!directory.isDirectory() || !directory.canWrite())
            throw new IOException("Dashcam directory is not writable");
    }
}
