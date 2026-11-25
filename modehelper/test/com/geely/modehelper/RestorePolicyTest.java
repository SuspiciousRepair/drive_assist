package com.geely.modehelper;

/** Dependency-free test runner for {@link RestorePolicy}. */
public final class RestorePolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // window
        check(RestorePolicy.inWindow(99, 100), "before the end: in window");
        check(!RestorePolicy.inWindow(100, 100), "at the end: out of window");

        // #88: the window is short, so a change the driver makes once the
        // car is ready (AVAS on, then out of Park) is not undone
        check(RestorePolicy.WINDOW_MS <= 15_000L, "the hold lasts at most 15 s");
        check(RestorePolicy.WINDOW_MS >= 8_000L, "the hold still spans two 4 s ticks after the wake");
        check(!RestorePolicy.inWindow(20_000, RestorePolicy.WINDOW_MS),
              "20 s after the wake the window is closed");

        // #12 / #201: after the window a change made in the car stays
        check(!RestorePolicy.shouldWrite(true, false, false),
              "window closed, no tap: do not write");
        check(RestorePolicy.shouldWrite(true, true, false),
              "in window: write the saved value");
        check(RestorePolicy.shouldWrite(true, false, true),
              "tap outside window: write");
        check(RestorePolicy.shouldWrite(true, true, true),
              "tap in window: write");
        // nothing saved: never (untouched AEB/AVAS stay factory)
        check(!RestorePolicy.shouldWrite(false, true, true),
              "nothing saved: never write");

        // #201: AEB/AVAS automatic restore waits until car is not known off; taps always run
        check(!RestorePolicy.canRestoreNow(2, false), "car off (2): wait for car on");
        check(!RestorePolicy.canRestoreNow(3, false), "car starting (3): wait for car on");
        check(RestorePolicy.canRestoreNow(4, false), "car on (4): restore");
        check(RestorePolicy.canRestoreNow(null, false), "missing prop: restore");
        check(RestorePolicy.canRestoreNow(2, true), "explicit tap while off: write now");

        // review finding 3: wake fallback from deep-sleep time
        check(RestorePolicy.sleptBetween(1_000, 1_000 + RestorePolicy.SLEEP_GAP_MS),
              "a sleep of the gap length counts as a wake");
        check(!RestorePolicy.sleptBetween(1_000, 1_200), "a few ms of jitter is not a wake");
        check(!RestorePolicy.sleptBetween(5_000, 5_000), "no sleep: no wake");
        System.out.println("RestorePolicyTest: OK");
    }
}
