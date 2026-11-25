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
}
