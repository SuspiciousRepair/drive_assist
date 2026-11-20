package com.geely.modehelper;

/** Dependency-free test runner for {@link BacklightPolicy}. */
public final class BacklightPolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // levels: only off (1) or the lowest lit level (2)
        check(BacklightPolicy.darkLevel(1) == 1, "darkness 100 %: panel off");
        check(BacklightPolicy.darkLevel(2) == 2, "lowest lit level");
        check(BacklightPolicy.darkLevel(0) == 2, "0 is never written: lowest lit level");
        check(BacklightPolicy.darkLevel(255) == 2, "a bright value is never a dark level");
        check(BacklightPolicy.darkLevel(-5) == 2, "bad value: lowest lit level");

        // heartbeat: Drive Assist says "still here" every 5 s
        check(!BacklightPolicy.expired(0, 5_000), "one beat late: still dark");
        check(!BacklightPolicy.expired(0, 14_999), "two beats late: still dark");
        check(BacklightPolicy.expired(0, BacklightPolicy.BEAT_TIMEOUT_MS), "no beat for 15 s: light back");
        check(BacklightPolicy.expired(0, 600_000), "Drive Assist gone: light back");

        // restore never leaves the panel dark
        check(BacklightPolicy.restoreLevel(166) == 166, "the car's level");
        check(BacklightPolicy.restoreLevel(1) == 2, "a stored 1 is not off");
        check(BacklightPolicy.restoreLevel(0) == 2, "a stored 0 is not off");
        check(BacklightPolicy.restoreLevel(999) == 255, "kept in range");

        // ADB opened for a write is closed after it, unless someone else opened it meanwhile
        check(BacklightPolicy.closeAdbAfter(7, 7), "we opened it, nobody else since: close");
        check(!BacklightPolicy.closeAdbAfter(-1, 7), "it was already on: leave it");
        check(!BacklightPolicy.closeAdbAfter(7, 8), "opened again by someone else: leave it");

        System.out.println("BacklightPolicyTest: OK");
    }
}
