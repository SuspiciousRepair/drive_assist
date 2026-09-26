package com.geely.modehelper;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;

/** Parked motion detection from the dashcam's OWN recording.
 *
 * The first attempt opened a second EVS connection to the `dvr` camera next
 * to DashRecorder's, and a Park->Reverse shift then left the factory reverse
 * camera frozen on a stale frame (2026-09-15; see git history of the removed
 * ParkedMonitoringProbe). Nothing but DashRecorder may open that camera.
 *
 * So this takes nothing from the camera. DashRecorder hands it each KEY
 * frame it has just encoded (one a second); a hardware decoder turns it back
 * into a picture; its luma, shrunk to 480x200, goes through MotionGate and
 * MotionEventPolicy, both desktop-tested. A key frame decodes on its own, so
 * nothing else of the stream is needed. At most one frame is in flight: if
 * the decoder is still busy, the next key frame is dropped, never queued. */
final class KeyframeMotion implements AutoCloseable {
    static final String TAG = "ModeHelper";
    static final int WIDTH = 480, HEIGHT = 200;

    interface Listener {
        /** Motion began: an event is now open. */
        void onEventStarted(int changedPixels);
        /** Once per analysed frame while an event is open. */
        void onEventActive();
        /** The event closed after its quiet tail. */
        void onEventFinished();
    }

    private final MediaCodec decoder;
    private final Listener listener;
    private final MotionGate gate = new MotionGate(WIDTH, HEIGHT);
    private final MotionEventPolicy events = new MotionEventPolicy();
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final Object lock = new Object();
    private final Thread thread;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private byte[] pending = new byte[1 << 20];
    private int pendingLen;
    private long pendingPtsUs;
    private boolean hasPending;
    private volatile boolean closed;
    private long analysed;

    /** `format` is the encoder's output format (it carries csd-0/csd-1). */
    KeyframeMotion(MediaFormat format, int width, int height, Listener listener) throws Exception {
        this.listener = listener;
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        f.setByteBuffer("csd-0", format.getByteBuffer("csd-0").duplicate());
        f.setByteBuffer("csd-1", format.getByteBuffer("csd-1").duplicate());
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        decoder.configure(f, null, null, 0);
        decoder.start();
        thread = new Thread(this::run, "dashcam-motion");
        thread.start();
    }

    /** Called on the encoder thread with a key frame; copies it (the
     * encoder's buffer is released right after) unless one is in flight. */
    void offer(ByteBuffer buf, int offset, int size, long ptsUs) {
        synchronized (lock) {
            if (hasPending || closed) return;
            if (pending.length < size) pending = new byte[size * 2];
            ByteBuffer d = buf.duplicate();
            d.limit(offset + size);
            d.position(offset);
            d.get(pending, 0, size);
            pendingLen = size;
            pendingPtsUs = ptsUs;
            hasPending = true;
            lock.notifyAll();
        }
    }

    private void run() {
        while (!closed) {
            synchronized (lock) {
                while (!hasPending && !closed) {
                    try { lock.wait(); } catch (InterruptedException e) { return; }
                }
                if (closed) return;
            }
            try {
                decodeOne();
            } catch (Throwable t) {
                if (!closed) Log.w(TAG, "motion: decode: " + t);
            } finally {
                synchronized (lock) { hasPending = false; }
            }
        }
    }

    private void decodeOne() {
        int in = decoder.dequeueInputBuffer(200_000);
        if (in < 0) return;
        ByteBuffer ib = decoder.getInputBuffer(in);
        ib.clear();
        ib.put(pending, 0, pendingLen);
        decoder.queueInputBuffer(in, 0, pendingLen, pendingPtsUs, 0);
        // A decoder may hold a frame until more input arrives; then this
        // analyses the previous key frame, a second late. That is fine.
        for (int tries = 0; tries < 5 && !closed; tries++) {
            int out = decoder.dequeueOutputBuffer(info, 100_000);
            if (out < 0) continue;
            try {
                Image image = decoder.getOutputImage(out);
                if (image == null) return;
                Image.Plane y = image.getPlanes()[0];
                android.graphics.Rect crop = image.getCropRect();
                MotionGate.shrink(y.getBuffer(), y.getRowStride(), y.getPixelStride(),
                           crop.left, crop.top, crop.width(), crop.height(), gray, WIDTH, HEIGHT);
                image.close();
            } finally {
                decoder.releaseOutputBuffer(out, false);
            }
            analyse();
            return;
        }
    }

    private void analyse() {
        long now = android.os.SystemClock.elapsedRealtime();
        MotionGate.Result r = gate.accept(gray);
        // Proof the decode path works, then a sparse heartbeat: without it a
        // quiet street and a decoder that never delivers look the same.
        if (++analysed == 1 || analysed % 300 == 0)
            Log.i(TAG, "motion: analysed " + analysed + " frames, last change " + r.changedPixels + " px");
        MotionEventPolicy.Result e = events.update(now, r.active);
        if (e.started) listener.onEventStarted(r.changedPixels);
        else if (e.active) listener.onEventActive();
        if (e.finished) listener.onEventFinished();
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            lock.notifyAll();
        }
        try { thread.join(1_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { decoder.stop(); } catch (Throwable ignored) { }
        try { decoder.release(); } catch (Throwable ignored) { }
    }
}
