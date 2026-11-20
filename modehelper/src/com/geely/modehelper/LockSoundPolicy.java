package com.geely.modehelper;

/**
 * Decides what the lock sound needs, and which files may become one.
 *
 * The car plays the file named by {@code Settings.System lock_car_audio_path}
 * when the doors are locked with the key. CarService checks only that the
 * file exists, but the playing is done by mediaextractor, and SELinux lets
 * that read the car's own sounds ({@code vendor_configs_file}) and not our
 * files dir: pointed at our file, the car played nothing (avc denied,
 * 2026-11-20). So our sound is bind-mounted, through the local root shell,
 * over EVERY sound in {@link #CARLOCK}. The setting keeps naming a car
 * sound: whichever one is picked, in our screen or in the car's own
 * Settings, plays ours. Nothing on /vendor is written; a reboot drops the
 * mounts and {@link LockSoundReceiver#reapply} puts them back.
 *
 * Ids: "car" takes our mounts away (the car's sounds play again); "fart" is
 * bundled; "custom" is the file the user imported (USB or link), and exists
 * only after an import.
 */
final class LockSoundPolicy {
    private LockSoundPolicy() {}

    static final String SETTING = "lock_car_audio_path";
    /** What CarService itself falls back to when the setting is empty. */
    static final String OEM_DEFAULT = "/vendor/etc/carlock/yuedongyinghe.wav";
    static final String CAR = "car";
    /** Bundled in assets/locksound/fart.wav. */
    static final String FART = "fart";
    /** Imported by the user; see {@link #checkWav}. */
    static final String CUSTOM = "custom";
    /** Same name Tesla uses, so a stick made for one works for the other. */
    static final String USB_NAME = "LockChime.wav";
    /** The car's own (broken) USB import has the same limit, and so does
     * Tesla's. A lock sound is a second or two; more is a mistake. */
    static final long MAX_BYTES = 1024 * 1024;

    // Why a file cannot be a lock sound. Sent back to Drive Assist as-is.
    static final String OK = "ok";
    static final String EMPTY = "empty";
    static final String TOO_BIG = "too_big";
    static final String NOT_WAV = "not_wav";
    /** Another import is still running. */
    static final String BUSY = "busy";

    /** The whole import, first byte to last, must end inside this. A timeout
     * per read does not bound it: a server that sends one byte now and then
     * would hold the broadcast until the system kills our process for it
     * (ANR), and that process also hosts the dashcam recorder. */
    static final long FETCH_DEADLINE_MS = 20_000;
    /** How many redirects one download may follow. */
    static final int MAX_REDIRECTS = 3;

    // Matched by package, not by our current getFilesDir(): the same file is
    // reachable as /data/data/... and /data/user/0/..., and a path written
    // by hand over adb must still count as ours.
    private static final String OURS = "/com.geely.modehelper/files/lock_";

    static String fileName(String id) { return "lock_" + id + ".wav"; }

    static boolean isOurs(String path) { return path != null && path.contains(OURS); }

