package com.geely.modehelper;

/** Small dependency-free test runner for {@link MotionStall}. */
public final class MotionStallTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // A healthy decoder: every key frame answered, some a frame late.
        MotionStall ok = new MotionStall();
        for (int i = 0; i < 1000; i++) {
            ok.offered();
            check(!ok.stalled(), "a decoder that answers must never stall");
            if (i % 3 != 0) ok.analysed();
        }

        // A decoder that stops: given up at LIMIT key frames, not before.
        MotionStall dead = new MotionStall();
        dead.offered();
        dead.analysed();
        for (int i = 1; i < MotionStall.LIMIT; i++) {
            dead.offered();
            check(!dead.stalled(), "stalled after only " + i + " key frames");
        }
        dead.offered();
        check(dead.stalled(), "must stall at LIMIT key frames with no answer");

        // One that never gave a picture at all stalls the same way.
        MotionStall never = new MotionStall();
        for (int i = 0; i < MotionStall.LIMIT; i++) never.offered();
        check(never.stalled(), "a decoder that never answers must stall");

        // No key frames (the car is off) is not a stall, however long.
        check(!new MotionStall().stalled(), "silence with nothing offered is not a stall");

        // An answer after the stall clears it.
        dead.analysed();
        check(!dead.stalled(), "an answer clears the count");
        System.out.println("MotionStallTest OK");
    }
}
