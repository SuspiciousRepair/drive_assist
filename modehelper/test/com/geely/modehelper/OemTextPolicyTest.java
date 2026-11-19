package com.geely.modehelper;

import java.util.Arrays;
import java.util.List;

/** Dependency-free test runner for {@link OemTextPolicy}. */
public final class OemTextPolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static boolean refused(Runnable r) {
        try { r.run(); return false; } catch (IllegalArgumentException e) { return true; }
    }

    public static void main(String[] args) {
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
        check(!OemTextPolicy.validFile("https://evil/x.json"), "a URL as a file is refused");
        check(OemTextPolicy.validLang("pt") && !OemTextPolicy.validLang("pt-BR") && !OemTextPolicy.validLang(""),
            "a language is two lower-case letters");
        check(OemTextPolicy.validName("wifi_level_strong") && !OemTextPolicy.validName("a b"), "string names");
        check(!OemTextPolicy.validText("") && OemTextPolicy.validText("Forte"), "an empty text is refused");
        check(!OemTextPolicy.validText(new String(new char[OemTextPolicy.MAX_TEXT_CHARS + 1])), "a huge text is refused");

        List<String> systems = Arrays.asList("SW0E22H0128H111100000");
        check(OemTextPolicy.systemAllowed("SW0E22H0128H111100000", systems), "1111 is allowed when listed");
        check(!OemTextPolicy.systemAllowed("SW0E22H0306H111400000", systems), "1114 is refused when not listed");
        check(!OemTextPolicy.systemAllowed("", systems) && !OemTextPolicy.systemAllowed(null, systems),
            "an unknown system version is refused");
        check(!OemTextPolicy.systemAllowed("SW0E22H0128H111100000", null), "a manifest with no list is refused");
        check(!OemTextPolicy.systemAllowed("SW0E22H0128H1111", systems), "only an exact match counts");

        // Pins: one exact commit, and a SHA-256 for every file the car may fetch.
        check(OemTextPins.COMMIT.matches("[0-9a-f]{40}"), "the pin is a full commit hash");
        check(OemTextPolicy.BASE_URL.contains("/" + OemTextPins.COMMIT + "/") && !OemTextPolicy.BASE_URL.contains("/main/"),
            "downloads come from the pinned commit, never main");
        check(OemTextPolicy.pinnedSha256(OemTextPolicy.MANIFEST) != null, "the manifest is pinned");
        for (String[] f : OemTextPins.FILES) {
            check(f[1].matches("[0-9a-f]{64}"), "a pin is a SHA-256: " + f[0]);
            check(f[0].equals(OemTextPolicy.MANIFEST) || OemTextPolicy.validFile(f[0]), "a pinned path is valid: " + f[0]);
        }
        check(OemTextPolicy.sha256("abc".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
            .equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "sha256 of abc");
        check(!OemTextPolicy.hashOk(OemTextPolicy.MANIFEST, "{\"format\":1}".getBytes()), "other bytes fail the pin");
        check(!OemTextPolicy.hashOk("apps/evil.json", new byte[0]), "a file that is not pinned fails");
        check(!OemTextPolicy.hashOk(OemTextPolicy.MANIFEST, null), "no bytes fail");
        check(!OemTextPolicy.pinnedAnywhere("x".getBytes()) && !OemTextPolicy.pinnedAnywhere(null), "unpinned bytes");

        check(OemTextPolicy.copyPath(apk).equals("/data/local/oem-i18n/AutoEnergy.apk"), "copy path");

        String m = OemTextPolicy.mountCommand(staged, apk, pkg);
        check(m.contains("mount -o bind /data/local/oem-i18n/AutoEnergy.apk " + apk), "mount binds the copy over the apk");
        check(m.contains("chcon u:object_r:system_file:s0"), "the copy gets the system_file label");
        check(m.endsWith("echo " + OemTextPolicy.MOUNTED), "mount reports success last");
        check(m.indexOf("am force-stop") < m.indexOf("mount -o bind"), "the app stops before the mount");
        check(refused(() -> OemTextPolicy.mountCommand(staged, apk, "x; rm -rf /")), "mount refuses a bad package");
        check(refused(() -> OemTextPolicy.mountCommand("/tmp/x.apk", apk, pkg)), "mount refuses a staged file outside /data");
        check(refused(() -> OemTextPolicy.mountCommand(staged, "/vendor/app/X/X.apk", pkg)), "mount refuses a non-/system target");

        String r = OemTextPolicy.remountCommand(apk, pkg);
        check(r.contains(OemTextPolicy.NO_FILE) && r.contains(OemTextPolicy.MOUNTED), "remount says both outcomes");
        check(!r.contains("mv "), "remount moves nothing");

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
    }
}
