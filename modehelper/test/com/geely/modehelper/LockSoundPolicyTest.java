package com.geely.modehelper;

/** Dependency-free test runner for {@link LockSoundPolicy}. */
public final class LockSoundPolicyTest {
    private static final String DIR = "/data/user/0/com.geely.modehelper/files";
    private static final String OEM = "/vendor/etc/carlock/haoyunbugu.wav";

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    public static void main(String[] args) {
        LockSoundPolicy.Decision d = LockSoundPolicy.decide(OEM, null, "flatulence", DIR);
        check(eq(DIR + "/lock_flatulence.wav", d.write), "a sound must point the setting at our file");
        check(eq(OEM, d.savedPrev), "the car's own sound must be remembered");

        d = LockSoundPolicy.decide(DIR + "/lock_flatulence.wav", OEM, "flatus", DIR);
        check(eq(DIR + "/lock_flatus.wav", d.write), "switching sounds must write the new file");
        check(eq(OEM, d.savedPrev), "switching sounds must keep the car's sound, not ours");

        d = LockSoundPolicy.decide("/data/data/com.geely.modehelper/files/lock_flatus.wav", null, "flatulence", DIR);
        check(d.savedPrev == null, "our file under /data/data must count as ours too");

        d = LockSoundPolicy.decide(DIR + "/lock_flatus.wav", OEM, "car", DIR);
        check(eq(OEM, d.write), "car must restore the remembered sound");
        check(d.savedPrev == null, "car must forget the remembered sound");

        d = LockSoundPolicy.decide(DIR + "/lock_flatus.wav", null, "car", DIR);
        check(eq(LockSoundPolicy.OEM_DEFAULT, d.write), "car with nothing remembered must use the car's default");

        d = LockSoundPolicy.decide(DIR + "/lock_flatus.wav", DIR + "/lock_flatulence.wav", "car", DIR);
        check(eq(LockSoundPolicy.OEM_DEFAULT, d.write), "car must never restore one of our own files");

        d = LockSoundPolicy.decide(OEM, null, "car", DIR);
        check(d.write == null, "car must not touch a sound chosen in the car's Settings");

        d = LockSoundPolicy.decide(null, null, "flatus", DIR);
        check(d.savedPrev == null, "an empty setting must not be remembered");
        d = LockSoundPolicy.decide("null", null, "flatus", DIR);
        check(d.savedPrev == null, "the string \"null\" must not be remembered");

        check(LockSoundPolicy.decide(OEM, null, "../../etc/passwd", DIR) == null, "unknown ids must be refused");
        check(LockSoundPolicy.decide(OEM, null, null, DIR) == null, "a missing id must be refused");
    }
}
