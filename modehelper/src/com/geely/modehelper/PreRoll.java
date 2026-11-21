package com.geely.modehelper;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** The last few seconds of encoded video, in memory, while parked motion
 * watch writes nothing: the start of a parked clip (see DashRecorder).
 *
 * Starts on a key frame and drops whole GOPs from the front, so what it
 * holds can always begin a playable file. It keeps the fewest GOPs that
 * still cover `spanMs`: at a key frame a second, about spanMs plus one
 * second of video, ~2 MB a second at the recorder's bitrate.
 *
 * It holds ONE piece of video. A frame that arrives more than HOLE_MS after
 * the one before it starts over: the cameras stopped in between (the car
 * went off and slept). Without this the last second before a sleep opened
 * the first clip after it, and gave that clip its name (seen on the car
 * 2026-11-20: a clip named 17:11:08 holding video from 18:02:18).
 *
 * Plain Java, so it is tested on a desktop JVM. Encoder thread only. */
final class PreRoll {
    static final class Frame {
        final byte[] data;
        final boolean key;
        final long uptimeMs, wallMs;
        final String tele;

        Frame(byte[] data, boolean key, long uptimeMs, long wallMs, String tele) {
            this.data = data; this.key = key; this.uptimeMs = uptimeMs; this.wallMs = wallMs; this.tele = tele;
        }
    }

    private final long spanMs;
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();

    // Frames come 40 ms apart. Both clocks are checked: uptime stands still
    // while the unit sleeps, and the wall clock does not.
    static final long HOLE_MS = 2_000L;

    PreRoll(long spanMs) { this.spanMs = spanMs; }

    /** Copies one access unit (the encoder releases its buffer right after). */
    void add(ByteBuffer buf, int offset, int size, boolean key, long uptimeMs, long wallMs, String tele) {
        Frame last = frames.peekLast();
        if (last != null && (uptimeMs - last.uptimeMs > HOLE_MS
                             || Math.abs(wallMs - last.wallMs) > HOLE_MS)) frames.clear();
        if (frames.isEmpty() && !key) return;          // must start on a key frame
        byte[] data = new byte[size];
        ByteBuffer d = buf.duplicate();
        d.limit(offset + size);
        d.position(offset);
        d.get(data);
        if (key) trim(uptimeMs);
        frames.addLast(new Frame(data, key, uptimeMs, wallMs, tele));
    }

    /** Before a new GOP at `nowMs`: drops the oldest GOP while the next one
     * still starts at least spanMs back. */
    private void trim(long nowMs) {
        for (;;) {
            Frame next = null;
            boolean first = true;
            for (Frame f : frames) {
                if (first) { first = false; continue; }
                if (f.key) { next = f; break; }
            }
            if (next == null || nowMs - next.uptimeMs < spanMs) return;
            while (frames.peekFirst() != next) frames.removeFirst();
        }
    }

    boolean isEmpty() { return frames.isEmpty(); }
    long firstUptimeMs() { return frames.peekFirst().uptimeMs; }
    long firstWallMs() { return frames.peekFirst().wallMs; }
    List<Frame> frames() { return new ArrayList<>(frames); }
    void clear() { frames.clear(); }
}
