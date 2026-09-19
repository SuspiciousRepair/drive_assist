package com.geely.drivemem.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Validated dashcam preferences and the app's mounted recording destinations. */
public final class DashcamSettings {
    public static final String KEY_SEGMENT_MINUTES = "dashcam_segment_minutes";
    public static final String KEY_STORAGE = "dashcam_storage";
    public static final String INTERNAL = "internal";
    public static final int DEFAULT_SEGMENT_MINUTES = 5;
    public static final int[] SEGMENT_MINUTES = {1, 3, 5, 10};
    private static final String APP_FILES_SUFFIX = "/Android/data/com.geely.drivemem/files";

    private DashcamSettings() {}

    public static final class StorageOption {
        public final String id;
        public final File directory;
        public final boolean removable;

        private StorageOption(String id, File directory, boolean removable) {
            this.id = id;
            this.directory = directory;
            this.removable = removable;
        }
    }

    public static int validatedSegmentMinutes(int minutes) {
        return minutes == 1 || minutes == 3 || minutes == 5 || minutes == 10
                ? minutes : DEFAULT_SEGMENT_MINUTES;
    }

    public static boolean validStorageId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
                && !"emulated".equalsIgnoreCase(id)
                && !"self".equalsIgnoreCase(id)
                && !"primary".equalsIgnoreCase(id);
    }

    public static String validatedStorageId(String id) {
        return validStorageId(id) ? id : INTERNAL;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
    }

    public static int segmentMinutes(Context context) {
        return validatedSegmentMinutes(preferences(context)
                .getInt(KEY_SEGMENT_MINUTES, DEFAULT_SEGMENT_MINUTES));
    }

    public static String preferredStorage(Context context) {
        return validatedStorageId(preferences(context).getString(KEY_STORAGE, INTERNAL));
    }

    public static void setSegmentMinutes(Context context, int minutes) {
        preferences(context).edit().putInt(KEY_SEGMENT_MINUTES,
                validatedSegmentMinutes(minutes)).apply();
        sendConfiguration(context);
    }

    public static void setStorage(Context context, String id) {
        preferences(context).edit().putString(KEY_STORAGE, validatedStorageId(id)).apply();
        sendConfiguration(context);
    }

    /** Explicit receiver also accepts preferences while its service is stopped. */
    public static Intent configurationIntent(Context context) {
        return new Intent("com.geely.modehelper.DASHCAM")
                .setClassName("com.geely.modehelper", "com.geely.modehelper.DashReceiver")
                .putExtra("on", -1)
                .putExtra(KEY_SEGMENT_MINUTES, segmentMinutes(context))
                .putExtra(KEY_STORAGE, preferredStorage(context))
                .putExtra("dashcam_limit_gb", Math.max(1, Math.min(500,
                        preferences(context).getInt("dashcam_limit_gb", 10))));
    }

    public static void sendConfiguration(Context context) {
        context.sendBroadcast(configurationIntent(context));
    }

    /** Writable destinations for new recordings; read-only USB is never selectable. */
    public static List<StorageOption> storageOptions(Context context) {
        return storageOptions(context, true);
    }

    /** Existing recordings remain visible on OS-mounted read-only removable media. */
    public static List<StorageOption> readableStorageOptions(Context context) {
        return storageOptions(context, false);
    }

    static boolean acceptsStorageState(String state, boolean requireWritable) {
        return Environment.MEDIA_MOUNTED.equals(state)
            || (!requireWritable && Environment.MEDIA_MOUNTED_READ_ONLY.equals(state));
    }

    private static List<StorageOption> storageOptions(Context context, boolean requireWritable) {
        File primary = context.getExternalFilesDir(null);
        if (primary == null) {
            // Keep the established shared primary path even when temporarily
            // unavailable; private internal storage is invisible to modehelper.
            primary = new File(Environment.getExternalStorageDirectory(),
                    "Android/data/com.geely.drivemem/files");
        }
        List<File> mounted = new ArrayList<>();
        File[] directories = context.getExternalFilesDirs(null);
        if (directories != null) {
            for (File directory : directories) {
                if (directory == null) {
                    continue;
                }
                try {
                    if (acceptsStorageState(Environment.getExternalStorageState(directory), requireWritable)
                            && Environment.isExternalStorageRemovable(directory)
                            && (!requireWritable || directory.canWrite())) {
                        mounted.add(directory);
                    }
                } catch (IllegalArgumentException | SecurityException ignored) {
                    // A drive can disappear between discovery and checking its state.
                }
            }
        }
        return discover(primary, mounted.toArray(new File[0]));
    }

    /** Pure mapping: only known app-files paths are eligible, never caller-supplied paths. */
    static List<StorageOption> discover(File primary, File[] mountedDirectories) {
        List<StorageOption> options = new ArrayList<>();
        options.add(new StorageOption(INTERNAL, new File(primary, "dashcam"), false));
        if (mountedDirectories != null) {
            for (File directory : mountedDirectories) {
                String id = removableId(directory);
                if (id == null || directory.equals(primary)) {
                    continue;
                }
                boolean duplicate = false;
                for (StorageOption option : options) {
                    if (option.id.equals(id)) {
                        duplicate = true;
                    }
                }
                if (!duplicate) {
                    options.add(new StorageOption(id, new File(directory, "dashcam"), true));
                }
            }
        }
        return Collections.unmodifiableList(options);
    }

    static String removableId(File directory) {
        if (directory == null) {
            return null;
        }
        String path = directory.getAbsolutePath();
        if (!path.startsWith("/storage/") || !path.endsWith(APP_FILES_SUFFIX)) {
            return null;
        }
        try {
            if (!path.equals(directory.getCanonicalPath())) {
                return null;
            }
        } catch (IOException ignored) {
            return null;
        }
        String id = path.substring("/storage/".length(), path.length() - APP_FILES_SUFFIX.length());
        return validStorageId(id) && !INTERNAL.equals(id) ? id : null;
    }

    static StorageOption resolveStorage(String preferred, List<StorageOption> options) {
        String validated = validatedStorageId(preferred);
        for (StorageOption option : options) {
            if (option.id.equals(validated)) {
                return option;
            }
        }
        return options.get(0);
    }

    /** Falls back without erasing the preferred USB ID, so reconnecting restores the choice. */
    public static StorageOption resolveStorage(Context context) {
        return resolveStorage(preferredStorage(context), storageOptions(context));
    }

    public static boolean isStorageFallback(Context context) {
        return !preferredStorage(context).equals(resolveStorage(context).id);
    }
}
