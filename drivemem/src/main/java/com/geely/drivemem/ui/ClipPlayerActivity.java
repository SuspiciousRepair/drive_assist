package com.geely.drivemem.ui;

import com.geely.drivemem.R;

import com.geely.drivemem.util.Clips;
import com.geely.drivemem.util.Style;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Video player for 2x2 fisheye camera clips with dewarping correction.
 * Supports multiple camera views (front, rear, sides, orbit) with dynamic
 * fisheye-to-rectilinear conversion via OpenGL shader. */
public class ClipPlayerActivity extends Activity {
    public static final String EXTRA_PATH = "path";

    // Which quadrant each camera is: top-left/right are sides, bottom-left
    // is front, bottom-right is rear.
    //
    // Front and rear are mounted upside down, which is why those two get 180
    // degrees and the sides get none.
    // qy = 0 is the bottom row. Texture space runs bottom-up, so an off-by-one
    // row here shows the wrong camera in the wrong quadrant. Front and rear
    // are the bottom pair, left and right the top. Which cell qx/qy select
    // does NOT depend on the vertex coordinates below: the shader builds the
    // sample point from them directly.
    //
    // All four take 180: the whole composite arrives rotated, rather than the
    // front and rear being individually upside down. A side reading "flipped
    // and upside down" at zero is what a 180 error looks like — both axes at
    // once.
    //
    // The 180 is applied to the sampling DIRECTION, at the end, not to the screen
    // point at the start. Applied at the start the pitch lands downstream of it
    // and tilts in SAMPLE space, so with every camera at 180 each "look up" was a
    // look down. That cost three builds of raising numbers that made it worse.
    // helpers/dewarp-check.py holds the line against v360.
    //
    // Framing parameters have been measured against the actual lenses.
    // They are tuned to minimize black (out-of-image) bands.
    //
    // Black fraction is the check that catches this and it costs nothing to
    // compute, so do it before shipping a framing rather than after.
    //
    // Pitch, hfov, and vfov are chosen by visual comparison to OEM
    // rendering. Perfectly straight lines are not the goal; coverage is.
    //
    // Lens and squash parameters are measured via calibration against a
    // checkerboard. Front/rear use one lens type; mirrors use a different
    // (narrower) lens. Image center offsets are measured but not yet applied.
    //                            qx   qy   rot   pitch  hfov  vfov  lens  squash
    static final float[] FRONT = { 0f,  0f, 180f,  21f,  100f,  70f, 95.4f, 1.561f };
    // PICKED OFF THE REAL CELL, not computed. p16/v72 was arithmetic from the
    // mount angle and the supposed +-56 limit, and on the panel it reached only
    // as far up as the bicycle wheels where the OEM shows the whole alley. The
    // numbers cannot be trusted to mean degrees while SRC_SQUASH is unmeasured,
    // so these were chosen by rendering a grid of candidates over a cell pulled
    // off the car and matching the one that looks like Visão Traseira.
    // Provisional until helpers/calib says what the lens actually does.
    static final float[] REAR  = { 1f,  0f, 180f,  30f,  100f,  70f, 94.2f, 1.532f };
    // The mirrors get the same treatment on the same reasoning - same lens, same
    // error - but on a WEAKER measurement: their mount is only a lower bound,
    // ">55 degrees", because the horizon never appeared in frame to be measured
    // against. 58 is the assumption. If they still read low, that number is the
    // one to doubt.
    // Moved with the rear because 17 came out of the same wrong arithmetic, not
    // because anyone has seen them right: the car was parked with a leaf across
    // the left mirror, so its cell had nothing in it to frame against. 46 is
    // where the rear landed, and a sweep of the same cell shows black creeping
    // in from the top by 56, so this is under the ceiling.
    static final float[] LEFT  = { 0f,  1f, 180f,  20f,  115f,  55f, 80.3f, 1.713f };
    static final float[] RIGHT = { 1f,  1f, 180f,  20f,  115f,  55f, 80.3f, 1.713f };

