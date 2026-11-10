package com.geely.modehelper;

/**
 * When modehelper may write a saved setting (drive mode, regen, AEB, AVAS)
 * back to the car (issues #12, #13, #16).
 *
 * It used to write every 4 s, forever, so a change made in the car's own
 * Settings or in another app was undone within seconds. Now it only writes
 * during a short window after the car wakes (or this service starts), and
 * only for a setting whose "Restore at start" switch is on and that has a
 * saved value. That window is still what beats the car re-arming AVAS on
 * wake (v0.4.9); after it, whatever the driver picks stays until next start.
 */
final class RestorePolicy {
    /** How long after a wake saved values are held. */
    static final long WINDOW_MS = 60_000L;

    private RestorePolicy() {}

    /** The "Restore at start" switch. Before Drive Assist has pushed it, a
     * saved value means the old always-enforce behaviour was in use, so the
     * update does not silently stop restoring it. */
    static boolean restoreOn(boolean hasFlag, boolean flag, boolean hasValue) {
        return hasFlag ? flag : hasValue;
    }

    /** True while the post-wake window is open. */
    static boolean inWindow(long nowMs, long windowEndMs) {
        return nowMs < windowEndMs;
    }

    /** Whether this setting should be written to the car on this tick. */
    static boolean shouldEnforce(long nowMs, long windowEndMs, boolean restoreOn, boolean hasValue) {
        return restoreOn && hasValue && inWindow(nowMs, windowEndMs);
    }
}
