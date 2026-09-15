package com.geely.modehelper;

import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * Inactive analysis consumer for the safe {@code dvr} render path.
 *
 * <p>This class deliberately does not contact {@link EvsClient}. A future,
 * explicitly enabled on-car diagnostic must first prove that the engine accepts
 * this surface concurrently with its encoder surface and factory camera UI.
 * Until then it is a locally testable conversion and rate-limiting boundary,
 * not a feature activation path.</p>
 */
final class DvrAnalysisTap implements AutoCloseable {
    // EvsHold, an earlier attended diagnostic retained on the car, proved this
    // exact dvr ImageReader shape/format at ~25 fps, including during reverse.
    // Keep the producer surface identical to that evidence; downscale only in
    // our CPU copy rather than asking evsengine for an unproven output size.
    static final int SOURCE_WIDTH = 1920;
    static final int SOURCE_HEIGHT = 800;
    static final int WIDTH = 480;
    static final int HEIGHT = 200;
    static final long SAMPLE_INTERVAL_MS = 500L; // 2 fps maximum for the gate.
    static final int PRE_EVENT_SECONDS = 10;

    interface Listener {
        /** False discards the sample and resets the gate/pre-event state. */
        boolean isArmed(long receivedAtMs);
        void onGateResult(MotionGate.Result result, long receivedAtMs);
        void onFrameError(String message, Throwable error);
    }

    private final Listener listener;
    private final MotionGate gate = new MotionGate(WIDTH, HEIGHT);
    private final AnalysisFrameRing preEvent = new AnalysisFrameRing(
        (int) (PRE_EVENT_SECONDS * 1000L / SAMPLE_INTERVAL_MS), WIDTH * HEIGHT);
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final HandlerThread thread;
    private final Handler handler;
    private final ImageReader reader;
    private volatile boolean closed;
    private long lastSampleMs;

    DvrAnalysisTap(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener required");
        this.listener = listener;
        thread = new HandlerThread("dvr-analysis");
        thread.start();
        handler = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(SOURCE_WIDTH, SOURCE_HEIGHT, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onImage, handler);
    }

    /** The surface a future guarded diagnostic may offer to {@code dvr}. */
    Surface surface() { return reader.getSurface(); }

    private void onImage(ImageReader source) {
        Image image = null;
        try {
            image = source.acquireLatestImage();
            if (image == null || closed) return;

            long now = SystemClock.elapsedRealtime();
            if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return;
            lastSampleMs = now;
            copyRgbaToGray(image, gray);
            if (!listener.isArmed(now)) {
                gate.reset();
                return;
            }
            preEvent.add(now, gray);
            listener.onGateResult(gate.accept(gray), now);
        } catch (Throwable t) {
            // Closing an ImageReader invalidates an in-flight plane buffer.
            // That is expected during the attended probe's explicit stop, not
            // a capture failure worth reporting to the operator.
            if (!closed) listener.onFrameError("could not process DVR analysis frame", t);
        } finally {
            if (image != null) image.close();
        }
    }

    /** Returns copies of the last ten seconds of sampled analysis frames. */
    java.util.List<AnalysisFrameRing.Frame> preEventFrames() {
        return preEvent.snapshot();
    }

    /**
     * Converts an RGBA_8888 ImageReader frame to luminance without assuming
     * tightly packed rows. The EVS producer's actual pixel format must be
     * checked by the diagnostic; incompatible frames fail closed here.
     */
    static void copyRgbaToGray(Image image, byte[] out) {
        if (image.getWidth() != SOURCE_WIDTH || image.getHeight() != SOURCE_HEIGHT
                || image.getPlanes().length != 1 || out.length != WIDTH * HEIGHT) {
            throw new IllegalArgumentException("unexpected DVR analysis frame shape");
        }
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        if (pixelStride < 4 || rowStride < SOURCE_WIDTH * pixelStride) {
            throw new IllegalArgumentException("unexpected RGBA plane stride");
        }
        int base = pixels.position();
        for (int y = 0; y < HEIGHT; y++) {
            int row = base + (y * (SOURCE_HEIGHT / HEIGHT)) * rowStride;
            for (int x = 0; x < WIDTH; x++) {
                int at = row + (x * (SOURCE_WIDTH / WIDTH)) * pixelStride;
                int red = pixels.get(at) & 0xff;
                int green = pixels.get(at + 1) & 0xff;
                int blue = pixels.get(at + 2) & 0xff;
                // Integer Rec. 601 luminance approximation.
                out[y * WIDTH + x] = (byte) ((77 * red + 150 * green + 29 * blue) >> 8);
            }
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        reader.setOnImageAvailableListener(null, null);
        reader.close();
        thread.quitSafely();
    }
}
