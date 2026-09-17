package com.geely.modehelper;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.view.Surface;

import java.util.function.BooleanSupplier;

/** Owns the IPC Surface copy and lease, independently of recorder start/stop. */
final class DashcamPreviewController {
    private final DashcamPreviewLease lease = new DashcamPreviewLease();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BooleanSupplier recording;
    private Surface surface;
    private ResultReceiver callback;
    private EvsFrameFanout renderer;
    private String idleState = "stopped";

    DashcamPreviewController(BooleanSupplier recording) { this.recording = recording; }

    synchronized void accept(String session, Surface ownedSurface, ResultReceiver status) {
        if (!DashcamPreviewLease.validSession(session)) {
            if (ownedSurface != null) ownedSurface.release();
            return;
        }
        if (ownedSurface == null) {
            if (lease.matches(session)) detach(session, status);
            else if (renderer != null)
                renderer.detachPreview(session, (owner, state) -> send(status, owner, state));
            else send(status, session, "detached");
            return;
        }
        if (!ownedSurface.isValid()) {
            ownedSurface.release(); send(status, session, "error"); return;
        }
        boolean changed = lease.attach(session, SystemClock.uptimeMillis());
        if (!changed) {
            ownedSurface.release(); // Heartbeat keeps existing EGLSurface alive.
            if (renderer == null) send(status, session, idleState);
            return;
        }
        if (surface != null) surface.release();
        surface = ownedSurface; callback = status;
        if (renderer != null) attachRenderer();
        else send(status, session, idleState);
        handler.removeCallbacks(expiry);
        handler.postDelayed(expiry, 1000L);
    }

    synchronized void bind(EvsFrameFanout fanout) {
        renderer = fanout;
        if (surface != null) attachRenderer();
    }

    synchronized void unbind(EvsFrameFanout fanout, boolean failed) {
        // A worker may fail EGL initialization before bind(). It still owns the
        // sole recording lifecycle and must publish its failure to the waiting UI.
        if (renderer != null && renderer != fanout) return;
        renderer = null;
        idleState = failed ? "error" : "stopped";
        if (lease.session() != null) send(callback, lease.session(), idleState);
    }

    synchronized void starting() {
        idleState = "waiting";
        if (lease.session() != null) send(callback, lease.session(), idleState);
    }

    synchronized void close() {
        if (lease.session() != null) detach(lease.session(), callback);
        handler.removeCallbacks(expiry);
    }

    private void attachRenderer() {
        String owner = lease.session(); ResultReceiver status = callback;
        try {
            renderer.setPreview(owner, copy(surface), (session, state) -> send(status, session, state));
        } catch (Throwable unavailable) { send(status, owner, "error"); }
    }

    private void detach(String session, ResultReceiver status) {
        lease.detach(session);
        if (surface != null) surface.release();
        surface = null; callback = null;
        if (renderer != null)
            renderer.detachPreview(session, (owner, state) -> send(status, owner, state));
        else send(status, session, "detached");
    }

    private final Runnable expiry = new Runnable() {
        @Override public void run() {
            synchronized (DashcamPreviewController.this) {
                if (lease.expired(SystemClock.uptimeMillis())) detach(lease.session(), callback);
                if (lease.session() != null) handler.postDelayed(this, 1000L);
            }
        }
    };

    private void send(ResultReceiver status, String session, String state) {
        if (status == null || session == null) return;
        Bundle result = new Bundle();
        result.putString("preview_session", session); result.putString("state", state);
        result.putBoolean("recording", recording.getAsBoolean());
        try { status.send(0, result); } catch (Throwable ignored) { }
    }

    private static Surface copy(Surface surface) {
        Parcel parcel = Parcel.obtain();
        try {
            surface.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            return Surface.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }
}
