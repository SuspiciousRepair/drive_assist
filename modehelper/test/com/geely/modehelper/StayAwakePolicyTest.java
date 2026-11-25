package com.geely.modehelper;

/** Dependency-free test runner for {@link StayAwakePolicy}. */
public final class StayAwakePolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(StayAwakePolicy.holdWakeLock(true, 0x201B0104), "home Wi-Fi + Parking Mode 1 h: awake");
        check(!StayAwakePolicy.holdWakeLock(true, 0), "home Wi-Fi, Parking Mode off: sleep");
        check(!StayAwakePolicy.holdWakeLock(true, null), "home Wi-Fi, Parking Mode not read: sleep");
        check(!StayAwakePolicy.holdWakeLock(false, 0x201B0104), "other network: sleep");
        System.out.println("StayAwakePolicyTest OK");
    }
}
