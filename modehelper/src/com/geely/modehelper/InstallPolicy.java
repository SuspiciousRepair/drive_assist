package com.geely.modehelper;

/** What Installer skips or refuses. Plain Java, so it is tested on a desktop JVM. */
final class InstallPolicy {
    static final String INSTALLER_PKG = "com.geely.installer";

    // Copies of CarMode.GEAR_PARK and CarMode.CHARGE_ON. CarMode needs the
    // Android and car jars; this class and its test must compile without them.
    static final int GEAR_PARK = 4;
    static final int CHARGE_ON = 605028611;
    /** Below this, the charge current reading is noise. */
    private static final float CHARGING_MIN_A = 0.5f;

    private InstallPolicy() {}

    /** Why an install must not be committed now, or null if it may. A null
     * reading never blocks: a bench unit without CarService must still
     * update. Current alone does not mean charging when the plug reads
     * "not connected" (0). */
    static String installBlockedReason(boolean force, Integer gear, Float chargeCurrentA,
                                       Integer plugConnected, Integer chargeSwitch) {
        if (force) return null;
        if (gear != null && gear != GEAR_PARK) return "not in Park (gear=" + gear + ")";
        boolean switchOn = chargeSwitch != null && chargeSwitch == CHARGE_ON;
        boolean currentIn = chargeCurrentA != null && chargeCurrentA > CHARGING_MIN_A
            && (plugConnected == null || plugConnected != 0);
        return (switchOn || currentIn) ? "vehicle is charging" : null;
    }

    /** Skip a payload already installed at this versionCode, except the
     * installer: it only carries Drive Assist and modehelper, and one left on
     * the car (its self-removal failed) must not block the next one. On
     * 2026-11-23 an installer left at v1 made every later update over the
     * air a "skipped, already at versionCode 1". */
    static boolean skipSameVersion(String pkg, boolean installed, long curVc, long newVc) {
        return installed && curVc == newVc && !INSTALLER_PKG.equals(pkg);
    }
}
