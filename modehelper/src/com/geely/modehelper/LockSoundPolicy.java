package com.geely.modehelper;

/**
 * Decides what to write to the car's lock-sound setting.
 *
 * The car plays the file named by {@code Settings.System lock_car_audio_path}
 * when the doors are locked with the key (CarService, uid system, checks only
 * that the file exists). So a sound we ship is a file in modehelper's own
 * files dir plus that path. CarService runs as the same uid and its SELinux
 * domain may read {@code system_app_data_file}, so no root is needed.
 *
 * "car" means: give the car back the sound it had before we changed it.
 */
final class LockSoundPolicy {
    private LockSoundPolicy() {}

    static final String SETTING = "lock_car_audio_path";
    /** What CarService itself falls back to when the setting is empty. */
    static final String OEM_DEFAULT = "/vendor/etc/carlock/yuedongyinghe.wav";
    static final String CAR = "car";
    /** The sounds bundled in assets/locksound/&lt;id&gt;.wav. */
    static final String[] SOUNDS = {"flatulence", "flatus"};

    // Matched by package, not by our current getFilesDir(): the same file is
    // reachable as /data/data/... and /data/user/0/..., and a path written
    // by hand over adb must still count as ours.
    private static final String OURS = "/com.geely.modehelper/files/lock_";

    static boolean isKnown(String id) {
        if (id == null) return false;
        for (String s : SOUNDS) if (s.equals(id)) return true;
        return false;
    }

    static String fileName(String id) { return "lock_" + id + ".wav"; }

    static boolean isOurs(String path) { return path != null && path.contains(OURS); }

    /** What to write and what to remember. {@code write == null}: leave the setting alone. */
    static final class Decision {
        final String write;
        final String savedPrev;
        Decision(String write, String savedPrev) { this.write = write; this.savedPrev = savedPrev; }
    }

    /**
     * @param current   the setting now (may be null)
     * @param savedPrev the car's own sound, saved when we first replaced it (may be null)
     * @param id        "car" or one of {@link #SOUNDS}
     * @param dir       modehelper's files dir
     * @return null for an unknown id
     */
    static Decision decide(String current, String savedPrev, String id, String dir) {
        if (CAR.equals(id)) {
            if (!isOurs(current)) return new Decision(null, null);   // already the car's own
            String back = (savedPrev != null && !savedPrev.isEmpty() && !isOurs(savedPrev))
                ? savedPrev : OEM_DEFAULT;
            return new Decision(back, null);
        }
        if (!isKnown(id)) return null;
        // Remember the car's sound only when replacing it, never one of ours:
        // switching fart to fart must not make a fart the thing "car" restores.
        String keep = (current != null && !current.isEmpty() && !"null".equals(current) && !isOurs(current))
            ? current : savedPrev;
        return new Decision(dir + "/" + fileName(id), keep);
    }
}
