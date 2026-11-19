package com.geely.modehelper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/** The pure part of {@link OemText}: what is allowed, and the root shell
 * commands. No Android, so tools/verify.sh tests it on the desktop.
 *
 * Every value that goes into a command is checked against a strict pattern
 * first (package names, /system paths, our own file paths). */
final class OemTextPolicy {
    private OemTextPolicy() {}

    /** Where the patched copies live on the car. Deleting it (and a reboot,
     * or {@link #wipeCommand}) gives the car its factory text back. */
    static final String DIR = "/data/local/oem-i18n";
    /** How /proc/self/mountinfo names our sources: relative to the /data mount. */
    static final String MOUNT_ROOT = "/local/oem-i18n/";
    static final String MOUNTED = "OEMTEXT_MOUNTED";
    static final String REMOVED = "OEMTEXT_REMOVED";
    static final String WIPED = "OEMTEXT_WIPED";

    private static final Pattern PKG = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    private static final Pattern FILE = Pattern.compile("apps/[A-Za-z0-9_][A-Za-z0-9_.-]*\\.json");
    private static final Pattern SYSTEM_APK =
        Pattern.compile("/system/(app|priv-app)/[A-Za-z0-9_][A-Za-z0-9_.-]*/[A-Za-z0-9_][A-Za-z0-9_.-]*\\.apk");
    private static final Pattern STAGED = Pattern.compile("/data/[A-Za-z0-9_][A-Za-z0-9_./-]*\\.apk");
    private static final Pattern LANG = Pattern.compile("[a-z]{2}");

    static boolean validSha(String s) { return s != null && s.matches("[0-9a-f]{64}"); }

    /** SHA-256 of a file, read in pieces (the Settings app is 105 MB). */
    static String sha256(java.io.File f) throws java.io.IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] d) {
        StringBuilder s = new StringBuilder(64);
        for (byte b : d) s.append(String.format("%02x", b & 0xff));
        return s.toString();
    }

    static boolean validPackage(String s) { return s != null && s.length() <= 200 && PKG.matcher(s).matches(); }
    static boolean validFile(String s) { return s != null && !s.contains("..") && FILE.matcher(s).matches(); }
    static boolean validSystemApk(String s) { return s != null && !s.contains("..") && SYSTEM_APK.matcher(s).matches(); }
    static boolean validStaged(String s) { return s != null && !s.contains("..") && STAGED.matcher(s).matches(); }
    static boolean validLang(String s) { return s != null && LANG.matcher(s).matches(); }

    /** The copy's path in {@link #DIR}: the same file name as on /system. */
    static String copyPath(String systemApk) {
        return DIR + "/" + systemApk.substring(systemApk.lastIndexOf('/') + 1);
    }

    // Removes every mount on `target`, however many there are.
    private static String unmountLoop(String target) {
        return "while grep -q ' " + target + " ' /proc/self/mountinfo; do umount -l " + target + " || break; done";
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new IllegalArgumentException("refused: " + what);
    }

    /** Moves our freshly patched copy into {@link #DIR} and mounts it over
     * the /system apk. The app is stopped before and after, so it opens the
     * copy the next time it starts. Prints {@link #MOUNTED} on success. */
    static String mountCommand(String staged, String systemApk, String pkg) {
        check(validStaged(staged), staged);
        check(validSystemApk(systemApk), systemApk);
        check(validPackage(pkg), pkg);
        String f = copyPath(systemApk);
        return "am force-stop " + pkg + "; " + unmountLoop(systemApk)
            + "; mkdir -p " + DIR + " && chmod 755 " + DIR
            + " && mv -f " + staged + " " + f + " && chmod 644 " + f
            + " && chcon u:object_r:system_file:s0 " + f
            + " && mount -o bind " + f + " " + systemApk
            + " && am force-stop " + pkg + " && echo " + MOUNTED;
    }

    /** Takes one app's copy away (after it crashed, or when the app is not
     * the known file). Prints {@link #REMOVED}. */
    static String unmountCommand(String systemApk, String pkg) {
        check(validSystemApk(systemApk), systemApk);
        check(validPackage(pkg), pkg);
        return unmountLoop(systemApk) + "; rm -f " + copyPath(systemApk)
            + "; am force-stop " + pkg + "; echo " + REMOVED;
    }

    /** Lists our mounts: one "source target" line each. */
    static String listCommand() {
        return "grep ' " + MOUNT_ROOT + "' /proc/self/mountinfo | cut -d' ' -f4,5";
    }

    /** The /system targets in {@link #listCommand}'s output. */
    static List<String> parseList(String out) {
        List<String> targets = new ArrayList<>();
        if (out == null) return targets;
        for (String line : out.split("\n")) {
            String[] f = line.trim().split(" ");
            if (f.length == 2 && f[0].startsWith(MOUNT_ROOT) && f[1].startsWith("/system/")) targets.add(f[1]);
        }
        return targets;
    }

    /** Removes every mount whose source is in {@link #DIR}, stops the apps,
     * and deletes the folder. Prints {@link #WIPED}. */
    static String wipeCommand(Collection<String> pkgs) {
        StringBuilder s = new StringBuilder();
        s.append("for m in $(grep ' ").append(MOUNT_ROOT)
         .append("' /proc/self/mountinfo | cut -d' ' -f5); do umount -l $m; done; ");
        // Twice: a target mounted twice shows up again after the first pass.
        s.append("for m in $(grep ' ").append(MOUNT_ROOT)
         .append("' /proc/self/mountinfo | cut -d' ' -f5); do umount -l $m; done; ");
        for (String p : pkgs) if (validPackage(p)) s.append("am force-stop ").append(p).append("; ");
        s.append("rm -rf ").append(DIR).append("; echo ").append(WIPED);
        return s.toString();
    }

    /** The package a DropBox crash entry is about ("Process: <pkg>" on its
     * first line), or null. */
    static String crashedPackage(String entryText) {
        if (entryText == null || !entryText.startsWith("Process: ")) return null;
        int end = entryText.indexOf('\n');
        String p = (end < 0 ? entryText.substring(9) : entryText.substring(9, end)).trim();
        // A process can be "pkg:service"; the package is before the colon.
        int colon = p.indexOf(':');
        if (colon > 0) p = p.substring(0, colon);
        return validPackage(p) ? p : null;
    }
}
