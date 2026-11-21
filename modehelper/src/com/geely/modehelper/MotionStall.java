package com.geely.modehelper;

import java.util.concurrent.atomic.AtomicInteger;

/** Tells a motion detector that stopped answering from a quiet street.
 *
 * While parked motion watch is armed the recorder writes nothing until the
 * detector reports motion. A decoder that stops giving pictures would then
 * mean no recording at all, with nothing to show for it. This counts the key
 * frames handed to the detector since it last analysed one; at LIMIT the
 * recorder stops trusting it and records all the time (see DashRecorder).
 *
 * Counted in key frames, not seconds: when the car goes off the cameras
 * stop, no key frame comes, and that silence is not the detector's fault.
 *
 * Plain Java, so it is tested on a desktop JVM. `offered` runs on the
 * encoder thread, `analysed` on the motion thread. */
final class MotionStall {
    // A healthy decoder answers every key frame, at most one late.
    static final int LIMIT = 15;

    private final AtomicInteger unanswered = new AtomicInteger();

    void offered() { unanswered.incrementAndGet(); }
    void analysed() { unanswered.set(0); }
    boolean stalled() { return unanswered.get() >= LIMIT; }
}
