package com.geely.modehelper;

/** Dependency-free test runner for {@link RestorePolicy}. */
public final class RestorePolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long end = 100_000L;
        // #12: outside the window nothing is written, whatever the switch says
        check(!RestorePolicy.shouldEnforce(end, end, true, true),
              "after the window a change made in the car must stay");
        check(!RestorePolicy.shouldEnforce(end + 5_000, end, true, true),
              "long after the window nothing is written");
        // inside the window, only with the switch on AND a saved value
        check(RestorePolicy.shouldEnforce(end - 1, end, true, true),
              "inside the window a restored setting is written");
        check(!RestorePolicy.shouldEnforce(0, end, false, true),
              "switch off: never written, even right after wake");
        check(!RestorePolicy.shouldEnforce(0, end, true, false),
              "nothing saved: never written (no forced Eco/Mid)");
        // switch defaults before Drive Assist pushes it
        check(RestorePolicy.restoreOn(false, false, true),
              "no flag yet + saved value: keep restoring (update keeps behaviour)");
        check(!RestorePolicy.restoreOn(false, false, false),
              "no flag yet + nothing saved: off");
        check(!RestorePolicy.restoreOn(true, false, true),
              "explicit off wins over a saved value");
        check(RestorePolicy.restoreOn(true, true, false),
              "explicit on is kept even before a value is saved");
        System.out.println("RestorePolicyTest: OK");
    }
}
