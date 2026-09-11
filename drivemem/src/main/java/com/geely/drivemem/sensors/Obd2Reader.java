package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.car.Telemetry;
import com.geely.drivemem.controls.DoorWindow;
import com.geely.drivemem.hvac.ComfortHub;
import com.geely.drivemem.net.AbrpUploader;
import com.geely.drivemem.services.TelemetryService;
import com.geely.drivemem.state.CarplayState;
import com.geely.drivemem.state.ChargeSession;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Reads real-time battery power (voltage × current) from an OBD2 dongle over Bluetooth.
 *
 * The VHAL does not provide an instant-power property, so this reader connects
 * via an ELM327-style dongle to read PIDs directly from the battery ECU. See
 * field-catalog.md for the complete PID table and verification.
 *
 * Supports both classic Bluetooth (tried first, if previously bonded) and BLE
 * (fallback). Connection handling is transport-agnostic and persists for the
 * lifetime of the process once enabled. Updates are published via a listener
 * interface rather than polled, and operate independently from the car's VHAL.
 *
 * The byte parser handles UDS responses with headers (ATH1) and spaces (ATS0).
 */
public final class Obd2Reader {
    static final String TAG = "DriveMem";
    // Specific enough to avoid false matches on unrelated devices (e.g. "Canon").
    private static final String[] NAME_HINTS = {
        "OBD", "ELM", "ENGIE", "V-LINK", "VLINK", "VEEPEAK", "OBDII", "ECU"
    };

    // ATSP6: Use ISO 15765-4 CAN 11bit/500kbps (avoids auto-detect overhead).
    // ATSH7E2: Battery/BMS ECU header for all subsequent PID reads.
    private static final String[] INIT_CMDS = {
        "ATZ", "ATE0", "ATSP6", "ATM0", "ATS0", "ATAL", "ATAT1", "ATH1", "ATST64", "ATSH7E2"
    };

    // 45s retry interval (not 15s): this Bluetooth stack's internal ~15s timeout per
    // connect strategy meant shorter intervals would overlap, poisoning the link.
    private static final long RETRY_MS = 45_000;
    private static final long POLL_MS = 2_000;
    private static final long CMD_TIMEOUT_MS = 4_000;
    private static final long BLE_SCAN_MS = 6_000;
    // Longer than a direct-connect would need: autoConnect=true (see
    // BleChannel.connect()) means Android manages the connection attempt
    // itself rather than failing fast, which trades a slower first
    // connect for one that actually completes on flaky stacks.
    private static final long BLE_CONNECT_TIMEOUT_MS = 20_000;

    private static volatile boolean enabledWanted = false;
    private static volatile boolean running = false;

    // Latest snapshot -- one writer (this class's own thread), many readers
    // (Telemetry.java, AbrpUploader), same volatile-fields discipline
    // ChargeSession's own static state already uses.
    private static volatile boolean connected = false;
    private static volatile Double soc, voltage, current, battTempC, powerKw;
    private static volatile Integer reportedSpeedKmh;
    private static volatile long lastReadingAtMs = 0;

    // Listeners are notified on state change (edge events) rather than by polling
    // (level snapshots), which prevents stale "Connected" states on screen.
    // Notifications are synchronous on this reader thread, independent from
    // EntityBus/CarActor to avoid serialization overhead on a separate radio link.
    // The reader thread is the only writer; freshXxx() getters exist for backward
    // compatibility but onObd2Reading() callbacks should be used for new consumers.
    /** Listener for OBD2 connection state and reading events. */
    public interface Listener {
        /** Called when the OBD2 connection state changes. */
        void onObd2ConnectedChanged(boolean connected);
        /** Called when a new reading is available (optional). */
        default void onObd2Reading(Reading r) {}
    }

    /** A snapshot of OBD2 readings from the battery ECU. */
    public static final class Reading {
        public final Double soc, voltage, current, battTempC, powerKw;
        public final Integer speedKmh;
        public final long atMs;
        Reading(Double soc, Double voltage, Double current, Double battTempC,
                Double powerKw, Integer speedKmh, long atMs) {
            this.soc = soc; this.voltage = voltage; this.current = current;
            this.battTempC = battTempC; this.powerKw = powerKw;
            this.speedKmh = speedKmh; this.atMs = atMs;
        }
    }

