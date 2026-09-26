package com.geely.drivemem.util;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.util.Log;
import java.io.File;

/** Native MediaMuxer containment boundary; manifest assigns process=:cliprecovery. */
public final class ClipRecoveryService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String path = intent == null ? null : intent.getStringExtra(ClipRecovery.EXTRA_PATH);
        ResultReceiver result = intent == null ? null : intent.getParcelableExtra(ClipRecovery.EXTRA_RESULT);
        new Thread(() -> {
            boolean ok = false; String message;
            try {
                if (path == null) throw new IllegalArgumentException("Missing recording path");
                message = ClipRecovery.doRecover(new File(path)); ok = true;
            } catch (Throwable t) {
                Log.w("DriveMem", "clip recovery failed", t);
                message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            if (result != null) { Bundle data = new Bundle(); data.putString(ClipRecovery.RESULT_MESSAGE, message);
                result.send(ok ? ClipRecovery.RESULT_OK : ClipRecovery.RESULT_ERROR, data); }
            stopSelf(startId);
        }, "clip-recovery").start();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
