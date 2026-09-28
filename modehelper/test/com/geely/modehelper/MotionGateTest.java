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

        // shrink(): padded rows, a 2-byte pixel stride and a crop offset, as a
        // hardware decoder's luma plane can have. Pixel value = x + 10*y.
        int rowStride = 40, pixelStride = 2, left = 2, top = 1;
        java.nio.ByteBuffer plane = java.nio.ByteBuffer.allocate(rowStride * 12);
        for (int y = 0; y < 10; y++)
            for (int x = 0; x < 8; x++)
                plane.put((top + y) * rowStride + (left + x) * pixelStride, (byte) (x + 10 * y));
        plane.position(0);
        byte[] small = new byte[4 * 5];
        MotionGate.shrink(plane, rowStride, pixelStride, left, top, 8, 10, small, 4, 5);
        for (int y = 0; y < 5; y++)
            for (int x = 0; x < 4; x++)
                check(small[y * 4 + x] == (byte) (2 * x + 10 * (2 * y)), "shrink at " + x + "," + y);
        check(plane.position() == 0, "shrink moved the buffer");

        // What moves all the time (leaves in the wind) is learned and left
        // out; something new beside it still starts an event.
        MotionGate g = new MotionGate(10, 10, 10, 8, 90, 2, 3);
        byte[] even = new byte[100], odd = new byte[100];
        for (int i = 0; i < 9; i++) odd[(i / 3) * 10 + (i % 3)] = (byte) 200;   // 3x3 "leaves"
        g.accept(even);
        for (int i = 0; i < 40; i++) g.accept(i % 2 == 0 ? odd : even);
        for (int i = 0; i < 20; i++)
            check(!g.accept(i % 2 == 0 ? odd : even).active, "flickering leaves still an event");
        byte[] person = even.clone(), personLeaves = odd.clone();
        for (int i = 0; i < 9; i++) {
            person[60 + (i / 3) * 10 + 5 + (i % 3)] = (byte) 200;
            personLeaves[60 + (i / 3) * 10 + 5 + (i % 3)] = (byte) 200;
        }
        g.accept(personLeaves);
        check(g.accept(person).began, "a new object beside the leaves must still start an event");
    }
}
