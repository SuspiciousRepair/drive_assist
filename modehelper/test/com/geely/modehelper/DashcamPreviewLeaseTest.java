package com.geely.modehelper;

/** Screen replacement, stale detach and dead-process lease interleavings. */
public final class DashcamPreviewLeaseTest {
    public static void main(String[] args) {
        DashcamPreviewLease lease = new DashcamPreviewLease();
        check(lease.attach("screen_A", 100), "first attachment changes ownership");
        check(!lease.attach("screen_A", 3000), "same-owner heartbeat does not recreate output");
        check(!lease.expired(12_999), "heartbeat extends lease");
        check(lease.expired(13_000), "dead screen expires at ten seconds");
        check(lease.attach("screen_B", 13_100), "new screen replaces ownership");
        check(!lease.detach("screen_A") && lease.matches("screen_B"), "stale detach cannot clear newer screen");
        check(lease.detach("screen_B") && lease.session() == null, "current owner can detach");
        check(!lease.expired(99_999), "no active screen needs expiry");
        check(lease.attach("screen_C", 100_000), "later screen can attach");
        check(!lease.detach(null) && lease.matches("screen_C"), "missing owner cannot detach");
        for (String invalid : new String[]{null, "", "../screen", "screen with spaces", "screen\n"})
            check(!DashcamPreviewLease.validSession(invalid), "invalid IPC session rejected");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
