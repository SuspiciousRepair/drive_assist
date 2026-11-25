package com.geely.modehelper;

/**
 * When the helper may keep the head unit awake on the trusted home Wi-Fi.
 *
 * <p>Only while the car's own Parking Mode is on. The car ends that mode by
 * itself (the duration the driver picked), so the wake lock follows the same
 * schedule and cannot hold the head unit awake all night. With Parking Mode
 * off, or its state unknown, the head unit sleeps as the car intends.</p>
 */
final class StayAwakePolicy {
    private StayAwakePolicy() {}

    /** @param parkModeRaw the car's Parking Mode value: 0 = off, null = not read. */
    static boolean holdWakeLock(boolean onTrustedWifi, Integer parkModeRaw) {
        return onTrustedWifi && parkModeRaw != null && parkModeRaw != 0;
    }

    /** True when a helper that has just started must not open the cameras yet:
     * the car is known to be off and the screen is off. A helper restarted in
     * that state (an update, a crash) used to start the dashcam, and the open
     * cameras then kept the head unit from sleeping (seen on the car
     * 2026-11-24: no suspend for 1 h 45 min after a helper install at car-off).
     * An unknown car state does not hold the start. */
    static boolean holdDashStart(Integer vehicleState, boolean screenOn) {
        return vehicleState != null && vehicleState != RestorePolicy.VEHICLE_ON && !screenOn;
    }
}
