package com.geely.modehelper;

/**
 * Fail-closed admission boundary for a future parked-monitoring runtime.
 *
 * <p>This class does not own a camera, recorder, wake lock, or classifier.
 * It only turns externally supplied state into an arm/disarm decision, so
 * hardware side effects remain impossible until a separately approved owner
 * consumes the decision.</p>
 */
final class ParkedMonitoringLifecycle {
    enum Decision { DISARMED, SETTLING, ARMED }

    private final ParkedMonitorPolicy policy = new ParkedMonitorPolicy();

    Decision update(long nowMs, boolean enabled, boolean awake, boolean parked) {
        // Missing/stale awake state must never leave monitoring armed.
        if (!enabled || !awake) {
            policy.reset();
            return Decision.DISARMED;
        }
        switch (policy.update(nowMs, parked)) {
            case SETTLING: return Decision.SETTLING;
            case ARMED: return Decision.ARMED;
            default: return Decision.DISARMED;
        }
    }

    void reset() { policy.reset(); }
}
