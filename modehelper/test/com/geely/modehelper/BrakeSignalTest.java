package com.geely.modehelper;

/** Small dependency-free test runner for {@link BrakeSignal}. */
public final class BrakeSignalTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // Only Drive Assist may report (the maker of the message's PendingIntent).
        check(BrakeSignal.fromDriveAssist("com.geely.drivemem"), "Drive Assist is believed");
        check(!BrakeSignal.fromDriveAssist(null), "a message without proof is dropped");
        check(!BrakeSignal.fromDriveAssist("com.other.app"), "another app is dropped");

        // Nothing heard: nothing shown.
        check(!new BrakeSignal().take(1_000), "nothing heard must show nothing");

        // Held: shown on every line while the repeats keep coming.
        BrakeSignal b = new BrakeSignal();
        b.report(1, 10_000);
        check(b.take(10_500), "pressed must show");
        check(b.take(11_500), "still pressed on the next line");
        b.report(1, 12_000);                       // the 2 s repeat
        check(b.take(12_500), "pressed after a repeat");
        b.report(0, 13_000);
        check(b.take(13_500), "a release between two lines: the press was in that second");
        check(!b.take(14_500), "released must show nothing on the line after");

        // A tap that begins and ends between two lines is shown once.
        BrakeSignal tap = new BrakeSignal();
        tap.report(1, 20_100);
        tap.report(0, 20_400);
        check(tap.take(21_000), "a short tap must reach one line");
        check(!tap.take(22_000), "and only one");

        // Drive Assist died with the pedal down: no repeats, no BRAKE for long.
        BrakeSignal dead = new BrakeSignal();
        dead.report(1, 30_000);
        check(dead.take(30_000 + BrakeSignal.HOLD_MS), "believed up to HOLD_MS");
        check(!dead.take(30_000 + BrakeSignal.HOLD_MS + 1), "not believed after HOLD_MS with no repeat");

        // Not known (the CAN reader went down) ends a press like a release.
        BrakeSignal gone = new BrakeSignal();
        gone.report(1, 40_000);
        gone.take(40_500);
        gone.report(-1, 41_000);
        check(gone.take(41_500), "it was pressed in that second");
        check(!gone.take(42_500), "not known must show nothing after it");
        BrakeSignal never = new BrakeSignal();
        never.report(-1, 45_000);
        never.report(0, 45_100);
        check(!never.take(45_500), "not known and released show nothing");

        // A release that comes long after the last "pressed" (Drive Assist
        // restarted) does not bring the press back.
        BrakeSignal late = new BrakeSignal();
        late.report(1, 46_000);
        late.take(46_500);
        late.report(0, 46_000 + 60_000);
        check(!late.take(46_000 + 60_500), "a stale press must not be shown at a late release");

        // An old tap nobody took (the recorder was off) is not shown later.
        BrakeSignal old = new BrakeSignal();
        old.report(1, 50_000);
        old.report(0, 50_200);
        check(!old.take(50_200 + BrakeSignal.HOLD_MS + 1), "a tap older than HOLD_MS is not shown");

        // A clock that went back cannot make a press fresh.
        BrakeSignal back = new BrakeSignal();
        back.report(1, 60_000);
        check(!back.take(59_000), "a report from the future is not believed");
        System.out.println("BrakeSignalTest OK");
    }
}
