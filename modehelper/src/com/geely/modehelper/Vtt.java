package com.geely.modehelper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/** WebVTT subtitle generator for dashcam video. See docs/DASHCAM.md for context.
 * Uses WebVTT (not SRT) for native browser support. Small file size (~18 KB per
 * 5 min) allows posting the subtitles to Home Assistant over MQTT, keeping the
 * video on the car and making telemetry data searchable. */
public final class Vtt {
    private final BufferedWriter w;
    private boolean closed;

    public Vtt(File f) throws IOException {
        w = new BufferedWriter(new FileWriter(f));
        w.write("WEBVTT\n\n");
    }

    /** Adds a subtitle cue. Times are in microseconds from segment start (the encoder's
     * clock), ensuring subtitle sync with video over long durations. */
    public void cue(long startUs, long endUs, String text) throws IOException {
        w.write(ts(startUs));
        w.write(" --> ");
        w.write(ts(endUs));
        w.write('\n');
        w.write(text);
        w.write("\n\n");
    }

    public void close() {
        if (closed) return;
        closed = true;
        try { w.flush(); w.close(); } catch (IOException ignored) { }
    }

    // WebVTT wants HH:MM:SS.mmm with a DOT before the milliseconds. SRT uses a
    // comma; a player fed the wrong one silently shows no subtitles at all.
    static String ts(long us) {
        long ms = us / 1000;
        long h  = ms / 3600000; ms -= h * 3600000;
        long m  = ms / 60000;   ms -= m * 60000;
        long s  = ms / 1000;    ms -= s * 1000;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, ms);
    }
}
