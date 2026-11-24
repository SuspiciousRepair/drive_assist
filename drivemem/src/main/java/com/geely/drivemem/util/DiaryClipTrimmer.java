package com.geely.drivemem.util;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;

/** Lossless 5-second dashcam clip extractor for Car Diary incidents.
 *
 * DashRecorder writes keyframes every 1 second (IFRAME_SEC = 1), so seeking to
 * the previous sync frame around (target - 3 s) and remuxing until (target + 2 s)
 * produces a ~3-5 MB standalone MP4 in under half a second with zero re-encoding,
 * instead of locking a ~600 MB 5-minute segment in keep/. */
public final class DiaryClipTrimmer {
    private DiaryClipTrimmer() {}

    public static final String DIR_NAME = "diary_clips";
    public static final long PRE_MS = 3_000L;
    public static final long POST_MS = 2_000L;
    private static final int BUF_SIZE = 1024 * 1024;

    public static File dir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File d = new File(base, DIR_NAME);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Resolves a stored clip_name (basename in diary_clips/ or fallback in dashcam keep/). */
    public static File resolveClip(Context ctx, String clipName) {
        if (clipName == null || clipName.trim().isEmpty()) return null;
        if (clipName.contains("/") || clipName.contains("\\") || clipName.contains("..")) return null;
        File f = new File(dir(ctx), clipName);
        if (f.exists()) return f;
        File kept = new File(Clips.keepDir(ctx), clipName);
        if (kept.exists()) return kept;
        return null;
    }

    public static void deleteClip(Context ctx, String clipName) {
        if (clipName == null || clipName.trim().isEmpty()) return;
        File f = new File(dir(ctx), clipName);
        if (f.exists()) f.delete();
    }

    /** Finds the best closed dashcam clip covering targetWallMs (or latest playable clip
     * if within 10 minutes), and extracts a lossless 5-second slice into diary_clips/.
     * Must be called off the UI thread. Returns the saved basename, or null on failure. */
    public static String captureFiveSecondClip(Context ctx, long targetWallMs, String uuid) {
        List<Clips.Clip> all = Clips.list(ctx);
        Clips.Clip match = findMatchingClip(all, targetWallMs);
        if (match == null || match.mp4 == null || !match.mp4.exists()) {
            Log.i(CarAccess.TAG, "diary clip: no closed clip covering ts=" + targetWallMs
                + " (total clips=" + (all == null ? 0 : all.size())
                + ", liveCovering=" + hasLiveSegmentCovering(all, targetWallMs) + ")");
            return null;
        }

        long durMs = effectiveDurationMs(match);
        long offsetMs = computeOffsetMs(match.whenMs, (int) (durMs / 1000L), targetWallMs);
        long startUs = Math.max(0L, (offsetMs - PRE_MS) * 1000L);
        long endUs = (offsetMs + POST_MS) * 1000L;

        String outName = "incident_" + uuid.substring(0, Math.min(8, uuid.length())) + ".mp4";
        File outFile = new File(dir(ctx), outName);
        boolean ok = trimMp4(match.mp4, outFile, startUs, endUs);
        if (ok && outFile.exists() && outFile.length() > 1024) {
            Log.i(CarAccess.TAG, "diary clip: saved " + outName + " (" + (outFile.length() / 1024)
                + " KB) from " + match.mp4.getName() + " offset=" + (offsetMs / 1000L) + "s");
            return outName;
        }
        Log.w(CarAccess.TAG, "diary clip: trim produced empty output from " + match.mp4.getName());
        if (outFile.exists()) outFile.delete();
        return null;
    }

    /** True when a dashcam segment covering targetWallMs is currently RECORDING (.mp4.tmp). */
    public static boolean hasLiveSegmentCovering(Context ctx, long targetWallMs) {
        return hasLiveSegmentCovering(Clips.list(ctx), targetWallMs);
    }

