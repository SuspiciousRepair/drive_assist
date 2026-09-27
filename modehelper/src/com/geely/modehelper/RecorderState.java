package com.geely.modehelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** `dashcam/recorder.state`: what the recorder is doing, for Drive Assist,
 * which runs in another process and cannot ask. Plain `key=value` lines:
 *
 *   state=recording|stopped
 *   stem=dash_20260926_093937     (the open segment; empty when stopped)
 *   beat=<wall-clock ms>          (refreshed every few seconds while running)
 *   error=<last failure, if any>
 *
 * Drive Assist's Clips reads it (Clips.liveStem) so the segment being
 * recorded is never offered for recovery, even when its frames have stalled
 * and its files look cold. The file is replaced by rename, so a reader never
 * sees half of it. */
final class RecorderState {
    static final String FILE = "recorder.state";

    private RecorderState() { }

    static void write(File dir, String state, String stem, long beatMs, String error) {
        File tmp = new File(dir, FILE + ".tmp");
        String body = "state=" + state + "\nstem=" + (stem == null ? "" : stem)
            + "\nbeat=" + beatMs + "\nerror=" + oneLine(error) + "\n";
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        tmp.renameTo(new File(dir, FILE));
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
    }
}
