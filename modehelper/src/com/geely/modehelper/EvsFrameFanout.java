package com.geely.modehelper;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One EVS input, an encoder output and an optional independent preview output.
 * Preview BufferQueue backpressure is isolated from the encoder GL thread.
 * No camera attachment is changed when an Activity appears or disappears. */
public final class EvsFrameFanout {
    public interface PreviewStatus { void state(String session, String state); }

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final int PREVIEW_WIDTH = 960, PREVIEW_HEIGHT = 400;
    private static final float[] QUAD = {-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1};
    private static final String VERTEX = "attribute vec2 aPosition; attribute vec2 aTexCoord;"
        + "uniform mat4 uMatrix; varying vec2 vTexCoord; void main(){"
        + "gl_Position=vec4(aPosition,0.0,1.0); vTexCoord=(uMatrix*vec4(aTexCoord,0.0,1.0)).xy;}";
    private static final String OES_FRAGMENT = "#extension GL_OES_EGL_image_external : require\n"
        + "precision mediump float; varying vec2 vTexCoord; uniform samplerExternalOES uTexture;"
        + "void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}";
    private static final String TEXTURE_FRAGMENT = "precision mediump float; varying vec2 vTexCoord;"
        + "uniform sampler2D uTexture; void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}";

    private final Surface encoder;
    private final int width, height;
    private final HandlerThread captureThread = new HandlerThread("dashcam-gl");
    private Handler capture;
    private final CountDownLatch initialized = new CountDownLatch(1);
    private final CountDownLatch captureStopped = new CountDownLatch(1);
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean frameQueued = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final PreviewFrameSlots slots = new PreviewFrameSlots();
    private final int[] sharedTextures = new int[2];
    private final float[] textureMatrix = new float[16];
    private final FloatBuffer vertices = vertices();
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface encoderWindow = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture cameraTexture;
    private Surface cameraSurface;
    private PreviewOutput preview;
    private int oesTexture, program, framebuffer;
    private volatile Throwable failure;
    private volatile long lastFrameMs;
    private long lastPresentationNs;

    public EvsFrameFanout(Surface encoder, int width, int height) {
        this.encoder = encoder;
        this.width = width;
        this.height = height;
    }

