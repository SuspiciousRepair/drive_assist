package com.geely.modehelper;

import java.nio.ByteBuffer;
import java.util.List;

/** Small dependency-free test runner for {@link PreRoll}. */
public final class PreRollTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void frame(PreRoll p, long ms, boolean key) {
        ByteBuffer b = ByteBuffer.wrap(new byte[] {9, 0, 0, 1, (byte) (key ? 0x65 : 0x41), (byte) ms, 9});
        p.add(b, 1, 5, key, ms, 1_000_000 + ms, "t" + ms);
    }

    public static void main(String[] args) {
        PreRoll p = new PreRoll(6_000);
        check(p.isEmpty(), "starts empty");

        // Frames before the first key frame cannot start a file.
        frame(p, 0, false);
        frame(p, 40, false);
        check(p.isEmpty(), "must start on a key frame");

        // 20 s of 25 fps with a key frame every second.
        for (long ms = 1000; ms < 21_000; ms += 40) frame(p, ms, ms % 1000 == 0);
        List<PreRoll.Frame> fs = p.frames();
        check(fs.get(0).key, "front must be a key frame");
        long span = 20_960 - p.firstUptimeMs();
        check(span >= 6_000 && span < 7_000, "keeps 6 s and at most one GOP more, has " + span);
        check(p.firstWallMs() == 1_000_000 + p.firstUptimeMs(), "wall time follows the first frame");
        check(fs.get(0).data.length == 5 && fs.get(0).data[3] == 0x65, "copies exactly the access unit");
        check(fs.get(fs.size() - 1).tele.equals("t20960"), "telemetry travels with the frame");

        p.clear();
        check(p.isEmpty(), "clear");
        System.out.println("PreRollTest OK");
    }
}
