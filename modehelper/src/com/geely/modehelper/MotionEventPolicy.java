package com.geely.modehelper;

/**
 * Merges noisy motion-gate pulses into one future recording session.
 * It owns no recorder: callers perform side effects only from its transitions.
 */
final class MotionEventPolicy {
    static final long QUIET_TAIL_MS = 10_000L;

    static final class Result {
        final boolean started;
        final boolean active;
        final boolean finished;
        Result(boolean started, boolean active, boolean finished) {
            this.started = started; this.active = active; this.finished = finished;
        }
    }

    private boolean active;
    private long quietSinceMs = -1L;

    Result update(long nowMs, boolean gateActive) {
        if (gateActive) {
            boolean started = !active;
            active = true;
            quietSinceMs = -1L;
            return new Result(started, true, false);
        }
        if (!active) return new Result(false, false, false);
        if (quietSinceMs < 0L || nowMs < quietSinceMs) quietSinceMs = nowMs;
        if (nowMs - quietSinceMs < QUIET_TAIL_MS) return new Result(false, true, false);
        active = false;
        quietSinceMs = -1L;
        return new Result(false, false, true);
    }

    void reset() { active = false; quietSinceMs = -1L; }
}
