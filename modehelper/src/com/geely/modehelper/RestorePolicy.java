package com.geely.modehelper;

/**
 * When modehelper may write a saved setting (drive mode, regen, AEB, AVAS)
 * to the car (issues #12, #13, #16).
 *
 * Drive Assist owns the car's START state: in the window after a wake, every
 * setting whose "Restore at start" switch is on (the default) is put back to
 * the saved choice. After the window, a change made in the car or another app
 * stays for the trip. A user tap in Drive Assist is written at once, window
 * or not.
 *
 * Why a window and not one write: the car applies its OWN defaults when it
 * wakes (AVAS back on, regen back to Medium), and it can do so a few seconds
 * AFTER our first write. One write could lose that race (v0.4.9). So the
 * saved value is written again on each tick while the window is open.
 *
 * Why the window is short: everything written in it overrides the driver.
 * With 60 s, a driver who turned AVAS on and shifted out of Park within the
 * first minute had it muted again (issue #88). 15 s still covers several
 * 4 s ticks after the wake, and leaves the rest of the trip to the driver,
 * the car's own screen and other apps.
 *
 * Every decision the service makes goes through here, so it is tested.
 */
final class RestorePolicy {
    /** How long after a wake saved values are held: long enough to beat the
     * car's own wake defaults, short enough not to undo the driver (#88). */
    static final long WINDOW_MS = 15_000L;
    /** More deep-sleep time than this between two poll ticks means the unit
     * suspended and woke: a fallback for when SUSPEND_EXIT never arrives. */
    static final long SLEEP_GAP_MS = 5_000L;

    private RestorePolicy() {}

    /** The "Restore at start" switch. ON until the user turns it off. */
    static boolean restoreOn(boolean hasFlag, boolean flag) {
        return !hasFlag || flag;
    }

    /** True while the post-wake window is open. */
    static boolean inWindow(long nowMs, long windowEndMs) {
        return nowMs < windowEndMs;
    }

    /** Whether to write this setting to the car on this tick. Nothing saved:
     * never. A tap: always. Otherwise only in the window, with restore on. */
    static boolean shouldWrite(boolean hasValue, boolean restoreOn, boolean inWindow, boolean tap) {
        return hasValue && (tap || (restoreOn && inWindow));
    }

    /** {@code sleepMs} is elapsedRealtime() - uptimeMillis(): time spent in
     * deep sleep since boot. It only grows while the unit is suspended. */
    static boolean sleptBetween(long prevSleepMs, long nowSleepMs) {
        return nowSleepMs - prevSleepMs >= SLEEP_GAP_MS;
    }
}
