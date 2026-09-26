package com.geely.modehelper;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
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
    // then nav-speed, in that fallback order — see plausible() below.
    static final int P_CAM_LIMIT = 0x2140b029;
    static final int P_NAVI_LIMIT = 0x2140303a;
    static final int P_NAVSPEED   = 0x2140a405;

    // A real speed limit is a small round number. Anything outside this is an
    // enum, a sentinel or noise, and putting it on screen would be a lie.
    private static int plausible(Integer v) {
        return (v != null && v >= 5 && v <= 200) ? v : -1;
    }

    private static final SimpleDateFormat NAME =
        new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private static final SimpleDateFormat SUB_TIME =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private final Context ctx;
    private final CarMode car;
    private volatile Location fix;
    private LocationManager lm;
    private LocationListener gps;
    private final EvsClient evs = new EvsClient();
    private volatile boolean running;
    private volatile Seg activeSegment;
    private volatile boolean rotateRequested;
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

    // GPS runs only while recording. It is not free, and this thing now records
    // whenever the system is up — leaving the receiver on around the clock to
    // stamp a heading on a parked car would be a poor trade.
    private void startGps() {
        try {
            lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            fix = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            gps = new LocationListener() {
                @Override public void onLocationChanged(Location l) { fix = l; }
                @Override public void onStatusChanged(String p, int st, Bundle e) { }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
            };
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gps,
                                      Looper.getMainLooper());
        } catch (Throwable t) { Log.w(TAG, "dashcam: gps: " + t); }
    }

    private void stopGps() {
        try { if (lm != null && gps != null) lm.removeUpdates(gps); }
        catch (Throwable ignored) { }
        lm = null; gps = null; fix = null;
    }

    // 16 points, which is as fine as a heading is worth reading at a glance.
    private static final String[] ROSE = {
        "N","NNE","NE","ENE","E","ESE","SE","SSE","S","SSW","SW","WSW","W","WNW","NW","NNW" };

    static String heading(float deg) {
        int i = (int) Math.round(deg / 22.5) % 16;
        if (i < 0) i += 16;
        return ROSE[i] + " " + Math.round(deg) + "\u00B0";
    }

    public boolean isRunning() { return running; }

    /** uptime when start() last ran; the supervisor resets its backoff once a
     * recorder has stayed up a while. */
    public long startedUptimeMs() { return startedUptimeMs; }

    public synchronized void start() {
        if (running) return;
        running = true;
        startedUptimeMs = SystemClock.uptimeMillis();
        lastError = null;
        startGps();
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

    /** Preserve and promptly close the segment containing a detected event. */
    public void saveCurrentSegmentForEvent() {
        Seg segment = activeSegment;
        if (segment == null) {
            Log.w(TAG, "dashcam: event had no active segment to save");
            return;
        }
        segment.markHeld();
        rotateRequested = true;
        Log.i(TAG, "dashcam: event segment marked for keep " + segment.mp4.getName());
    }

    /** Close the open segment at the next key frame (within a second) and
     * carry on in a new one. Called when the screen goes off and when the car
     * announces a suspend: the unit then sleeps with the segment open for
     * hours, and a power cut in that time used to orphan a whole segment. */
    public void closeSegmentSoon(String why) {
        if (activeSegment == null) return;
        rotateRequested = true;
        Log.i(TAG, "dashcam: " + why + " — closing the segment");
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
        while (running) {
            Seg open = activeSegment;
            if (tick++ % 5 == 0 && open != null)
                RecorderState.write(dir(), "recording", open.stem, System.currentTimeMillis(), null);
            try {
                if (car.isReady()) {
                    boolean valet = new File(dir(), "valet.active").exists();
                    if (valet != lastValet) { lastValet = valet; valetEdge = true; }
                    StringBuilder b = new StringBuilder(64);
                    b.append(SUB_TIME.format(new Date()));
                    Float sp = car.readSpeed();
                    b.append(" · ").append(sp == null ? "?" : String.valueOf(Math.round(sp))).append(" km/h");
                    Integer g = car.readGear();
                    if (g != null) b.append(" · ").append(gear(g));
                    Float t = car.readOutsideTempC();
                    if (t != null) b.append(" · ").append(String.format(Locale.US, "%.1f°", t));

                    // Heading only when moving: GPS bearing is derived from velocity
                    // and unreliable when parked.
                    Location f = fix;
                    if (f != null && f.hasBearing() && sp != null && sp > 3f)
                        b.append(" · ").append(heading(f.getBearing()));

                    Integer camL  = car.readIntProp(P_CAM_LIMIT, 0);
                    Integer naviL = car.readIntProp(P_NAVI_LIMIT, 0);
                    Integer navS  = car.readIntProp(P_NAVSPEED, 0);

                    int lim = plausible(camL);
                    if (lim < 0) lim = plausible(naviL);
                    if (lim < 0) lim = plausible(navS);
                    if (lim > 0) b.append(" · max ").append(lim);

                    // Turn signal omitted: no verified property in field-catalog yet.
                    tele = b.toString();
                }
            } catch (Throwable t) { Log.w(TAG, "dashcam: sample: " + t); }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }

    private static String gear(int g) {
        switch (g) { case 1: return "N"; case 2: return "R"; case 4: return "P"; case 8: return "D"; }
        return "g" + g;
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
                int idx = codec.dequeueOutputBuffer(info, 20000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFmt = codec.getOutputFormat();
                    seg = new Seg(outFmt);
                    activeSegment = seg;
                    continue;
                }
                if (idx < 0) continue;

                ByteBuffer buf = codec.getOutputBuffer(idx);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean key    = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                // csd lives in the output format, which the writer already put in
                // the avcC; writing it as a sample as well produces a file some
                // players refuse.
                if (config || seg == null || buf == null) { codec.releaseOutputBuffer(idx, false); continue; }

                // Rotate segment at a key frame: segments must start seekable.
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
                    seg = new Seg(outFmt);
                    activeSegment = seg;
                    rotateArmed = false;
                    lastCue = -1;
                }

                // Use segment-relative clock: encoder timestamps are untrusted; this
                // ensures monotonic PTS and sync with subtitle timing.
                long pts = seg.ptsUs();
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
            stopGps();
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
        final File mp4, vtt, mp4Tmp, vttTmp, raw, jpg, hold;
        // Use uptimeMillis, not elapsedRealtime: suspension doesn't interrupt the
        // timeline. Ensures video duration reflects actual recording time.
        final long startMs = SystemClock.uptimeMillis();
        // Wall-clock start; the name is this second. See the rotation check.
        final long startWallMs;
        FragmentedMp4.Writer out;
        Vtt sub;
        long lastPts = -1;
        boolean done;

        Seg(MediaFormat f) throws Exception {
            // A segment is named after the second it starts in, and only one
            // may start per second (see the rotation check). A recorder
            // restarting within the second its last segment started would
            // meet a used name: it waits for the next second instead of
            // writing over that clip.
            String suffix = new File(dir(), "valet.active").exists() ? "_valet" : "";
            long now = System.currentTimeMillis();
            String name = "dash_" + NAME.format(new Date(now)) + suffix;
            for (int tries = 0; SegmentFiles.taken(dir(), name) && tries < 3; tries++) {
                Thread.sleep(1000 - now % 1000 + 5);
                now = System.currentTimeMillis();
                name = "dash_" + NAME.format(new Date(now)) + suffix;
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
            hold   = new File(d, stem + ".hold");
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

        long ptsUs() {
            long p = (SystemClock.uptimeMillis() - startMs) * 1000L;
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

        void cue(long fromUs, long toUs, String text) {
            if (sub == null || text == null || text.isEmpty()) return;
            try { sub.cue(fromUs, toUs, text); } catch (Throwable ignored) { }
        }

        void markHeld() {
            try { hold.createNewFile(); }
            catch (Throwable t) { Log.w(TAG, "dashcam: could not mark event segment", t); }
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
