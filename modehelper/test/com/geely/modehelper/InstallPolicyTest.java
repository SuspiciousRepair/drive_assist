package com.geely.modehelper;

/** Small dependency-free test runner for {@link InstallPolicy}. */
public final class InstallPolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(InstallPolicy.skipSameVersion("com.geely.drivemem", true, 5, 5), "same app, same version: skipped");
        check(!InstallPolicy.skipSameVersion("com.geely.drivemem", true, 5, 6), "a newer version installs");
        check(!InstallPolicy.skipSameVersion("com.geely.drivemem", false, 0, 5), "not installed: installs");
        check(!InstallPolicy.skipSameVersion("com.geely.installer", true, 1, 1),
              "an installer left on the car never blocks the next one");
        System.out.println("InstallPolicyTest OK");
    }
}
