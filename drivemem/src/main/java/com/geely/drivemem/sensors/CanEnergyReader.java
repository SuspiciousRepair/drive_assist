package com.geely.drivemem.sensors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.util.Log;

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
 * The daemon itself only accepts Drive Assist's and modehelper's uids.
 *
 * Energy tier: CAN when it is live, else OBD2, else the SoC estimate
 * (EnergyIntegrator, Telemetry.tick). Everything is optional: with the switch
 * off nothing here runs, and if the daemon is missing the app silently falls
 * back and Diagnostics says why. */
public final class CanEnergyReader {
    static final String TAG = "DriveMem";
    public static final String STATUS_ACTION = "com.geely.drivemem.CAN_STATUS";

    public interface PowerListener { void onCanPower(long nowMono, double kw); }

    private static final CanPower POWER = new CanPower();
    private static volatile PowerListener powerListener;
    private static volatile boolean wanted = false;
    private static volatile Thread thread;
    private static volatile LocalSocket socket;

    /** What this side sees: off, connecting, live, no_daemon, refused, error. */
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
        status = "off";
        statusDetail = null;
    }

    private static void loop() {
        long backoff = 5_000;
        Set<String> want = new HashSet<>(Arrays.asList(CanPower.SIGNALS));
        while (wanted) {
            LocalSocket s = new LocalSocket();
            socket = s;
            final boolean[] sub = {false};
            try {
                status = "connecting";
                s.connect(new LocalSocketAddress("roadcast", LocalSocketAddress.Namespace.ABSTRACT));
                RoadcastClient c = new RoadcastClient(s.getInputStream(), s.getOutputStream(), want);
                c.run(new RoadcastClient.Listener() {
                    @Override public void onSignal(String name, double physical, long raw, boolean valid, boolean calibrated) {
                        long now = SystemClock.elapsedRealtime();
                        Double kw = POWER.onSignal(name, physical, valid, now);
                        if (kw != null && sub[0]) deliver(now, kw);
                    }
                    @Override public void onHeartbeat(int sourceState, int milliHz) {
                        long now = SystemClock.elapsedRealtime();
                        Double kw = POWER.onHeartbeat(sourceState, now);
                        if (kw != null && sub[0]) deliver(now, kw);
                        status = sourceState == 1 ? "live" : "no_source";
                    }
                    @Override public void onSubscribed() {
                        sub[0] = true;
                        status = "live";
                        statusDetail = c.missing().isEmpty() ? null : "missing " + c.missing();
                        Log.i(TAG, "can: reading the CAN bus" + (statusDetail != null ? " (" + statusDetail + ")" : ""));
                    }
                });
            } catch (IOException e) {
                String m = String.valueOf(e.getMessage());
                // connect() failing = no daemon; a reset right after connect =
                // the daemon's allow-list refused this uid (CanReader restarts
                // it with the right one at the next ADB window).
                if (m.contains("Connection refused") || m.contains("No such file")) status = "no_daemon";
                else if (m.contains("reset") || m.contains("closed by daemon")) status = "refused";
                else status = "error";
                statusDetail = m;
            } catch (Throwable t) {
                status = "error";
                statusDetail = String.valueOf(t);
            } finally {
                closeSocket();
                POWER.reset();
            }
            if (!wanted) break;
            if (sub[0]) backoff = 5_000;   // it worked for a while: retry soon
            try { Thread.sleep(backoff); } catch (InterruptedException e) { if (!wanted) break; }
            backoff = Math.min(backoff * 2, 60_000);
        }
        status = "off";
    }

    private static void deliver(long now, double kw) {
        PowerListener l = powerListener;
        if (l != null) {
            try { l.onCanPower(now, kw); } catch (Throwable t) { Log.w(TAG, "can: listener " + t); }
        }
    }

    private static void closeSocket() {
        LocalSocket s = socket;
        socket = null;
        if (s != null) {
            try { s.shutdownInput(); } catch (Throwable ignored) {}
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private static long now() { return SystemClock.elapsedRealtime(); }

    public static boolean isLive() { return POWER.live(now()); }
    public static Float freshPowerKw() { return f(POWER.powerKw(now())); }
    public static Float freshPackVolts() { return f(POWER.packVolts(now())); }
    public static Float freshPackAmps() { return f(POWER.packAmps(now())); }
    public static Float freshClimateKw() { return f(POWER.climateKw(now())); }
    public static Float freshChargerInputW() { return f(POWER.chargerInputW(now())); }

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
