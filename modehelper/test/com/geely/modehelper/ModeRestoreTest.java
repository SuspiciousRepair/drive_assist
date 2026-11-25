package com.geely.modehelper;

/** Dependency-free test runner for {@link ModeRestore} and the on/off edges in {@link RestorePolicy}. */
public final class ModeRestoreTest {
    private static final int ECO = 1, COMFORT = 2, SPORT = 3;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static boolean wrote(ModeRestore.Action a, int want) {
        return a != null && a.write != null && a.write == want && a.learn == null;
    }

    private static boolean learned(ModeRestore.Action a, int want) {
        return a != null && a.learn != null && a.learn == want && a.write == null;
    }

    private static boolean none(ModeRestore.Action a) {
        return a != null && a.write == null && a.learn == null;
    }

    public static void main(String[] args) {
        // #130: the car's on signal
        check(RestorePolicy.turnedOn(2, 4), "2 -> 4 is the car switched on");
        check(!RestorePolicy.turnedOn(null, 4), "first reading is no edge");
        check(!RestorePolicy.turnedOn(4, 4), "still on is no edge");
        check(!RestorePolicy.turnedOn(2, null), "unreadable is no edge");
        check(RestorePolicy.turnedOff(4, 2), "4 -> 2 is the car switched off");
        check(!RestorePolicy.turnedOff(2, 2), "still off is no edge");
        check(!RestorePolicy.turnedOff(null, 2), "first reading is no edge");
        // seen on the car 2026-11-19: 2 -> 3 -> 4 at switch-on
        check(RestorePolicy.turnedOn(3, 4), "3 -> 4 is the car switched on");
        check(RestorePolicy.knownOff(2), "2: car off, do not write");
        check(RestorePolicy.knownOff(3), "3: car not on yet, do not write");
        check(!RestorePolicy.knownOff(4), "4: car on");
        check(!RestorePolicy.knownOff(null), "property missing: not off, the wake window still works");

        // not armed, matches saved: no write, no learn
        ModeRestore r = new ModeRestore();
        check(none(r.observe(ECO, ECO, true, false, 0)), "not armed and matching: none");

        // #130: car started in Comfort, saved Eco -> written in Park
        r = new ModeRestore();
        r.arm(0);
        check(wrote(r.observe(COMFORT, ECO, true, false, 0), ECO), "reset value in Park: write saved");
        check(none(r.observe(COMFORT, ECO, true, false, 4_000)), "write not settled yet: wait");
        check(wrote(r.observe(COMFORT, ECO, true, false, 8_000), ECO), "still reset after settle: write again");
        check(none(r.observe(ECO, ECO, true, false, 12_000)), "matches: no write");
        check(r.isPending(), "match is held against a late car default");
        // the v0.4.9 race: the car puts its default back a few seconds later
        check(wrote(r.observe(COMFORT, ECO, true, false, 16_000), ECO), "late default in the hold: write again");
        check(none(r.observe(ECO, ECO, true, false, 20_000)), "matches again");
        r.observe(ECO, ECO, true, false, 20_000 + ModeRestore.HOLD_MS - 1);
        check(r.isPending(), "still in the hold");
        r.observe(ECO, ECO, true, false, 20_000 + ModeRestore.HOLD_MS);
        check(!r.isPending(), "held long enough: restore ends");

        // #201: after restore ends, a steady external change is learned
        check(none(r.observe(SPORT, ECO, false, false, 60_000)), "first tick of Sport: wait for stability");
        check(none(r.observe(SPORT, ECO, false, false, 64_000)), "still stabilizing");
        check(learned(r.observe(SPORT, ECO, false, false, 60_000 + ModeRestore.LEARN_STABLE_MS), SPORT),
            "steady Sport learned");
        check("learned " + SPORT, r.takeNote());
        // once saved matches Sport, no repeated learns
        check(none(r.observe(SPORT, SPORT, false, false, 80_000)), "already saved as Sport: none");

        // never written outside Park; the restore waits for Park
        r = new ModeRestore();
        r.arm(0);
        check(none(r.observe(COMFORT, ECO, false, false, 0)), "not parked: no write");
        check(r.isPending(), "not parked: still waiting");
        check(wrote(r.observe(COMFORT, ECO, true, false, 30_000), ECO), "back in Park: write");

        // the driver picks another mode in the car's screen during restore: stop fighting and learn it
        r = new ModeRestore();
        r.arm(0);
        check(wrote(r.observe(COMFORT, ECO, true, false, 0), ECO), "first write");
        check(none(r.observe(SPORT, ECO, true, false, ModeRestore.SETTLE_MS)), "driver picked Sport");
        check(!r.isPending(), "driver pick ends the restore");
        check(r.takeNote().startsWith("driver picked"), "the log says why");
        check(r.takeNote() == null, "note is read once");
        // after LEARN_STABLE_MS, the driver's pick is learned
        check(learned(r.observe(SPORT, ECO, true, false, ModeRestore.SETTLE_MS + ModeRestore.LEARN_STABLE_MS), SPORT),
            "driver pick during restore is learned once steady");

        // the car puts a second default over our write before it settles:
        // that is the car, not the driver (review of next, 2026-11-20)
        r = new ModeRestore();
        r.arm(0);
        check(wrote(r.observe(SPORT, ECO, true, false, 0), ECO), "car reset to Sport: write Eco");
        check(none(r.observe(COMFORT, ECO, true, false, 4_000)), "Comfort while our write lands: wait");
        check(r.isPending(), "Comfort while our write lands is not a driver pick");
        check(wrote(r.observe(COMFORT, ECO, true, false, 8_000), ECO), "Comfort is the car's new default: write Eco again");
        check(none(r.observe(ECO, ECO, true, false, 12_000)), "matches");
        check(r.isPending(), "held");

        // a new value after the write settled is still the driver's pick
        r = new ModeRestore();
        r.arm(0);
        check(wrote(r.observe(SPORT, ECO, true, false, 0), ECO), "first write");
        check(none(r.observe(COMFORT, ECO, true, false, ModeRestore.SETTLE_MS)), "Comfort after settle");
        check(!r.isPending(), "after settle a third value is the driver's pick");

        // #201: time limit gives up AND must NOT learn the un-restored car reset
        r = new ModeRestore();
        r.arm(0);
        check(none(r.observe(COMFORT, ECO, false, false, ModeRestore.RESTORE_MS + 1)), "too late: no write");
        check(!r.isPending(), "too late: restore ends");
        check(none(r.observe(COMFORT, ECO, false, false, ModeRestore.RESTORE_MS + 30_000)),
            "un-restored Comfort must not overwrite saved Eco");
        // but if the driver subsequently picks Sport, that IS learned
        check(none(r.observe(SPORT, ECO, false, false, ModeRestore.RESTORE_MS + 40_000)), "driver picks Sport");
        check(learned(r.observe(SPORT, ECO, false, false, ModeRestore.RESTORE_MS + 40_000 + ModeRestore.LEARN_STABLE_MS), SPORT),
            "driver pick after give-up is learned");

        // a car that never takes the write: at most MAX_WRITES, and un-restored value is not learned
        r = new ModeRestore();
        r.arm(0);
        int writes = 0;
        long lastT = 0;
        for (long t = 0; t < ModeRestore.RESTORE_MS; t += 4_000) {
            lastT = t;
            ModeRestore.Action a = r.observe(COMFORT, ECO, true, false, t);
            if (a.write != null) writes++;
            check(a.learn == null, "must not learn un-restored Comfort after MAX_WRITES");
        }
        check(writes == ModeRestore.MAX_WRITES, "writes capped at MAX_WRITES, got " + writes);
        check(!r.isPending(), "gave up after the cap");
        check(none(r.observe(COMFORT, ECO, true, false, lastT + 30_000)),
            "still must not learn un-restored Comfort");

        // #201: fresh install (saved == null) exits restore and learns the car's steady mode
        r = new ModeRestore();
        r.arm(0);
        check(none(r.observe(COMFORT, null, true, false, 0)), "first reading with no saved value: start stabilizing");
        check(!r.isPending(), "nothing to restore when saved is null");
        check(none(r.observe(COMFORT, null, true, false, 4_000)), "not yet stable");
        check(learned(r.observe(COMFORT, null, true, false, ModeRestore.LEARN_STABLE_MS), COMFORT),
            "fresh install learns car's current steady mode");

        // #201: shutdown / cancel drops an unconfirmed learn candidate (reset right before car-off)
        r = new ModeRestore();
        check(none(r.observe(COMFORT, SPORT, true, false, 100_000)), "car resets to Comfort right before off");
        r.cancel();
        check(none(r.observe(COMFORT, SPORT, true, false, 100_000 + ModeRestore.LEARN_STABLE_MS)),
            "cancelled candidate must restart stability timer, not learn immediately");

        // #201: temporary boost (Turbo / Strong-regen toggle) suppresses learning and stops restore
        r = new ModeRestore();
        r.arm(0);
        check(none(r.observe(SPORT, ECO, false, true, 0)), "boost active: no write, no learn");
        check(!r.isPending(), "boost active stops pending restore");
        check(none(r.observe(SPORT, ECO, false, true, 20_000)), "held for 20s: still not learned");
        // boost ends and car reverts to Eco
        check(none(r.observe(ECO, ECO, false, false, 24_000)), "back to Eco after boost: none");

        // #201: manual save in Drive Assist cancels pending restore and resets candidate/ignore
        r = new ModeRestore();
        r.arm(0);
        check(wrote(r.observe(COMFORT, ECO, true, false, 0), ECO), "first write");
        r.onManualSave();
        check(!r.isPending(), "manual save stops pending restore");
        check(none(r.observe(SPORT, SPORT, true, false, 4_000)), "manual Sport matches saved: none");

        // the car going off cancels; the next start re-arms
        r = new ModeRestore();
        r.arm(0);
        r.cancel();
        check(none(r.observe(COMFORT, ECO, true, false, 0)), "cancelled: no write");
        r.arm(100_000);
        check(wrote(r.observe(COMFORT, ECO, true, false, 100_000), ECO), "re-armed at the next start: write");

        // unreadable value: nothing, and resets any candidate
        r = new ModeRestore();
        r.arm(0);
        check(none(r.observe(null, ECO, true, false, 0)), "unreadable: no write");
        check(r.isPending(), "unreadable: still waiting");

        System.out.println("ModeRestoreTest: OK");
    }

    private static void check(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " got " + actual);
    }
}
