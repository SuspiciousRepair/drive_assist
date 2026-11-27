package com.geely.modehelper;

/**
 * When modehelper may write a saved setting (AEB, AVAS) to the car, and
 * vehicle power/screen edges shared with {@link ModeRestore} (issues #12,
 * #13, #88, #130, #201).
 *
 * AEB and AVAS are explicit Drive Assist overrides (never learned from OEM
 * Settings): when the driver has toggled one in Drive Assist, it is put back
 * during the short window after a wake, car switch-on, or screen-on. After
 * the window, a change made in the car or another app stays for the trip. A
 * user tap in Drive Assist is written at once, window or not.
 *
 * Why a window and not one write: the car applies its OWN defaults when it
 * wakes or switches on (AVAS back on, AEB back on), and it can do so a few
 * seconds AFTER our first write. One write could lose that race (v0.4.9). So
 * the saved value is written again on each tick while the window is open.
 *
 * Why the window is short: everything written in it overrides the driver.
 * With 60 s, a driver who turned AVAS on and shifted out of Park within the
 * first minute had it muted again (issue #88). 15 s still covers several
 * 4 s ticks after the start, and leaves the rest of the trip to the driver,
 * the car's own screen and other apps.
 *
 * Every decision the service makes goes through here, so it is tested.
 */
final class RestorePolicy {
    /** How long after a wake/car-on saved AEB/AVAS values are held: long
     * enough to beat the car's own start defaults, short enough not to undo
     * the driver (#88). */
    static final long WINDOW_MS = 15_000L;
    /** More deep-sleep time than this between two poll ticks means the unit
     * suspended and woke: a fallback for when SUSPEND_EXIT never arrives. */
    static final long SLEEP_GAP_MS = 5_000L;

    /** Vendor property that follows the car's on/off state: 4 while the car is
     * on, 2 while it is switched off (same coding as IGNITION_STATE). Found by
     * Funkyfal (github.com/Funkyfal/drive_assist, seen 2026-11-15); the car
     * resets the drive mode at the moment it is switched off. Used to re-arm
     * startup restore (issues #130, #201). */
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

    /** True while the post-start window is open. */
    static boolean inWindow(long nowMs, long windowEndMs) {
        return nowMs < windowEndMs;
    }

    /** Whether to write this AEB/AVAS setting to the car on this tick.
     * Nothing saved: never. A tap: always. Otherwise only in the window. */
    static boolean shouldWrite(boolean hasValue, boolean inWindow, boolean tap) {
        return hasValue && (tap || inWindow);
    }

    /** Whether a write may happen in the car's current power state: a tap
     * always runs; automatic restore waits until the car is not known off. */
    static boolean canRestoreNow(Integer vehicleState, boolean tap) {
        return tap || !knownOff(vehicleState);
    }

    /** {@code sleepMs} is elapsedRealtime() - uptimeMillis(): time spent in
     * deep sleep since boot. It only grows while the unit is suspended. */
    static boolean sleptBetween(long prevSleepMs, long nowSleepMs) {
        return nowSleepMs - prevSleepMs >= SLEEP_GAP_MS;
    }
}
