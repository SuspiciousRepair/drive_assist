package com.geely.modehelper;

import android.content.Context;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/**
 * Explicit, metadata-only Stage-1 diagnostic for the {@code dvr} analysis
 * surface. It is never started by boot, Park state, or the dashcam lifecycle.
 */
final class ParkedMonitoringProbe {
    interface EventSink { void onEventComplete(long frames); }
    private final EvsClient evs = new EvsClient();
    private final Context context;
    private final CarMode car = new CarMode();
    private final ParkedMonitorPolicy parkPolicy = new ParkedMonitorPolicy();
    private final boolean requireOwnParkSettle;
    private final EventSink eventSink;
    private final MotionEventPolicy eventPolicy = new MotionEventPolicy();
    private volatile boolean running;
    private DvrAnalysisTap tap;
    private volatile long frames;
    private volatile long firstFrameAtMs;
    private volatile long firstCpuMs;

    ParkedMonitoringProbe(Context context) { this(context, true, null); }

    /** Main-service ownership has already established the Park settle window. */
    ParkedMonitoringProbe(Context context, boolean requireOwnParkSettle) {
        this(context, requireOwnParkSettle, null);
    }

    ParkedMonitoringProbe(Context context, boolean requireOwnParkSettle, EventSink eventSink) {
        this.context = context.getApplicationContext();
        this.requireOwnParkSettle = requireOwnParkSettle;
        this.eventSink = eventSink;
    }

    // DISABLED 2026-09-15 — this probe used to open its own independent
    // EvsClient/openCamera/attach on the exact same "avm"/"dvr" camera
    // DashRecorder already holds continuously whenever dashcam is on (which
    // is the normal, default state — see ModeHelperService.maybeAutoStart()).
    // Two independent app-side claims on the same evsengine camera+type is
    // exactly the class of bug this project's own docs already warned about
    // for a DIFFERENT pairing (this app vs. the factory camera app) — it
    // just wasn't re-checked when this probe was added later. It happened for
    // real: a Park->Reverse transition left the OEM reverse-camera app
    // frozen on a stale frame from departure time, a real safety hazard while
    // backing up. DashRecorder's camera pipeline is a zero-copy GPU surface
    // straight into the H.264 encoder (see DashRecorder.loop()) — there is no
    // accessible frame data anywhere in it to safely fan out to a second
    // consumer without a real capture-sharing redesign. Until that redesign
    // exists, nothing but DashRecorder may open this camera. Re-enabling this
    // by reverting this one method without that redesign reintroduces the
    // exact bug that was just found live on the car.
    synchronized void start() {
        Log.w(ModeHelperService.TAG,
            "parked probe: disabled — would open a second, independent camera "
          + "connection alongside DashRecorder's; see this method's own comment");
    }

