package com.geely.drivemem.util;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;

/** Turns an orphaned `.h264` (see {@link Clips.Kind#ORPHAN}'s own comment --
 * raw footage left behind when a dashcam segment never closed) back into a
 * normal playable `.mp4`, in-app, using nothing but Android's own
 * MediaMuxer -- no ffmpeg binary is bundled or needed.
 *
 * The raw stream is DashRecorder's own MediaCodec output, byte for byte
 * (see modehelper/DashRecorder.java's Seg -- writeCsd() dumps csd-0/csd-1
 * once, then every subsequent write() call appends one encoded frame),
 * so remuxing it is exactly: read the SPS/PPS back out, hand them to a
 * fresh MediaMuxer as the track format, then feed every following slice
 * NAL back in as one sample each. The only real information lost is each
 * frame's original presentation time -- never stored in the raw stream --
 * so playback timing is synthesized at DashRecorder's own fixed encode
 * rate (FPS below) rather than recovered exactly; this is inaudible/
 * invisible in practice since the encoder is CFR (constant frame rate) to
 * begin with. */
public final class ClipRecovery {
    private static final String TAG = "DriveMem";

    // MUST match modehelper/DashRecorder.java's DashRecorder.W/H/FPS exactly --
    // a separate APK/process, so these can't be shared as one constant. If
    // that encoder's resolution or frame rate ever changes, this needs the
    // same edit or every future recovery decodes at the wrong size/speed.
    private static final int W = 1920, H = 800;
    private static final int FPS = 25;

    public interface Callback { void onDone(boolean ok, String message); }

    /** Runs entirely on a background thread; `cb` is always invoked on the
     * main thread. Never throws -- every failure reaches `cb` as ok=false.
     *
     * Catches Throwable, not just Exception: this thread maps a whole
     * orphan file (some real ones are 250+ MB) into memory and pushes it
     * through the codec framework one frame at a time, which is a real way
     * to hit OutOfMemoryError on a memory-constrained head unit -- an
     * Error, not an Exception, so `catch (Exception e)` would have let it
     * straight through. Android kills the WHOLE app on any thread's
     * uncaught throwable, not just the main thread's, so a bug here could
     * crash the app well after the tap that started it -- exactly the
     * "crashed after a while" report this class of miss produces. */
    public static void recover(File h264, Callback cb) {
        new Thread(() -> {
            boolean ok;
            String msg;
            try {
                msg = doRecover(h264);
                ok = true;
            } catch (Throwable t) {
                Log.w(TAG, "clip recovery failed for " + h264.getName() + ": " + t, t);
                ok = false;
                msg = String.valueOf(t.getMessage());
            }
            final boolean fok = ok;
            final String fmsg = msg;
            new Handler(Looper.getMainLooper()).post(() -> cb.onDone(fok, fmsg));
        }, "clip-recovery").start();
    }

    private static String doRecover(File h264) throws IOException {
        String stem = Clips.name(h264);
        File dir = h264.getParentFile();
        File mp4 = new File(dir, stem + ".mp4");
        File mp4Tmp = new File(dir, stem + ".mp4.tmp");
        int frameCount;

        try (RandomAccessFile raf = new RandomAccessFile(h264, "r")) {
            long len = raf.length();
            if (len < 16) throw new IOException("File too small to be real footage");
            MappedByteBuffer mapped = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, len);
            AnnexB.Source src = mappedSource(mapped, (int) len);
            List<AnnexB.Nal> nals = AnnexB.split(src);
            if (nals.size() < 3) {
                throw new IOException("Not enough recoverable data (found " + nals.size() + " NAL unit(s))");
            }

            AnnexB.Nal sps = nals.get(0);
            AnnexB.Nal pps = nals.get(1);
            int spsType = AnnexB.type(src, sps);
            int ppsType = AnnexB.type(src, pps);
            if (spsType != 7 || ppsType != 8) {
                throw new IOException("Expected SPS/PPS at the start, found types " + spsType + "/" + ppsType);
            }

            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
            fmt.setByteBuffer("csd-0", slice(mapped, sps));
            fmt.setByteBuffer("csd-1", slice(mapped, pps));

            MediaMuxer muxer = new MediaMuxer(mp4Tmp.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            try {
                int track = muxer.addTrack(fmt);
                muxer.start();

                long frameUs = 1_000_000L / FPS;
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                frameCount = 0;
                for (int i = 2; i < nals.size(); i++) {
                    AnnexB.Nal nal = nals.get(i);
                    int type = AnnexB.type(src, nal);
                    if (type != 1 && type != 5) continue; // not a slice -- skip defensively
                    if (nal.length() < 4) continue;       // a crash-truncated trailing NAL
                    ByteBuffer sample = slice(mapped, nal);
                    info.set(0, sample.remaining(), (long) frameCount * frameUs,
                        type == 5 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    try {
                        muxer.writeSampleData(track, sample, info);
                        frameCount++;
                    } catch (Throwable t) {
                        Log.w(TAG, "clip recovery: dropped one bad sample: " + t);
                    }
                }
            } finally {
                try { muxer.stop(); } catch (Throwable ignored) { }
                try { muxer.release(); } catch (Throwable ignored) { }
            }
        }

        if (frameCount == 0) {
            mp4Tmp.delete();
            throw new IOException("No playable video frames found");
        }
        if (!mp4Tmp.renameTo(mp4)) {
            mp4Tmp.delete();
            throw new IOException("Could not finalize the recovered file");
        }
        h264.delete();
        // Never completed either, and there is no timing left to recover it
        // from once the original per-frame PTS is gone -- litter, not a clip.
        new File(dir, stem + ".vtt.tmp").delete();
        thumbnail(mp4, new File(dir, stem + ".jpg"));
        return stem;
    }

    private static AnnexB.Source mappedSource(MappedByteBuffer mapped, int len) {
        return new AnnexB.Source() {
            @Override public int length() { return len; }
            @Override public byte get(int i) { return mapped.get(i); }
        };
    }

    /** A duplicate view spanning exactly `nal`'s bytes, position 0, so callers
     * never have to reason about MediaMuxer's offset/position contract. */
    private static ByteBuffer slice(MappedByteBuffer mapped, AnnexB.Nal nal) {
        ByteBuffer dup = mapped.duplicate();
        dup.position(nal.start);
        dup.limit(nal.end);
        return dup.slice();
    }

    // Same technique DashRecorder.Seg.thumbnail() uses on a normal close --
    // duplicated here rather than shared because that one lives in a
    // separate APK (modehelper) this app cannot call into for a plain
    // library method.
    private static void thumbnail(File mp4, File jpg) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        java.io.FileOutputStream out = null;
        try {
            r.setDataSource(mp4.getAbsolutePath());
            android.graphics.Bitmap full = r.getFrameAtTime(1_000_000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (full == null) return;
            android.graphics.Bitmap small =
                android.graphics.Bitmap.createScaledBitmap(full, 480, 200, true);
            out = new java.io.FileOutputStream(jpg);
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out);
            small.recycle();
            full.recycle();
        } catch (Throwable t) {
            Log.w(TAG, "clip recovery: thumbnail: " + t);
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    private ClipRecovery() {}
}
