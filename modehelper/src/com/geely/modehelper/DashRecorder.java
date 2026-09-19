package com.geely.modehelper;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Records the full DVR camera composite through one EVS/EGL input shared with
 * an optional live preview. Encoding lives in the foreground helper and continues
 * when the preview Activity closes. See docs/DASHCAM.md and docs/EVS-CAMERA.md. */
public final class DashRecorder {
    static final String TAG = "ModeHelper";

    static final int  W = 1920, H = 800;          // the dvr surface, exactly — no scaling
    static final int  FPS = 25;
    // 16 Mbit/s: increased from 6 for better quality. Ring buffer duration is
    // roughly inversely proportional (10 GB holds ~1.4 hours at this rate).
    static final int  BITRATE = 16_000_000;
    static final int  IFRAME_SEC = 1;
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
    private final DashcamRunState runState = new DashcamRunState();
    private final DashcamPreviewController previewController = new DashcamPreviewController(this::isRunning);
    private volatile Seg activeSegment;
    private volatile boolean rotateRequested;
    private Thread thread;
    private Thread sampler;

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

    public boolean isRunning() { return runState.isRecording(); }

    public void preview(String session, Surface ownedSurface, ResultReceiver status) {
        previewController.accept(session, ownedSurface, status);
    }

    public void closePreview() { previewController.close(); }

    public synchronized void start() {
        if (runState.requestStart()) launch();
    }

    /** Called under this recorder's lock, after runState grants sole ownership. */
    private void launch() {
        rotateRequested = false;
        status("starting", "");
        previewController.starting();
        startGps();
        thread = new Thread(this::loop, "dashcam");
        sampler = new Thread(this::sample, "dashcam-tele");
        sampler.start();
        thread.start();
        Log.i(TAG, "dashcam: starting");
    }

    public synchronized void stop() {
        runState.requestStop();
        if (sampler != null) sampler.interrupt();
        Log.i(TAG, "dashcam: stop requested");
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

    private File nextDirectory(String preferred) throws IOException {
        return DashcamStorage.resolve(preferred, new File(CLIP_DIR), new File("/storage"),
            volume -> {
                try {
                    return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(volume))
                        && Environment.isExternalStorageRemovable(volume) && volume.canWrite();
                } catch (IllegalArgumentException | SecurityException unavailable) {
                    return false;
                }
            });
    }

    private void status(String state, String error) {
        ctx.getSharedPreferences("modehelper", Context.MODE_PRIVATE).edit()
            .putString("dashcam_state", state).putString("dashcam_error", error).apply();
    }

    // ------------------------------------------------------------ telemetry

