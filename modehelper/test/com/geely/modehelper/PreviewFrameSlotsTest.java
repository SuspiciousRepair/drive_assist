package com.geely.modehelper;

/** Capture stays bounded and never overwrites a texture currently read by preview. */
public final class PreviewFrameSlotsTest {
    public static void main(String[] args) {
        PreviewFrameSlots frames = new PreviewFrameSlots();
        int first = frames.beginWrite();
        frames.publish(first);
        int reading = frames.acquireLatest();
        check(reading == first, "consumer receives published frame");
        int second = frames.beginWrite();
        check(second >= 0 && second != reading, "producer cannot overwrite consumer's texture");
        frames.publish(second);
        check(frames.beginWrite() == -1, "capture skips preview when both slots are occupied");
        frames.release(reading);

        // Preview returns its sampled texture before it calls its potentially
        // blocking window swap. Capture must continue replacing the queued frame.
        int latest = second;
        for (int frame = 0; frame < 10_000; frame++) {
            latest = frames.beginWrite();
            check(latest >= 0, "stalled preview window cannot exhaust capture's bounded handoff");
            frames.publish(latest);
        }
        check(frames.acquireLatest() == latest, "slow consumer receives latest frame instead of a backlog");
        check(frames.acquireLatest() == -1, "frame cannot be read twice concurrently");
        frames.release(latest);
        check(frames.beginWrite() >= 0, "consumer release returns capacity");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
