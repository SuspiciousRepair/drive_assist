package com.geely.modehelper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/** Dependency-free test runner for {@link OemTextPolicy}, and for the text
 * bundled in modehelper/assets/oem-text (run from the repo root). */
public final class OemTextPolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static boolean refused(Runnable r) {
        try { r.run(); return false; } catch (IllegalArgumentException e) { return true; }
    }

    public static void main(String[] args) throws Exception {
        String apk = "/system/app/AutoEnergy/AutoEnergy.apk";
        String pkg = "com.flyme.auto.energy";
        String staged = "/data/user/0/com.geely.modehelper/files/oem-text-stage/AutoEnergy.apk";

        check(OemTextPolicy.validPackage(pkg), "a normal package passes");
        check(!OemTextPolicy.validPackage("com.x; reboot"), "a package with shell text is refused");
        check(!OemTextPolicy.validPackage("nodot"), "a package needs a dot");
        check(OemTextPolicy.validSystemApk(apk), "a /system/app apk passes");
        check(OemTextPolicy.validSystemApk("/system/priv-app/X/X.apk"), "a /system/priv-app apk passes");
        check(!OemTextPolicy.validSystemApk("/data/app/x-1/base.apk"), "an updated (/data) app is refused");
        check(!OemTextPolicy.validSystemApk("/system/app/../../data/x/x.apk"), "a path with .. is refused");
        check(!OemTextPolicy.validSystemApk("/system/app/A B/A.apk"), "a path with a space is refused");
        check(OemTextPolicy.validFile("apps/com.flyme.auto.energy.json"), "a file in apps/ passes");
        check(!OemTextPolicy.validFile("apps/../x.json"), "a file with .. is refused");
        check(OemTextPolicy.validLang("pt") && !OemTextPolicy.validLang("pt-BR") && !OemTextPolicy.validLang(""),
            "a language is two lower-case letters");

        String abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        File tmp = File.createTempFile("oemsha", ".bin");
        Files.write(tmp.toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
        check(OemTextPolicy.sha256(tmp).equals(abc), "sha256 of a file");
        tmp.delete();
        check(OemTextPolicy.validSha(abc) && !OemTextPolicy.validSha(abc.toUpperCase()) && !OemTextPolicy.validSha("ab"),
            "a SHA-256 is 64 lower-case hex digits");

        check(OemTextPolicy.copyPath(apk).equals("/data/local/oem-i18n/AutoEnergy.apk"), "copy path");

        String m = OemTextPolicy.mountCommand(staged, apk, pkg);
        check(m.contains("mount -o bind /data/local/oem-i18n/AutoEnergy.apk " + apk), "mount binds the copy over the apk");
        check(m.contains("chcon u:object_r:system_file:s0"), "the copy gets the system_file label");
        check(m.endsWith("echo " + OemTextPolicy.MOUNTED), "mount reports success last");
        check(m.indexOf("am force-stop") < m.indexOf("mount -o bind"), "the app stops before the mount");
        check(refused(() -> OemTextPolicy.mountCommand(staged, apk, "x; rm -rf /")), "mount refuses a bad package");
        check(refused(() -> OemTextPolicy.mountCommand("/tmp/x.apk", apk, pkg)), "mount refuses a staged file outside /data");
        check(refused(() -> OemTextPolicy.mountCommand(staged, "/vendor/app/X/X.apk", pkg)), "mount refuses a non-/system target");

        String u = OemTextPolicy.unmountCommand(apk, pkg);
        check(u.contains("umount -l " + apk) && u.contains("rm -f /data/local/oem-i18n/AutoEnergy.apk"), "unmount");

        String w = OemTextPolicy.wipeCommand(Arrays.asList(pkg, "bad; reboot"));
        check(w.contains("rm -rf /data/local/oem-i18n") && w.endsWith(OemTextPolicy.WIPED), "wipe deletes the folder");
        check(w.contains("am force-stop " + pkg) && !w.contains("reboot"), "wipe stops only valid packages");

        List<String> l = OemTextPolicy.parseList(
            "/local/oem-i18n/AutoEnergy.apk /system/app/AutoEnergy/AutoEnergy.apk\n"
            + "/local/oem-i18n/oat/X.odex /system/app/X/oat/arm64/X.odex\n"
            + "/something/else /system/app/Y/Y.apk\n\n");
        check(l.equals(Arrays.asList("/system/app/AutoEnergy/AutoEnergy.apk", "/system/app/X/oat/arm64/X.odex")),
            "parseList keeps only our mounts: " + l);
        check(OemTextPolicy.parseList(null).isEmpty(), "parseList of nothing");

        check(pkg.equals(OemTextPolicy.crashedPackage("Process: com.flyme.auto.energy\nFlags: 0x1\n")), "crash package");
        check("com.flyme.auto.settings".equals(OemTextPolicy.crashedPackage("Process: com.flyme.auto.settings:remote\n")),
            "a process name with :suffix gives the package");
        check(OemTextPolicy.crashedPackage("Subject: x") == null, "no Process line gives null");
        check(OemTextPolicy.crashedPackage(null) == null, "null gives null");

        check(OemTextPolicy.validCommit("a6d9ad6fea7f0817605ebdb50ce96788b38fcc1b"), "a full commit hash");
        check(!OemTextPolicy.validCommit("a6d9ad6") && !OemTextPolicy.validCommit("<html>"), "anything else is not");
        check(OemTextPolicy.LATEST_URL.startsWith("https://api.github.com/repos/SuspiciousRepair/integration-oem-translations/")
            && OemTextPolicy.RAW_URL.startsWith("https://raw.githubusercontent.com/SuspiciousRepair/integration-oem-translations/"),
            "the text comes only from the integration repo, over https");
    }
}
