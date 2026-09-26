package com.geely.drivemem.util;

import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Recovers an interrupted dashcam segment. Native muxing runs in a separate
 * process so a malformed/truncated elementary stream cannot crash the UI. */
public final class ClipRecovery {
    private static final String TAG = "DriveMem";
    static final String EXTRA_PATH = "path", EXTRA_RESULT = "result", RESULT_MESSAGE = "message";
    static final int RESULT_OK = 1, RESULT_ERROR = 2;
    private static final int W = 1920, H = 800, FPS = 25;
    private static final int BITRATE = 16_000_000, IFRAME_SEC = 1;
    private static final int MAX_SAMPLE_BYTES = 4 * 1024 * 1024;
    private static final long RECOVERY_TIMEOUT_MS = 5 * 60_000L;

    public interface Callback { void onDone(boolean ok, String message); }

    /** A native MediaMuxer abort only terminates :cliprecovery. The original
     * .h264 is retained, and the watchdog returns control to the UI. */
    public static void recover(Context context, File h264, Callback cb) {
        AtomicBoolean delivered = new AtomicBoolean();
        Handler main = new Handler(Looper.getMainLooper());
        Runnable timedOut = () -> {
            if (delivered.compareAndSet(false, true))
                cb.onDone(false, "Recovery process stopped or timed out; original footage was kept");
        };
        ResultReceiver result = new ResultReceiver(main) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (delivered.compareAndSet(false, true)) {
                    main.removeCallbacks(timedOut);
                    cb.onDone(code == RESULT_OK, data == null ? "Unknown recovery error"
                        : data.getString(RESULT_MESSAGE, "Unknown recovery error"));
                }
            }
        };
        try {
            context.startService(new Intent(context, ClipRecoveryService.class)
                .putExtra(EXTRA_PATH, h264.getAbsolutePath()).putExtra(EXTRA_RESULT, result));
            main.postDelayed(timedOut, RECOVERY_TIMEOUT_MS);
        } catch (Throwable t) {
            Log.w(TAG, "could not start clip recovery", t);
            if (delivered.compareAndSet(false, true)) cb.onDone(false, String.valueOf(t.getMessage()));
        }
    }

    /** Called only by the isolated service. */
    static String doRecover(File h264) throws IOException {
        String stem = Clips.name(h264);
        File dir = h264.getParentFile();
        File mp4 = new File(dir, stem + ".mp4"), mp4Tmp = new File(dir, stem + ".mp4.tmp");
        if (mp4Tmp.exists() && !mp4Tmp.delete()) throw new IOException("Could not clear prior partial recovery");
        int frames = 0;
        MediaMuxer muxer = null;
        try (AnnexB.Reader reader = new AnnexB.Reader(h264)) {
            byte[] sps = reader.next(), pps = reader.next();
            if (sps == null || pps == null || AnnexB.type(sps) != 7 || AnnexB.type(pps) != 8)
                throw new IOException("Expected SPS/PPS at the start of the recording");
            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd(sps))); fmt.setByteBuffer("csd-1", ByteBuffer.wrap(csd(pps)));
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE); fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_SEC); fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_SAMPLE_BYTES);
            muxer = new MediaMuxer(mp4Tmp.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = muxer.addTrack(fmt); muxer.start();
            List<byte[]> accessUnit = new ArrayList<>();
            boolean hasSlice = false, key = false;
            long frameUs = 1_000_000L / FPS;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] nal;
            while ((nal = reader.next()) != null) {
                int type = AnnexB.type(nal);
                if (type != 1 && type != 5) continue;
                // first_mb_in_slice=0 begins a picture; group all its slices
                // into one muxer sample rather than passing invalid partial frames.
                if (hasSlice && AnnexB.firstMbInSlice(nal) == 0) {
                    writeAccessUnit(muxer, track, accessUnit, frames++, frameUs, key, info);
                    accessUnit.clear(); key = false;
                }
                accessUnit.add(nal); hasSlice = true; key |= type == 5;
            }
            if (hasSlice) writeAccessUnit(muxer, track, accessUnit, frames++, frameUs, key, info);
        } finally {
            if (muxer != null) { try { muxer.stop(); } catch (Throwable ignored) { }
                try { muxer.release(); } catch (Throwable ignored) { } }
        }
        if (frames == 0) { mp4Tmp.delete(); throw new IOException("No complete video frames found"); }
        if (!mp4Tmp.renameTo(mp4)) { mp4Tmp.delete(); throw new IOException("Could not finalize recovered video"); }
        if (!h264.delete()) Log.w(TAG, "recovered clip but could not delete " + h264);
        keepSidecar(dir, stem); thumbnail(mp4, new File(dir, stem + ".jpg"));
        return stem;
    }

    /** The orphan's subtitles become the recovered clip's: they carry its
     * telemetry, and Clips reads the clip's duration from the cue count. */
    static void keepSidecar(File dir, String stem) {
        File tmp = new File(dir, stem + ".vtt.tmp"), vtt = new File(dir, stem + ".vtt");
        if (tmp.exists() && (vtt.exists() || !tmp.renameTo(vtt))) tmp.delete();
    }

    private static void writeAccessUnit(MediaMuxer muxer, int track, List<byte[]> nals, int frame,
                                        long frameUs, boolean key, MediaCodec.BufferInfo info) throws IOException {
        byte[] sample = sample(nals);
        if (sample.length < 6) return;
        info.set(0, sample.length, frame * frameUs, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        muxer.writeSampleData(track, ByteBuffer.wrap(sample), info);
    }

    /** One muxer sample from a picture's NAL units, each behind a FOUR-byte
     * start code. AnnexB.Reader hands them over with three-byte codes, and
     * Android 9's MPEG4Writer strips only `00 00 00 01`: a three-byte code
     * stays in the sample, the writer computes that NAL's length as 3 - 4,
     * and the size_t underflow aborts the process in
     * addLengthPrefixedSample_l. That was every in-app recovery.
     *
     * Trailing zero bytes are dropped too: the Reader leaves a four-byte
     * code's extra zero on the end of the PREVIOUS unit, and a NAL unit
     * never legitimately ends in 0x00 (rbsp_trailing_bits ends on a 1). */
    /** SPS or PPS for the track format, behind a four-byte start code. Android
     * 9's MPEG4Writer builds the avcC box from csd only when it begins
     * `00 00 00 01`; anything else it copies verbatim AS an avcC box, so a
     * three-byte code produced a header with no parameter sets: every
     * frame then failed with "non-existing PPS 0 referenced". */
    static byte[] csd(byte[] nal) throws IOException {
        return sample(java.util.Collections.singletonList(nal));
    }

    static byte[] sample(List<byte[]> nals) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] nal : nals) {
            int end = nal.length;
            while (end > 4 && nal[end - 1] == 0) end--;
            if (out.size() + end + 1 > MAX_SAMPLE_BYTES) throw new IOException("Video frame is too large to recover safely");
            out.write(0);
            out.write(nal, 0, end);
        }
        return out.toByteArray();
    }

    private static void thumbnail(File mp4, File jpg) {
        MediaMetadataRetriever r = new MediaMetadataRetriever(); java.io.FileOutputStream out = null;
        try {
            r.setDataSource(mp4.getAbsolutePath());
            android.graphics.Bitmap full = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (full == null) return;
            android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(full, 480, 200, true);
            out = new java.io.FileOutputStream(jpg); small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out);
            small.recycle(); full.recycle();
        } catch (Throwable t) { Log.w(TAG, "clip recovery thumbnail", t); }
        finally { try { if (out != null) out.close(); } catch (Throwable ignored) { }
            try { r.release(); } catch (Throwable ignored) { } }
    }

    private ClipRecovery() { }
}
