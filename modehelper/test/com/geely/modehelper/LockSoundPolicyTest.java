package com.geely.modehelper;

/** Dependency-free test runner for {@link LockSoundPolicy}. */
public final class LockSoundPolicyTest {
    private static final String DIR = "/data/user/0/com.geely.modehelper/files";
    private static final String OEM = "/vendor/etc/carlock/haoyunbugu.wav";

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    private static byte[] wavHead() {
        return new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E', 'f', 'm', 't', ' '};
    }

    public static void main(String[] args) {
        final String OURS_FART = DIR + "/lock_fart.wav";
        final String OURS_CUSTOM = "/data/data/com.geely.modehelper/files/lock_custom.wav";

        // Which ids can be applied.
        check(LockSoundPolicy.valid("car", false), "car is always allowed");
        check(LockSoundPolicy.valid("fart", false), "fart is bundled, always allowed");
        check(LockSoundPolicy.valid("custom", true), "custom with an import is allowed");
        check(!LockSoundPolicy.valid("custom", false), "custom with nothing imported must be refused");
        check(!LockSoundPolicy.valid("../../etc/passwd", true), "unknown ids must be refused");
        check(!LockSoundPolicy.valid(null, true), "a missing id must be refused");

        // The car's own sounds.
        check(LockSoundPolicy.isOem(OEM), "a car sound is a car sound");
        check(LockSoundPolicy.isOem(LockSoundPolicy.OEM_DEFAULT), "the default is a car sound");
        check(!LockSoundPolicy.isOem("/vendor/etc/carlock/../../system/x.wav"), "no way out of the folder");
        check(!LockSoundPolicy.isOem("/storage/BFD4-1DEB/x.wav"), "a USB path is not a car sound");
        check(!LockSoundPolicy.isOem(OURS_FART), "ours is not a car sound");
        check(!LockSoundPolicy.isOem(null), "null is not a car sound");

        // The setting: a car sound the user picked is never touched.
        check(LockSoundPolicy.settingFor("fart", OEM, null) == null, "fart must keep the picked car sound");
        check(LockSoundPolicy.settingFor("custom", OEM, null) == null, "custom must keep the picked car sound");
        check(LockSoundPolicy.settingFor("car", OEM, null) == null, "car must keep the picked car sound");
        // ... only a setting the mount cannot cover is fixed.
        check(eq(OEM, LockSoundPolicy.settingFor("fart", OURS_FART, OEM)),
            "a setting left on our file must go back to the saved car sound");
        check(eq(LockSoundPolicy.OEM_DEFAULT, LockSoundPolicy.settingFor("fart", OURS_FART, null)),
            "with nothing saved, the car's default");
        check(eq(LockSoundPolicy.OEM_DEFAULT, LockSoundPolicy.settingFor("car", OURS_CUSTOM, OURS_FART)),
            "never give back one of our own files");
        check(eq(LockSoundPolicy.OEM_DEFAULT, LockSoundPolicy.settingFor("fart", null, null)),
            "fart with an empty setting must name a car sound");
        check(eq(LockSoundPolicy.OEM_DEFAULT, LockSoundPolicy.settingFor("custom", "/storage/BFD4-1DEB/x.wav", null)),
            "a USB path cannot be covered by the mount");
        check(LockSoundPolicy.settingFor("car", null, null) == null, "car leaves an empty setting alone");

        // What an old helper left behind.
        check(eq("fart", LockSoundPolicy.idForSetting(OURS_FART)), "old fart setting");
        check(eq("custom", LockSoundPolicy.idForSetting(OURS_CUSTOM)), "old custom setting");
        check(eq("car", LockSoundPolicy.idForSetting(OEM)), "a car sound is car");
        check(eq("car", LockSoundPolicy.idForSetting(DIR + "/lock_flatulence.wav")), "an unknown file of ours is car");
        check(eq("car", LockSoundPolicy.idForSetting(null)), "empty is car");

        // The car sound our mount goes over: the picked one.
        check(eq(OEM, LockSoundPolicy.target(OEM, null)), "the picked car sound");
        check(eq(OEM, LockSoundPolicy.target(OURS_FART, OEM)), "an old helper's saved pick");
        check(eq(LockSoundPolicy.OEM_DEFAULT, LockSoundPolicy.target(null, null)), "empty: the car's default");
        check(eq(LockSoundPolicy.OEM_DEFAULT, LockSoundPolicy.target(OURS_FART, OURS_CUSTOM)), "never one of ours");

        // The root commands.
        String mount = LockSoundPolicy.mountCommand(OURS_FART, OEM);
        check(mount.contains("mount -o bind " + LockSoundPolicy.MOUNT_FILE + " " + OEM), "mounts the copy over the pick");
        check(!mount.contains("*.wav"), "over the pick only, not every car sound");
        check(mount.contains("[ -f " + OEM + " ]"), "only over a sound that exists");
        check(mount.contains("chcon " + LockSoundPolicy.LABEL + " "), "labelled like the car's sounds");
        check(mount.indexOf("umount -l") < mount.indexOf("cp -f"), "old mounts off before the copy changes");
        check(mount.endsWith("echo " + LockSoundPolicy.MOUNTED), "says when it mounted");
        LockSoundPolicy.mountCommand(OURS_CUSTOM, LockSoundPolicy.OEM_DEFAULT);
        for (String bad : new String[] {null, "/data/local/tmp/x.wav", DIR + "/lock_x.wav",
                DIR + "/lock_fart.wav; reboot", "/data/user/0/com.geely.modehelper/files/../lock_fart.wav"}) {
            boolean refused = false;
            try { LockSoundPolicy.mountCommand(bad, OEM); } catch (IllegalArgumentException e) { refused = true; }
            check(refused, "must refuse " + bad);
        }
        for (String bad : new String[] {null, "/system/etc/hosts", OURS_FART, OEM + "; reboot",
                "/vendor/etc/carlock/../../system/x.wav"}) {
            boolean refused = false;
            try { LockSoundPolicy.mountCommand(OURS_FART, bad); } catch (IllegalArgumentException e) { refused = true; }
            check(refused, "must refuse target " + bad);
        }
        String un = LockSoundPolicy.unmountCommand();
        check(un.contains("grep ' " + LockSoundPolicy.MOUNT_ROOT + "'"), "only our mounts are taken away");
        check(un.endsWith("echo " + LockSoundPolicy.REMOVED), "says when done");

        String info = "1024 23 259:17 /local/locksound/lock.wav /vendor/etc/carlock/haoyunbugu.wav rw - ext4 x rw\n"
            + "1021 22 259:17 /local/oem-i18n/AutoEnergy.apk /system/app/AutoEnergy/AutoEnergy.apk rw - ext4 x rw\n";
        java.util.List<String> mounted = LockSoundPolicy.mountedTargets(info);
        check(mounted.size() == 1 && eq(OEM, mounted.get(0)), "lists our mount, not the others");
        check(LockSoundPolicy.mountedTargets("").isEmpty() && LockSoundPolicy.mountedTargets(null).isEmpty(), "none");

        // A new pick in the car's own Settings takes ours off.
        check(!LockSoundPolicy.pickedAnother("fart", mounted, OEM), "same pick: ours stays");
        check(LockSoundPolicy.pickedAnother("fart", mounted, "/vendor/etc/carlock/dajidali.wav"), "another pick: ours off");
        check(LockSoundPolicy.pickedAnother("custom", mounted, null), "empty setting is the default, another pick");
        check(!LockSoundPolicy.pickedAnother("car", mounted, "/vendor/etc/carlock/dajidali.wav"), "car: nothing to take off");
        check(!LockSoundPolicy.pickedAnother("fart", new java.util.ArrayList<>(), "/vendor/etc/carlock/dajidali.wav"),
            "nothing mounted (a reboot) is not a new pick");
        check(!LockSoundPolicy.pickedAnother("fart", mounted, OURS_FART), "an old helper's setting is not a pick");
        java.util.List<String> def = LockSoundPolicy.mountedTargets(
            "9 1 259:17 /local/locksound/lock.wav " + LockSoundPolicy.OEM_DEFAULT + " rw - ext4 x rw\n");
        check(!LockSoundPolicy.pickedAnother("fart", def, null), "empty setting on the default mount: ours stays");

        // The import check.
        check(eq("ok", LockSoundPolicy.checkWav(wavHead(), 264678)), "a small wav must pass");
        check(eq("ok", LockSoundPolicy.checkWav(wavHead(), LockSoundPolicy.MAX_BYTES)), "exactly 1 MB must pass");
        check(eq("too_big", LockSoundPolicy.checkWav(wavHead(), LockSoundPolicy.MAX_BYTES + 1)), "over 1 MB must fail");
        check(eq("empty", LockSoundPolicy.checkWav(wavHead(), 0)), "an empty file must fail");
        check(eq("not_wav", LockSoundPolicy.checkWav("ID3\u0004xxxxxxxxx".getBytes(), 5000)), "an mp3 must fail");
        check(eq("not_wav", LockSoundPolicy.checkWav(new byte[] {'R', 'I', 'F', 'F'}, 5000)), "a short header must fail");
        check(eq("not_wav", LockSoundPolicy.checkWav(null, 5000)), "no header must fail");
    
        // Links: http or https. Plain http is the owner's choice; no file: or content:.
        check(LockSoundPolicy.webUrl("https://example.com/LockChime.wav"), "https must pass");
        check(LockSoundPolicy.webUrl("  HTTPS://example.com/a.wav "), "scheme case and spaces around must not matter");
        check(LockSoundPolicy.webUrl("http://example.com/LockChime.wav"), "plain http must pass");
        check(!LockSoundPolicy.webUrl("file:///data/system/users/0/settings_system.xml"), "file: must be refused");
        check(!LockSoundPolicy.webUrl("content://settings/system"), "content: must be refused");
        check(!LockSoundPolicy.webUrl("example.com/LockChime.wav"), "no scheme must be refused");
        check(!LockSoundPolicy.webUrl("https:///LockChime.wav"), "no host must be refused");
        check(!LockSoundPolicy.webUrl("https://exa mple.com/a.wav"), "a broken link must be refused");
        check(!LockSoundPolicy.webUrl(null) && !LockSoundPolicy.webUrl(""), "nothing must be refused");

        // Redirects: http or https only, and the chain is short.
        String from = "https://example.com/sounds/a.wav";
        check(eq("https://cdn.example.com/a.wav",
            LockSoundPolicy.redirectTarget(from, "https://cdn.example.com/a.wav", 0)), "https to https must be followed");
        check(eq("https://example.com/b.wav",
            LockSoundPolicy.redirectTarget(from, "/b.wav", 0)), "a relative redirect stays on the same site");
        check(eq("https://example.com/sounds/c.wav",
            LockSoundPolicy.redirectTarget(from, "c.wav", 1)), "a relative path resolves against the link");
        check(eq("http://example.com/a.wav", LockSoundPolicy.redirectTarget(from, "http://example.com/a.wav", 0)),
            "a redirect to http must be followed");
        check(LockSoundPolicy.redirectTarget(from, "//example.com/a.wav", 0) != null,
            "a scheme-relative redirect keeps https");
        check(LockSoundPolicy.redirectTarget(from, "file:///sdcard/a.wav", 0) == null, "a redirect to a file must be refused");
        check(LockSoundPolicy.redirectTarget(from, null, 0) == null, "a redirect with no Location must be refused");
        check(LockSoundPolicy.redirectTarget(from, "https://cdn.example.com/a.wav", LockSoundPolicy.MAX_REDIRECTS) == null,
            "a long redirect chain must end");
        check(eq("https://example.com/a.wav",
            LockSoundPolicy.redirectTarget("http://example.com/a.wav", "https://example.com/a.wav", 0)),
            "http to https must be followed");
        check(LockSoundPolicy.redirectTarget("file:///sdcard/a.wav", "https://example.com/a.wav", 0) == null,
            "a redirect from a link we would not open must be refused");
        check(LockSoundPolicy.isRedirect(301) && LockSoundPolicy.isRedirect(302) && LockSoundPolicy.isRedirect(307)
            && LockSoundPolicy.isRedirect(308) && !LockSoundPolicy.isRedirect(200) && !LockSoundPolicy.isRedirect(304),
            "only real redirects are redirects");
        check(LockSoundPolicy.FETCH_DEADLINE_MS <= 20_000, "the whole import must end well inside the broadcast timeout");
    }
}