    static boolean hasLiveSegmentCovering(List<Clips.Clip> clips, long targetWallMs) {
        if (clips == null) return false;
        for (Clips.Clip c : clips) {
            if (c.kind != Clips.Kind.RECORDING) continue;
            if (targetWallMs >= c.whenMs - 5_000L && targetWallMs <= c.whenMs + 6 * 60_000L) {
                return true;
            }
        }
        return false;
    }

    /** Estimates clip duration without relying solely on VTT cue count (which can stop early). */
    private static long effectiveDurationMs(Clips.Clip c) {
        long vttMs = c.seconds > 0 ? c.seconds * 1000L : 0L;
        long mtimeMs = (c.mp4 != null && c.mp4.lastModified() > c.whenMs)
            ? (c.mp4.lastModified() - c.whenMs) : 0L;
        if (mtimeMs > 10 * 60_000L) mtimeMs = 0L; // ignore skewed mtime
        long best = Math.max(vttMs, mtimeMs);
        return best > 5_000L ? best : 5 * 60_000L;
    }

    /** Pure helper: picks the closed clip whose span covers targetWallMs, else the
     * newest closed clip if within 10 minutes of targetWallMs. */
    static Clips.Clip findMatchingClip(List<Clips.Clip> clips, long targetWallMs) {
        if (clips == null || clips.isEmpty()) return null;
        Clips.Clip newest = null;
        for (Clips.Clip c : clips) {
            if (!c.playable()) continue;
            if (newest == null || c.whenMs > newest.whenMs) newest = c;
            long durMs = effectiveDurationMs(c);
            if (targetWallMs >= c.whenMs - 2_000L && targetWallMs <= c.whenMs + durMs + 2_000L) {
                return c;
            }
        }
        if (newest != null && Math.abs(targetWallMs - newest.whenMs) <= 10 * 60_000L) {
            return newest;
        }
        return null;
    }

    /** Pure helper: offset in ms inside the clip for targetWallMs. If target is past the
     * clip end, anchors near the end of the clip so the last 5 seconds are kept. */
    static long computeOffsetMs(long clipStartWallMs, int clipSeconds, long targetWallMs) {
        long durMs = clipSeconds > 0 ? clipSeconds * 1000L : 5 * 60_000L;
        long rel = targetWallMs - clipStartWallMs;
        if (rel < 0) return Math.min(PRE_MS, durMs);
        if (rel > durMs) return Math.max(0L, durMs - POST_MS);
        return rel;
    }

    private static boolean trimMp4(File src, File dst, long startUs, long endUs) {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            extractor.setDataSource(src.getAbsolutePath());
            int videoTrack = -1;
            MediaFormat videoFmt = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat fmt = extractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    videoFmt = fmt;
                    break;
                }
            }
            if (videoTrack < 0) return false;
            extractor.selectTrack(videoTrack);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            muxer = new MediaMuxer(dst.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int dstTrack = muxer.addTrack(videoFmt);
            muxer.start();
            started = true;

            ByteBuffer buf = ByteBuffer.allocate(BUF_SIZE);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long basePts = -1L;
            long lastWrittenPts = -1L;
            int frames = 0;
            while (true) {
                int size = extractor.readSampleData(buf, 0);
                if (size < 0) break;
                long pts = extractor.getSampleTime();
                if (pts < 0) break;
                if (basePts < 0) basePts = pts;
                if (pts > endUs && frames > 0) break;

                long relPts = Math.max(0L, pts - basePts);
                if (lastWrittenPts >= 0 && relPts <= lastWrittenPts) {
                    relPts = lastWrittenPts + 1L;
                }
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = relPts;
                int flags = extractor.getSampleFlags();
                info.flags = (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                muxer.writeSampleData(dstTrack, buf, info);
                lastWrittenPts = relPts;
                frames++;
                extractor.advance();
            }
            return frames > 0;
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "diary clip trim failed: " + t);
            return false;
        } finally {
            try { extractor.release(); } catch (Throwable ignored) {}
            if (muxer != null) {
                try { if (started) muxer.stop(); } catch (Throwable ignored) {}
                try { muxer.release(); } catch (Throwable ignored) {}
            }
        }
    }
}
