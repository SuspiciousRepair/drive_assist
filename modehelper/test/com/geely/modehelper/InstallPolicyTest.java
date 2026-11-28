package com.geely.modehelper;

/** Small dependency-free test runner for {@link InstallPolicy}. */
public final class InstallPolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final int PARK = 4, DRIVE = 8, REVERSE = 2, NEUTRAL = 1;
    private static final int CHARGE_ON = 605028611, CHARGE_OFF = 605028609, CHARGE_MID = 605028610;

    private static void allowed(Integer gear, Float amps, Integer plug, Integer sw, String message) {
        String r = InstallPolicy.installBlockedReason(false, gear, amps, plug, sw);
        check(r == null, message + ": got " + r);
    }

    private static void blocked(Integer gear, Float amps, Integer plug, Integer sw,
                                String reason, String message) {
        String r = InstallPolicy.installBlockedReason(false, gear, amps, plug, sw);
        check(reason.equals(r), message + ": got " + r);
    }

    private static void installGate() {
        String charging = "vehicle is charging";
        allowed(PARK, 0f, 0, CHARGE_OFF, "parked and idle");
        allowed(null, null, null, null, "no CarService: nothing to block on");
        blocked(DRIVE, 0f, 0, CHARGE_OFF, "not in Park (gear=8)", "drive");
        blocked(REVERSE, null, null, null, "not in Park (gear=2)", "reverse");
        blocked(NEUTRAL, null, null, null, "not in Park (gear=1)", "neutral");
        blocked(0, null, null, null, "not in Park (gear=0)", "unknown gear value");
        blocked(DRIVE, 10f, 3, CHARGE_ON, "not in Park (gear=8)", "gear is reported first");

        blocked(PARK, 0f, 0, CHARGE_ON, charging, "charge switch on");
        blocked(PARK, null, null, CHARGE_ON, charging, "charge switch on, nothing else read");
        blocked(null, null, null, CHARGE_ON, charging, "charging blocks without a gear");
        allowed(PARK, 0f, 3, CHARGE_MID, "plugged in, session not flowing");
        allowed(PARK, null, 3, null, "plugged in, no current reading");

        blocked(PARK, 6.5f, 3, CHARGE_OFF, charging, "current with the plug in");
        blocked(PARK, 6.5f, null, null, charging, "current, plug unknown");
        allowed(PARK, 6.5f, 0, CHARGE_OFF, "current without a plug is not charging");
        allowed(PARK, 0.5f, 3, CHARGE_OFF, "at the noise limit");
        allowed(PARK, -20f, 3, CHARGE_OFF, "negative current");
        allowed(PARK, Float.NaN, 3, CHARGE_OFF, "unreadable current");

        check(InstallPolicy.installBlockedReason(true, DRIVE, 50f, 3, CHARGE_ON) == null,
              "force overrides gear and charging");
        check(InstallPolicy.installBlockedReason(true, null, null, null, null) == null,
              "force with no readings");
    }

    public static void main(String[] args) {
        check(InstallPolicy.skipSameVersion("com.geely.drivemem", true, 5, 5), "same app, same version: skipped");
        check(!InstallPolicy.skipSameVersion("com.geely.drivemem", true, 5, 6), "a newer version installs");
        check(!InstallPolicy.skipSameVersion("com.geely.drivemem", false, 0, 5), "not installed: installs");
        check(!InstallPolicy.skipSameVersion("com.geely.installer", true, 1, 1),
              "an installer left on the car never blocks the next one");
        installGate();
        System.out.println("InstallPolicyTest OK");
    }
}
