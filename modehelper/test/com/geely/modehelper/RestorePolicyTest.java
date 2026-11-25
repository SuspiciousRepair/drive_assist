package com.geely.modehelper;

/** Dependency-free test runner for {@link RestorePolicy}. */
public final class RestorePolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // restore switch: ON by default, OFF only when the user says so
        check(RestorePolicy.restoreOn(false, false), "no switch stored yet: restore is on");
        check(!RestorePolicy.restoreOn(true, false), "switch turned off: restore is off");
        check(RestorePolicy.restoreOn(true, true), "switch on: restore is on");

        // window
        check(RestorePolicy.inWindow(99, 100), "before the end: in window");
        check(!RestorePolicy.inWindow(100, 100), "at the end: out of window");

        // #88: the window is short, so a change the driver makes once the
        // car is ready (AVAS on, then out of Park) is not undone
        check(RestorePolicy.WINDOW_MS <= 15_000L, "the hold lasts at most 15 s");
        check(RestorePolicy.WINDOW_MS >= 8_000L, "the hold still spans two 4 s ticks after the wake");
        check(!RestorePolicy.inWindow(20_000, RestorePolicy.WINDOW_MS),
              "20 s after the wake the window is closed");

        // #12: after the window a change made in the car stays
        check(!RestorePolicy.shouldWrite(true, true, false, false),
              "restore on, window closed, no tap: do not write");
        check(RestorePolicy.shouldWrite(true, true, true, false),
              "restore on, in window: write the saved value");
        // #16: restore off is never written by the window
        check(!RestorePolicy.shouldWrite(true, false, true, false),
              "restore off, in window: do not write");
        // review finding 1: a tap is written even in the window with restore off
        check(RestorePolicy.shouldWrite(true, false, true, true),
              "tap in window with restore off: write");
        check(RestorePolicy.shouldWrite(true, false, false, true),
              "tap outside window: write");
        // nothing saved: never (no forced Eco/Mid)
        check(!RestorePolicy.shouldWrite(false, true, true, true),
              "nothing saved: never write");

        // review finding 3: wake fallback from deep-sleep time
        check(RestorePolicy.sleptBetween(1_000, 1_000 + RestorePolicy.SLEEP_GAP_MS),
              "a sleep of the gap length counts as a wake");
        check(!RestorePolicy.sleptBetween(1_000, 1_200), "a few ms of jitter is not a wake");
        check(!RestorePolicy.sleptBetween(5_000, 5_000), "no sleep: no wake");
        check(RestorePolicy.screenTurnedOn(false, true), "screen off to on re-arms the restore");
        check(!RestorePolicy.screenTurnedOn(null, true), "first reading is not an edge");
        check(!RestorePolicy.screenTurnedOn(true, true), "screen stays on: no edge");
        check(!RestorePolicy.screenTurnedOn(true, false), "screen goes off: no edge");
        System.out.println("RestorePolicyTest: OK");
    }
}
