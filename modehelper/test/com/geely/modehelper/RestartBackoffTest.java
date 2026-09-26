package com.geely.modehelper;

/** Small dependency-free test runner for {@link RestartBackoff}. */
public final class RestartBackoffTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        RestartBackoff b = new RestartBackoff();
        long t = 1_000_000;
        check(b.due(t), "first restart must be immediate");
        check(!b.due(t + 29_999), "second restart before 30 s");
        check(b.due(t + 30_000), "second restart due at 30 s");
        t += 30_000;
        check(!b.due(t + 59_999) && b.due(t + 60_000), "third waits 60 s");
        t += 60_000;
        for (int i = 0; i < 10; i++) { long next = t; while (!b.due(next)) next += 1000; t = next; }
        check(!b.due(t + RestartBackoff.MAX_MS - 1) && b.due(t + RestartBackoff.MAX_MS), "capped at 10 min");
        t += RestartBackoff.MAX_MS;

        // Up for 4 minutes: still backing off. Up for 5: cleared, immediate again.
        b.running(t, t + 4 * 60_000);
        check(!b.due(t + 4 * 60_000), "cleared too early");
        b.running(t, t + RestartBackoff.HEALTHY_MS);
        check(b.due(t + RestartBackoff.HEALTHY_MS), "healthy run must clear the backoff");

        System.out.println("RestartBackoffTest OK");
    }
}
