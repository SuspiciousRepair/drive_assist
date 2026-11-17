package com.geely.drivemem.sensors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.util.Log;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.controls.HelperSync;
import com.geely.drivemem.util.Prefs;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** "Exact energy (experimental)": battery V x I from the CAN bus, with no
 * OBD2 dongle. plan/active/CAN-ENERGY-ROADMAP.md.
 *
 * roadcastd (third_party/roadcast) reads the vehicle HAL's copy of the CAN
 * frames and serves them on the abstract socket @roadcast. modehelper starts
 * and watches that daemon (CanReader); this class only connects to it and
 * reads. An ordinary app may connect to that su-domain socket under
 * Enforcing: checked on the car 2026-11-14 with a plain untrusted_app probe.
 * Any other app may read it too, on purpose (docs/CAN-API.md).
 *
 * Energy tier: CAN when it is live, else OBD2, else the SoC estimate
 * (EnergyIntegrator, Telemetry.tick). Everything is optional: with the switch
 * off nothing here runs, and if the daemon is missing the app silently falls
 * back and Diagnostics says why. */
public final class CanEnergyReader {
    static final String TAG = "DriveMem";
    public static final String STATUS_ACTION = "com.geely.drivemem.CAN_STATUS";

    /** One battery power sample (kW, + = discharge), with the drive and
     * climate values CAN held at that moment (raw signals, null = unknown). */
    public interface PowerListener { void onCanPower(long nowMono, double kw, Double driveKw, Double climateKw); }

    private static final CanPower POWER = new CanPower();
    private static final CanBrake BRAKE = new CanBrake();
    private static volatile PowerListener powerListener;
    private static volatile boolean wanted = false;
    private static volatile Thread thread;
    private static volatile LocalSocket socket;
    /** The daemon sends a heartbeat every second. This long with nothing to
     * read means it stalled: the read fails and loop() reconnects. */
    static final int READ_TIMEOUT_MS = 5_000;

    /** What this side sees: off, connecting, live, no_daemon, dropped, error. */
    private static volatile String status = "off";
    private static volatile String statusDetail;
    /** What modehelper says about the daemon (CanReader.report). */
    private static volatile String helperStatus;
    private static volatile String helperDetail;
    private static volatile int helperAdbOpens;
    private static volatile long helperAdbOpenedAt;

    private CanEnergyReader() {}

    public static void setPowerListener(PowerListener l) { powerListener = l; }

    public static boolean isEnabled(Context ctx) { return Prefs.getCanEnergyEnabled(ctx); }

    /** The switch. Saves it, tells modehelper (which starts or stops the
     * daemon), and starts or stops reading. */
    public static void setEnabled(Context ctx, boolean on) {
        Prefs.setCanEnergyEnabled(ctx, on);
        HelperSync.push(ctx);
        if (on) ensureStarted(ctx); else stop();
    }

