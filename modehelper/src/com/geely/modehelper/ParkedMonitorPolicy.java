package com.geely.modehelper;

/**
 * Pure lifecycle policy for parked motion monitoring.
 *
 * <p>It intentionally knows nothing about camera hardware: its only job is to
 * avoid arming on transient Park selections. A caller supplies the authoritative
 * gear state at each poll. The motion gate is armed after thirty uninterrupted
 * seconds in Park and disarmed immediately when Park is left.</p>
 */
final class ParkedMonitorPolicy {
    static final long PARK_SETTLE_MS = 30_000L;

    enum State { DISARMED, SETTLING, ARMED }

    private long parkedSinceMs = -1L;
    private long lastNowMs = -1L;
    private State state = State.DISARMED;

    State update(long nowMs, boolean parked) {
        // A clock rollback cannot prove uninterrupted parking. Restart the
        // settle interval instead of accidentally arming early.
        if (lastNowMs >= 0L && nowMs < lastNowMs) {
            parkedSinceMs = parked ? nowMs : -1L;
            state = parked ? State.SETTLING : State.DISARMED;
        }
        lastNowMs = nowMs;

        if (!parked) {
            parkedSinceMs = -1L;
            state = State.DISARMED;
            return state;
        }
        if (parkedSinceMs < 0L) parkedSinceMs = nowMs;
        state = nowMs - parkedSinceMs >= PARK_SETTLE_MS ? State.ARMED : State.SETTLING;
        return state;
    }

    State state() { return state; }

    void reset() {
        parkedSinceMs = -1L;
        lastNowMs = -1L;
        state = State.DISARMED;
    }
}