    private void sample() {
        while (runState.isRecording() && !Thread.currentThread().isInterrupted()) {
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
        EvsFrameFanout fanout = null;
        Seg seg = null;
        Throwable failure = null;
        try {
            if (!evs.connect()) throw new IOException("Camera engine unavailable");
            if (!evs.openCamera(EvsClient.CAMERA_AVM)) throw new IOException("Camera open refused");

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

            // One camera consumer feeds both outputs. Preview attach/detach never
            // touches EVS and never stops this encoder/background worker.
            fanout = new EvsFrameFanout(input, W, H);
            fanout.start();
            previewController.bind(fanout);
            if (!evs.attach(fanout.inputSurface(), EvsClient.TYPE_DVR, EvsClient.CAMERA_AVM)) {
                throw new IOException("Camera attachment refused");
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaFormat outFmt = null;
            boolean rotateArmed = false;
            long lastCue = -1;

            while (runState.isRecording()) {
                fanout.checkHealth();
                int idx = codec.dequeueOutputBuffer(info, 20000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFmt = codec.getOutputFormat();
                    seg = new Seg(outFmt);
                    activeSegment = seg;
                    status("recording", "");
                    continue;
                }
                if (idx < 0) continue;

                try {
                    ByteBuffer buf = codec.getOutputBuffer(idx);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    boolean key    = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                    // csd lives in the output format, which the muxer already took;
                    // writing it as a sample as well produces a file some players
                    // refuse.
                    if (config || seg == null || buf == null) continue;

                    // Rotate segment at a key frame: segments must start seekable.
                    if (rotateArmed && key) {
                        if (!seg.finish()) throw new IOException("Could not finalize dashcam segment");
                        enforceBudget(ctx, seg.directory);
                        activeSegment = null;
                        seg = new Seg(outFmt);
                        activeSegment = seg;
                        rotateArmed = false;
                        lastCue = -1;
                    }

                    // Use segment-relative clock: encoder timestamps are untrusted;
                    // this ensures monotonic PTS and sync with subtitle timing.
                    long pts = seg.ptsUs();
                    info.presentationTimeUs = pts;
                    seg.write(buf, info);

                    // Snap cue to the whole second: ensures each subtitle covers a
                    // full second despite frame timing variations.
                    long sec = (pts / CUE_US) * CUE_US;
                    if (sec > lastCue) {
                        seg.cue(sec, sec + CUE_US, tele);
                        lastCue = sec;
                    }

                    if (!rotateArmed && (seg.ageMs() >= seg.durationMs || valetEdge || rotateRequested)) {
                        valetEdge = false;
                        rotateRequested = false;
                        Bundle b = new Bundle();
                        b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        codec.setParameters(b);
                        rotateArmed = true;
                    }
                } finally {
                    codec.releaseOutputBuffer(idx, false);
                }
            }
        } catch (Throwable t) {
            failure = t;
            Log.w(TAG, "dashcam: " + t, t);
        } finally {
            runState.workerStopping();
            if (sampler != null) sampler.interrupt();
            if (fanout != null) {
                fanout.requestStop();
                // EGL encoder swap can wait for a free codec buffer. Continue
                // draining while GL shuts down; joining GL first would deadlock.
                if (codec != null) {
                    long until = SystemClock.uptimeMillis() + 3000L;
                    boolean eosSent = false;
                    MediaCodec.BufferInfo closing = new MediaCodec.BufferInfo();
                    try {
                        while (SystemClock.uptimeMillis() < until) {
                            if (fanout.isStopped() && !eosSent) {
                                codec.signalEndOfInputStream(); eosSent = true;
                            }
                            int index = codec.dequeueOutputBuffer(closing, 20_000);
                            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && seg == null)
                                seg = new Seg(codec.getOutputFormat());
                            if (index < 0) continue;
                            try {
                                ByteBuffer buffer = codec.getOutputBuffer(index);
                                if (seg != null && buffer != null && closing.size > 0
                                        && (closing.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    closing.presentationTimeUs = seg.ptsUs();
                                    seg.write(buffer, closing);
                                }
                            } finally { codec.releaseOutputBuffer(index, false); }
                            if ((closing.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                        }
                    } catch (Throwable problem) { if (failure == null) failure = problem; }
                }
            }
            // A stalled GL swap can also be released by stopping its codec. Keep
            // the camera owner reserved until both GL threads have actually exited.
            try { if (codec != null) codec.stop(); } catch (Throwable ignored) { }
            if (fanout != null) {
                if (!fanout.awaitStopped(10_000L)) {
                    if (failure == null) failure = new IOException("Camera renderer shutdown delayed");
                    status("error", "RendererShutdown");
                    while (!fanout.awaitStopped(1000L)) {
                        // No retry or second EVS owner while an old output still
                        // owns resources. Main's dead-screen lease releases its UI.
                        if (Thread.currentThread().isInterrupted()) break;
                    }
                }
            }
            if (seg != null && !seg.finish() && failure == null)
                failure = new IOException("Could not finalize dashcam segment");
            if (fanout == null || fanout.isStopped()) previewController.unbind(fanout, failure != null);
            activeSegment = null;
            try { if (codec != null) codec.release(); } catch (Throwable ignored) { }
            try { if (input != null) input.release(); } catch (Throwable ignored) { }
            if (seg != null) enforceBudget(ctx, seg.directory);
            stopGps();
            status(failure == null ? "stopped" : "error",
                failure == null ? "" : failure.getClass().getSimpleName());
            Log.i(TAG, "dashcam: stopped");
            synchronized (this) {
                // An explicit On received during teardown waits here; a later
                // Off cancels it. Never attach a second encoder concurrently.
                if ((fanout == null || fanout.isStopped()) && runState.workerFinished()) launch();
            }
        }
    }

    // ------------------------------------------------------------ a segment

    // Written as .tmp and renamed on completion, so a clip that was interrupted is
    // never mistaken for a whole one — by the ring buffer, by a player, or by
    // whatever eventually uploads them.
    private final class Seg {
        final File directory;
        final long durationMs;
        final File mp4, vtt, mp4Tmp, vttTmp, raw, jpg, hold;
        MediaMuxer muxer;
        int track;
        // Use uptimeMillis, not elapsedRealtime: suspension doesn't interrupt the
        // timeline. Ensures video duration reflects actual recording time.
        final long startMs = SystemClock.uptimeMillis();
        Vtt sub;
        java.io.FileOutputStream rawOut;
        long lastPts = -1;
        boolean done;
        boolean failed;
        boolean finalized;

        Seg(MediaFormat f) throws Exception {
            // Snapshot the options for this clip. Changing preferences
            // affects the next segment, never the deadline/path of this one.
            SharedPreferences preferences = ctx.getSharedPreferences("modehelper", Context.MODE_PRIVATE);
            durationMs = DashcamOptions.segmentMinutes(preferences.getInt(
                DashcamOptions.SEGMENT_MINUTES, DashcamOptions.DEFAULT_SEGMENT_MINUTES)) * 60_000L;
            String preferred = DashcamOptions.storage(preferences.getString(
                DashcamOptions.STORAGE, DashcamOptions.INTERNAL));
            directory = nextDirectory(preferred);
            String actual = directory.equals(new File(CLIP_DIR)) ? DashcamOptions.INTERNAL : preferred;
            preferences.edit().putString("dashcam_active_storage", actual).apply();
            if (!actual.equals(preferred)) Log.w(TAG, "dashcam: USB unavailable, using internal storage");
            enforceBudget(ctx, directory);
            String base = "dash_" + NAME.format(new Date())
                + (new File(dir(), "valet.active").exists() ? "_valet" : "");
            String stem = base;
            // A stop/start or event can happen within one second. Never overwrite
            // an older clip (including protected/recovery files) with that name.
            int duplicate = 1;
            while (stemExists(directory, stem)) stem = base + "_" + (++duplicate);
            File d = directory;
            mp4 = new File(d, stem + ".mp4");
            vtt = new File(d, stem + ".vtt");
            mp4Tmp = new File(d, stem + ".mp4.tmp");
            vttTmp = new File(d, stem + ".vtt.tmp");
            raw    = new File(d, stem + ".h264");
            jpg    = new File(d, stem + ".jpg");
            hold   = new File(d, stem + ".hold");
            // The write-ahead stream, and the reason it exists.
            //
            // MP4 keeps its index in a moov atom that MediaMuxer only writes on
            // stop(), so a .mp4.tmp is worthless until the segment closes: a
            // crash mid-segment leaves an unplayable file with no moov atom and
            // no recoverable footage — losing exactly the event you'd want it for.
            //
            // So every access unit is also appended raw, Annex-B, to a .h264.
            // A raw elementary stream has no index to lose — it is valid at every
            // byte — and `ffmpeg -i x.h264 -c copy x.mp4` remuxes it. On a clean
            // close it is deleted, so the cost is 2x disk for the CURRENT segment
            // only, never for the archive.
            try {
                muxer = new MediaMuxer(mp4Tmp.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                track = muxer.addTrack(f);
                muxer.start();
                sub = new Vtt(vttTmp);
                rawOut = new java.io.FileOutputStream(raw);
                writeCsd(f);
            } catch (Exception failure) {
                failed = true;
                finish();
                throw failure;
            }

            Log.i(TAG, "dashcam: segment " + mp4.getName() + " storage=" + actual
                + " minutes=" + durationMs / 60_000L);
        }

        long ageMs() { return SystemClock.uptimeMillis() - startMs; }

        long ptsUs() {
            long p = (SystemClock.uptimeMillis() - startMs) * 1000L;
            if (p <= lastPts) p = lastPts + 1;   // the muxer demands strictly increasing
            lastPts = p;
            return p;
        }

        // SPS/PPS first, or the stream cannot be decoded from the top. They come
        // out of the output format as csd-0 / csd-1 rather than as a separate
        // config buffer, which is why the config-flagged buffers are still
        // skipped everywhere else.
        void writeCsd(MediaFormat f) throws Exception {
            for (String k : new String[]{"csd-0", "csd-1"}) {
                if (!f.containsKey(k)) continue;
                ByteBuffer c = f.getByteBuffer(k).duplicate();
                byte[] a = new byte[c.remaining()];
                c.get(a);
                rawOut.write(a);
            }
        }

        void write(ByteBuffer b, MediaCodec.BufferInfo i) throws IOException {
            try {
                ByteBuffer sample = b.duplicate();
                sample.position(i.offset);
                sample.limit(i.offset + i.size);
                byte[] a = new byte[i.size];
                sample.get(a);
                rawOut.write(a);
                muxer.writeSampleData(track, b, i);
            } catch (Exception failure) {
                failed = true;
                throw new IOException("Dashcam video write failed", failure);
            }
        }

        void cue(long fromUs, long toUs, String text) throws IOException {
            if (sub == null || text == null || text.isEmpty()) return;
            try { sub.cue(fromUs, toUs, text); }
            catch (IOException failure) {
                failed = true;
                throw new IOException("Dashcam subtitle write failed", failure);
            }
        }

        void markHeld() {
            try { hold.createNewFile(); }
            catch (Throwable t) { Log.w(TAG, "dashcam: could not mark event segment", t); }
        }

        boolean finish() {
            if (done) return finalized;
            done = true;
            boolean closed = !failed;
            try { if (muxer != null) muxer.stop(); else closed = false; }
            catch (Throwable failure) { closed = false; Log.w(TAG, "dashcam: muxer close failed", failure); }
            try { if (muxer != null) muxer.release(); } catch (Throwable ignored) { }
            if (sub != null) sub.close();
            try { if (rawOut != null) rawOut.close(); }
            catch (IOException failure) { closed = false; Log.w(TAG, "dashcam: raw close failed", failure); }
            // The mp4 is renamed FIRST: a .vtt with no clip beside it is litter,
            // but a clip with no subtitles is still footage.
            finalized = closed && mp4Tmp.exists() && mp4Tmp.length() > 0
                && !mp4.exists() && mp4Tmp.renameTo(mp4);
            if (finalized && vttTmp.exists() && !vttTmp.renameTo(vtt))
                Log.w(TAG, "dashcam: subtitle sidecar could not be finalized");
            // The write-ahead stream is a safety net for a segment that never
            // closed. Only discard it after a successful muxer stop AND rename;
            // a failed close must never masquerade as a complete MP4.
            if (finalized) { raw.delete(); thumbnail(); }
            else Log.w(TAG, "dashcam: kept " + raw.getName() + " — the mp4 never closed");
            Log.i(TAG, "dashcam: closed " + mp4.getName() + " " + (mp4.length() / 1024) + " KB");
            return finalized;
        }

        // ONE THUMBNAIL PER SEGMENT, made here at close rather than by the
        // gallery per row. MediaMetadataRetriever has to open and index the
        // container to find a frame, and doing that to a 225 MB file every time a
        // list draws is the same mistake the clip DURATION avoids by counting
        // cues in the sidecar instead.
        //
        // Asked for a frame one second in: at zero the camera is often still
        // adjusting exposure, and with a keyframe every second the seek is cheap
        // either way. The whole 2x2 is kept rather than one quadrant, because
        // what the clip contains IS four cameras and the row should say so.
        void thumbnail() {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            java.io.FileOutputStream out = null;
            try {
                r.setDataSource(mp4.getAbsolutePath());
                android.graphics.Bitmap full = r.getFrameAtTime(1_000_000,
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
    }

    // ------------------------------------------------------------ ring buffer

    // Oldest first against a byte budget, applied to the actual segment volume.
    // Run before opening and after finishing a segment.
    //
    // Held clips cannot be evicted (including pending .hold markers), but they
    // DO count against the budget, so
    // holding more leaves less room for new recording instead of being free
    // storage on top of it.
    static void enforceBudget(Context ctx, File directory) {
        try {
            int gb = ctx.getSharedPreferences("modehelper", Context.MODE_PRIVATE)
                .getInt("dashcam_limit_gb", DEFAULT_BUDGET_GB);
            long budgetBytes = Math.max(1, gb) * 1024L * 1024 * 1024;
            DashcamBudget.enforce(directory, budgetBytes);
        } catch (Throwable t) { Log.w(TAG, "dashcam: budget: " + t); }
    }

    private static boolean stemExists(File directory, String stem) throws IOException {
        for (File location : new File[]{directory, new File(directory, "keep")}) {
            for (String suffix : new String[]{".mp4", ".mp4.tmp", ".vtt", ".vtt.tmp", ".h264", ".jpg", ".hold"}) {
                File file = new File(location, stem + suffix);
                if (file.exists() || !DashcamStorage.directChild(location, file)) return true;
            }
        }
        return false;
    }
}
