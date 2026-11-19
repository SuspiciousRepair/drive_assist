package com.geely.modehelper;

/** Dependency-free test runner for {@link ModeRestore} and the on/off edges in {@link RestorePolicy}. */
public final class ModeRestoreTest {
    private static final int ECO = 1, COMFORT = 2, SPORT = 3;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static boolean is(Integer v, int want) {
        return v != null && v == want;
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

        // not armed: never writes
        ModeRestore r = new ModeRestore();
        check(r.observe(COMFORT, ECO, true, 0) == null, "not armed: no write");

        // #130: car started in Comfort, saved Eco -> written in Park
        r = new ModeRestore();
        r.arm(0);
        check(is(r.observe(COMFORT, ECO, true, 0), ECO), "reset value in Park: write saved");
        check(r.observe(COMFORT, ECO, true, 4_000) == null, "write not settled yet: wait");
        check(is(r.observe(COMFORT, ECO, true, 8_000), ECO), "still reset after settle: write again");
        check(r.observe(ECO, ECO, true, 12_000) == null, "matches: no write");
        check(r.isPending(), "match is held against a late car default");
        // the v0.4.9 race: the car puts its default back a few seconds later
        check(is(r.observe(COMFORT, ECO, true, 16_000), ECO), "late default in the hold: write again");
        check(r.observe(ECO, ECO, true, 20_000) == null, "matches again");
        r.observe(ECO, ECO, true, 20_000 + ModeRestore.HOLD_MS - 1);
        check(r.isPending(), "still in the hold");
        r.observe(ECO, ECO, true, 20_000 + ModeRestore.HOLD_MS);
        check(!r.isPending(), "held long enough: restore ends");
        check(r.observe(SPORT, ECO, true, 60_000) == null, "after the end a change stays");

        // never written outside Park; the restore waits for Park
        r = new ModeRestore();
        r.arm(0);
        check(r.observe(COMFORT, ECO, false, 0) == null, "not parked: no write");
        check(r.isPending(), "not parked: still waiting");
        check(is(r.observe(COMFORT, ECO, true, 30_000), ECO), "back in Park: write");

        // the driver picks another mode in the car's screen: stop fighting it
        r = new ModeRestore();
        r.arm(0);
        check(is(r.observe(COMFORT, ECO, true, 0), ECO), "first write");
        check(r.observe(SPORT, ECO, true, ModeRestore.SETTLE_MS) == null, "driver picked Sport");
        check(!r.isPending(), "driver pick ends the restore");
        check(r.takeNote().startsWith("driver picked"), "the log says why");
        check(r.takeNote() == null, "note is read once");

        // time limit
        r = new ModeRestore();
        r.arm(0);
        check(r.observe(COMFORT, ECO, false, ModeRestore.RESTORE_MS + 1) == null, "too late: no write");
        check(!r.isPending(), "too late: restore ends");

        // a car that never takes the write: at most MAX_WRITES
        r = new ModeRestore();
        r.arm(0);
        int writes = 0;
        for (long t = 0; t < ModeRestore.RESTORE_MS; t += 4_000) {
            if (r.observe(COMFORT, ECO, true, t) != null) writes++;
        }
        check(writes == ModeRestore.MAX_WRITES, "writes capped at MAX_WRITES, got " + writes);
        check(!r.isPending(), "gave up after the cap");

        // the car going off cancels; the next start re-arms
        r = new ModeRestore();
        r.arm(0);
        r.cancel();
        check(r.observe(COMFORT, ECO, true, 0) == null, "cancelled: no write");
        r.arm(100_000);
        check(is(r.observe(COMFORT, ECO, true, 100_000), ECO), "re-armed at the next start: write");

        // unreadable value: nothing
        r = new ModeRestore();
        r.arm(0);
        check(r.observe(null, ECO, true, 0) == null, "unreadable: no write");
        check(r.isPending(), "unreadable: still waiting");

        System.out.println("ModeRestoreTest: OK");
    }
}
