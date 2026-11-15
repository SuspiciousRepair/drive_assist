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
        LockSoundPolicy.Decision d = LockSoundPolicy.decide(OEM, null, "fart", DIR, false);
        check(eq(DIR + "/lock_fart.wav", d.write), "fart must point the setting at our file");
        check(eq(OEM, d.savedPrev), "the car's own sound must be remembered");

        d = LockSoundPolicy.decide(DIR + "/lock_fart.wav", OEM, "custom", DIR, true);
        check(eq(DIR + "/lock_custom.wav", d.write), "custom must write the imported file");
        check(eq(OEM, d.savedPrev), "switching sounds must keep the car's sound, not ours");

        check(LockSoundPolicy.decide(OEM, null, "custom", DIR, false) == null,
            "custom with nothing imported must be refused");

        d = LockSoundPolicy.decide("/data/data/com.geely.modehelper/files/lock_custom.wav", null, "fart", DIR, true);
        check(d.savedPrev == null, "our file under /data/data must count as ours too");

        d = LockSoundPolicy.decide(DIR + "/lock_custom.wav", OEM, "car", DIR, true);
        check(eq(OEM, d.write), "car must restore the remembered sound");
        check(d.savedPrev == null, "car must forget the remembered sound");

        d = LockSoundPolicy.decide(DIR + "/lock_fart.wav", null, "car", DIR, false);
        check(eq(LockSoundPolicy.OEM_DEFAULT, d.write), "car with nothing remembered must use the car's default");

        d = LockSoundPolicy.decide(DIR + "/lock_fart.wav", DIR + "/lock_custom.wav", "car", DIR, true);
        check(eq(LockSoundPolicy.OEM_DEFAULT, d.write), "car must never restore one of our own files");

        d = LockSoundPolicy.decide(OEM, null, "car", DIR, false);
        check(d.write == null, "car must not touch a sound chosen in the car's Settings");

        d = LockSoundPolicy.decide(null, null, "fart", DIR, false);
        check(d.savedPrev == null, "an empty setting must not be remembered");
        d = LockSoundPolicy.decide("null", null, "fart", DIR, false);
        check(d.savedPrev == null, "the string \"null\" must not be remembered");

        check(LockSoundPolicy.decide(OEM, null, "../../etc/passwd", DIR, true) == null, "unknown ids must be refused");
        check(LockSoundPolicy.decide(OEM, null, null, DIR, true) == null, "a missing id must be refused");

        // The import check.
        check(eq("ok", LockSoundPolicy.checkWav(wavHead(), 264678)), "a small wav must pass");
        check(eq("ok", LockSoundPolicy.checkWav(wavHead(), LockSoundPolicy.MAX_BYTES)), "exactly 1 MB must pass");
        check(eq("too_big", LockSoundPolicy.checkWav(wavHead(), LockSoundPolicy.MAX_BYTES + 1)), "over 1 MB must fail");
        check(eq("empty", LockSoundPolicy.checkWav(wavHead(), 0)), "an empty file must fail");
        check(eq("not_wav", LockSoundPolicy.checkWav("ID3\u0004xxxxxxxxx".getBytes(), 5000)), "an mp3 must fail");
        check(eq("not_wav", LockSoundPolicy.checkWav(new byte[] {'R', 'I', 'F', 'F'}, 5000)), "a short header must fail");
        check(eq("not_wav", LockSoundPolicy.checkWav(null, 5000)), "no header must fail");
    }
}
