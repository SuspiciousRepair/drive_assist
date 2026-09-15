package com.geely.modehelper;

/** Small dependency-free test runner for {@link MotionGate}. */
public final class MotionGateTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static byte[] frame(int size, int value) {
        byte[] out = new byte[size];
        java.util.Arrays.fill(out, (byte) value);
        return out;
    }

    public static void main(String[] args) {
        MotionGate gate = new MotionGate(10, 10, 10, 8, 90, 2, 3);
        check(!gate.accept(frame(100, 20)).active, "seed must be idle");

        byte[] object = frame(100, 20);
        for (int i = 20; i < 35; i++) object[i] = 80;
        check(!gate.accept(object).active, "one changed frame must debounce");
        check(gate.accept(object).began, "second changed frame must start event");

        // A foreground object that stops moving should end the event even if it
        // remains in view; active-state stillness is frame-to-frame.
        check(!gate.accept(object).ended, "event tail must hold");
        check(!gate.accept(object).ended, "event tail must hold again");
        check(gate.accept(object).ended, "third still frame must end event");
        check(!gate.accept(object).active, "rebased static object must not retrigger");

        gate.reset();
        gate.accept(frame(100, 20));
        check(!gate.accept(frame(100, 100)).active,
              "global exposure shift must not start an event");
    }
}