    private void run() {
        try {
            if (!car.connect(context)) {
                Log.w(ModeHelperService.TAG, "parked probe: car connection failed");
                stop();
                return;
            }
            DvrAnalysisTap localTap = new DvrAnalysisTap(new DvrAnalysisTap.Listener() {
                private ParkedMonitorPolicy.State lastState = ParkedMonitorPolicy.State.DISARMED;

                @Override public boolean isArmed(long atMs) {
                    if (!requireOwnParkSettle) return true;
                    Integer gear = car.readGear();
                    ParkedMonitorPolicy.State state = parkPolicy.update(atMs,
                        gear != null && gear == CarMode.GEAR_PARK);
                    if (state != lastState) {
                        Log.i(ModeHelperService.TAG, "parked probe: gate " + state);
                        lastState = state;
                    }
                    return state == ParkedMonitorPolicy.State.ARMED;
                }

                @Override public void onGateResult(MotionGate.Result result, long atMs) {
                    frames++;
                    if (firstFrameAtMs == 0) {
                        firstFrameAtMs = atMs;
                        firstCpuMs = Process.getElapsedCpuTime();
                    }
                    // A periodic counter is enough to prove ImageReader delivery
                    // without emitting frame content or retaining it on disk.
                    if (frames % 10 == 0) {
                        long elapsedMs = Math.max(1, atMs - firstFrameAtMs);
                        long fpsTimes100 = (frames * 100000L) / elapsedMs;
                        Log.i(ModeHelperService.TAG, "parked probe: analysis frames=" + frames
                            + " rate=" + (fpsTimes100 / 100) + "." + (fpsTimes100 % 100)
                            + "fps");
                    }
                    if (frames % 60 == 0) logHealth(atMs);
                    if (result.began || result.ended) {
                        Log.i(ModeHelperService.TAG, "parked probe: motion=" + result.active
                            + " changed=" + result.changedPixels + " frames=" + frames);
                    }
                    MotionEventPolicy.Result event = eventPolicy.update(atMs, result.active);
                    if (event.started) Log.i(ModeHelperService.TAG,
                        "parked event: started frames=" + frames);
                    if (event.finished) {
                        Log.i(ModeHelperService.TAG, "parked event: completed frames=" + frames);
                        if (eventSink != null) eventSink.onEventComplete(frames);
                    }
                }

                @Override public void onFrameError(String message, Throwable error) {
                    Log.w(ModeHelperService.TAG, "parked probe: " + message, error);
                }
            });
            synchronized (this) {
                if (!running) { localTap.close(); return; }
                tap = localTap;
            }
            if (!evs.connect() || !evs.openCamera(EvsClient.CAMERA_AVM)) {
                Log.w(ModeHelperService.TAG, "parked probe: EVS connection/open failed");
                stop();
                return;
            }
            if (!running) return;
            // This is the only camera-attachment call in the diagnostic. It is
            // reached solely through the DUMP-protected explicit probe action.
            if (!evs.attach(localTap.surface(), EvsClient.TYPE_DVR, EvsClient.CAMERA_AVM)) {
                Log.w(ModeHelperService.TAG, "parked probe: DVR analysis attach refused");
                stop();
                return;
            }
            Log.i(ModeHelperService.TAG, "parked probe: attached "
                + DvrAnalysisTap.SOURCE_WIDTH + "x" + DvrAnalysisTap.SOURCE_HEIGHT
                + "; analysis=" + DvrAnalysisTap.WIDTH + "x" + DvrAnalysisTap.HEIGHT
                + "; metadata only");
        } catch (Throwable t) {
            Log.w(ModeHelperService.TAG, "parked probe: startup failed", t);
            stop();
        }
    }

    /** One parseable, metadata-only health line every thirty seconds at 2 fps. */
    private void logHealth(long atMs) {
        long elapsedMs = Math.max(1, atMs - firstFrameAtMs);
        long cpuMs = Math.max(0, Process.getElapsedCpuTime() - firstCpuMs);
        Float ambientC = car.readOutsideTempC();
        Integer charging = car.readCharging();
        Float chargeV = car.readChargeVoltage();
        Float chargeA = car.readChargeCurrent();
        Log.i(ModeHelperService.TAG, "parked-metric"
            + " session_ms=" + elapsedMs
            + " analysis_frames=" + frames
            + " fps_x100=" + ((frames * 100000L) / elapsedMs)
            + " cpu_ms=" + cpuMs
            + " pss_kb=" + Debug.getPss()
            + " gate=" + (requireOwnParkSettle ? parkPolicy.state() : "ARMED")
            + " ambient_c=" + (ambientC == null ? "na" : ambientC)
            + " charging=" + (charging == null ? "na" : charging)
            // These are charging-system readings, not an auxiliary 12 V claim.
            + " charge_v=" + (chargeV == null ? "na" : chargeV)
            + " charge_a=" + (chargeA == null ? "na" : chargeA));
    }

    synchronized void stop() {
        if (!running && tap == null) return;
        running = false;
        parkPolicy.reset();
        eventPolicy.reset();
        car.disconnect();
        if (tap != null) {
            tap.close();
            tap = null;
        }
        Log.i(ModeHelperService.TAG, "parked probe: stopped after " + frames + " analysis frames");
    }

    synchronized boolean isRunning() { return running; }
}