    public void start() throws IOException {
        captureThread.start();
        capture = new Handler(captureThread.getLooper());
        lastFrameMs = SystemClock.uptimeMillis();
        capture.post(() -> {
            try { initialize(); }
            catch (Throwable problem) { failure = problem; requestStop(); }
            finally { initialized.countDown(); }
        });
        try {
            if (!initialized.await(5, TimeUnit.SECONDS)) {
                requestStop();
                throw new IOException("Camera renderer initialization timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            requestStop();
            throw new IOException("Camera renderer initialization interrupted", interrupted);
        }
        checkHealth();
    }

    public Surface inputSurface() { return cameraSurface; }

    /** Ownership of this parcelled Surface wrapper transfers to the preview GL thread. */
    public void setPreview(String session, Surface surface, PreviewStatus callback) {
        PreviewOutput target = preview;
        if (target == null || stopping.get()) {
            if (surface != null) surface.release();
            notifyStatus(callback, session, "detached");
            return;
        }
        target.set(session, surface, callback);
    }

    public void detachPreview(String session, PreviewStatus callback) {
        PreviewOutput target = preview;
        if (target == null) notifyStatus(callback, session, "detached");
        else target.detach(session, callback);
    }

    public void checkHealth() throws IOException {
        if (failure != null) throw new IOException("Camera renderer failed", failure);
        if (!stopping.get() && SystemClock.uptimeMillis() - lastFrameMs > 10_000L)
            throw new IOException("Camera frames timed out");
    }

    public void requestStop() {
        if (!stopping.compareAndSet(false, true)) return;
        PreviewOutput target = preview;
        if (target != null) target.close();
        Handler handler = capture;
        if (handler == null) { captureStopped.countDown(); return; }
        handler.post(this::releaseCapture);
    }

    public boolean isStopped() {
        PreviewOutput target = preview;
        return captureStopped.getCount() == 0 && (target == null || target.stopped.getCount() == 0);
    }

    /** The caller must drain MediaCodec while stopping, before waiting here. */
    public boolean awaitStopped(long timeoutMs) {
        long until = SystemClock.uptimeMillis() + timeoutMs;
        try {
            if (!captureStopped.await(timeoutMs, TimeUnit.MILLISECONDS)) return false;
            PreviewOutput target = preview;
            if (target != null && !target.stopped.await(
                    Math.max(0, until - SystemClock.uptimeMillis()), TimeUnit.MILLISECONDS)) return false;
            if (terminated.compareAndSet(false, true) && display != EGL14.EGL_NO_DISPLAY)
                EGL14.eglTerminate(display);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        require(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, version, 0, version, 1), "EGL display");
        int[] attributes = {EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
            EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT|EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID,1,EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
        require(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
            && count[0] > 0, "recordable EGL config");
        config = configs[0];
        context = createContext(EGL14.EGL_NO_CONTEXT);
        encoderWindow = window(encoder);
        makeCurrent(encoderWindow, context);
        program = program(OES_FRAGMENT);
        int[] names = new int[1]; GLES20.glGenTextures(1, names, 0); oesTexture = names[0];
        texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        cameraTexture = new SurfaceTexture(oesTexture);
        cameraTexture.setDefaultBufferSize(width, height);
        cameraSurface = new Surface(cameraTexture);
        GLES20.glGenTextures(2, sharedTextures, 0);
        for (int id : sharedTextures) {
            texture(GLES20.GL_TEXTURE_2D, id);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                PREVIEW_WIDTH, PREVIEW_HEIGHT, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        }
        GLES20.glGenFramebuffers(1, names, 0); framebuffer = names[0];
        GLES20.glFinish();
        preview = new PreviewOutput();
        preview.start();
        cameraTexture.setOnFrameAvailableListener(ignored -> {
            if (!stopping.get() && frameQueued.compareAndSet(false, true)) capture.post(() -> {
                frameQueued.set(false);
                if (stopping.get()) return;
                try { frame(); }
                catch (Throwable problem) { failure = problem; requestStop(); }
            });
        });
        glCheck("capture initialization");
    }

    private void frame() {
        makeCurrent(encoderWindow, context);
        cameraTexture.updateTexImage();
        cameraTexture.getTransformMatrix(textureMatrix);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        draw(program, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture, vertices, textureMatrix, width, height);
        long timestamp = Math.max(cameraTexture.getTimestamp(), lastPresentationNs + 1);
        lastPresentationNs = timestamp;
        require(EGLExt.eglPresentationTimeANDROID(display, encoderWindow, timestamp), "encoder timestamp");
        require(EGL14.eglSwapBuffers(display, encoderWindow), "encoder swap");
        lastFrameMs = SystemClock.uptimeMillis();

        PreviewOutput output = preview;
        if (output == null || !output.accepting || stopping.get()) return;
        int slot = slots.beginWrite();
        if (slot < 0) return; // A slow preview never blocks or grows a frame queue.
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, sharedTextures[slot], 0);
            require(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE,
                "preview framebuffer");
            draw(program, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture, vertices, textureMatrix,
                PREVIEW_WIDTH, PREVIEW_HEIGHT);
            // ES2 has no portable Java fence API on API28. Finish this small copy
            // before publishing; capture never waits for the preview consumer.
            GLES20.glFinish();
            slots.publish(slot);
            output.frameAvailable();
        } catch (Throwable problem) {
            slots.release(slot);
            // The encoded frame is already submitted. An optional preview copy
            // failure must not terminate an otherwise healthy background recorder.
            output.fail();
            GLES20.glGetError();
        } finally { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); }
    }