    // On a fixed-width panel, a larger horizontal FOV spreads more degrees
    // across the same pixels (you see more, smaller), making the frame
    // narrower. Smaller FOV crops sides (road looks bigger), making the
    // frame taller.
    //
    // h130 gave a 4.12:1 band, 1920x466 on the panel, with the road small inside
    // it. h115 is 3.02:1 at 1920x637, h100 is 2.29:1 at 1920x839.
    //
    // Field of view is tuned for coverage of the relevant driving area,
    // not for minimal magnification.
    //
    // 839 of roughly 950 usable rows. Below about h95 the frame is taller than
    // the view, and then `dh > vh` starts trimming WIDTH to fit the height —
    // the shape stops obeying hfov and cropping further makes the picture
    // smaller, not bigger.
    //
    // Sides are the worst of it at 5.59:1 — a 1920x343 slot — because their low
    // vfov is what buys the 34 degrees of lift. They and the rear are still at
    // 115 and have not been looked at since the aim was fixed.

    // Mount pitch angles: how far down each camera points. Front and rear
    // are separate values to account for different mounting heights.
    static final float MOUNT_FRONT = 26f;
    static final float MOUNT_REAR  = 46f;   // the pitch the rear actually frames at
    static final float MOUNT_SIDES = 46f;   // ditto, and equally provisional
    static final float[] ALL   = null;          // the raw 2x2, no correction
    // The orbit. Not a camera — a viewpoint in the middle of the car that
    // mixes the cameras that see the way you are looking (OrbitRig).
    static final float[] ORBIT = { -2f };
    // How far the orbit tilts, degrees. Up is negative.
    // The Orbit view moves up and down like a shift lens (OrbitRig.screenRay);
    // past 8 up there is little but black, past 25 down the ground next to
    // the car, which two cameras see from two places.
    static final float PITCH_UP = -8f, PITCH_DOWN = 25f, PITCH_START = 4f;

    // Unsharp mask to restore detail lost in upscaling. Narrower views
    // need higher sharpening due to greater magnification.
    static final float SHARPEN = 0.85f;

    // Cell-space coordinate scaling: the 2x2 composite is 2.4:1, but the
    // sensor is 1.6:1. When showing a single camera, it's drawn at its
    // native aspect and sampling accounts for the squeeze.
    static final float CAM_W = 1280f, CAM_H = 800f;      // the sensor
    static final float CELL_W = 960f, CELL_H = 400f;     // its slot in the 2x2
    static final float FULL_W = 1920f, FULL_H = 800f;    // the whole frame

    static final float CELL_SCALE_X = 1f;
    static final float CELL_SCALE_Y = CAM_H / CAM_W;     // 0.625, the sensor's shape