    private static final java.util.List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Subscribes a listener to OBD2 events. */
    public static void subscribe(Listener l) { listeners.add(l); }
    /** Unsubscribes a listener from OBD2 events. */
    public static void unsubscribe(Listener l) { listeners.remove(l); }

    private static void setConnected(boolean c) {
        if (connected == c) return;
        connected = c;
        for (Listener l : listeners) l.onObd2ConnectedChanged(c);
    }

    private static void notifyReading() {
        Reading r = new Reading(soc, voltage, current, battTempC, powerKw, reportedSpeedKmh, lastReadingAtMs);
        logReading(r);
        for (Listener l : listeners) l.onObd2Reading(r);
    }

    private Obd2Reader() {}

    /** Returns whether an OBD2 connection is currently active. */
    public static boolean isConnected() { return connected; }

    private static boolean fresh(long maxAgeMs) {
        return lastReadingAtMs > 0 && System.currentTimeMillis() - lastReadingAtMs <= maxAgeMs;
    }
    /** Returns the last measured power (kW) if within maxAgeMs, or null. */
    public static Float freshPowerKw(long maxAgeMs)   { return (fresh(maxAgeMs) && powerKw != null) ? powerKw.floatValue() : null; }
    /** Returns the last measured SOC (%) if within maxAgeMs, or null. */
    public static Float freshSoc(long maxAgeMs)       { return (fresh(maxAgeMs) && soc != null) ? soc.floatValue() : null; }
    /** Returns the last measured voltage (V) if within maxAgeMs, or null. */
    public static Float freshVoltage(long maxAgeMs)   { return (fresh(maxAgeMs) && voltage != null) ? voltage.floatValue() : null; }
    /** Returns the last measured current (A) if within maxAgeMs, or null. */
    public static Float freshCurrent(long maxAgeMs)   { return (fresh(maxAgeMs) && current != null) ? current.floatValue() : null; }
    /** Returns the last measured battery temperature (C) if within maxAgeMs, or null. */
    public static Float freshBattTempC(long maxAgeMs) { return (fresh(maxAgeMs) && battTempC != null) ? battTempC.floatValue() : null; }

    private static volatile Thread readerThread;
    private static volatile Context appCtx;

    /** Starts the reader thread if enabled and not already running (idempotent). */
    public static synchronized void ensureStarted(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
        enabledWanted = p.getBoolean("obd2_enabled", false);
        if (enabledWanted && !running) {
            running = true;
            Context app = ctx.getApplicationContext();
            appCtx = app;
            Thread t = new Thread(() -> loop(app), "obd2-reader");
            t.setDaemon(true);
            readerThread = t;
            t.start();
        }
    }

