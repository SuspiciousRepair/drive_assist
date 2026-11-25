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
        check(StayAwakePolicy.holdDashStart(2, false), "car off, screen off: do not open the cameras");
        check(!StayAwakePolicy.holdDashStart(2, true), "car off, screen on (door opened): start");
        check(!StayAwakePolicy.holdDashStart(RestorePolicy.VEHICLE_ON, false), "car on: start");
        check(!StayAwakePolicy.holdDashStart(null, false), "car state not read: start as before");
        System.out.println("StayAwakePolicyTest OK");
    }
}
