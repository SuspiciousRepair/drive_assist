package com.geely.modehelper;

/** When to restart a recorder that stopped on its own: at once the first
 * time, then after 30 s, 1, 2, 4, 8 and at most 10 minutes. A recorder that
 * stays up 5 minutes clears the count. Times are uptime in ms. */
final class RestartBackoff {
    static final long FIRST_MS = 30_000L, MAX_MS = 10 * 60_000L, HEALTHY_MS = 5 * 60_000L;

    private int failures;
    private long notBeforeMs;

    /** Called on each poll while the recorder is wanted but not running.
     * True when a restart is due now; it then arms the next wait. */
    boolean due(long nowMs) {
        if (nowMs < notBeforeMs) return false;
        notBeforeMs = nowMs + Math.min(MAX_MS, FIRST_MS << Math.min(failures, 5));
        failures++;
        return true;
    }

    /** Called on each poll while the recorder runs. */
    void running(long startedMs, long nowMs) {
        if (nowMs - startedMs >= HEALTHY_MS) { failures = 0; notBeforeMs = 0; }
    }
}
