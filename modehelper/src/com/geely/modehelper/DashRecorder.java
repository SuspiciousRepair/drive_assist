package com.geely.modehelper;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Dashcam recorder: direct encode of the DVR camera view (2x2 of all four cameras)
 * without GL pipeline. Encodes directly to MediaCodec, bypassing unnecessary
 * transformations. See docs/DASHCAM.md and docs/EVS-CAMERA.md. */
public final class DashRecorder {
    static final String TAG = "ModeHelper";

    static final int  W = 1920, H = 800;          // the dvr surface, exactly — no scaling
    static final int  FPS = 25;
    // 16 Mbit/s: increased from 6 for better quality. Ring buffer duration is
    // roughly inversely proportional (10 GB holds ~1.4 hours at this rate).
    static final int  BITRATE = 16_000_000;
    static final int  IFRAME_SEC = 1;
    static final long SEGMENT_MS = 5 * 60 * 1000L;
    // Default ring-buffer size; the owner can override it from Drive Assist's
    // Recordings panel (SET_MODE's "dashcam_limit_gb" extra, read fresh in
    // enforceBudget() below rather than cached, so a change takes effect on
    // the very next segment rotation, not the next restart).
    static final int  DEFAULT_BUDGET_GB = 10;
    static final long CUE_US = 1_000_000L;                        // one subtitle per second

    // Speed limit candidates for the subtitle overlay: camera sign, then nav,
    // then nav-speed, in that fallback order — see SubtitleLine.limit().
    static final int P_CAM_LIMIT = 0x2140b029;
    static final int P_NAVI_LIMIT = 0x2140303a;
    static final int P_NAVSPEED   = 0x2140a405;

    private static final ThreadLocal<SimpleDateFormat> NAME =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> SUB_TIME =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss", Locale.US));    // the date is the clip's name

    private final Context ctx;
    private final CarMode car;
    private final EvsClient evs = new EvsClient();
    private volatile boolean running;
    private volatile Seg activeSegment;
    private volatile boolean rotateRequested;
    private volatile boolean closeNow;
    private Thread thread;
    private Thread sampler;
    // Closes finished segments: the moov write, a thumbnail decoded from a
    // 600 MB file, the ring buffer. On the encoder thread that work stopped
    // the drain for as long as it took, and frames were dropped at every
    // rotation. One thread, so segments still close in order.
    private ExecutorService closer;
    private volatile long startedUptimeMs;
    private volatile String lastError;

    // Latest telemetry, refreshed off the encoder thread so a slow binder read can
    // never stall the drain loop.
    private volatile String tele = "";
    private volatile boolean valetEdge;
    private boolean lastValet;

    public DashRecorder(Context ctx, CarMode car) { this.ctx = ctx; this.car = car; }

    public boolean isRunning() { return running; }

    /** uptime when start() last ran; the supervisor resets its backoff once a
     * recorder has stayed up a while. */
    public long startedUptimeMs() { return startedUptimeMs; }

    public synchronized void start() {
        if (running) return;
        running = true;
        startedUptimeMs = SystemClock.uptimeMillis();
        lastError = null;
        thread = new Thread(this::loop, "dashcam");
        thread.start();
        sampler = new Thread(this::sample, "dashcam-tele");
        sampler.start();
        Log.i(TAG, "dashcam: starting");
    }

    public synchronized void stop() {
        running = false;
        Log.i(TAG, "dashcam: stop requested");
    }

