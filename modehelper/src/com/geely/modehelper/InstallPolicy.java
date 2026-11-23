package com.geely.modehelper;

/** What Installer skips. Plain Java, so it is tested on a desktop JVM. */
final class InstallPolicy {
    static final String INSTALLER_PKG = "com.geely.installer";

    private InstallPolicy() {}

    /** Skip a payload already installed at this versionCode, except the
     * installer: it only carries Drive Assist and modehelper, and one left on
     * the car (its self-removal failed) must not block the next one. On
     * 2026-11-23 an installer left at v1 made every later update over the
     * air a "skipped, already at versionCode 1". */
    static boolean skipSameVersion(String pkg, boolean installed, long curVc, long newVc) {
        return installed && curVc == newVc && !INSTALLER_PKG.equals(pkg);
    }
}