    // Source squash is measured per camera; mirrors use a different lens.

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<long[]> spans = new ArrayList<>();
    private final List<String> texts = new ArrayList<>();
    private GLSurfaceView gl;
    private Renderer renderer;
    private MediaPlayer player;
    private TextView tele;
    private MediaController controller;
    private final List<TextView> camButtons = new ArrayList<>();
    private int lastCue = -1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Style.load(this);
        Style.edgeToEdge(this);
        final String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) { finish(); return; }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        renderer = new Renderer(path);
        gl.setRenderer(renderer);
        // Render only when a new frame arrives, not continuously.
        gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(gl, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int pad = Style.dp(this, 14);

        // Camera picker on the left, matching the car's surround view layout.
        LinearLayout picker = new LinearLayout(this);
        picker.setOrientation(LinearLayout.VERTICAL);
        picker.addView(camButton(getString(R.string.cam_all),   ALL,   true));
        picker.addView(camButton(getString(R.string.cam_front), FRONT, false));
        picker.addView(camButton(getString(R.string.cam_rear),  REAR,  false));
        picker.addView(camButton(getString(R.string.cam_left),  LEFT,  false));
        picker.addView(camButton(getString(R.string.cam_right), RIGHT, false));
        picker.addView(camButton(getString(R.string.cam_orbit), ORBIT, false));

        // Drag to turn (orbit mode only; ignored in other modes).
        gl.setOnTouchListener(new android.view.View.OnTouchListener() {
            float lx, ly, dx0;
            @Override public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    lx = e.getX(); ly = e.getY(); dx0 = 0f; return true;
                }
                if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                    if (dx0 < 12f && controller != null) controller.show(4000);
                    return true;
                }
                if (e.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                    final float dx = (e.getX() - lx) * 0.25f, dy = (e.getY() - ly) * 0.25f;
                    dx0 += Math.abs(e.getX() - lx) + Math.abs(e.getY() - ly);
                    lx = e.getX(); ly = e.getY();
                    gl.queueEvent(() -> renderer.turn(-dx, -dy));
                    gl.requestRender();
                    return true;
                }
                return true;
            }
        });
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        pp.leftMargin = Style.dp(this, 20);
        root.addView(picker, pp);

        tele = new TextView(this);
        tele.setTextSize(30);
        tele.setTextColor(0xFFFFFFFF);
        tele.setBackgroundColor(0xB0000000);
        tele.setPadding(pad, pad / 2, pad, pad / 2);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        tp.bottomMargin = Style.dp(this, 40);
        root.addView(tele, tp);

        TextView back = new TextView(this);
        back.setText(getString(R.string.clips_back));
        back.setTextSize(26);
        back.setTextColor(0xFFFFFFFF);
        back.setBackgroundColor(0xB0000000);
        back.setPadding(pad, pad / 2, pad, pad / 2);
        back.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.TOP | Gravity.START;
        bp.leftMargin = Style.dp(this, 20);
        bp.topMargin  = Style.dp(this, 20) + Style.statusBarHeight(this);
        root.addView(back, bp);

        // Playback controls via MediaController, needed because GLSurfaceView
        // doesn't provide them like VideoView did.
        controller = new MediaController(this) {
            // Otherwise the system back closes the controller and the player
            // stays put, which reads as a dead back button.
            @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
                if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) { finish(); return true; }
                return super.dispatchKeyEvent(e);
            }
        };
        controller.setMediaPlayer(new MediaController.MediaPlayerControl() {
            @Override public void start() { if (player != null) player.start(); }
            @Override public void pause() { if (player != null) player.pause(); }
            @Override public int getDuration() { return player == null ? 0 : player.getDuration(); }
            @Override public int getCurrentPosition() {
                return player == null ? 0 : player.getCurrentPosition();
            }
            @Override public void seekTo(int p) {
                if (player != null) player.seekTo(p);
                // Seek invalidates the cue cache to avoid stale overlays.
                lastCue = -1;
            }
            @Override public boolean isPlaying() { return player != null && player.isPlaying(); }
            @Override public int getBufferPercentage() { return 100; }
            @Override public boolean canPause() { return true; }
            @Override public boolean canSeekBackward() { return true; }
            @Override public boolean canSeekForward() { return true; }
            @Override public int getAudioSessionId() { return 0; }
        });
        controller.setAnchorView(gl);

        // Tap shows controls (drag is for orbit rotation).
        root.setOnClickListener(v -> controller.show(4000));

        setContentView(root);
        File f = new File(path);
        loadVtt(new File(f.getParentFile(), Clips.name(f) + ".vtt"));
    }

    private TextView camButton(String label, final float[] cam, boolean selected) {
        final TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(22);
        t.setPadding(Style.dp(this, 18), Style.dp(this, 10), Style.dp(this, 18), Style.dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Style.dp(this, 8);
        t.setLayoutParams(lp);
        t.setTag(cam);
        paint(t, selected);
        t.setOnClickListener(v -> {
            for (TextView o : camButtons) paint(o, o == t);
            gl.queueEvent(() -> renderer.select(cam));
            gl.requestRender();
        });
        camButtons.add(t);
        return t;
    }

    private void paint(TextView t, boolean on) {
        t.setBackground(on ? Style.card(Style.CARD_ON, this) : Style.card(0xB0000000, this));
        t.setTextColor(on ? Style.onFill(Style.CARD_ON) : 0xFFDDDDDD);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacksAndMessages(null);
        if (player != null && player.isPlaying()) player.pause();
        gl.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        gl.onResume();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (player != null) { try { player.release(); } catch (Throwable ignored) { } player = null; }
    }

    private void tick() {
        ui.postDelayed(this::tick, 250);
        MediaPlayer p = player;
        if (p == null || !p.isPlaying()) return;
        long pos = p.getCurrentPosition();
        int hit = -1;
        for (int i = 0; i < spans.size(); i++) {
            long[] s = spans.get(i);
            if (pos >= s[0] && pos < s[1]) { hit = i; break; }
        }
        if (hit == lastCue) return;
        lastCue = hit;
        tele.setText(hit < 0 ? "" : texts.get(hit));
    }

    // ---------------------------------------------------------------- GL

    private final class Renderer implements GLSurfaceView.Renderer,
                                            SurfaceTexture.OnFrameAvailableListener {
        private final String path;
        private int texId, plain, dewarp, orbitP;
        private SurfaceTexture surfaceTex;
        private final float[] texM = new float[16];
        private FloatBuffer verts, coords;
        private volatile float[] cam = ALL;
        private volatile float yaw = 0f, pitchDeg = PITCH_START;
        private int topTex;

        void turn(float dYaw, float dPitch) {
            yaw += dYaw;
            // Clamped so you cannot roll past the poles into nonsense. Down more
            // than up, because everything interesting near a car is below the
            // horizon. A NEGATIVE pitch looks up: the limits were the wrong
            // way round, 60 up into black and only 25 down. Past 35 down the
            // screen is mostly the dark patch under the car.
            pitchDeg = Math.max(PITCH_UP, Math.min(PITCH_DOWN, pitchDeg + dPitch));
        }
        private int vw, vh;

        Renderer(String path) { this.path = path; }

        void select(float[] c) { cam = c; }

        @Override public void onSurfaceCreated(GL10 unused, EGLConfig cfg) {
            plain  = program(VERT, FRAG_PLAIN);
            dewarp = program(VERT, FRAG_DEWARP);
            orbitP = program(VERT, FRAG_ORBIT);

            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            texId = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            // Clamp to edge so sampling past cell boundaries doesn't wrap to
            // neighboring cameras.
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            topTex = coverageTexture();

            verts  = buf(new float[]{-1,-1,  1,-1,  -1,1,  1,1});
            // No flip: uTexMatrix from SurfaceTexture already carries the
            // decoder's orientation. The rotation below is the physical
            // correction; no additional flip is needed.
            coords = buf(new float[]{ 0, 0,  1, 0,   0,1,  1,1});

            surfaceTex = new SurfaceTexture(texId);
            surfaceTex.setOnFrameAvailableListener(this);
            startPlayer(new Surface(surfaceTex));
        }

        // OrbitRig.coverageTop() as a 128 x 1 texture: linear between entries,
        // and round the circle (REPEAT; 128 is a power of two, as GLES 2 needs).
        private int coverageTexture() {
            float[] top = OrbitRig.coverageTop(
                OrbitRig.rotations(new float[] { MOUNT_FRONT, MOUNT_SIDES, MOUNT_REAR, MOUNT_SIDES }),
                new double[] { Math.toRadians(FRONT[6]), Math.toRadians(RIGHT[6]),
                               Math.toRadians(REAR[6]),  Math.toRadians(LEFT[6]) },
                new double[] { FRONT[7], RIGHT[7], REAR[7], LEFT[7] });
            ByteBuffer b = ByteBuffer.allocateDirect(top.length);
            for (float e : top) {
                int v = Math.round((e - OrbitRig.COVER_LO) / (OrbitRig.COVER_HI - OrbitRig.COVER_LO) * 255f);
                b.put((byte) Math.max(0, Math.min(255, v)));
            }
            b.position(0);
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, top.length, 1, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, b);
            return t[0];
        }

        private void startPlayer(Surface s) {
            ui.post(() -> {
                try {
                    MediaPlayer mp = new MediaPlayer();
                    mp.setSurface(s);
                    mp.setDataSource(path);
                    mp.setOnPreparedListener(m -> {
                        m.start();
                        tick();
                        if (controller != null) { controller.setEnabled(true); controller.show(4000); }
                    });
                    mp.setOnErrorListener((m, what, extra) -> {
                        tele.setText(getString(R.string.clip_play_failed, what, extra));
                        return true;
                    });
                    mp.prepareAsync();
                    player = mp;
                } catch (Throwable t) {
                    tele.setText(getString(R.string.clip_play_failed, -1, -1));
                }
            });
        }

        @Override public void onFrameAvailable(SurfaceTexture st) { gl.requestRender(); }

        @Override public void onSurfaceChanged(GL10 unused, int w, int h) { vw = w; vh = h; }

        @Override public void onDrawFrame(GL10 unused) {
            if (surfaceTex == null) return;
            surfaceTex.updateTexImage();
            surfaceTex.getTransformMatrix(texM);

            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // LETTERBOX. Both the whole 2x2 and one 960x400 cell are 2.4:1, and
            // the panel is 1.78:1 — without this the picture is stretched tall.
            // The raw 2x2 keeps the frame's shape. A single camera is drawn in
            // whatever shape its two fields of view imply — for a rectilinear
            // projection the tangents are in the same ratio as the sides — so the
            // sides come out as a wide strip and the front nearly 1.7:1, with no
            // stretching in either.
            float[] c = cam;
            boolean orbit = (c != null && c.length == 1);
            float want = orbit ? OrbitRig.ASPECT : (c == null) ? (FULL_W / FULL_H)
                : (float) (Math.tan(Math.toRadians(c[4]) / 2.0)
                         / Math.tan(Math.toRadians(c[5]) / 2.0));
            int dw = vw, dh = (int) (vw / want);
            if (dh > vh) { dh = vh; dw = (int) (vh * want); }
            GLES20.glViewport((vw - dw) / 2, (vh - dh) / 2, dw, dh);

            int prog = (c == null) ? plain : (orbit ? orbitP : dewarp);
            GLES20.glUseProgram(prog);

            int aPos = GLES20.glGetAttribLocation(prog, "aPosition");
            int aTex = GLES20.glGetAttribLocation(prog, "aTexCoord");
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, verts);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, coords);

            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTexMatrix"), 1, false, texM, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "sTexture"), 0);

            if (orbit) {
                double y = Math.toRadians(yaw), pt = Math.toRadians(pitchDeg);
                GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uView"),
                    (float) y, (float) pt);
                GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uFov"),
                    (float) Math.toRadians(OrbitRig.MID),
                    (float) Math.toRadians(OrbitRig.EDGE - OrbitRig.MID),
                    (float) Math.toRadians(OrbitRig.MID) / OrbitRig.ASPECT);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, topTex);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "sTop"), 1);
                GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uTop"),
                    OrbitRig.COVER_LO, OrbitRig.COVER_HI - OrbitRig.COVER_LO);
                // All in yaw order, NOT the order the table is written in:
                // 0 front, 1 right, 2 rear, 3 left.
                GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(prog, "uRot"), 4, false,
                    OrbitRig.rotations(new float[] {
                        MOUNT_FRONT, MOUNT_SIDES, MOUNT_REAR, MOUNT_SIDES }), 0);
                GLES20.glUniform3fv(GLES20.glGetUniformLocation(prog, "uPos"), 4,
                    OrbitRig.positions(), 0);
                GLES20.glUniform2fv(GLES20.glGetUniformLocation(prog, "uAxis"), 4,
                    OrbitRig.axes(), 0);
                // Lens half angle and squash per camera: the mirrors are a
                // different lens from the front and rear.
                GLES20.glUniform2fv(GLES20.glGetUniformLocation(prog, "uLens"), 4, new float[] {
                    (float) Math.toRadians(FRONT[6]), FRONT[7],
                    (float) Math.toRadians(RIGHT[6]), RIGHT[7],
                    (float) Math.toRadians(REAR[6]),  REAR[7],
                    (float) Math.toRadians(LEFT[6]),  LEFT[7] }, 0);
                // Cell of each camera in the 2x2: front=BL, right=TR, rear=BR, left=TL.
                GLES20.glUniform2fv(GLES20.glGetUniformLocation(prog, "uCell"), 4, new float[] {
                    FRONT[0], FRONT[1], RIGHT[0], RIGHT[1], REAR[0], REAR[1], LEFT[0], LEFT[1] }, 0);
                GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uBall"),
                    OrbitRig.EYE, OrbitRig.BALL, OrbitRig.BAND);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSharp"), SHARPEN);
                GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uTexel"),
                    1f / FULL_W, 1f / FULL_H);
            } else if (c != null) {
                double rot   = Math.toRadians(c[2]);
                // Straight through with no negation (see FRAG_DEWARP).
                double pitch = Math.toRadians(c[3]);
                float tanX = (float) Math.tan(Math.toRadians(c[4]) / 2.0);
                float tanY = (float) Math.tan(Math.toRadians(c[5]) / 2.0);
                GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uQuadSelect"), c[0], c[1]);
                GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uTan"), tanX, tanY);
                GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uRot"),
                    (float) Math.cos(rot), (float) Math.sin(rot));
                GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uPitch"),
                    (float) Math.cos(pitch), (float) Math.sin(pitch));
                // Per-camera measured value (see camera table above).
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uLensHalf"),
                    (float) Math.toRadians(c[6]));
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSrcSquash"), c[7]);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSharp"), SHARPEN);
                // One texel of the whole frame (the sampling grid).
                GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uTexel"),
                    1f / FULL_W, 1f / FULL_H);
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPos);
            GLES20.glDisableVertexAttribArray(aTex);
        }

        private FloatBuffer buf(float[] a) {
            FloatBuffer b = ByteBuffer.allocateDirect(a.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
            b.put(a).position(0);
            return b;
        }

        private int program(String v, String f) {
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, v));
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, f));
            GLES20.glLinkProgram(p);
            return p;
        }

        private int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            return s;
        }
    }

    private static final String VERT =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "varying vec2 vRawTextureCoord;\n" +
        "void main() { gl_Position = aPosition; vRawTextureCoord = aTexCoord; }\n";

    private static final String FRAG_PLAIN =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vRawTextureCoord;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "uniform samplerExternalOES sTexture;\n" +
        "void main() {\n" +
        "  gl_FragColor = texture2D(sTexture, (uTexMatrix * vec4(vRawTextureCoord,0.0,1.0)).xy);\n" +
        "}\n";


    // Orbit mode: one viewpoint, four cameras. Each output pixel becomes a
    // ray, turned by yaw/pitch. The ray lands on a point on a ball around
    // the car (OrbitRig). Each camera then looks at that point from its own
    // place on the car, so things about that far away line up where two
    // cameras meet. The camera the ray points to shows; in a narrow band
    // where two meet they are mixed, so there is no line between them.
    // OrbitRig.cell() and weight() are the same steps in Java; keep the two
    // in step.
    private static final String FRAG_ORBIT =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision highp float;\n" +
        "varying vec2 vRawTextureCoord;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "uniform samplerExternalOES sTexture;\n" +
        "uniform vec2  uView;\n" +
        "uniform vec3  uFov;\n" +       // middle rad/half screen, extra at the edge, vertical scale
        "uniform sampler2D sTop;\n" +   // the top of the picture per yaw (OrbitRig.coverageTop)
        "uniform vec2  uTop;\n" +       // its range: lowest, span (degrees)
        "uniform mat3  uRot[4];\n" +     // world direction to lens direction
        "uniform vec3  uPos[4];\n" +     // where each camera is, metres
        "uniform vec2  uAxis[4];\n" +    // the side each camera looks to (x, z)
        "uniform vec2  uLens[4];\n" +    // lens half angle, squash
        "uniform vec2  uCell[4];\n" +    // its cell in the 2x2
        "uniform vec3  uBall;\n" +       // eye height, ball radius, mix band
        "uniform float uSharp;\n" +
        "uniform vec2  uTexel;\n" +
        "void main() {\n" +
        "  vec2 p = vRawTextureCoord * 2.0 - 1.0;\n" +
        // The middle sharp, the sides squeezed (OrbitRig.screenRay).
        "  float ax = abs(p.x);\n" +
        "  float yw = uView.x + sign(p.x) * (uFov.x * ax + uFov.y * ax * ax * ax);\n" +
        "  vec3 r = normalize(vec3(sin(yw), p.y * uFov.z - tan(uView.y), cos(yw)));\n" +
        // The point in the world this ray lands on: the ball.
        "  vec3 X = vec3(0.0, uBall.x, 0.0) + r * uBall.y;\n" +
        "  float best = max(abs(r.x), abs(r.z));\n" +
        "  vec3 acc = vec3(0.0); float ws = 0.0;\n" +
        "  for (int i = 0; i < 4; i++) {\n" +
        // The camera the ray points to, and its neighbour in a narrow band.
        "    float w = smoothstep(0.0, 1.0, 1.0 - (best - dot(r.xz, uAxis[i])) / uBall.z);\n" +
        "    if (w <= 0.0) continue;\n" +
        "    vec3 rc = uRot[i] * normalize(X - uPos[i]);\n" +
        "    float rad = acos(clamp(rc.z, -1.0, 1.0)) / uLens[i].x;\n" +
        "    if (rad > 1.0) continue;\n" +
        "    float dl = length(rc.xy);\n" +
        // Negated: account for 180-degree rotation
        "    vec2 dir = dl > 1e-6 ? -rc.xy / dl : vec2(0.0);\n" +
        "    vec2 cell = vec2(0.5) + dir * rad * vec2(0.5, 0.5 * uLens[i].y);\n" +
        "    if (cell.x < 0.0 || cell.x > 1.0 || cell.y < 0.0 || cell.y > 1.0) continue;\n" +
        "    vec2 g = (uCell[i] + cell) * 0.5;\n" +
        "    vec2 sc = (uTexMatrix * vec4(g, 0.0, 1.0)).xy;\n" +
        "    vec3 col = texture2D(sTexture, sc).rgb;\n" +
        "    vec3 b = (texture2D(sTexture, sc + vec2(uTexel.x, 0.0)).rgb\n" +
        "            + texture2D(sTexture, sc - vec2(uTexel.x, 0.0)).rgb\n" +
        "            + texture2D(sTexture, sc + vec2(0.0, uTexel.y)).rgb\n" +
        "            + texture2D(sTexture, sc - vec2(0.0, uTexel.y)).rgb) * 0.25;\n" +
        "    acc += clamp(col + uSharp * (col - b), 0.0, 1.0) * w;\n" +
        "    ws += w;\n" +
        "  }\n" +
        // Fade to black over 4 degrees under the top of the picture, so the
        // edge where no camera sees is soft, not a jagged step.
        "  float el = degrees(asin(clamp(r.y, -1.0, 1.0)));\n" +
        "  float u = atan(r.x, r.z) / 6.2831853 + 0.5 / 128.0;\n" +
        "  float top = uTop.x + texture2D(sTop, vec2(u, 0.5)).r * uTop.y;\n" +
        "  float fade = smoothstep(0.0, 1.0, (top - el) / 4.0);\n" +
        "  gl_FragColor = vec4((ws > 1e-5 ? acc / ws : vec3(0.0)) * fade, 1.0);\n" +
        "}\n";

    // Fisheye-to-rectilinear conversion via 3D geometry: each output pixel
    // becomes a ray pitched in 3D; its angle to the lens axis picks the
    // sample radius (same model as ffmpeg v360).
    private static final String FRAG_DEWARP =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision highp float;\n" +
        "varying vec2 vRawTextureCoord;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "uniform samplerExternalOES sTexture;\n" +
        "uniform vec2  uQuadSelect;\n" +
        "uniform vec2  uTan;\n" +
        "uniform vec2  uRot;\n" +
        "uniform vec2  uPitch;\n" +
        "uniform float uLensHalf;\n" +
        "uniform float uSrcSquash;\n" +
        "uniform float uSharp;\n" +
        "uniform vec2  uTexel;\n" +
        "void main() {\n" +
        "  vec2 p = vRawTextureCoord * 2.0 - 1.0;\n" +
        "  vec3 ray = normalize(vec3(p.x * uTan.x, p.y * uTan.y, 1.0));\n" +
        "  ray = vec3(ray.x,\n" +
        "             ray.y * uPitch.x + ray.z * uPitch.y,\n" +
        "            -ray.y * uPitch.y + ray.z * uPitch.x);\n" +
        "  float theta = acos(clamp(ray.z, -1.0, 1.0));\n" +
        "  float r = theta / uLensHalf;\n" +
        "  float dl = length(ray.xy);\n" +
        "  vec2 dir = dl > 1e-6 ? ray.xy / dl : vec2(0.0);\n" +
        // The 180-degree rotation is applied to the direction (not the initial
        // point), so the pitch is computed in the correct space.
        "  dir = mat2(uRot.x, uRot.y, -uRot.y, uRot.x) * dir;\n" +
        // Squash corrects the aspect: sensor is squeezed into a narrower cell.
        "  vec2 cell = vec2(0.5) + dir * r * vec2(0.5, 0.5 * uSrcSquash);\n" +
        "  if (r > 1.0 || cell.x < 0.0 || cell.x > 1.0 || cell.y < 0.0 || cell.y > 1.0) {\n" +
        "    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return;\n" +
        "  }\n" +
        "  vec2 g = (uQuadSelect + cell) * 0.5;\n" +
        "  vec2 sc = (uTexMatrix * vec4(g, 0.0, 1.0)).xy;\n" +
        "  vec3 c = texture2D(sTexture, sc).rgb;\n" +
        // High-pass sharpening at source scale (five taps: center + 4 neighbors).
        "  vec3 b = (texture2D(sTexture, sc + vec2(uTexel.x, 0.0)).rgb\n" +
        "          + texture2D(sTexture, sc - vec2(uTexel.x, 0.0)).rgb\n" +
        "          + texture2D(sTexture, sc + vec2(0.0, uTexel.y)).rgb\n" +
        "          + texture2D(sTexture, sc - vec2(0.0, uTexel.y)).rgb) * 0.25;\n" +
        "  gl_FragColor = vec4(clamp(c + uSharp * (c - b), 0.0, 1.0), 1.0);\n" +
        "}\n";

    // Enough WebVTT for what Vtt.java writes and no more.
    private void loadVtt(File f) {
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                int arrow = line.indexOf(" --> ");
                if (arrow < 0) continue;
                long from = ms(line.substring(0, arrow).trim());
                long to   = ms(line.substring(arrow + 5).trim());
                String text = r.readLine();
                if (from < 0 || to < 0 || text == null) continue;
                spans.add(new long[]{from, to});
                texts.add(text);
            }
        } catch (Exception ignored) { }
    }

    private static long ms(String t) {
        try {
            String[] p = t.split(":");
            if (p.length != 3) return -1;
            String[] s = p[2].split("\\.");
            return Long.parseLong(p[0]) * 3600000L + Long.parseLong(p[1]) * 60000L
                 + Long.parseLong(s[0]) * 1000L + (s.length > 1 ? Long.parseLong(s[1]) : 0);
        } catch (Exception e) { return -1; }
    }
}
