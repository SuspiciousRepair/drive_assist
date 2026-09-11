package com.geely.modehelper;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

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
    static final long BUDGET_BYTES = 10L * 1024 * 1024 * 1024;   // 10 GB ring buffer
    static final long CUE_US = 1_000_000L;                        // one subtitle per second

    // Speed limit candidates: multiple sources available (camera, navigation).
    // All are logged to determine which are reliable.
    static final int P_CAM_LIMIT = 0x2140b029;
    static final int P_NAVI_LIMIT = 0x2140303a;
    static final int P_NAVSPEED   = 0x2140a405;
    static final int P_TSR_KPH    = 0x2140a80f;

    private String lastLimits = "";

    // A real speed limit is a small round number. Anything outside this is an
    // enum, a sentinel or noise, and putting it on screen would be a lie.
    private static int plausible(Integer v) {
        return (v != null && v >= 5 && v <= 200) ? v : -1;
    }

    private static final SimpleDateFormat NAME =
        new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private final Context ctx;
    private final CarMode car;
    private volatile Location fix;
    private LocationManager lm;
    private LocationListener gps;
    private final EvsClient evs = new EvsClient();
    private volatile boolean running;
    private Thread thread;
    private Thread sampler;

    // Latest telemetry, refreshed off the encoder thread so a slow binder read can
    // never stall the drain loop.
    private volatile String tele = "";

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

    public synchronized void start() {
        if (running) return;
        running = true;
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
        while (running) {
            try {
                if (car.isReady()) {
                    StringBuilder b = new StringBuilder(48);
                    Float sp = car.readSpeed();
                    b.append(sp == null ? "?" : String.valueOf(Math.round(sp))).append(" km/h");
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
                    Integer tsrK  = car.readIntProp(P_TSR_KPH, 0);

                    int lim = plausible(camL);
                    if (lim < 0) lim = plausible(naviL);
                    if (lim < 0) lim = plausible(navS);
                    if (lim > 0) b.append(" · max ").append(lim);

                    // Log speed limits only on change: reduces clutter in the log.
                    String now = camL + "," + naviL + "," + navS + "," + tsrK;
                    if (!now.equals(lastLimits)) {
                        lastLimits = now;
                        limitLog("cam=" + camL + " navi=" + naviL + " navspeed=" + navS
                               + " tsrkph=" + tsrK + " speed=" + (sp == null ? "?" : Math.round(sp)));
                    }
                    // Turn signal omitted: no verified property in field-catalog yet.
                    tele = b.toString();
                }
            } catch (Throwable t) { Log.w(TAG, "dashcam: sample: " + t); }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }

    // Its own file rather than the subtitle track, because this is an
    // experiment: it has to survive whichever clip was rotating at the time and
    // be readable without opening a video.
    private void limitLog(String line) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(new File(dir(), "limits.log"), true);
            w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "  " + line + "\n");
            w.close();
        } catch (Throwable t) { Log.w(TAG, "dashcam: limitLog: " + t); }
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
            if (!evs.connect()) { Log.w(TAG, "dashcam: no engine, giving up"); running = false; return; }
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
                Log.w(TAG, "dashcam: attach refused");
                running = false;
                return;
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaFormat outFmt = null;
            boolean rotateArmed = false;
            long lastCue = -1;

            while (running) {
                int idx = codec.dequeueOutputBuffer(info, 20000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFmt = codec.getOutputFormat();
                    seg = new Seg(outFmt);
                    continue;
                }
                if (idx < 0) continue;

                ByteBuffer buf = codec.getOutputBuffer(idx);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean key    = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                // csd lives in the output format, which the muxer already took;
                // writing it as a sample as well produces a file some players
                // refuse.
                if (config || seg == null || buf == null) { codec.releaseOutputBuffer(idx, false); continue; }

                // Rotate segment at a key frame: segments must start seekable.
                if (rotateArmed && key) {
                    seg.finish();
                    enforceBudget();
                    seg = new Seg(outFmt);
                    rotateArmed = false;
                    lastCue = -1;
                }

                // Use segment-relative clock: encoder timestamps are untrusted; this
                // ensures monotonic PTS and sync with subtitle timing.
                long pts = seg.ptsUs();
                info.presentationTimeUs = pts;
                seg.write(buf, info);

                // Snap cue to the whole second: ensures each subtitle covers a full
                // second and stays synchronized despite frame timing variations.
                long sec = (pts / CUE_US) * CUE_US;
                if (sec > lastCue) {
                    seg.cue(sec, sec + CUE_US, tele);
                    lastCue = sec;
                }

                codec.releaseOutputBuffer(idx, false);

                if (!rotateArmed && seg.ageMs() >= SEGMENT_MS) {
                    Bundle b = new Bundle();
                    b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    codec.setParameters(b);
                    rotateArmed = true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "dashcam: " + t, t);
        } finally {
            running = false;
            if (seg != null) seg.finish();
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) { }
            try { if (input != null) input.release(); } catch (Throwable ignored) { }
            enforceBudget();
            stopGps();
            Log.i(TAG, "dashcam: stopped");
        }
    }

    // ------------------------------------------------------------ a segment

    // Written as .tmp and renamed on completion, so a clip that was interrupted is
    // never mistaken for a whole one — by the ring buffer, by a player, or by
    // whatever eventually uploads them.
    private final class Seg {
        final File mp4, vtt, mp4Tmp, vttTmp, raw, jpg;
        final MediaMuxer muxer;
        final int track;
        // Use uptimeMillis, not elapsedRealtime: suspension doesn't interrupt the
        // timeline. Ensures video duration reflects actual recording time.
        final long startMs = SystemClock.uptimeMillis();
        Vtt sub;
        java.io.FileOutputStream rawOut;
        long lastPts = -1;
        boolean done;

        Seg(MediaFormat f) throws Exception {
            String stem = "dash_" + NAME.format(new Date());
            File d = dir();
            mp4 = new File(d, stem + ".mp4");
            vtt = new File(d, stem + ".vtt");
            mp4Tmp = new File(d, stem + ".mp4.tmp");
            vttTmp = new File(d, stem + ".vtt.tmp");
            raw    = new File(d, stem + ".h264");
            jpg    = new File(d, stem + ".jpg");
            muxer = new MediaMuxer(mp4Tmp.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            track = muxer.addTrack(f);
            muxer.start();
            try { sub = new Vtt(vttTmp); } catch (Throwable t) { sub = null; }

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
                rawOut = new java.io.FileOutputStream(raw);
                writeCsd(f);
            } catch (Throwable t) { rawOut = null; }

            Log.i(TAG, "dashcam: segment " + mp4.getName());
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

        void write(ByteBuffer b, MediaCodec.BufferInfo i) {
            if (rawOut != null) {
                try {
                    int pos = b.position(), lim = b.limit();
                    byte[] a = new byte[i.size];
                    b.position(i.offset);
                    b.limit(i.offset + i.size);
                    b.get(a);
                    rawOut.write(a);
                    b.position(pos);
                    b.limit(lim);
                } catch (Throwable t) { Log.w(TAG, "dashcam: raw write: " + t); rawOut = null; }
            }
            try { muxer.writeSampleData(track, b, i); }
            catch (Throwable t) { Log.w(TAG, "dashcam: writeSampleData: " + t); }
        }

        void cue(long fromUs, long toUs, String text) {
            if (sub == null || text == null || text.isEmpty()) return;
            try { sub.cue(fromUs, toUs, text); } catch (Throwable ignored) { }
        }

        void finish() {
            if (done) return;
            done = true;
            try { muxer.stop(); } catch (Throwable ignored) { }
            try { muxer.release(); } catch (Throwable ignored) { }
            if (sub != null) sub.close();
            // The mp4 is renamed FIRST: a .vtt with no clip beside it is litter,
            // but a clip with no subtitles is still footage.
            if (mp4Tmp.exists() && mp4Tmp.length() > 0) mp4Tmp.renameTo(mp4); else mp4Tmp.delete();
            if (vttTmp.exists()) { if (mp4.exists()) vttTmp.renameTo(vtt); else vttTmp.delete(); }
            // The write-ahead stream is a safety net for a segment that never
            // closed. This one closed, so it goes — otherwise it would double the
            // archive for nothing.
            try { if (rawOut != null) rawOut.close(); } catch (Throwable ignored) { }
            if (mp4.exists()) { raw.delete(); thumbnail(); }
            else Log.w(TAG, "dashcam: kept " + raw.getName() + " — the mp4 never closed");
            Log.i(TAG, "dashcam: closed " + mp4.getName() + " " + (mp4.length() / 1024) + " KB");
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

    // Oldest first against a byte budget — the same shape as DualDashcam's
    // ClipStore.enforceBudget, which is the one part of that app worth copying
    // outright. Run after every finished segment.
    //
    // Held clips still cannot be evicted — keepDir() is never in the
    // candidate list below — but they DO count against BUDGET_BYTES, so
    // holding more leaves less room for new recording instead of being free
    // storage on top of the 10 GB budget.
    static void enforceBudget() {
        try {
            File[] all = dir().listFiles();
            if (all == null) return;
            File[] clips = Arrays.stream(all)
                .filter(f -> f.isFile() && f.getName().endsWith(".mp4"))
                .sorted(Comparator.comparingLong(File::lastModified))
                .toArray(File[]::new);
            long used = 0;
            for (File f : all) if (f.isFile()) used += Math.max(0, f.length());
            File[] held = keepDir().listFiles();
            if (held != null) for (File f : held) if (f.isFile()) used += Math.max(0, f.length());
            if (used <= BUDGET_BYTES) return;
            for (File f : clips) {
                if (used <= BUDGET_BYTES) return;
                String stem = f.getName().substring(0, f.getName().length() - 4);
                File side = new File(f.getParentFile(), stem + ".vtt");
                File th   = new File(f.getParentFile(), stem + ".jpg");
                long freed = Math.max(0, f.length())
                           + (side.exists() ? Math.max(0, side.length()) : 0)
                           + (th.exists()   ? Math.max(0, th.length())   : 0);
                if (f.delete()) {
                    side.delete();
                    th.delete();
                    used -= freed;
                    Log.i(TAG, "dashcam: budget dropped " + f.getName());
                }
            }
        } catch (Throwable t) { Log.w(TAG, "dashcam: budget: " + t); }
    }
}