    // Persistent append-only record of readings for post-drive analysis.
    // Logcat rotates and is lost within hours, so this file preserves data
    // for verification of charging/regen behavior and ABRP reliability.
    // Capped at LOG_CAP_BYTES and checked before each write to prevent unbounded growth.
    private static final long LOG_CAP_BYTES = 5L * 1024 * 1024;
    private static final java.text.SimpleDateFormat LOG_FMT =
        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);

    private static void logReading(Reading r) {
        Context app = appCtx;
        if (app == null) return;
        String line = LOG_FMT.format(new java.util.Date(r.atMs)) + '\t'
            + r.soc + '\t' + r.voltage + '\t' + r.current + '\t'
            + r.powerKw + '\t' + r.battTempC + '\t' + r.speedKmh;
        try {
            java.io.File dir = app.getExternalFilesDir(null);
            if (dir == null) dir = app.getFilesDir();
            java.io.File f = new java.io.File(dir, "obd2-reading.log");
            if (f.exists() && f.length() > LOG_CAP_BYTES) f.delete(); // rotate: start over, don't grow forever
            boolean fresh = !f.exists();
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            try {
                if (fresh) w.write("wall\tsoc\tvoltage\tcurrent\tpowerKw\tbattTempC\tspeedKmh\n");
                w.write(line + "\n");
            } finally { w.close(); }
        } catch (Throwable t) { Log.w(TAG, "obd2: reading log write: " + t); }
    }

    /** Interrupts any current retry delay to attempt connection immediately. */
    public static void forceReconnectSoon() {
        Thread t = readerThread;
        if (t != null) t.interrupt();
    }

    /** Enables or disables OBD2 reading. Disabling does not interrupt an in-flight session. */
    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE).edit().putBoolean("obd2_enabled", on).apply();
        if (on) ensureStarted(ctx); else enabledWanted = false;
    }

    /** Transport-agnostic interface for OBD2 command execution. */
    private interface Channel {
        String command(String cmd);
        boolean isConnected();
        void close();
    }

    // Maintains a persistent connection rather than connect-read-disconnect cycles.
    // The Bluetooth stack on this hardware requires 8-10+ seconds just to page the
    // dongle, making frequent reconnections prohibitively slow. See field-catalog.md.
    private static void loop(Context ctx) {
        Log.i(TAG, "obd2: reader thread started");
        while (enabledWanted) {
            Channel ch = null;
            try {
                Log.i(TAG, "obd2: attempt — trying classic bonded devices first");
                ch = openClassic();
                // A bonded classic match existing but failing to CONNECT this
                // round is not the same as no classic match at all -- falling
                // back to a BLE scan in that case can pick up the SAME
                // physical dongle's other identity (this device advertises
                // both "vLinker MC-Android" over classic and "vLinker MC-IOS"
                // over BLE simultaneously, per its own manual), which has its
                // own separate, still-unresolved pairing problems. Once a
                // classic device is bonded, keep retrying classic only.
                if (ch == null && !hasBondedClassicCandidate()) {
                    Log.i(TAG, "obd2: no classic match, trying BLE scan");
                    ch = openBle(ctx);
                }
                if (ch == null) {
                    Log.i(TAG, "obd2: nothing found this attempt, retrying in " + (RETRY_MS / 1000) + "s");
                    sleep(RETRY_MS);
                    continue;
                }
                Log.i(TAG, "obd2: connected (" + ch.getClass().getSimpleName() + ")");
                runSession(ch);
            } catch (Throwable t) {
                Log.w(TAG, "obd2: " + t);
            } finally {
                setConnected(false);
                if (ch != null) ch.close();
            }
            if (enabledWanted) sleep(RETRY_MS);
        }
        running = false;
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    // Init runs once per NEW connection (a fresh session after a real
    // disconnect gets a fresh ATZ), not once per app-process lifetime --
    // that per-process version assumed the physical dongle never loses
    // power for the life of the app, which doesn't hold: it got unplugged
    // mid-session during tonight's testing.
    private static void runSession(Channel ch) {
        for (String cmd : INIT_CMDS) ch.command(cmd);
        setConnected(true);
        while (enabledWanted && ch.isConnected()) {
            String socResp   = ch.command("224B36");
            String voltResp  = ch.command("224B21");
            String currResp  = ch.command("224B22");
            String tempResp  = ch.command("224B3C");
            String speedResp = ch.command("22DF01");
            applyReading(socResp, voltResp, currResp, tempResp, speedResp);
            sleep(POLL_MS);
        }
    }

    // =====================================================================
    // Classic Bluetooth (RFCOMM/SPP) -- tried first, only ever looks at
    // devices already bonded (no discovery, no pairing UI of our own).
    // =====================================================================

    // BLUETOOTH/BLUETOOTH_ADMIN are normal (install-time) permissions at
    // this app's targetSdkVersion (28) -- the runtime BLUETOOTH_CONNECT
    // dance only applies from targetSdk 31 on, so there is no permission
    // prompt to handle here, unlike a modern-targeted app.
    @SuppressLint("MissingPermission")
    private static Channel openClassic() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) { Log.i(TAG, "obd2: classic skipped — adapter off/missing"); return null; }
        Set<BluetoothDevice> bonded = a.getBondedDevices();
        if (bonded == null) bonded = java.util.Collections.emptySet();
        Log.i(TAG, "obd2: " + bonded.size() + " bonded device(s): "
            + bonded.stream().map(BluetoothDevice::getName).collect(java.util.stream.Collectors.joining(", ")));
        BluetoothDevice dev = null;
        for (BluetoothDevice d : bonded) {
            if (matchesHint(d.getName())) { dev = d; break; }
        }
        if (dev == null) return null;

        Log.i(TAG, "obd2: classic connecting to " + dev.getName());
        a.cancelDiscovery();
        BluetoothSocket sock = classicConnect(dev);
        if (sock == null) { Log.w(TAG, "obd2: classic connect failed (all 3 strategies)"); return null; }
        try {
            return new ClassicChannel(sock);
        } catch (IOException e) {
            try { sock.close(); } catch (Throwable ignored) {}
            return null;
        }
    }

    // Distinguishes "no classic device bonded" from "one is bonded but this
    // round's connect attempt failed" -- loop() uses this to decide whether
    // falling back to a BLE scan is safe (see loop()'s own comment for why
    // it isn't, once a classic match already exists).
    @SuppressLint("MissingPermission")
    private static boolean hasBondedClassicCandidate() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) return false;
        Set<BluetoothDevice> bonded = a.getBondedDevices();
        if (bonded == null) return false;
        for (BluetoothDevice d : bonded) {
            if (matchesHint(d.getName())) return true;
        }
        return false;
    }

    // Only reflection-based fixed-channel strategies succeed on this hardware.
    // The Bluetooth stack uses a vendor JNI shim that cannot match SDP-based
    // connections, and those attempts leave orphaned RFCOMM channels blocking
    // subsequent retries. Failed sockets must be closed before the next attempt.
    @SuppressLint("MissingPermission")
    private static BluetoothSocket classicConnect(BluetoothDevice dev) {
        BluetoothSocket s;

        s = tryStrategy(() -> {
            java.lang.reflect.Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
            return (BluetoothSocket) m.invoke(dev, 1);
        });
        if (s != null) return s;
        sleep(1500);

        s = tryStrategy(() -> {
            java.lang.reflect.Method m = dev.getClass().getMethod("createInsecureRfcommSocket", int.class);
            return (BluetoothSocket) m.invoke(dev, 1);
        });
        return s;   // null here means neither strategy worked -- caller falls back to BLE
    }

    private interface SocketFactory { BluetoothSocket make() throws Exception; }

    private static BluetoothSocket tryStrategy(SocketFactory f) {
        BluetoothSocket s = null;
        try {
            s = f.make();
            s.connect();
            return s;
        } catch (Throwable t) {
            Log.w(TAG, "obd2: classic strategy failed: " + t);
            if (s != null) { try { s.close(); } catch (Throwable ignored) {} }
            return null;
        }
    }

    private static boolean matchesHint(String name) {
        if (name == null) return false;
        String upper = name.toUpperCase(Locale.US);
        for (String hint : NAME_HINTS) if (upper.contains(hint)) return true;
        return false;
    }

    private static final class ClassicChannel implements Channel {
        private final BluetoothSocket sock;
        private final InputStream in;
        private final OutputStream out;
        // sock.isConnected() remains true even after the remote disconnects, so we
        // track actual connectivity via this flag, flipping to false on command failure.
        private volatile boolean alive = true;
        ClassicChannel(BluetoothSocket s) throws IOException {
            sock = s; in = s.getInputStream(); out = s.getOutputStream();
        }
        @Override public boolean isConnected() { return alive && sock.isConnected(); }
        @Override public void close() { try { sock.close(); } catch (Throwable ignored) {} }

        // Writes cmd + CR, reads until the ELM327 '>' prompt or
        // CMD_TIMEOUT_MS elapses -- matches the ELM327's own plain-text
        // protocol, same idiom AAExCarro's own decompiled code uses
        // (busy-poll available(), no line framing beyond the prompt).
        @Override public String command(String cmd) {
            try {
                out.write((cmd + "\r").getBytes("US-ASCII"));
                out.flush();
                StringBuilder sb = new StringBuilder();
                long deadline = System.currentTimeMillis() + CMD_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    if (in.available() > 0) {
                        int b = in.read();
                        if (b < 0) { alive = false; break; }
                        char c = (char) b;
                        if (c == '>') break;
                        sb.append(c);
                    } else {
                        sleep(12);
                    }
                }
                return sb.toString();
            } catch (Throwable t) {
                alive = false;
                Log.w(TAG, "obd2: classic command " + cmd + ": " + t);
                return null;
            }
        }
    }

    // BLE transport fallback (used when classic Bluetooth is unavailable).
    // Scans by name, connects via GATT, and picks write+notify characteristics
    // using a three-tier strategy: vLinker service UUIDs, HM10 service, or
    // the first writable+notifiable pair on any service.
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // The real one: confirmed live via a BLE scanner app against the actual
    // dongle (Service UUIDs: 18F0) AND found by name in fr3ts0n/AndrOBD's
    // own BleCommService.java (LE_VLINK_SVC/RX/TX) -- a mature, widely-used
    // open-source OBD2 app that has a dedicated vLinker entry in its own
    // service table. Checked FIRST, ahead of the generic fallbacks below.
    private static final UUID VLINK_SERVICE = UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb");
    private static final UUID VLINK_RX = UUID.fromString("00002af0-0000-1000-8000-00805f9b34fb");
    private static final UUID VLINK_TX = UUID.fromString("00002af1-0000-1000-8000-00805f9b34fb");
    private static final UUID HM10_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID HM10_CHAR = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_TX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_RX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    @SuppressLint("MissingPermission")
    private static Channel openBle(Context ctx) {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) { Log.i(TAG, "obd2: ble skipped — adapter off/missing"); return null; }
        BluetoothLeScanner scanner = a.getBluetoothLeScanner();
        if (scanner == null) { Log.w(TAG, "obd2: ble skipped — no BLE scanner on this radio"); return null; }

        BluetoothDevice[] holder = new BluetoothDevice[1];
        String matchedName = scanForCandidate(scanner, holder);
        if (holder[0] == null) { Log.i(TAG, "obd2: ble scan found no name match (see obd2: ble saw ... lines above)"); return null; }

        // AAExCarro's own connect() re-fetches the device via
        // getRemoteDevice(mac) rather than reusing the BluetoothDevice
        // object a ScanResult handed it -- a fresh handle, well after the
        // scan (and its callback) has fully torn down, not one still tied
        // to that scan session's lifecycle. Matched here rather than
        // assumed harmless, since the object straight from ScanResult is
        // exactly what was hanging with zero connection callback at all.
        BluetoothDevice dev = a.getRemoteDevice(holder[0].getAddress());

        Log.i(TAG, "obd2: ble connecting to " + matchedName + " " + dev.getAddress());
        try {
            return BleChannel.connect(ctx, dev);
        } catch (IOException e) {
            Log.w(TAG, "obd2: ble connect: " + e.getMessage());
            return null;
        }
    }

    // ACCESS_FINE_LOCATION (already held, for GpsReader) is what BLE scan
    // has required since Android 6 regardless of target/compile SDK -- no
    // separate BLUETOOTH_SCAN dance needed at this app's targetSdkVersion.
    @SuppressLint("MissingPermission")
    private static String scanForCandidate(BluetoothLeScanner scanner, BluetoothDevice[] outDevice) {
        final BluetoothDevice[] found = outDevice;
        final String[] foundName = new String[1];
        final Set<String> seen = new HashSet<>();
        ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                if (found[0] != null) return;
                BluetoothDevice d = result.getDevice();
                if (d == null || !seen.add(d.getAddress())) return;
                // BluetoothDevice.getName() is very often null pre-connect for a
                // device that has never been bonded -- it's only populated from
                // a cache the OS fills in AFTER a GATT connection (or classic
                // bonding) has happened once. The name a still-unknown BLE
                // peripheral is actually advertising RIGHT NOW lives in the scan
                // record instead, so that's checked first, with getName() only
                // as a fallback for a device Android already knows about.
                String advertised = scanRecordName(result);
                String cached = safeName(d);
                String name = (advertised != null) ? advertised : cached;
                Log.i(TAG, "obd2: ble saw \"" + name + "\" (adv=" + advertised + " cached=" + cached + ") " + d.getAddress());
                if (matchesHint(name)) { found[0] = d; foundName[0] = name; }
            }
            @Override public void onScanFailed(int errorCode) {
                Log.w(TAG, "obd2: ble scan failed, error " + errorCode);
            }
        };
        try {
            scanner.startScan(cb);
            long deadline = System.currentTimeMillis() + BLE_SCAN_MS;
            while (found[0] == null && System.currentTimeMillis() < deadline) sleep(200);
            scanner.stopScan(cb);
        } catch (Throwable t) {
            Log.w(TAG, "obd2: ble scan: " + t);
        }
        return foundName[0];
    }

    @SuppressLint("MissingPermission")
    private static String safeName(BluetoothDevice d) {
        try { return d.getName(); } catch (Throwable t) { return null; }
    }

    private static String scanRecordName(ScanResult result) {
        try {
            android.bluetooth.le.ScanRecord rec = result.getScanRecord();
            return (rec != null) ? rec.getDeviceName() : null;
        } catch (Throwable t) { return null; }
    }

    // Synchronous command()/connect() over an inherently async GATT API,
    // via CountDownLatch -- same shape AAExCarro's own BleTransport uses,
    // so the rest of this class (runSession) never has to know it's
    // talking to a callback-driven transport underneath.
    private static final class BleChannel implements Channel {
        private final BluetoothGatt gatt;
        private final BluetoothGattCharacteristic writeChar;
        private final BluetoothGattCharacteristic notifyChar;
        private final StringBuilder rxBuf = new StringBuilder();
        private volatile CountDownLatch cmdLatch;
        private volatile boolean connectedFlag = true;

        private BleChannel(BluetoothGatt g, BluetoothGattCharacteristic w, BluetoothGattCharacteristic n) {
            gatt = g; writeChar = w; notifyChar = n;
        }

        @SuppressLint("MissingPermission")
        static BleChannel connect(Context ctx, BluetoothDevice dev) throws IOException {
            final CountDownLatch ready = new CountDownLatch(1);
            final BluetoothGattCharacteristic[] picked = new BluetoothGattCharacteristic[2];   // [write, notify]
            final String[] error = new String[1];
            final BleChannel[] self = new BleChannel[1];

            BluetoothGattCallback callback = new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    Log.i(TAG, "obd2: ble onConnectionStateChange status=" + status + " newState=" + newState);
                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        boolean started;
                        try { started = g.discoverServices(); }
                        catch (SecurityException e) { started = false; error[0] = "discoverServices: " + e; ready.countDown(); return; }
                        Log.i(TAG, "obd2: ble discoverServices() started=" + started);
                        if (!started) { error[0] = "discoverServices() returned false"; ready.countDown(); }
                    } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        if (self[0] != null) self[0].connectedFlag = false;
                        error[0] = "disconnected (status " + status + ")";
                        ready.countDown();
                        CountDownLatch cl = (self[0] != null) ? self[0].cmdLatch : null;
                        if (cl != null) cl.countDown();
                    }
                }
                @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                    Log.i(TAG, "obd2: ble onServicesDiscovered status=" + status
                        + " services=" + g.getServices().size());
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        error[0] = "discoverServices status " + status;
                        ready.countDown();
                        return;
                    }
                    for (BluetoothGattService svc : g.getServices())
                        Log.i(TAG, "obd2: ble service " + svc.getUuid() + " chars=" + svc.getCharacteristics().size());
                    pickCharacteristics(g, picked);
                    if (picked[0] == null || picked[1] == null) {
                        error[0] = "no UART-like service found (HM10/NUS/generic)";
                        ready.countDown();
                        return;
                    }
                    Log.i(TAG, "obd2: ble picked write=" + picked[0].getUuid() + " notify=" + picked[1].getUuid());
                    try {
                        g.setCharacteristicNotification(picked[1], true);
                        BluetoothGattDescriptor d = picked[1].getDescriptor(CCCD);
                        if (d != null) {
                            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            g.writeDescriptor(d);
                            return;   // ready.countDown() happens in onDescriptorWrite
                        }
                    } catch (SecurityException ignored) {}
                    ready.countDown();
                }
                @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
                    ready.countDown();
                }
                @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                    byte[] value = c.getValue();
                    if (value == null || self[0] == null) return;
                    synchronized (self[0].rxBuf) {
                        self[0].rxBuf.append(new String(value, java.nio.charset.StandardCharsets.US_ASCII));
                        CountDownLatch cl = self[0].cmdLatch;
                        if (self[0].rxBuf.indexOf(">") >= 0 && cl != null) cl.countDown();
                    }
                }
            };

            // autoConnect=true lets Android's retry loop handle connection,
            // which is slower initially but more reliable than failing immediately.
            // Used instead of false (which can hang with no callback on some stacks).
            // Follows AndrOBD's proven approach with BLE_CONNECT_TIMEOUT_MS sized generously.
            BluetoothGatt gatt = dev.connectGatt(ctx, true, callback, BluetoothDevice.TRANSPORT_LE);
            boolean signalled;
            try { signalled = ready.await(BLE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { signalled = false; }

            if (!signalled || picked[0] == null || picked[1] == null) {
                try { gatt.disconnect(); gatt.close(); } catch (Throwable ignored) {}
                throw new IOException(error[0] != null ? error[0] : "timeout waiting for BLE service");
            }
            BleChannel ch = new BleChannel(gatt, picked[0], picked[1]);
            self[0] = ch;
            return ch;
        }

        private static void pickCharacteristics(BluetoothGatt g, BluetoothGattCharacteristic[] out) {
            BluetoothGattService vlink = g.getService(VLINK_SERVICE);
            if (vlink != null) {
                // out[0]=write, out[1]=notify (this class's own convention) --
                // VLINK_TX is what the phone writes commands to, VLINK_RX is
                // what the dongle notifies responses on. Named the opposite
                // way in AndrOBD's own code (their "RX"/"TX" is from the
                // DONGLE's point of view), same characteristics either way.
                BluetoothGattCharacteristic tx = vlink.getCharacteristic(VLINK_TX);
                BluetoothGattCharacteristic rx = vlink.getCharacteristic(VLINK_RX);
                if (tx != null && rx != null) { out[0] = tx; out[1] = rx; return; }
            }
            BluetoothGattService hm10 = g.getService(HM10_SERVICE);
            if (hm10 != null) {
                BluetoothGattCharacteristic c = hm10.getCharacteristic(HM10_CHAR);
                if (c != null) { out[0] = c; out[1] = c; return; }
            }
            BluetoothGattService nus = g.getService(NUS_SERVICE);
            if (nus != null) {
                BluetoothGattCharacteristic tx = nus.getCharacteristic(NUS_TX);
                BluetoothGattCharacteristic rx = nus.getCharacteristic(NUS_RX);
                if (tx != null && rx != null) { out[0] = tx; out[1] = rx; return; }
            }
            // Generic fallback: first characteristic (on any service) with a
            // WRITE-shaped property, and the first with a NOTIFY/INDICATE
            // one -- covers a dongle whose vendor UUIDs aren't known here.
            for (BluetoothGattService svc : g.getServices()) {
                BluetoothGattCharacteristic w = null, n = null;
                for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                    int props = c.getProperties();
                    if (w == null && (props & (BluetoothGattCharacteristic.PROPERTY_WRITE
                                              | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) w = c;
                    if (n == null && (props & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                                              | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) n = c;
                }
                if (w != null && n != null) { out[0] = w; out[1] = n; return; }
            }
        }

        @Override public boolean isConnected() { return connectedFlag; }

        @SuppressLint("MissingPermission")
        @Override public void close() {
            try { gatt.disconnect(); gatt.close(); } catch (Throwable ignored) {}
            connectedFlag = false;
        }

        @SuppressLint("MissingPermission")
        @Override public String command(String cmd) {
            synchronized (rxBuf) { rxBuf.setLength(0); }
            CountDownLatch latch = new CountDownLatch(1);
            cmdLatch = latch;
            try {
                byte[] bytes = (cmd + "\r").getBytes("US-ASCII");
                writeChar.setWriteType((writeChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                writeChar.setValue(bytes);
                if (!gatt.writeCharacteristic(writeChar)) return null;
                try { latch.await(CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
                cmdLatch = null;
                if (!connectedFlag) return null;
                synchronized (rxBuf) { return rxBuf.toString().replace(">", "").trim(); }
            } catch (Throwable t) {
                Log.w(TAG, "obd2: ble command " + cmd + ": " + t);
                return null;
            }
        }
    }

    // =====================================================================
    // Shared: applying a poll's responses to the published snapshot.
    // =====================================================================
    // Returns whether any real field was actually parsed out of this round's
    // responses -- distinct from "the dongle answered something." Matters
    // now that a connect-read-disconnect cycle reports "connected" only when
    // this comes back true (see loop()) -- a cycle that reaches the dongle
    // but gets back only echoes/errors/"NO DATA" should not read as success.
    private static boolean applyReading(String socResp, String voltResp, String currResp,
                                      String tempResp, String speedResp) {
        int[] socB  = parseDataBytes(socResp,  "4B36", 2);
        int[] voltB = parseDataBytes(voltResp, "4B21", 2);
        int[] currB = parseDataBytes(currResp, "4B22", 2);
        int[] tempB = parseDataBytes(tempResp, "4B3C", 1);
        int[] spdB  = parseDataBytes(speedResp, "DF01", 1);

        Double newSoc = (socB != null) ? (socB[0] * 256 + socB[1]) / 10.0 : null;
        Double newVolt = (voltB != null) ? (voltB[0] * 256 + voltB[1]) / 10.0 : null;
        // -5000 raw-unit offset (500 in tenths) -- confirmed against
        // field-catalog.md's own table, positive = discharge, negative =
        // charge (matches ABRP's own sign convention for `power`).
        Double newCurr = (currB != null) ? (currB[0] * 256 + currB[1] - 5000) / 10.0 : null;
        Double newTemp = (tempB != null) ? (double) tempB[0] : null;
        Integer newSpeed = (spdB != null) ? spdB[0] : null;

        if (newSoc != null) soc = newSoc;
        if (newVolt != null) voltage = newVolt;
        if (newCurr != null) current = newCurr;
        if (newTemp != null) battTempC = newTemp;
        if (newSpeed != null) reportedSpeedKmh = newSpeed;
        if (newVolt != null && newCurr != null) powerKw = (newVolt * newCurr) / 1000.0;

        boolean any = newSoc != null || newVolt != null || newCurr != null;
        if (any) { lastReadingAtMs = System.currentTimeMillis(); notifyReading(); }
        return any;
    }

    // UDS ReadDataByIdentifier response = request service id + 0x40 (0x22 ->
    // 0x62) followed by the echoed PID, then nBytes of data. Headers-on
    // (ATH1) prefixes the CAN header itself, which this ignores by simply
    // searching for the response marker rather than assuming a fixed offset
    // -- robust to whichever header/length bytes precede it.
    private static int[] parseDataBytes(String resp, String pidHex, int nBytes) {
        if (resp == null) return null;
        String clean = resp.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);
        String marker = "62" + pidHex;
        int idx = clean.indexOf(marker);
        if (idx < 0) return null;
        int dataStart = idx + marker.length();
        if (dataStart + nBytes * 2 > clean.length()) return null;
        int[] out = new int[nBytes];
        try {
            for (int i = 0; i < nBytes; i++)
                out[i] = Integer.parseInt(clean.substring(dataStart + i * 2, dataStart + i * 2 + 2), 16);
            return out;
        } catch (NumberFormatException e) { return null; }
    }
}
