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

    /** Vendor property that follows the car's on/off state: 4 while the car is
     * on, 2 while it is switched off (same coding as IGNITION_STATE). Found by
     * Funkyfal (github.com/Funkyfal/drive_assist, seen 2026-11-15); the car
     * resets the drive mode at the moment it is switched off. Used to re-arm
     * the drive/regen restore (ModeRestore), issue #130. */
    static final int VEHICLE_STATE_PROP = 557875211;
    static final int VEHICLE_ON = 4;

    private RestorePolicy() {}

    /** The car was just switched on. An unknown earlier state (first
     * reading) is no edge: the service start already armed the restore. */
    static boolean turnedOn(Integer before, Integer now) {
        return before != null && now != null && before != VEHICLE_ON && now == VEHICLE_ON;
    }

    /** The car is known to be off (2, or 3 on the way on). The screen can
     * wake while the car is off (door opened); a drive/regen write then
     * changes nothing, seen on the car 2026-11-19: 5 wakes, 30 writes, all
     * ignored. Unknown (null, property missing) is not off: the wake window
     * keeps working on a unit without this property. */
    static boolean knownOff(Integer state) {
        return state != null && state != VEHICLE_ON;
    }

    /** The car was just switched off. */
    static boolean turnedOff(Integer before, Integer now) {
        return before != null && now != null && before == VEHICLE_ON && now != VEHICLE_ON;
    }

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

    /** The screen came on. A remote A/C start turns the car "on" with the
     * screen off and nobody inside, and the car refuses the drive mode then.
     * The driver who gets in while it still runs gives no new on signal, so
     * the screen is the signal that a person is there. */
    static boolean screenTurnedOn(Boolean before, boolean now) {
        return before != null && !before && now;
    }

    /** {@code sleepMs} is elapsedRealtime() - uptimeMillis(): time spent in
     * deep sleep since boot. It only grows while the unit is suspended. */
    static boolean sleptBetween(long prevSleepMs, long nowSleepMs) {
        return nowSleepMs - prevSleepMs >= SLEEP_GAP_MS;
    }
}
