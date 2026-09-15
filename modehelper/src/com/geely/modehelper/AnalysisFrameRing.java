package com.geely.modehelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, in-memory pre-event buffer for reduced analysis frames.
 *
 * <p>Frames are copied at insertion because ImageReader buffers are returned to
 * the producer immediately after a callback. Nothing is written to storage:
 * this ring only preserves the short interval before a future event recorder is
 * promoted. Callers must serialize access.</p>
 */
final class AnalysisFrameRing {
    static final class Frame {
        final long receivedAtMs;
        final byte[] grayscale;

        Frame(long receivedAtMs, byte[] grayscale) {
            this.receivedAtMs = receivedAtMs;
            this.grayscale = grayscale;
        }
    }

    private final byte[][] frames;
    private final long[] receivedAtMs;
    private final int frameBytes;
    private int next;
    private int size;

    AnalysisFrameRing(int capacity, int frameBytes) {
        if (capacity <= 0 || frameBytes <= 0) {
            throw new IllegalArgumentException("capacity and frame size must be positive");
        }
        this.frames = new byte[capacity][];
        this.receivedAtMs = new long[capacity];
        this.frameBytes = frameBytes;
    }

    void add(long atMs, byte[] grayscale) {
        if (grayscale == null || grayscale.length != frameBytes) {
            throw new IllegalArgumentException("unexpected analysis frame size");
        }
        byte[] copy = frames[next];
        if (copy == null) copy = new byte[frameBytes];
        System.arraycopy(grayscale, 0, copy, 0, frameBytes);
        frames[next] = copy;
        receivedAtMs[next] = atMs;
        next = (next + 1) % frames.length;
        if (size < frames.length) size++;
    }

    /** Returns oldest-to-newest copies suitable for a slow event writer. */
    List<Frame> snapshot() {
        List<Frame> out = new ArrayList<>(size);
        int first = (next - size + frames.length) % frames.length;
        for (int i = 0; i < size; i++) {
            int slot = (first + i) % frames.length;
            byte[] copy = new byte[frameBytes];
            System.arraycopy(frames[slot], 0, copy, 0, frameBytes);
            out.add(new Frame(receivedAtMs[slot], copy));
        }
        return out;
    }

    int size() { return size; }

    void clear() {
        next = 0;
        size = 0;
    }
}