    /**
     * May we download this link? http or https, with a host. Plain http is
     * allowed by the owner's choice (a sound served from a box at home has
     * no certificate); the file is still size-capped and must be a WAV. No
     * other scheme (file:, content:): this runs as uid system.
     */
    static boolean webUrl(String url) {
        if (url == null) return false;
        try {
            java.net.URI u = new java.net.URI(url.trim());
            String scheme = u.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && u.getHost() != null && !u.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isRedirect(int httpStatus) {
        return httpStatus == 301 || httpStatus == 302 || httpStatus == 303
            || httpStatus == 307 || httpStatus == 308;
    }

    /**
     * Where a redirect may take the download, or null to refuse it. The
     * target must be an http or https link too (never file: or content:),
     * and a chain ends after {@link #MAX_REDIRECTS}.
     *
     * @param from      the link that answered with a redirect
     * @param location  its Location header (may be null)
     * @param followed  how many redirects this download followed already
     */
    static String redirectTarget(String from, String location, int followed) {
        if (followed >= MAX_REDIRECTS || location == null || location.trim().isEmpty()) return null;
        if (!webUrl(from)) return null;
        try {
            String next = new java.net.URI(from.trim()).resolve(location.trim()).toString();
            return webUrl(next) ? next : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Can this file be a lock sound? Checks the size and the RIFF/WAVE
     * header only: the car's MediaPlayer decodes the rest, and a file it
     * cannot play is just silence, not harm.
     *
     * @param head the first bytes of the file (at least 12 to pass)
     * @param size the whole file's size in bytes
     */
    static String checkWav(byte[] head, long size) {
        if (size <= 0) return EMPTY;
        if (size > MAX_BYTES) return TOO_BIG;
        if (head == null || head.length < 12) return NOT_WAV;
        if (head[0] != 'R' || head[1] != 'I' || head[2] != 'F' || head[3] != 'F'
            || head[8] != 'W' || head[9] != 'A' || head[10] != 'V' || head[11] != 'E') return NOT_WAV;
        return OK;
    }

    /** The car's own lock sounds. Every *.wav here gets our mount. */
    static final String CARLOCK = "/vendor/etc/carlock";
    /** Where the root shell keeps the copy it mounts. */
    static final String MOUNT_DIR = "/data/local/locksound";
    static final String MOUNT_FILE = MOUNT_DIR + "/lock.wav";
    /** How /proc/self/mountinfo names our source: relative to the /data mount. */
    static final String MOUNT_ROOT = "/local/locksound/";
    /** The label of the car's own sounds, so mediaextractor may read ours. */
    static final String LABEL = "u:object_r:vendor_configs_file:s0";
    static final String MOUNTED = "LOCKSOUND_MOUNTED";
    static final String REMOVED = "LOCKSOUND_REMOVED";

    private static final java.util.regex.Pattern OEM_FILE =
        java.util.regex.Pattern.compile("/vendor/etc/carlock/[A-Za-z0-9_][A-Za-z0-9_.-]*\\.wav");
    private static final java.util.regex.Pattern OUR_FILE =
        java.util.regex.Pattern.compile("/data/(data|user/0)/com\\.geely\\.modehelper/files/lock_(fart|custom)\\.wav");

    /** One of the car's own sounds (the only files our mount covers). */
    static boolean isOem(String path) {
        return path != null && !path.contains("..") && OEM_FILE.matcher(path).matches();
    }

    /** Can this id be applied now? */
    static boolean valid(String id, boolean haveCustom) {
        return CAR.equals(id) || FART.equals(id) || (CUSTOM.equals(id) && haveCustom);
    }

    /** The id an old helper left in the setting (it pointed the setting at
     * our file), or "car". Used once, to carry that choice over. */
    static String idForSetting(String current) {
        if (!isOurs(current)) return CAR;
        return current.endsWith("/" + fileName(FART)) ? FART
            : current.endsWith("/" + fileName(CUSTOM)) ? CUSTOM : CAR;
    }

    /**
     * What to write to the setting, or null to leave it. A car sound the
     * user picked is never changed: our mount is on all of them. Only a
     * setting our mount cannot cover is fixed: our own file (left by an old
     * helper; the car cannot play it), or for fart/custom anything that is
     * not a car sound (empty, a USB path).
     *
     * @param savedPrev the car sound an old helper saved before pointing
     *                  the setting at our file (may be null)
     */
    static String settingFor(String id, String current, String savedPrev) {
        String back = isOem(savedPrev) ? savedPrev : OEM_DEFAULT;
        if (CAR.equals(id)) return isOurs(current) ? back : null;
        return isOem(current) ? null : back;
    }

    /** How many of our mounts a mountinfo text lists. */
    static int countMounts(String mountinfo) {
        int n = 0;
        if (mountinfo == null) return 0;
        for (String line : mountinfo.split("\n")) {
            String[] f = line.split(" ");
            if (f.length > 4 && f[3].startsWith(MOUNT_ROOT) && f[4].startsWith(CARLOCK + "/")) n++;
        }
        return n;
    }

    // Removes every mount whose source is ours, however many. Twice: a file
    // mounted twice shows up again after the first pass. Mounts of anybody
    // else are left alone.
    private static String unmountOurs() {
        String one = "for m in $(grep ' " + MOUNT_ROOT + "' /proc/self/mountinfo | cut -d' ' -f5); do umount -l $m; done; ";
        return one + one;
    }

    /** Copies {@code src} (our file) to {@link #MOUNT_FILE}, labels it like
     * the car's sounds and mounts it over each of them. Prints
     * {@link #MOUNTED} when at least one mount took. */
    static String mountCommand(String src) {
        if (src == null || src.contains("..") || !OUR_FILE.matcher(src).matches())
            throw new IllegalArgumentException("refused: " + src);
        return unmountOurs()
            + "mkdir -p " + MOUNT_DIR + " && chmod 755 " + MOUNT_DIR
            + " && cp -f " + src + " " + MOUNT_FILE + " && chmod 644 " + MOUNT_FILE
            + " && chcon " + LABEL + " " + MOUNT_FILE
            + " && { ok=; for t in " + CARLOCK + "/*.wav; do mount -o bind " + MOUNT_FILE + " $t && ok=1; done;"
            + " [ -n \"$ok\" ] && echo " + MOUNTED + "; }";
    }

    /** Takes every mount of ours away and deletes the copy. Prints {@link #REMOVED}. */
    static String unmountCommand() {
        return unmountOurs() + "rm -rf " + MOUNT_DIR + "; echo " + REMOVED;
    }
}
