package com.geely.voiceprobe;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class ProbeLog {
    static final String TAG = "VoiceProbe";
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FORMAT =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private ProbeLog() { }

    static void write(Context context, String message) {
        String line = FORMAT.format(new Date()) + " " + message;
        Log.i(TAG, message);
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return;
        synchronized (LOCK) {
            try (FileWriter writer = new FileWriter(new File(dir, "probe.log"), true)) {
                writer.write(line);
                writer.write('\n');
            } catch (IOException e) {
                Log.e(TAG, "log write failed", e);
            }
        }
    }
}
