package com.geely.modehelper;

import java.util.List;

/** Dependency-free test runner for {@link AnalysisFrameRing}. */
public final class AnalysisFrameRingTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static byte[] frame(int value) { return new byte[] { (byte) value, 9 }; }

    public static void main(String[] args) {
        AnalysisFrameRing ring = new AnalysisFrameRing(3, 2);
        ring.add(10, frame(1));
        ring.add(20, frame(2));
        ring.add(30, frame(3));
        ring.add(40, frame(4));
        List<AnalysisFrameRing.Frame> copy = ring.snapshot();
        check(copy.size() == 3, "ring must cap capacity");
        check(copy.get(0).receivedAtMs == 20, "oldest frame must rotate out");
        check((copy.get(2).grayscale[0] & 0xff) == 4, "newest frame missing");
        copy.get(0).grayscale[0] = 99;
        check((ring.snapshot().get(0).grayscale[0] & 0xff) == 2,
              "snapshot must not expose ring storage");
        ring.clear();
        check(ring.size() == 0 && ring.snapshot().isEmpty(), "clear must empty ring");
    }
}
