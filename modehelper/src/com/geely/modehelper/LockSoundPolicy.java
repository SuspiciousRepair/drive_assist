package com.geely.modehelper;

/**
 * Decides what to write to the car's lock-sound setting, and which files
 * may become a lock sound.
 *
 * The car plays the file named by {@code Settings.System lock_car_audio_path}
 * when the doors are locked with the key (CarService, uid system, checks only
 * that the file exists). So a sound of ours is a file in modehelper's own
 * files dir plus that path. CarService runs as the same uid and its SELinux
 * domain may read {@code system_app_data_file}, so no root is needed.
 *
 * Ids: "car" gives the car back the sound it had before we changed it;
 * "fart" is bundled; "custom" is the file the user imported (USB or link),
 * and exists only after an import.
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
     * May we download this link? https only, with a host. This runs as uid
     * system on behalf of any sender, so no plain http (anyone on the
     * network could swap the file) and no other scheme (file:, content:).
     */
    static boolean httpsUrl(String url) {
        if (url == null) return false;
        try {
            java.net.URI u = new java.net.URI(url.trim());
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && !u.getHost().isEmpty();
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
     * target must be https too (a relative Location stays on the https
     * site), and a chain ends after {@link #MAX_REDIRECTS}.
     *
     * @param from      the https link that answered with a redirect
     * @param location  its Location header (may be null)
     * @param followed  how many redirects this download followed already
     */
    static String redirectTarget(String from, String location, int followed) {
        if (followed >= MAX_REDIRECTS || location == null || location.trim().isEmpty()) return null;
        if (!httpsUrl(from)) return null;
        try {
            String next = new java.net.URI(from.trim()).resolve(location.trim()).toString();
            return httpsUrl(next) ? next : null;
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

    /** What to write and what to remember. {@code write == null}: leave the setting alone. */
    static final class Decision {
        final String write;
        final String savedPrev;
        Decision(String write, String savedPrev) { this.write = write; this.savedPrev = savedPrev; }
    }

    /**
     * @param current    the setting now (may be null)
     * @param savedPrev  the car's own sound, saved when we first replaced it (may be null)
     * @param id         "car", "fart" or "custom"
     * @param dir        modehelper's files dir
     * @param haveCustom true when an imported sound is in {@code dir}
     * @return null when the id cannot be applied (unknown, or custom with nothing imported)
     */
    static Decision decide(String current, String savedPrev, String id, String dir, boolean haveCustom) {
        if (CAR.equals(id)) {
            if (!isOurs(current)) return new Decision(null, null);   // already the car's own
            String back = (savedPrev != null && !savedPrev.isEmpty() && !isOurs(savedPrev))
                ? savedPrev : OEM_DEFAULT;
            return new Decision(back, null);
        }
        if (!FART.equals(id) && !(CUSTOM.equals(id) && haveCustom)) return null;
        // Remember the car's sound only when replacing it, never one of ours:
        // switching fart to custom must not make a fart the thing "car" restores.
        String keep = (current != null && !current.isEmpty() && !"null".equals(current) && !isOurs(current))
            ? current : savedPrev;
        return new Decision(dir + "/" + fileName(id), keep);
    }
}