    private void releaseCapture() {
        try {
            // initialize() may have created preview after an earlier stop request.
            PreviewOutput target = preview;
            if (target != null) target.close();
            if (cameraTexture != null) cameraTexture.setOnFrameAvailableListener(null);
            if (cameraSurface != null) cameraSurface.release();
            if (cameraTexture != null) cameraTexture.release();
            if (context != EGL14.EGL_NO_CONTEXT && encoderWindow != EGL14.EGL_NO_SURFACE) {
                makeCurrent(encoderWindow, context);
                if (program != 0) GLES20.glDeleteProgram(program);
                if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
                if (oesTexture != 0) GLES20.glDeleteTextures(1, new int[]{oesTexture}, 0);
                // Do not delete shared texture names here: preview may have
                // acquired a slot just before shutdown but not bound it yet.
                // Their storage is reclaimed when the last shared context dies.
            }
        } catch (Throwable problem) { if (failure == null) failure = problem; }
        finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (encoderWindow != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderWindow);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            captureStopped.countDown();
            captureThread.quitSafely();
        }
    }

    private final class PreviewOutput {
        final HandlerThread thread = new HandlerThread("dashcam-preview-gl");
        final CountDownLatch ready = new CountDownLatch(1), stopped = new CountDownLatch(1);
        final AtomicBoolean queued = new AtomicBoolean(), closing = new AtomicBoolean();
        final FloatBuffer quad = vertices();
        final float[] identity = new float[16];
        Handler handler;
        EGLContext previewContext = EGL14.EGL_NO_CONTEXT;
        EGLSurface pbuffer = EGL14.EGL_NO_SURFACE, output = EGL14.EGL_NO_SURFACE;
        Surface surface;
        String session;
        PreviewStatus callback;
        int previewProgram;
        volatile boolean accepting;
        Throwable initializationFailure;
        long lastStatus;

        void start() {
            synchronized (this) {
                if (closing.get()) return;
                thread.start(); handler = new Handler(thread.getLooper());
                // Queue initialization while holding the same lock as close(), so
                // cleanup can never run first and be followed by late initialization.
                handler.post(() -> {
                try {
                    previewContext = createContext(context);
                    pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                        new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE}, 0);
                    require(pbuffer != EGL14.EGL_NO_SURFACE, "preview pbuffer");
                    makeCurrent(pbuffer, previewContext);
                    previewProgram = program(TEXTURE_FRAGMENT);
                    Matrix.setIdentityM(identity, 0);
                } catch (Throwable problem) { initializationFailure = problem; }
                finally { ready.countDown(); }
                });
            }
            try {
                require(ready.await(5, TimeUnit.SECONDS), "preview initialization timeout");
                if (initializationFailure != null) throw new IllegalStateException("preview initialization", initializationFailure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("preview initialization interrupted", interrupted);
            }
        }

        void set(String owner, Surface ownedSurface, PreviewStatus status) {
            if (closing.get()) { ownedSurface.release(); notifyStatus(status, owner, "detached"); return; }
            handler.post(() -> {
                if (closing.get()) { ownedSurface.release(); notifyStatus(status, owner, "detached"); return; }
                if (owner.equals(session)) { ownedSurface.release(); return; }
                clear();
                session = owner; surface = ownedSurface; callback = status; lastStatus = 0;
                try {
                    require(surface.isValid(), "preview surface unavailable");
                    output = window(surface);
                    accepting = true;
                    notifyStatus(callback, session, "waiting");
                } catch (Throwable problem) {
                    notifyStatus(callback, session, "error");
                    clear();
                }
            });
        }

        void detach(String owner, PreviewStatus status) {
            if (stopped.getCount() == 0) { notifyStatus(status, owner, "detached"); return; }
            handler.post(() -> {
                if (owner.equals(session)) clear();
                notifyStatus(status, owner, "detached");
            });
        }

        void frameAvailable() {
            if (!closing.get() && queued.compareAndSet(false, true)) handler.post(() -> {
                queued.set(false);
                if (!accepting || closing.get()) return;
                int slot = slots.acquireLatest();
                if (slot < 0) return;
                boolean returned = false;
                try {
                    makeCurrent(output, previewContext);
                    int[] size = new int[1];
                    require(EGL14.eglQuerySurface(display, output, EGL14.EGL_WIDTH, size, 0), "preview width");
                    int w = size[0];
                    require(EGL14.eglQuerySurface(display, output, EGL14.EGL_HEIGHT, size, 0), "preview height");
                    int h = size[0];
                    GLES20.glViewport(0, 0, w, h);
                    GLES20.glClearColor(0,0,0,1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    float scale = Math.min(w / (float) width, h / (float) height);
                    int dw = Math.max(1, Math.round(width * scale)), dh = Math.max(1, Math.round(height * scale));
                    GLES20.glViewport((w-dw)/2, (h-dh)/2, dw, dh);
                    drawQuad(previewProgram, GLES20.GL_TEXTURE_2D, sharedTextures[slot], quad, identity);
                    GLES20.glFinish();
                    // Return before swap: BufferQueue backpressure belongs only to
                    // this output thread and never holds a capture texture hostage.
                    slots.release(slot); returned = true;
                    require(EGL14.eglSwapBuffers(display, output), "preview swap");
                    long now = SystemClock.uptimeMillis();
                    if (now - lastStatus >= 1000L) { lastStatus = now; notifyStatus(callback, session, "live"); }
                } catch (Throwable problem) {
                    notifyStatus(callback, session, "error");
                    clear(); // Preview abandonment must not stop recording.
                } finally { if (!returned) slots.release(slot); }
            });
        }

        void clear() {
            accepting = false;
            String oldSession = session; PreviewStatus oldCallback = callback;
            session = null; callback = null;
            if (previewContext != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE)
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, previewContext);
            if (output != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, output);
            output = EGL14.EGL_NO_SURFACE;
            if (surface != null) surface.release();
            surface = null;
            if (oldSession != null) notifyStatus(oldCallback, oldSession, "detached");
        }

        void fail() {
            accepting = false;
            handler.post(() -> { notifyStatus(callback, session, "error"); clear(); });
        }

        void close() {
            accepting = false;
            synchronized (this) {
                if (!closing.compareAndSet(false, true)) return;
                if (handler == null) { stopped.countDown(); return; }
            }
            handler.post(() -> {
                try {
                    clear();
                    if (previewProgram != 0) GLES20.glDeleteProgram(previewProgram);
                } finally {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer);
                    if (previewContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, previewContext);
                    EGL14.eglReleaseThread();
                    stopped.countDown(); thread.quitSafely();
                }
            });
        }
    }

    private EGLContext createContext(EGLContext share) {
        EGLContext created = EGL14.eglCreateContext(display, config, share,
            new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE}, 0);
        require(created != EGL14.EGL_NO_CONTEXT, "EGL context");
        return created;
    }

    private EGLSurface window(Surface surface) {
        EGLSurface created = EGL14.eglCreateWindowSurface(display, config, surface,
            new int[]{EGL14.EGL_NONE}, 0);
        require(created != EGL14.EGL_NO_SURFACE, "EGL window");
        return created;
    }

    private void makeCurrent(EGLSurface surface, EGLContext current) {
        require(EGL14.eglMakeCurrent(display, surface, surface, current), "EGL make current");
    }

    private static FloatBuffer vertices() {
        FloatBuffer result = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        result.put(QUAD).position(0);
        return result;
    }

    private static void texture(int target, int id) {
        GLES20.glBindTexture(target, id);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static void draw(int program, int target, int texture, FloatBuffer vertices,
                             float[] matrix, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0,0,0,1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        drawQuad(program, target, texture, vertices, matrix);
    }

    private static void drawQuad(int program, int target, int texture, FloatBuffer vertices, float[] matrix) {
        GLES20.glUseProgram(program);
        int position = GLES20.glGetAttribLocation(program, "aPosition");
        int coordinate = GLES20.glGetAttribLocation(program, "aTexCoord");
        vertices.position(0); GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,vertices);
        vertices.position(2); GLES20.glVertexAttribPointer(coordinate,2,GLES20.GL_FLOAT,false,16,vertices);
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(coordinate);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uMatrix"),1,false,matrix,0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(target, texture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uTexture"),0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(coordinate);
        glCheck("frame render");
    }

    private static int program(String fragment) {
        int vertex = shader(GLES20.GL_VERTEX_SHADER, VERTEX);
        int pixel = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int result = GLES20.glCreateProgram();
        GLES20.glAttachShader(result, vertex); GLES20.glAttachShader(result, pixel);
        GLES20.glLinkProgram(result);
        int[] linked = new int[1]; GLES20.glGetProgramiv(result,GLES20.GL_LINK_STATUS,linked,0);
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(pixel);
        if (linked[0] == 0) {
            String detail = GLES20.glGetProgramInfoLog(result); GLES20.glDeleteProgram(result);
            throw new IllegalStateException("GL program: " + detail);
        }
        return result;
    }

    private static int shader(int kind, String source) {
        int shader = GLES20.glCreateShader(kind); GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader);
        int[] compiled = new int[1]; GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,compiled,0);
        if (compiled[0] == 0) {
            String detail = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader);
            throw new IllegalStateException("GL shader: " + detail);
        }
        return shader;
    }

    private static void glCheck(String operation) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException(operation + " GL error " + error);
    }

    private static void require(boolean success, String operation) {
        if (!success) throw new IllegalStateException(operation + " EGL error " + EGL14.eglGetError());
    }

    private static void notifyStatus(PreviewStatus callback, String session, String state) {
        if (callback != null) try { callback.state(session, state); } catch (Throwable ignored) { }
    }
}
