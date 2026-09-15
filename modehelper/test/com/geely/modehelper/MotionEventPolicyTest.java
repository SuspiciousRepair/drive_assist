package com.geely.modehelper;

public final class MotionEventPolicyTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        MotionEventPolicy policy = new MotionEventPolicy();
        check(policy.update(0, true).started);
        check(policy.update(500, false).active);
        // A new pulse within the tail extends the same event rather than starting another.
        check(!policy.update(5_000, true).started);
        check(policy.update(6_000, false).active);
        check(policy.update(15_999, false).active);
        check(policy.update(16_000, false).finished);
    }
}
