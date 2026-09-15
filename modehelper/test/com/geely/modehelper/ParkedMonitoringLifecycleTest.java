package com.geely.modehelper;

/** Host-side regression checks for the side-effect-free admission boundary. */
public final class ParkedMonitoringLifecycleTest {
    public static void main(String[] args) {
        ParkedMonitoringLifecycle lifecycle = new ParkedMonitoringLifecycle();
        check(lifecycle.update(0, true, true, true) == ParkedMonitoringLifecycle.Decision.SETTLING);
        check(lifecycle.update(29_999, true, true, true) == ParkedMonitoringLifecycle.Decision.SETTLING);
        check(lifecycle.update(30_000, true, true, true) == ParkedMonitoringLifecycle.Decision.ARMED);
        check(lifecycle.update(30_001, true, false, true) == ParkedMonitoringLifecycle.Decision.DISARMED);
        check(lifecycle.update(30_002, true, true, true) == ParkedMonitoringLifecycle.Decision.SETTLING);
        check(lifecycle.update(60_002, false, true, true) == ParkedMonitoringLifecycle.Decision.DISARMED);
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}
