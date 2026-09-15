package com.geely.modehelper;

/** Dependency-free test runner for {@link ParkedMonitorPolicy}. */
public final class ParkedMonitorPolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        ParkedMonitorPolicy policy = new ParkedMonitorPolicy();
        check(policy.update(1_000, false) == ParkedMonitorPolicy.State.DISARMED,
              "driving must remain disarmed");
        check(policy.update(2_000, true) == ParkedMonitorPolicy.State.SETTLING,
              "Park must settle first");
        check(policy.update(31_999, true) == ParkedMonitorPolicy.State.SETTLING,
              "must not arm before thirty seconds");
        check(policy.update(32_000, true) == ParkedMonitorPolicy.State.ARMED,
              "must arm after thirty uninterrupted seconds");
        check(policy.update(32_001, false) == ParkedMonitorPolicy.State.DISARMED,
              "leaving Park must disarm immediately");
        check(policy.update(33_000, true) == ParkedMonitorPolicy.State.SETTLING,
              "re-entering Park must restart the settle period");
        check(policy.update(20_000, true) == ParkedMonitorPolicy.State.SETTLING,
              "clock rollback must not retain armed state");
    }
}
