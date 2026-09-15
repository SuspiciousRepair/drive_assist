package com.geely.modehelper;

/**
 * Low-cost motion gate for the parked-monitoring pipeline.
 *
 * <p>This deliberately has no Android or camera dependency: callers provide a
 * small grayscale frame (one unsigned byte per pixel). It is intended to run
 * before object classification, so inference is only considered after a
 * sustained, spatially meaningful change. The gate does not decide whether an
 * object is a person, vehicle, or threat.</p>
 *
 * <p>The first frame establishes a background. While idle, that background
 * adapts slowly to gradual daylight changes. A near-whole-frame change is
 * treated as an exposure transition rather than motion, preventing a camera
 * auto-exposure adjustment from creating an event.</p>
 */
final class MotionGate {
    static final class Result {
        final boolean active;
        final boolean began;
        final boolean ended;
        final int changedPixels;

        Result(boolean active, boolean began, boolean ended, int changedPixels) {
            this.active = active;
            this.began = began;
            this.ended = ended;
            this.changedPixels = changedPixels;
        }
    }

    private final int width;
    private final int height;
    private final int pixelThreshold;
    private final int minChangedPixels;
    private final int maxGlobalChangedPixels;
    private final int framesToStart;
    private final int framesToEnd;
    private final int[] background;
    private final byte[] previous;

    private boolean seeded;
    private boolean active;
    private int consecutiveMotion;
    private int consecutiveStill;

    MotionGate(int width, int height) {
        this(width, height, 18, Math.max(24, width * height / 250),
             width * height * 9 / 10, 2, 4);
    }

    MotionGate(int width, int height, int pixelThreshold, int minChangedPixels,
               int maxGlobalChangedPixels, int framesToStart, int framesToEnd) {
        if (width <= 0 || height <= 0 || pixelThreshold <= 0
                || minChangedPixels <= 0 || maxGlobalChangedPixels < minChangedPixels
                || framesToStart <= 0 || framesToEnd <= 0) {
            throw new IllegalArgumentException("invalid motion-gate configuration");
        }
        this.width = width;
        this.height = height;
        this.pixelThreshold = pixelThreshold;
        this.minChangedPixels = minChangedPixels;
        this.maxGlobalChangedPixels = maxGlobalChangedPixels;
        this.framesToStart = framesToStart;
        this.framesToEnd = framesToEnd;
        this.background = new int[width * height];
        this.previous = new byte[width * height];
    }

    /** Processes exactly one grayscale frame. Callers must serialize calls. */
    Result accept(byte[] frame) {
        if (frame == null || frame.length != background.length) {
            throw new IllegalArgumentException("frame dimensions do not match gate");
        }
        if (!seeded) {
            for (int i = 0; i < frame.length; i++) {
                background[i] = frame[i] & 0xff;
                previous[i] = frame[i];
            }
            seeded = true;
            return new Result(false, false, false, 0);
        }

        int changed = 0;
        int interFrameChanged = 0;
        for (int i = 0; i < frame.length; i++) {
            int now = frame[i] & 0xff;
            if (Math.abs(now - background[i]) >= pixelThreshold) changed++;
            if (Math.abs(now - (previous[i] & 0xff)) >= pixelThreshold) interFrameChanged++;
        }

        // A global brightness jump is normally auto-exposure or a lighting
        // transition. Rebase immediately; a genuinely sustained object will
        // still be visible on the next frame against the new background.
        boolean global = changed > maxGlobalChangedPixels;
        boolean meaningful = !global && changed >= minChangedPixels;
        boolean began = false;
        boolean ended = false;

        if (!active && meaningful) {
            consecutiveMotion++;
            consecutiveStill = 0;
            if (!active && consecutiveMotion >= framesToStart) {
                active = true;
                began = true;
            }
        } else if (!active) {
            consecutiveMotion = 0;
        } else {
            // Once an event is active, compare adjacent samples rather than
            // the old background. A stationary person must not hold a clip
            // open forever merely because they remain visible in the frame.
            boolean moving = interFrameChanged >= minChangedPixels
                && interFrameChanged <= maxGlobalChangedPixels;
            if (moving) {
                consecutiveStill = 0;
            } else if (++consecutiveStill >= framesToEnd) {
                active = false;
                ended = true;
            }
        }

        // Do not absorb a moving object into the background. Idle frames adapt
        // slowly (1/8 per sample); exposure transitions rebase in one step.
        if (!active) {
            for (int i = 0; i < frame.length; i++) {
                int now = frame[i] & 0xff;
                // Rebase after an event tail so a stationary foreground object
                // is absorbed instead of immediately retriggering the gate.
                background[i] = ended || global ? now
                    : background[i] + ((now - background[i]) >> 3);
            }
        }
        System.arraycopy(frame, 0, previous, 0, frame.length);
        return new Result(active, began, ended, changed);
    }

    void reset() {
        seeded = false;
        active = false;
        consecutiveMotion = 0;
        consecutiveStill = 0;
    }
}
