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

        // The car slept: uptime stood still (a few seconds pass around the
        // sleep), the wall clock moved 51 minutes. What was kept before the
        // sleep must not open the first clip after it.
        PreRoll s = new PreRoll(6_000);
        for (long ms = 0; ms < 8_000; ms += 40) frame(s, ms, ms % 1000 == 0);
        long wake = 8_000 + 5_000, slept = 51 * 60_000L;
        ByteBuffer b = ByteBuffer.wrap(new byte[] {0, 0, 1, 0x41});
        s.add(b, 0, 4, false, wake, 1_000_000 + wake + slept, "p");
        check(s.isEmpty(), "a frame after a hole must drop what was kept, and wait for a key frame");
        s.add(b, 0, 4, true, wake + 40, 1_000_000 + wake + 40 + slept, "k");
        s.add(b, 0, 4, false, wake + 80, 1_000_000 + wake + 80 + slept, "p");
        check(s.frames().size() == 2 && s.firstUptimeMs() == wake + 40
              && s.firstWallMs() == 1_000_000 + wake + 40 + slept,
              "after a hole it starts at the first new key frame");

        // Uptime alone (the cameras stopped while the unit stayed awake), and
        // the wall clock alone (uptime does not count a sleep).
        PreRoll u = new PreRoll(6_000);
        frame(u, 0, true);
        frame(u, 40, false);
        frame(u, 40 + PreRoll.HOLE_MS + 1, true);
        check(u.frames().size() == 1 && u.firstUptimeMs() == 40 + PreRoll.HOLE_MS + 1, "hole in uptime");
        PreRoll w = new PreRoll(6_000);
        w.add(b, 0, 4, true, 0, 5_000_000, "k");
        w.add(b, 0, 4, true, 40, 5_000_000 + 40 + PreRoll.HOLE_MS + 1, "k");
        check(w.frames().size() == 1 && w.firstUptimeMs() == 40, "hole in the wall clock");
        // An ordinary second between key frames is no hole.
        PreRoll n = new PreRoll(6_000);
        frame(n, 0, true);
        frame(n, PreRoll.HOLE_MS, false);
        check(n.frames().size() == 2, "frames HOLE_MS apart are still one piece");
        System.out.println("PreRollTest OK");
    }
}
