package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.os.Parcelable;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.Surface;

/** Starts/stops recording or updates options. Receives DASHCAM from drivemem.
 * Exported because drivemem has a different signer (signature-level permissions
 * cannot fence it). Starting/stopping recording is low-risk: it's user-visible
 * and bounded by the ring buffer. */
public class DashReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent i) {
        if (i == null) return;
        if ("com.geely.modehelper.DASHCAM_PREVIEW".equals(i.getAction())) {
            preview(ctx, i);
            return;
        }
        if (!"com.geely.modehelper.DASHCAM".equals(i.getAction())) return;
        int on = i.getIntExtra("on", -1);
        Log.i(CarMode.TAG, "dashcam: broadcast on=" + on);
        Intent svc = new Intent(ctx, ModeHelperService.class)
                        .setAction(ModeHelperService.ACTION_DASHCAM)
                        .putExtra("on", on);
        // Forward only the small, typed contract. In particular, an exported
        // receiver must not relay arbitrary paths or service extras to UID 1000.
        if (i.hasExtra(DashcamOptions.SEGMENT_MINUTES))
            svc.putExtra(DashcamOptions.SEGMENT_MINUTES,
                i.getIntExtra(DashcamOptions.SEGMENT_MINUTES, DashcamOptions.DEFAULT_SEGMENT_MINUTES));
        if (i.hasExtra(DashcamOptions.STORAGE))
            svc.putExtra(DashcamOptions.STORAGE, i.getStringExtra(DashcamOptions.STORAGE));
        if (i.hasExtra("dashcam_limit_gb"))
            svc.putExtra("dashcam_limit_gb",
                i.getIntExtra("dashcam_limit_gb", DashRecorder.DEFAULT_BUDGET_GB));
        start(ctx, svc);
    }

    private static void preview(Context ctx, Intent intent) {
        Surface surface = null;
        try {
            Parcelable supplied = intent.getParcelableExtra("surface");
            if (supplied != null && !(supplied instanceof Surface)) return;
            surface = (Surface) supplied;
            Parcelable proof = intent.getParcelableExtra("preview_owner");
            if (!(proof instanceof PendingIntent)) return;
            PendingIntent owner = (PendingIntent) proof;
            // Exported IPC cannot trust an action/package extra. PendingIntent
            // creator identity is assigned by Android; this token is never sent.
            int expectedUid = ctx.getPackageManager().getApplicationInfo("com.geely.drivemem", 0).uid;
            if (!"com.geely.drivemem".equals(owner.getCreatorPackage())
                    || owner.getCreatorUid() != expectedUid) return;
            String session = intent.getStringExtra("preview_session");
            if (!DashcamPreviewLease.validSession(session)) return;
            Parcelable status = intent.getParcelableExtra("preview_status");
            if (!(status instanceof ResultReceiver)) return;
            Intent service = new Intent(ctx, ModeHelperService.class)
                .setAction(ModeHelperService.ACTION_DASHCAM_PREVIEW)
                .putExtra("preview_session", session)
                .putExtra("surface", surface)
                .putExtra("preview_status", (ResultReceiver) status);
            start(ctx, service);
        } catch (Throwable invalid) {
            Log.w(CarMode.TAG, "dashcam: rejected preview request: " + invalid.getClass().getSimpleName());
        } finally {
            // AMS has parcelled its service copy by the time start returns. The
            // receiver's temporary wrapper must not leak on every heartbeat.
            if (surface != null) surface.release();
        }
    }

    private static void start(Context ctx, Intent svc) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
        } catch (Throwable t) { Log.w(CarMode.TAG, "dashcam: start failed: " + t); }
    }
}