    /** Used for orderly service shutdown. Waiting for the encoder loop means
     * the last fragment and the index are written before Android can kill
     * the helper. */
    public void stopAndWait(long timeoutMs) {
        stop();
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(timeoutMs); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    // ------------------------------------------------------------ parked motion

    // While motion watch is armed (the car awake in Park with Park
    // monitoring on), nothing is written until something moves. The encoder
    // keeps running -- its key frames are what KeyframeMotion watches -- and
    // the last PRE_ROLL_MS of frames wait in memory. When an event starts,
    // a `_park` segment opens with that pre-roll, so the clip shows what led
    // up to the motion; it closes when the event ends. These are ordinary
    // clips: the ring buffer ages them out like any other, and nothing is
    // held automatically (holding every clip with motion kept EVERY parked
    // clip at home, 600 MB each, 2026-09-27).
    //
    // An event is declared about two key frames after motion begins, and a
    // key frame is analysed up to a second late, so the pre-roll must cover
    // well over three seconds to catch the start.
    //
    // When the watch ends (the car left Park) the pre-roll opens the
    // ordinary clip that follows. The helper reads the gear every 4 s, so
    // the watch ends up to 5 s after the shift; dropping the pre-roll there
    // left that long a hole at the start of every drive (seen on the car
    // 2026-11-20: nothing between 16:53:02 and 16:53:05, the car moving at
    // 16:53:06). A parked clip still open at that moment is closed, so the
    // drive is not labelled as parked movement.
    //
    // A watch that cannot start, or whose decoder stops giving pictures
    // (MotionStall), is given up until the car leaves Park: the recorder
    // then writes all the time, as with the switch off. Writing nothing
    // because the detector died is the one failure not allowed here.
    static final long PRE_ROLL_MS = 6_000L;
    static final String PARK_SUFFIX = "_park";

    private volatile boolean motionWanted;
    private volatile boolean motionEvent;          // set on the motion thread
    private KeyframeMotion motion;                // encoder thread only
    private boolean motionBroken;                 // encoder thread only
    private final PreRoll preRoll = new PreRoll(PRE_ROLL_MS);   // encoder thread only

    /** Parked motion watch on or off; ModeHelperService calls this on every
     * park-state update. The encoder thread acts on it at the next key frame. */
    public void setMotionWatch(boolean on) { motionWanted = on; }

    // Encoder thread, for each key frame.
    private void motionFrame(MediaFormat outFmt, ByteBuffer buf, MediaCodec.BufferInfo info) {
        if (!motionWanted) motionBroken = false;       // a new Park stay tries again
        if (motionWanted && !motionBroken && motion == null && outFmt != null) {
            try {
                motionEvent = false;
                motion = new KeyframeMotion(outFmt, W, H, new KeyframeMotion.Listener() {
                    @Override public void onEventStarted(int changed) {
                        motionEvent = true;
                        Log.i(TAG, "motion: event started (" + changed + " px changed), recording");
                    }
                    @Override public void onEventActive() { }
                    @Override public void onEventFinished() {
                        motionEvent = false;
                        Log.i(TAG, "motion: event ended");
                    }
                });
                Log.i(TAG, "motion: watching, recording only on motion");
            } catch (Throwable t) {
                Log.w(TAG, "motion: could not start, recording all the time until Park is left: " + t);
                motionBroken = true;
            }
        } else if (!motionWanted && motion != null) {
            stopMotion();
        }
        if (motion == null) return;
        motion.offer(buf, info.offset, info.size, info.presentationTimeUs);
        if (motion.stalled()) {
            Log.w(TAG, "motion: " + MotionStall.LIMIT + " key frames with no picture analysed,"
                + " recording all the time until Park is left");
            motionBroken = true;
            stopMotion();
        }
    }

    // Leaves the pre-roll alone: the loop opens the next clip with it.
    private void stopMotion() {
        if (motion == null) return;
        motion.close();
        motion = null;
        motionEvent = false;
        Log.i(TAG, "motion: stopped watching");
    }

    /** Close the open segment NOW, without waiting for a key frame, and
     * start the next one at the first key frame once pictures flow again.
     * For the car going off: it sends ACTION_SHUTDOWN_HU, the cameras stop
     * at that moment, the screen goes off three seconds later, and the unit
     * sleeps. A close that waited for a key frame (the first version) never
     * ran until the car woke hours later (seen on 2026-09-26: screen off at
     * 22:20:23, segment closed at 00:01:12). A fragmented MP4 can close at
     * any sample, so nothing needs to wait. */
    public void closeSegmentNow(String why) {
        if (activeSegment == null) return;
        closeNow = true;
        Log.i(TAG, "dashcam: " + why + " — closing the segment now");
    }

    // Clips stored in drivemem's external files directory. Accessible to both apps
    // without additional permissions. Uninstalling drivemem removes clips. Hardcoded
    // because modehelper cannot query drivemem's files dir.
    static final String CLIP_DIR =
        "/sdcard/Android/data/com.geely.drivemem/files/dashcam";

    public static File dir() {
        File d = new File(CLIP_DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // Clips the driver has HELD. Out of the ring buffer's reach entirely — it
    // never looks in here, so a held clip is safe until it is deleted by hand.
    public static File keepDir() {
        File d = new File(dir(), "keep");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------ telemetry

    private void sample() {
        int tick = 0;
        Float lastSpeed = null;
        long lastSpeedUpMs = 0;
        while (running) {
            Seg open = activeSegment;
            if (tick++ % 5 == 0 && open != null)
                RecorderState.write(dir(), "recording", open.stem, System.currentTimeMillis(), null);
            else if (tick % 5 == 1 && open == null && motionWanted)
                // Parked, nothing moving: alive, but no segment is open.
                RecorderState.write(dir(), "watching", "", System.currentTimeMillis(), null);
            try {
                if (car.isReady()) {
                    boolean valet = new File(dir(), "valet.active").exists();
                    if (valet != lastValet) { lastValet = valet; valetEdge = true; }
                    // One short line, only what tells how the car was driven:
                    // see SubtitleLine. No GPS is run for it any more (the
                    // compass heading was its only use here).
                    long up = SystemClock.uptimeMillis();
                    Float sp = car.readSpeed();
                    String trend = SubtitleLine.trend(lastSpeed, sp, up - lastSpeedUpMs);
                    lastSpeed = sp;
                    lastSpeedUpMs = up;
                    int lim = SubtitleLine.limit(car.readIntProp(P_CAM_LIMIT, 0),
                        car.readIntProp(P_NAVI_LIMIT, 0), car.readIntProp(P_NAVSPEED, 0));
                    // Turn signal omitted: no verified property in field-catalog yet.
                    // The pedal comes from Drive Assist (BrakeSignal); nothing
                    // while its CAN reader is off.
                    boolean brake = BrakeSignal.SHARED.take(SystemClock.elapsedRealtime());
                    tele = SubtitleLine.line(SUB_TIME.get().format(new Date()), sp, trend, car.readGear(), brake, lim);
                }
            } catch (Throwable t) { Log.w(TAG, "dashcam: sample: " + t); }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }

    // ------------------------------------------------------------ the loop

    private void loop() {
        MediaCodec codec = null;
        Surface input = null;
        Seg seg = null;
        try {
            if (!evs.connect()) { lastError = "no EVS engine"; Log.w(TAG, "dashcam: no engine, giving up"); running = false; return; }
            evs.openCamera(EvsClient.CAMERA_AVM);

            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                           MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_SEC);

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = codec.createInputSurface();
            codec.start();

            // The engine renders straight into the encoder. Same IGraphicBufferProducer
            // trick as any other surface — MediaCodec's input surface is no different.
            if (!evs.attach(input, EvsClient.TYPE_DVR, EvsClient.CAMERA_AVM)) {
                lastError = "EVS attach refused";
                Log.w(TAG, "dashcam: attach refused");
                running = false;
                return;
            }

            closer = Executors.newSingleThreadExecutor(r -> new Thread(r, "dashcam-close"));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaFormat outFmt = null;
            boolean rotateArmed = false;
            long lastCue = -1;

            while (running) {
                // Checked on every pass, frames or not: after the car goes
                // off there are no more frames to wait for.
                if (closeNow) {
                    closeNow = false;
                    if (seg != null) {
                        final Seg old = seg;
                        closer.execute(() -> { old.finish(); enforceBudget(ctx, null); });
                        seg = null;
                        activeSegment = null;
                        rotateArmed = false;
                        rotateRequested = false;
                        lastCue = -1;
                        Bundle b = new Bundle();
                        b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        codec.setParameters(b);
                    }
                }
                int idx = codec.dequeueOutputBuffer(info, 20000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFmt = codec.getOutputFormat();
                    continue;
                }
                if (idx < 0) continue;

                ByteBuffer buf = codec.getOutputBuffer(idx);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean key    = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                // csd lives in the output format, which the writer already put in
                // the avcC; writing it as a sample as well produces a file some
                // players refuse.
                // After closeSegmentNow() the next segment starts at the first
                // key frame; frames before it cannot start a playable file.
                if (config || buf == null || outFmt == null) { codec.releaseOutputBuffer(idx, false); continue; }
                long nowUp = SystemClock.uptimeMillis();
                // Watched whether or not anything is being written.
                boolean watched = motion != null;
                if (key) motionFrame(outFmt, buf, info);
                // The watch ended with a parked clip open: the car left Park
                // during an event. What follows is a drive and gets its own
                // clip (this is a key frame, so the rotation below does it).
                if (watched && motion == null && seg != null && seg.park) rotateArmed = true;
                // Read once: the motion thread changes it at any moment.
                boolean event = motion != null && motionEvent;

                // Armed and nothing moving: close what is open, keep the last
                // seconds in memory, write nothing.
                if (motion != null && !event) {
                    if (seg != null) {
                        final Seg old = seg;
                        closer.execute(() -> { old.finish(); enforceBudget(ctx, null); });
                        seg = null;
                        activeSegment = null;
                        rotateArmed = false;
                        rotateRequested = false;
                        lastCue = -1;
                    }
                    preRoll.add(buf, info.offset, info.size, key, nowUp, System.currentTimeMillis(), tele);
                    codec.releaseOutputBuffer(idx, false);
                    continue;
                }

                // An event just started, or the watch just ended: the clip
                // begins with the pre-roll (a parked clip only for an event).
                if (seg == null && !preRoll.isEmpty()) {
                    seg = new Seg(outFmt, preRoll.firstUptimeMs(), preRoll.firstWallMs(), event);
                    activeSegment = seg;
                    lastCue = -1;
                    for (PreRoll.Frame f : preRoll.frames()) {
                        long p = seg.ptsUs(f.uptimeMs);
                        seg.write(f.data, p, f.key);
                        long sec = (p / CUE_US) * CUE_US;
                        if (sec > lastCue) { seg.cue(sec, sec + CUE_US, f.tele); lastCue = sec; }
                    }
                }
                preRoll.clear();
                if (seg == null && key) {
                    seg = new Seg(outFmt, nowUp, System.currentTimeMillis(), event);
                    activeSegment = seg;
                    lastCue = -1;
                }
                if (seg == null) { codec.releaseOutputBuffer(idx, false); continue; }

                // Rotate at a key frame (segments must start seekable), and
                // never within the second the current segment started: a
                // close requested right after a rotation (an event, screen
                // off) waits for the next key frame, about a second, so two
                // segments never start in the same second.
                if (rotateArmed && key
                        && !SegmentFiles.sameSecond(seg.startWallMs, System.currentTimeMillis())) {
                    final Seg old = seg;
                    closer.execute(() -> {
                        old.finish();
                        Seg live = activeSegment;
                        enforceBudget(ctx, live == null ? null : live.stem);
                    });
                    seg = new Seg(outFmt, nowUp, System.currentTimeMillis(), event);
                    activeSegment = seg;
                    rotateArmed = false;
                    lastCue = -1;
                }

                // Use segment-relative clock: encoder timestamps are untrusted; this
                // ensures monotonic PTS and sync with subtitle timing.
                long pts = seg.ptsUs(nowUp);
                info.presentationTimeUs = pts;
                seg.write(buf, info, key);

                // Snap cue to the whole second: ensures each subtitle covers a full
                // second and stays synchronized despite frame timing variations.
                long sec = (pts / CUE_US) * CUE_US;
                if (sec > lastCue) {
                    seg.cue(sec, sec + CUE_US, tele);
                    lastCue = sec;
                }

                codec.releaseOutputBuffer(idx, false);

                if (!rotateArmed && (seg.ageMs() >= SEGMENT_MS || valetEdge || rotateRequested)) {
                    valetEdge = false;
                    rotateRequested = false;
                    Bundle b = new Bundle();
                    b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    codec.setParameters(b);
                    rotateArmed = true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "dashcam: " + t, t);
            lastError = String.valueOf(t);
        } finally {
            running = false;
            stopMotion();
            preRoll.clear();
            if (seg != null) seg.finish();
            activeSegment = null;
            if (closer != null) {
                closer.shutdown();
                try { closer.awaitTermination(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) { }
            try { if (input != null) input.release(); } catch (Throwable ignored) { }
            enforceBudget(ctx, null);
            RecorderState.write(dir(), "stopped", "", System.currentTimeMillis(), lastError);
            Log.i(TAG, "dashcam: stopped");
        }
    }

    // ------------------------------------------------------------ a segment

    // Written as .tmp and renamed on completion, so a clip that was interrupted is
    // never mistaken for a whole one — by the ring buffer, by a player, or by
    // whatever eventually uploads them.
    private final class Seg {
        final String stem;
        final File mp4, vtt, mp4Tmp, vttTmp, raw, jpg;
        // Use uptimeMillis, not elapsedRealtime: suspension doesn't interrupt the
        // timeline. Ensures video duration reflects actual recording time.
        // Earlier than now for a parked clip, which starts with its pre-roll.
        final long startMs;
        // Wall-clock start; the name is this second. See the rotation check.
        final long startWallMs;
        // A parked-motion clip; closed when the watch ends, see the loop.
        final boolean park;
        FragmentedMp4.Writer out;
        Vtt sub;
        long lastPts = -1;
        boolean done;

        /** `park`: a parked-motion clip, named `_park`. `startUptimeMs` and
         * `startWallMs` are those of its first frame. */
        Seg(MediaFormat f, long startUptimeMs, long startWall, boolean park) throws Exception {
            startMs = startUptimeMs;
            this.park = park;
            // A segment is named after the second it starts in, and only one
            // may start per second (see the rotation check). A recorder
            // restarting within the second its last segment started would
            // meet a used name: it waits for the next second instead of
            // writing over that clip.
            String suffix = new File(dir(), "valet.active").exists() ? "_valet"
                          : park ? PARK_SUFFIX : "";
            long now = startWall;
            String name = "dash_" + NAME.get().format(new Date(now)) + suffix;
            for (int tries = 0; SegmentFiles.taken(dir(), name) && tries < 3; tries++) {
                Thread.sleep(1000 - now % 1000 + 5);
                now = System.currentTimeMillis();
                name = "dash_" + NAME.get().format(new Date(now)) + suffix;
            }
            stem = name;
            startWallMs = now;
            File d = dir();
            mp4 = new File(d, stem + ".mp4");
            vtt = new File(d, stem + ".vtt");
            mp4Tmp = new File(d, stem + ".mp4.tmp");
            vttTmp = new File(d, stem + ".vtt.tmp");
            raw    = new File(d, stem + ".h264");   // legacy name, never written now
            jpg    = new File(d, stem + ".jpg");
            // Fragmented MP4, one fragment per key frame, each forced to
            // storage: the .mp4.tmp is a playable video at every moment, so a
            // crash costs at most the last second and there is nothing to
            // recover. It replaced MediaMuxer plus a raw .h264 write-ahead
            // copy, which wrote every frame twice. See FragmentedMp4.
            out = new FragmentedMp4.Writer(mp4Tmp, W, H, parameterSet(f, "csd-0"), parameterSet(f, "csd-1"));
            try { sub = new Vtt(vttTmp); } catch (Throwable t) { sub = null; }
            Log.i(TAG, "dashcam: segment " + mp4.getName());
            RecorderState.write(dir(), "recording", stem, System.currentTimeMillis(), null);
        }

        long ageMs() { return SystemClock.uptimeMillis() - startMs; }

        /** The frame captured at `uptimeMs`, in this segment's time. */
        long ptsUs(long uptimeMs) {
            long p = (uptimeMs - startMs) * 1000L;
            if (p <= lastPts) p = lastPts + 1;   // durations must be positive
            lastPts = p;
            return p;
        }

        void write(ByteBuffer b, MediaCodec.BufferInfo i, boolean key) {
            if (out == null) return;
            try { out.sample(b, i.offset, i.size, i.presentationTimeUs, key); }
            catch (Throwable t) {
                // Everything already written stays playable; stop here rather
                // than log 25 failures a second.
                Log.w(TAG, "dashcam: write failed, segment ends here: " + t);
                out = null;
            }
        }

        /** A frame copied earlier (the pre-roll), Annex-B. */
        void write(byte[] data, long ptsUs, boolean key) {
            if (out == null) return;
            try { out.sample(ByteBuffer.wrap(data), 0, data.length, ptsUs, key); }
            catch (Throwable t) {
                Log.w(TAG, "dashcam: write failed, segment ends here: " + t);
                out = null;
            }
        }

        void cue(long fromUs, long toUs, String text) {
            if (sub == null || text == null || text.isEmpty()) return;
            try { sub.cue(fromUs, toUs, text); } catch (Throwable ignored) { }
        }

        void finish() {
            if (done) return;
            done = true;
            boolean closed = false;
            FragmentedMp4.Writer w = out;
            out = null;
            try { closed = w != null && w.close(mp4Tmp); }
            catch (Throwable t) { Log.w(TAG, "dashcam: close failed: " + t); }
            if (sub != null) sub.close();
            // A segment that did not close cleanly keeps its .mp4.tmp, which
            // SegmentFiles.repair() turns into a clip later.
            if (SegmentFiles.finish(mp4Tmp, mp4, vttTmp, vtt, raw, closed)) {
                if (SegmentFiles.dropTiny(dir(), stem, lastPts)) {
                    Log.i(TAG, "dashcam: dropped " + mp4.getName() + ", under 2 s of video");
                    return;
                }
                thumbnail(mp4, jpg);
                SegmentFiles.keepIfHeld(dir(), keepDir(), stem);
            } else Log.w(TAG, "dashcam: " + mp4Tmp.getName() + " did not close; kept for repair");
            Log.i(TAG, "dashcam: closed " + mp4.getName() + " " + (mp4.length() / 1024) + " KB");
        }
    }

    /** An SPS or PPS from the encoder's output format, without its start
     * code or trailing zeros. */
    static byte[] parameterSet(MediaFormat f, String key) {
        ByteBuffer c = f.getByteBuffer(key).duplicate();
        byte[] a = new byte[c.remaining()];
        c.get(a);
        int from = 0;
        while (from < a.length && a[from] == 0) from++;
        if (from < a.length && a[from] == 1) from++;
        int end = a.length;
        while (end > from && a[end - 1] == 0) end--;
        return java.util.Arrays.copyOfRange(a, from, end);
    }

    // ONE THUMBNAIL PER SEGMENT, made at close rather than by the gallery
    // per row. MediaMetadataRetriever has to open and index the container to
    // find a frame, and doing that to a 600 MB file every time a list draws
    // is the same mistake the clip DURATION avoids by counting cues in the
    // sidecar instead.
    //
    // Asked for a frame one second in: at zero the camera is often still
    // adjusting exposure, and with a keyframe every second the seek is cheap
    // either way. The whole 2x2 is kept rather than one quadrant, because
    // what the clip contains IS four cameras and the row should say so.
    static void thumbnail(File mp4, File jpg) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        java.io.FileOutputStream out = null;
        try {
            r.setDataSource(mp4.getAbsolutePath());
            android.graphics.Bitmap full = r.getFrameAtTime(1_000_000,
                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            // A clip shorter than about a second and a half (closed right
            // after it started) has no frame there; take its first one.
            if (full == null) full = r.getFrameAtTime(0,
                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (full == null) return;
            android.graphics.Bitmap small =
                android.graphics.Bitmap.createScaledBitmap(full, 480, 200, true);
            out = new java.io.FileOutputStream(jpg);
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out);
            small.recycle();
            full.recycle();
        } catch (Throwable t) {
            Log.w(TAG, "dashcam: thumbnail: " + t);
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------ ring buffer

    // Oldest first against a byte budget — the same shape as DualDashcam's
    // ClipStore.enforceBudget, which is the one part of that app worth copying
    // outright. Run after every finished segment.
    //
    // Held clips still cannot be evicted — keepDir() is never in the
    // candidate list below, and neither is a clip still waiting for keepIfHeld
    // (a .hold beside it) — but they DO count against the budget, so
    // holding more leaves less room for new recording instead of being free
    // storage on top of it.
    //
    // Runs on the close thread while the NEXT segment is already recording.
    // That segment's files, like a recovery's, are written continuously, and
    // SegmentFiles leaves anything written in the last 10 minutes alone.
    static void enforceBudget(Context ctx, String liveStem) {
        // A segment whose writer never closed (a crash, a power cut) is still
        // a playable file up to its last fragment; finishing it makes it an
        // ordinary clip. Done here, before the budget counts it.
        try {
            for (String stem : SegmentFiles.repair(dir(), System.currentTimeMillis(), liveStem)) {
                Log.i(TAG, "dashcam: repaired " + stem + ".mp4");
                thumbnail(new File(dir(), stem + ".mp4"), new File(dir(), stem + ".jpg"));
                SegmentFiles.keepIfHeld(dir(), keepDir(), stem);
            }
        } catch (Throwable t) { Log.w(TAG, "dashcam: repair: " + t); }
        try {
            int gb = ctx.getSharedPreferences("modehelper", Context.MODE_PRIVATE)
                .getInt("dashcam_limit_gb", DEFAULT_BUDGET_GB);
            long budgetBytes = Math.max(1, gb) * 1024L * 1024 * 1024;
            for (String name : SegmentFiles.evict(dir(), keepDir(), budgetBytes,
                                                  System.currentTimeMillis()))
                Log.i(TAG, "dashcam: budget dropped " + name);
        } catch (Throwable t) { Log.w(TAG, "dashcam: budget: " + t); }
    }
}