    /** Idempotent; no-op while the switch is off. */
    public static synchronized void ensureStarted(Context ctx) {
        if (!Prefs.getCanEnergyEnabled(ctx)) return;
        wanted = true;
        // Hub first (CLAUDE.md rule 7): consumers read the can.* keys.
        CanHub.register(com.geely.drivemem.car.CarActor.get(ctx));
        if (thread != null && thread.isAlive()) return;
        Thread t = new Thread(CanEnergyReader::loop, "can-energy");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    public static synchronized void stop() {
        wanted = false;
        closeSocket();
        Thread t = thread;
        if (t != null) t.interrupt();
        POWER.reset();
        BRAKE.reset();
        status = "off";
        statusDetail = null;
        CanHub.unregister(com.geely.drivemem.car.CarActor.get());
    }

    private static void loop() {
        long backoff = 5_000;
        Set<String> want = new HashSet<>(Arrays.asList(CanPower.SIGNALS));
        want.addAll(Arrays.asList(CanBrake.SIGNALS));
        while (wanted) {
            LocalSocket s = new LocalSocket();
            socket = s;
            // stop() may have run between the loop test and the line above:
            // it then found no socket to close. `wanted` is volatile and
            // stop() clears it before it closes, so one of the two sees the
            // other: either stop() closes s, or this test stops the thread.
            if (!wanted) { closeSocket(); break; }
            final boolean[] sub = {false};
            try {
                status = "connecting";
                s.connect(new LocalSocketAddress("roadcast", LocalSocketAddress.Namespace.ABSTRACT));
                s.setSoTimeout(READ_TIMEOUT_MS);   // after connect: needs the socket's fd
                RoadcastClient c = new RoadcastClient(s.getInputStream(), s.getOutputStream(), want);
                c.run(new RoadcastClient.Listener() {
                    @Override public void onSignal(String name, double physical, long raw, boolean valid, boolean calibrated) {
                        if (!wanted) { close(s); return; }   // switched off: end the read
                        long now = SystemClock.elapsedRealtime();
                        CarActor.Reading brake = BRAKE.onSignal(name, physical, valid);
                        if (brake != null) { injectBrake(brake); return; }
                        Double kw = POWER.onSignal(name, physical, valid, now);
                        if (kw != null && sub[0]) deliver(now, kw);
                    }
                    @Override public void onHeartbeat(int sourceState, int milliHz) {
                        if (!wanted) { close(s); return; }
                        long now = SystemClock.elapsedRealtime();
                        Double kw = POWER.onHeartbeat(sourceState, now);
                        if (kw != null && sub[0]) deliver(now, kw);
                        status = sourceState == 1 ? "live" : "no_source";
                    }
                    @Override public void onSubscribed() {
                        if (!wanted) { close(s); return; }
                        sub[0] = true;
                        status = "live";
                        statusDetail = c.missing().isEmpty() ? null : "missing " + c.missing();
                        Log.i(TAG, "can: reading the CAN bus" + (statusDetail != null ? " (" + statusDetail + ")" : ""));
                    }
                });
            } catch (IOException e) {
                String m = String.valueOf(e.getMessage());
                // connect() failing = no daemon; a reset or close mid-session =
                // the daemon went away (restarted, or the HAL changed).
                if (m.contains("Connection refused") || m.contains("No such file")) status = "no_daemon";
                // "Try again" (EAGAIN) = the read timed out: a stalled daemon.
                else if (m.contains("reset") || m.contains("closed by daemon") || m.contains("Try again")) status = "dropped";
                else status = "error";
                statusDetail = m;
            } catch (Throwable t) {
                status = "error";
                statusDetail = String.valueOf(t);
            } finally {
                closeSocket();
                POWER.reset();
                BRAKE.reset();
                if (wanted) injectBrake(BRAKE.current());   // NOT_AVAILABLE until it reconnects
            }
            if (!wanted) break;
            if (sub[0]) backoff = 5_000;   // it worked for a while: retry soon
            try { Thread.sleep(backoff); } catch (InterruptedException e) { if (!wanted) break; }
            backoff = Math.min(backoff * 2, 60_000);
        }
        status = "off";
    }

    private static void injectBrake(CarActor.Reading r) {
        CarActor a = CarActor.get();
        if (a != null) a.injectReading(CanHub.BRAKE, r);
    }

    private static void deliver(long now, double kw) {
        PowerListener l = powerListener;
        if (l != null) {
            try { l.onCanPower(now, kw, POWER.heldDriveKw(), POWER.heldClimateKw()); } catch (Throwable t) { Log.w(TAG, "can: listener " + t); }
        }
    }

    private static void closeSocket() {
        LocalSocket s = socket;
        socket = null;
        close(s);
    }

    private static void close(LocalSocket s) {
        if (s == null) return;
        try { s.shutdownInput(); } catch (Throwable ignored) {}
        try { s.close(); } catch (Throwable ignored) {}
    }

    private static long now() { return SystemClock.elapsedRealtime(); }

    // Package-private on purpose: only CanHub reads these. Consumers use the
    // hub (CanHub.get), per CLAUDE.md rule 7.
    static boolean isLive() { return POWER.live(now()); }
    static Float freshPowerKw() { return f(POWER.powerKw(now())); }
    static Float freshPackVolts() { return f(POWER.packVolts(now())); }
    static Float freshPackAmps() { return f(POWER.packAmps(now())); }
    static Float freshClimateKw() { return f(POWER.climateKw(now())); }
    static Float freshDriveKw() { return f(POWER.driveKw(now())); }
    static Float freshChargerInputW() { return f(POWER.chargerInputW(now())); }

    private static Float f(Double d) { return d == null ? null : d.floatValue(); }

    /** One line for Diagnostics / the Config screen. */
    public static String statusLine() {
        StringBuilder b = new StringBuilder("app=").append(status);
        if (statusDetail != null) b.append(" (").append(statusDetail).append(')');
        if (helperStatus != null) {
            b.append(", helper=").append(helperStatus);
            if (helperDetail != null) b.append(" (").append(helperDetail).append(')');
            b.append(", ADB opened ").append(helperAdbOpens).append('x');
            if (helperAdbOpenedAt > 0) b.append(", last ").append(new java.util.Date(helperAdbOpenedAt));
        }
        return b.toString();
    }

    public static String status() { return status; }
    public static String helperStatus() { return helperStatus; }

    /** modehelper's report on the daemon. Text only, never vehicle data, so
     * a spoofed one can only mislabel Diagnostics. */
    public static final class StatusReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent i) {
            if (!STATUS_ACTION.equals(i.getAction())) return;
            helperStatus = i.getStringExtra("status");
            helperDetail = i.getStringExtra("detail");
            helperAdbOpens = i.getIntExtra("adb_opens", 0);
            helperAdbOpenedAt = i.getLongExtra("adb_opened_at", 0);
        }
    }
}
