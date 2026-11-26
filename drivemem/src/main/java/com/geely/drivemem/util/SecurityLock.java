package com.geely.drivemem.util;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Log;

import com.geely.drivemem.sensors.LocationHub;
import com.geely.drivemem.state.CarplayState;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Local PIN lock and Trusted Phone (Bluetooth / iPhone cable / Wi-Fi) unlock gate for
 * Valet Mode and Config (TelemetryActivity).
 *
 * Security invariants:
 * - PINs are stored as salted PBKDF2-HMAC-SHA256 hashes (never plaintext, never
 *   logged, never backed up, never restorable over HTTP from a phone).
 * - Home coordinates captured for emergency PIN reset are stored only locally
 *   in SharedPreferences — never displayed, logged, or published.
 * - Rate-limits repeated wrong PIN attempts (5 tries -> 30 s cooldown). */
public final class SecurityLock {
    private static final String TAG = "DriveMem";
    private static final int PBKDF2_ITERS = 10_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    public static final int MIN_PIN_LEN = 4;
    public static final int MAX_PIN_LEN = 6;
    public static final int MAX_ATTEMPTS = 5;
    public static final long LOCKOUT_MS = 30_000L;
    public static final double HOME_RESET_RADIUS_M = 100.0;
    /** Passive BLE presence window (ms) and minimum RSSI (dBm) for mere-presence unlock. */
    public static final long BLE_PRESENCE_TTL_MS = 45_000L;
    public static final int BLE_PRESENCE_MIN_RSSI = -85;
    /** Minimum Wi-Fi RSSI (dBm) to treat a nearby scanned known Wi-Fi as present. */
    public static final int WIFI_NEARBY_MIN_RSSI = -78;
    private static final int APPLE_COMPANY_ID = 0x004C;

    private static final Set<String> connectedMacs = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<String, Long> seenBleMacs = new ConcurrentHashMap<>();
    private static final Map<String, Long> seenBeacons = new ConcurrentHashMap<>();
    private static volatile boolean subscribed;
    private static volatile boolean bleMonitoring;
    private static ScanCallback passiveBleCb;
    private static ScanCallback pickerBleCb;
    private static int failedAttempts;

    private SecurityLock() {}

    /** Bluetooth / BLE / iBeacon descriptor for the Security picker UI. */
    public static final class BtDevice {
        public final String mac;
        public final String beaconUuid;
        public final String name;
        public final boolean connected;
        public final boolean paired;
        public final int rssi;

        public BtDevice(String mac, String name, boolean connected) {
            this(mac, "", name, connected, true, 0);
        }

        public BtDevice(String mac, String beaconUuid, String name, boolean connected, boolean paired, int rssi) {
            this.mac = mac != null ? mac : "";
            this.beaconUuid = beaconUuid != null ? beaconUuid : "";
            this.name = name != null ? name : "";
            this.connected = connected;
            this.paired = paired;
            this.rssi = rssi;
        }

        public boolean isBeacon() { return !beaconUuid.isEmpty(); }
    }

    public interface NearbyListener { void onFound(BtDevice device); }

    /** Subscribes to Bluetooth ACL connect/disconnect broadcasts and passive BLE presence; idempotent. */
    public static synchronized void ensureSubscribed(Context ctx) {
        if (subscribed || ctx == null) return;
        subscribed = true;
        Context app = ctx.getApplicationContext();
        seedConnectedDevices();
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String act = i.getAction();
                    if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(act)) {
                        int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                        if (st == BluetoothAdapter.STATE_ON) {
                            bleMonitoring = false;
                            refreshBleMonitor(app);
                        }
                        return;
                    }
                    BluetoothDevice dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (dev == null || dev.getAddress() == null) return;
                    String mac = dev.getAddress().toUpperCase(Locale.US);
                    if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(act)) {
                        connectedMacs.add(mac);
                    } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(act)) {
                        connectedMacs.remove(mac);
                    }
                }
            }, f);
        } catch (Throwable t) {
            Log.w(TAG, "security: bt receiver: " + t);
        }
        refreshBleMonitor(app);
    }

    /** Starts or stops passive low-power BLE scanning based on whether a BT MAC or iBeacon is configured. */
    public static synchronized void refreshBleMonitor(Context ctx) {
        if (ctx == null) return;
        boolean need = !Prefs.getSecurityBtMac(ctx).isEmpty() || !Prefs.getSecurityBeaconUuid(ctx).isEmpty();
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            BluetoothLeScanner scanner = (a != null && a.isEnabled()) ? a.getBluetoothLeScanner() : null;
            if (!need) {
                if (bleMonitoring && scanner != null && passiveBleCb != null) {
                    try { scanner.stopScan(passiveBleCb); } catch (Throwable ignored) {}
                }
                bleMonitoring = false;
                return;
            }
            if (bleMonitoring || scanner == null) return;
            if (passiveBleCb == null) {
                passiveBleCb = new ScanCallback() {
                    @Override public void onScanResult(int callbackType, ScanResult result) {
                        recordBleSighting(result);
                    }
                    @Override public void onBatchScanResults(List<ScanResult> results) {
                        if (results != null) for (ScanResult r : results) recordBleSighting(r);
                    }
                };
            }
            ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build();
            scanner.startScan(null, settings, passiveBleCb);
            bleMonitoring = true;
            Log.i(TAG, "security: passive BLE presence monitor started");
        } catch (Throwable t) {
            Log.w(TAG, "security: ble monitor: " + t);
        }
    }

    private static void recordBleSighting(ScanResult result) {
        if (result == null || result.getRssi() < BLE_PRESENCE_MIN_RSSI) return;
        long now = SystemClock.elapsedRealtime();
        BluetoothDevice dev = result.getDevice();
        if (dev != null && dev.getAddress() != null) {
            seenBleMacs.put(dev.getAddress().toUpperCase(Locale.US), now);
        }
        ScanRecord rec = result.getScanRecord();
        if (rec != null) {
            byte[] mfg = rec.getManufacturerSpecificData(APPLE_COMPANY_ID);
            String uuid = parseIBeaconUuid(mfg);
            if (uuid != null) seenBeacons.put(uuid, now);
        }
    }

    /** Starts an active BLE scan for the Security picker to discover unpaired nearby devices & iBeacons. */
    public static synchronized void startNearbyScan(NearbyListener listener) {
        stopNearbyScan();
        if (listener == null) return;
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null || !a.isEnabled()) return;
            BluetoothLeScanner scanner = a.getBluetoothLeScanner();
            if (scanner == null) return;
            pickerBleCb = new ScanCallback() {
                @Override public void onScanResult(int callbackType, ScanResult result) {
                    if (result == null) return;
                    recordBleSighting(result);
                    int rssi = result.getRssi();
                    ScanRecord rec = result.getScanRecord();
                    if (rec != null) {
                        String uuid = parseIBeaconUuid(rec.getManufacturerSpecificData(APPLE_COMPANY_ID));
                        if (uuid != null) {
                            String dn = rec.getDeviceName();
                            String label = (dn != null && !dn.trim().isEmpty())
                                ? dn.trim() + " (iBeacon)"
                                : "iBeacon " + uuid.substring(0, 8);
                            listener.onFound(new BtDevice("", uuid, label, true, false, rssi));
                        }
                    }
                    BluetoothDevice dev = result.getDevice();
                    if (dev != null && dev.getAddress() != null) {
                        String name = null;
                        if (rec != null) name = rec.getDeviceName();
                        if (name == null || name.trim().isEmpty()) name = dev.getName();
                        if (name != null && !name.trim().isEmpty()) {
                            listener.onFound(new BtDevice(dev.getAddress().toUpperCase(Locale.US),
                                "", name.trim(), false, false, rssi));
                        }
                    }
                }
            };
            ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
            scanner.startScan(null, settings, pickerBleCb);
        } catch (Throwable t) {
            Log.w(TAG, "security: nearby scan: " + t);
        }
    }

    public static synchronized void stopNearbyScan() {
        if (pickerBleCb == null) return;
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            BluetoothLeScanner scanner = (a != null && a.isEnabled()) ? a.getBluetoothLeScanner() : null;
            if (scanner != null) scanner.stopScan(pickerBleCb);
        } catch (Throwable ignored) {}
        pickerBleCb = null;
    }

    private static void seedConnectedDevices() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null || !a.isEnabled()) return;
            Set<BluetoothDevice> bonded = a.getBondedDevices();
            if (bonded == null) return;
            for (BluetoothDevice d : bonded) {
                if (d != null && d.getAddress() != null && isDeviceConnected(d)) {
                    connectedMacs.add(d.getAddress().toUpperCase(Locale.US));
                }
            }
        } catch (Throwable ignored) {}
    }

    /** True when the user has configured a PIN. */
    public static boolean hasPin(Context ctx) {
        return ctx != null
            && !Prefs.getSecurityPinHash(ctx).isEmpty()
            && !Prefs.getSecurityPinSalt(ctx).isEmpty();
    }

    /** Sets a new PIN (safe reset location is configured explicitly in Security). */
    public static boolean setPin(Context ctx, String pin) {
        if (ctx == null || !isValidPin(pin)) return false;
        String[] sh = hashPin(pin);
        Prefs.setSecurityPin(ctx, sh[0], sh[1]);
        failedAttempts = 0;
        return true;
    }

    /** Explicitly saves the car's current GPS fix as the safe PIN-reset location. */
    public static boolean setSafeLocationFromCurrentFix(Context ctx) {
        if (ctx == null) return false;
        LocationHub.Fix fix = LocationHub.get(ctx);
        if (fix == null) return false;
        Prefs.setSecurityHome(ctx, fix.lat, fix.lon);
        return true;
    }

    /** Clears the saved safe PIN-reset location. */
    public static void clearSafeLocation(Context ctx) {
        if (ctx == null) return;
        Prefs.clearSecurityHome(ctx);
    }

    public static void clearPin(Context ctx) {
        if (ctx == null) return;
        Prefs.clearSecurityPin(ctx);
        failedAttempts = 0;
    }

    /** Remaining lockout seconds, or 0 if not locked out. */
    public static int lockoutRemainingSec(Context ctx, long nowMs) {
        long until = Prefs.getSecurityLockoutUntil(ctx);
        if (until <= nowMs) return 0;
        return (int) Math.ceil((until - nowMs) / 1000.0);
    }

    /** Verifies a PIN attempt, enforcing the 5-attempt cooldown lockout. */
    public static synchronized boolean verifyPin(Context ctx, String pin, long nowMs) {
        if (ctx == null || !hasPin(ctx)) return true;
        if (lockoutRemainingSec(ctx, nowMs) > 0) return false;
        boolean ok = isValidPin(pin) && verifyHash(pin,
            Prefs.getSecurityPinSalt(ctx), Prefs.getSecurityPinHash(ctx));
        if (ok) {
            failedAttempts = 0;
            Prefs.setSecurityLockoutUntil(ctx, 0L);
            return true;
        }
        failedAttempts++;
        if (failedAttempts >= MAX_ATTEMPTS) {
            failedAttempts = 0;
            Prefs.setSecurityLockoutUntil(ctx, nowMs + LOCKOUT_MS);
        }
        return false;
    }

    /** True when the owner has configured a trusted Bluetooth phone, BLE beacon, Wi-Fi, or CarPlay trust. */
    public static boolean hasTrustedPhoneConfigured(Context ctx) {
        if (ctx == null) return false;
        String mac = Prefs.getSecurityBtMac(ctx);
        String beacon = Prefs.getSecurityBeaconUuid(ctx);
        return (mac != null && !mac.isEmpty())
            || (beacon != null && !beacon.isEmpty())
            || (Prefs.getSecurityTrustCarplay(ctx) && !Prefs.getSecurityCarplaySerial(ctx).isEmpty())
            || (Prefs.getSecurityTrustWifi(ctx) && !trustedWifiSsids(ctx).isEmpty());
    }

    /** Names of the trusted Wi-Fi networks, for the Security card. */
    public static List<String> trustedWifiSsids(Context ctx) {
        if (ctx == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (WifiEntry e : parseWifiEntries(Prefs.getSecurityWifiSsidsRaw(ctx))) out.add(e.ssid);
        return out;
    }

    /** Trusts the Wi-Fi the car is connected to now, by its router MAC
     * (BSSID), not only by its name: anyone can name a hotspot like the
     * owner's home network. Other routers of the same network heard
     * strongly right now (a mesh at home) are trusted too.
     * @return the network name, or empty when the car is not connected or
     *         Android hides the router MAC (no location permission). */
    public static String addTrustedWifi(Context ctx) {
        if (ctx == null) return "";
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return "";
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            if (info == null) return "";
            String ssid = cleanSsid(info.getSSID());
            String bssid = normalizeBssid(info.getBSSID());
            if (ssid.isEmpty() || bssid.isEmpty()) return "";
            List<String> bssids = new ArrayList<>();
            bssids.add(bssid);
            List<android.net.wifi.ScanResult> scans = wm.getScanResults();
            if (scans != null) {
                for (android.net.wifi.ScanResult sr : scans) {
                    if (sr == null || sr.level < WIFI_NEARBY_MIN_RSSI) continue;
                    if (!ssid.equalsIgnoreCase(cleanSsid(sr.SSID))) continue;
                    String b = normalizeBssid(sr.BSSID);
                    if (!b.isEmpty()) bssids.add(b);
                }
            }
            List<WifiEntry> entries = parseWifiEntries(Prefs.getSecurityWifiSsidsRaw(ctx));
            Prefs.setSecurityWifiSsidsRaw(ctx, formatWifiEntries(mergeWifiEntry(entries, ssid, bssids)));
            return ssid;
        } catch (Throwable t) {
            Log.w(TAG, "security: add wifi: " + t);
            return "";
        }
    }

    public static void clearExtraTrustedWifis(Context ctx) {
        if (ctx != null) Prefs.setSecurityWifiSsidsRaw(ctx, "");
    }

    /** Returns the trusted Wi-Fi the car is connected to, or hears strongly,
     * matched by router MAC; empty when none. */
    public static String matchedTrustedWifi(Context ctx) {
        if (ctx == null) return "";
        List<WifiEntry> trusted = parseWifiEntries(Prefs.getSecurityWifiSsidsRaw(ctx));
        if (trusted.isEmpty()) return "";
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return "";
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            if (info != null) {
                String hit = matchWifi(info.getSSID(), info.getBSSID(), trusted);
                if (!hit.isEmpty()) return hit;
            }
            List<android.net.wifi.ScanResult> scans = wm.getScanResults();
            if (scans != null) {
                for (android.net.wifi.ScanResult sr : scans) {
                    if (sr == null || sr.level < WIFI_NEARBY_MIN_RSSI) continue;
                    String hit = matchWifi(sr.SSID, sr.BSSID, trusted);
                    if (!hit.isEmpty()) return hit;
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** True when at least one layered recovery mechanism is configured. */
    public static boolean canOfferForgotPin(Context ctx) {
        if (ctx == null) return false;
        if (Prefs.hasSecurityHome(ctx)) return true;
        return hasTrustedPhoneConfigured(ctx);
    }

    /** Layered emergency PIN reset:
     *  Layer 1: a trusted signal is present (each one only while its own
     *          switch is on, exactly as for unlocking).
     *  Layer 2: parked at the saved safe GPS location (<= 100 m). */
    public static boolean tryHomeReset(Context ctx) {
        if (ctx == null) return false;
        boolean allowed = isTrustedPhonePresent(ctx);
        if (!allowed && Prefs.hasSecurityHome(ctx)) {
            LocationHub.Fix fix = LocationHub.get(ctx);
            if (fix != null && withinHomeResetRadius(fix.lat, fix.lon,
                    Prefs.getSecurityHomeLat(ctx), Prefs.getSecurityHomeLon(ctx))) {
                allowed = true;
            }
        }
        if (!allowed) return false;
        clearPin(ctx);
        return true;
    }

    /** Describes which trust signal is currently unlocking the car, or empty if locked. */
    public static String activeUnlockSource(Context ctx) {
        if (ctx == null) return "";
        if (Prefs.getSecurityTrustCarplay(ctx)) {
            // Only the owner's own iPhone, by its USB serial: any iPhone a
            // valet plugs in is an Apple USB device too.
            String serial = Prefs.getSecurityCarplaySerial(ctx);
            if (!serial.isEmpty() && CarplayState.appleUsbSerials(ctx).contains(serial)) {
                return "iPhone";
            }
        }
        if (Prefs.getSecurityTrustWifi(ctx)) {
            String ssid = matchedTrustedWifi(ctx);
            if (!ssid.isEmpty()) return "Wi-Fi (" + ssid + ")";
        }
        long now = SystemClock.elapsedRealtime();
        String beacon = Prefs.getSecurityBeaconUuid(ctx);
        if (beacon != null && !beacon.isEmpty()) {
            Long seen = seenBeacons.get(beacon.toLowerCase(Locale.US));
            if (seen != null && (now - seen) <= BLE_PRESENCE_TTL_MS) {
                return "iBeacon";
            }
        }
        String mac = Prefs.getSecurityBtMac(ctx);
        if (mac != null && !mac.isEmpty()) {
            String target = mac.toUpperCase(Locale.US);
            if (connectedMacs.contains(target)) return "Bluetooth";
            Long seen = seenBleMacs.get(target);
            if (seen != null && (now - seen) <= BLE_PRESENCE_TTL_MS) return "BLE";
            try {
                BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                if (a != null && a.isEnabled()) {
                    Set<BluetoothDevice> bonded = a.getBondedDevices();
                    if (bonded != null) {
                        for (BluetoothDevice d : bonded) {
                            if (d != null && target.equalsIgnoreCase(d.getAddress()) && isDeviceConnected(d)) {
                                connectedMacs.add(target);
                                return "Bluetooth";
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return "";
    }

    /** True when the owner's trusted phone, cable/wireless CarPlay, iBeacon, or known Wi-Fi is present. */
    public static boolean isTrustedPhonePresent(Context ctx) {
        return !activeUnlockSource(ctx).isEmpty();
    }

    public static boolean needsUnlockForValet(Context ctx) {
        return hasPin(ctx) && Prefs.getSecurityLockValet(ctx) && !isTrustedPhonePresent(ctx);
    }

    public static boolean needsUnlockForSettings(Context ctx) {
        return hasPin(ctx) && Prefs.getSecurityLockSettings(ctx) && !isTrustedPhonePresent(ctx);
    }

    /** What Config (TelemetryActivity) needs before it shows anything. */
    public enum SettingsGate { OPEN, NEED_PIN, BLOCKED }

    /** Decides the Config gate. The page asks this itself when it opens, so
     * every way into it (the cog, a shortcut, recents) meets the same lock.
     * During Valet a PIN is always needed unless a trusted signal is
     * present; Valet without a PIN keeps Config closed. */
    public static SettingsGate settingsGate(Context ctx) {
        if (com.geely.drivemem.state.ValetSession.isActive(ctx)) {
            if (!hasPin(ctx)) return SettingsGate.BLOCKED;
            return isTrustedPhonePresent(ctx) ? SettingsGate.OPEN : SettingsGate.NEED_PIN;
        }
        return needsUnlockForSettings(ctx) ? SettingsGate.NEED_PIN : SettingsGate.OPEN;
    }

    // ---- Cached presence for the hub thread ----

    private static final long PRESENCE_MAX_AGE_MS = 5_000L;
    private static volatile boolean presenceCached;
    private static volatile long presenceAtMs = -PRESENCE_MAX_AGE_MS;
    private static final java.util.concurrent.atomic.AtomicBoolean presenceBusy =
        new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.ExecutorService presenceExec =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "security-presence");
            t.setDaemon(true);
            return t;
        });

    /** Checks presence now (USB, Wi-Fi, Bluetooth: binder calls) and caches
     * it. Not for the hub thread. */
    public static boolean refreshTrustedPresenceNow(Context ctx) {
        boolean v = isTrustedPhonePresent(ctx);
        presenceCached = v;
        presenceAtMs = SystemClock.elapsedRealtime();
        return v;
    }

    /** Last known presence, at most a few seconds old. Cheap: when the value
     * is stale it starts a check on a worker thread and returns the old one,
     * so a hub listener never waits on USB, Wi-Fi or Bluetooth. */
    public static boolean trustedPresenceCached(Context ctx) {
        if (ctx != null && SystemClock.elapsedRealtime() - presenceAtMs > PRESENCE_MAX_AGE_MS
                && presenceBusy.compareAndSet(false, true)) {
            Context app = ctx.getApplicationContext();
            try {
                presenceExec.execute(() -> {
                    try { refreshTrustedPresenceNow(app); }
                    catch (Throwable t) { Log.w(TAG, "security: presence: " + t); }
                    finally { presenceBusy.set(false); }
                });
            } catch (Throwable t) {
                presenceBusy.set(false);
            }
        }
        return presenceCached;
    }

    /** Lists bonded Bluetooth devices, connected first then alphabetically. */
    public static java.util.List<BtDevice> listPairedDevices() {
        java.util.List<BtDevice> out = new ArrayList<>();
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) return out;
            Set<BluetoothDevice> bonded = a.getBondedDevices();
            if (bonded == null) return out;
            for (BluetoothDevice d : bonded) {
                if (d == null || d.getAddress() == null) continue;
                String mac = d.getAddress().toUpperCase(Locale.US);
                String name = d.getName();
                if (name == null || name.trim().isEmpty()) name = mac;
                boolean conn = connectedMacs.contains(mac) || isDeviceConnected(d);
                out.add(new BtDevice(mac, name, conn));
            }
            out.sort((x, y) -> {
                if (x.connected != y.connected) return x.connected ? -1 : 1;
                return x.name.compareToIgnoreCase(y.name);
            });
        } catch (Throwable t) {
            Log.w(TAG, "security: listPairedDevices: " + t);
        }
        return out;
    }

    private static boolean isDeviceConnected(BluetoothDevice dev) {
        try {
            Method m = dev.getClass().getMethod("isConnected");
            Object res = m.invoke(dev);
            return res instanceof Boolean && (Boolean) res;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---- Pure-Java testable helpers (no Android framework dependency) ----

    public static boolean isValidPin(String pin) {
        if (pin == null || pin.length() < MIN_PIN_LEN || pin.length() > MAX_PIN_LEN) return false;
        for (int i = 0; i < pin.length(); i++) {
            char c = pin.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    public static boolean withinHomeResetRadius(double lat, double lon, double homeLat, double homeLon) {
        return LocationHub.distanceM(lat, lon, homeLat, homeLon) <= HOME_RESET_RADIUS_M;
    }

    /** One trusted Wi-Fi network: its name and the router MACs (BSSIDs)
     * that were heard when the owner trusted it. */
    public static final class WifiEntry {
        public final String ssid;
        public final List<String> bssids;
        public WifiEntry(String ssid, List<String> bssids) {
            this.ssid = ssid;
            this.bssids = bssids;
        }
    }

    /** Strips the quotes Android puts around an SSID; empty for "<unknown ssid>". */
    public static String cleanSsid(String raw) {
        if (raw == null) return "";
        String s = raw.replace("\"", "").trim();
        return "<unknown ssid>".equalsIgnoreCase(s) ? "" : s;
    }

    /** Lower-case MAC, or empty when missing, malformed, or the placeholder
     * Android returns when it hides the real one. */
    public static String normalizeBssid(String raw) {
        if (raw == null) return "";
        String b = raw.trim().toLowerCase(Locale.US);
        if (!b.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}")) return "";
        if (b.equals("02:00:00:00:00:00") || b.equals("00:00:00:00:00:00")) return "";
        return b;
    }

    /** Parses the stored list: one network per line, "SSID<TAB>mac,mac".
     * A line without a MAC (the old name-only format) is dropped: it would
     * trust any hotspot with that name. */
    public static List<WifiEntry> parseWifiEntries(String raw) {
        List<WifiEntry> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String line : raw.split("[\\r\\n]+")) {
            int tab = line.lastIndexOf('\t');
            if (tab <= 0) continue;
            String ssid = line.substring(0, tab).trim();
            if (ssid.isEmpty()) continue;
            List<String> macs = new ArrayList<>();
            for (String m : line.substring(tab + 1).split(",")) {
                String b = normalizeBssid(m);
                if (!b.isEmpty() && !macs.contains(b)) macs.add(b);
            }
            if (!macs.isEmpty()) out.add(new WifiEntry(ssid, macs));
        }
        return out;
    }

    public static String formatWifiEntries(List<WifiEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (WifiEntry e : entries) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.ssid).append('\t').append(String.join(",", e.bssids));
        }
        return sb.toString();
    }

    /** Adds the routers to the network of the same name, or adds the network. */
    public static List<WifiEntry> mergeWifiEntry(List<WifiEntry> entries, String ssid, List<String> bssids) {
        List<WifiEntry> out = new ArrayList<>();
        List<String> macs = new ArrayList<>();
        for (WifiEntry e : entries) {
            if (e.ssid.equalsIgnoreCase(ssid)) macs.addAll(e.bssids);
            else out.add(e);
        }
        for (String m : bssids) {
            String b = normalizeBssid(m);
            if (!b.isEmpty() && !macs.contains(b)) macs.add(b);
        }
        if (!macs.isEmpty()) out.add(new WifiEntry(ssid, macs));
        return out;
    }

    /** The trusted network name when both the name and the router MAC match; else empty. */
    public static String matchWifi(String rawSsid, String rawBssid, List<WifiEntry> trusted) {
        String ssid = cleanSsid(rawSsid);
        String bssid = normalizeBssid(rawBssid);
        if (ssid.isEmpty() || bssid.isEmpty() || trusted == null) return "";
        for (WifiEntry e : trusted) {
            if (e.ssid.equalsIgnoreCase(ssid) && e.bssids.contains(bssid)) return e.ssid;
        }
        return "";
    }

    /** Parses an Apple iBeacon 16-byte UUID from manufacturer data (company ID 0x004C).
     *  Returns canonical lowercase hyphenated UUID, or null if not an iBeacon packet. */
    public static String parseIBeaconUuid(byte[] mfgData) {
        if (mfgData == null || mfgData.length < 23) return null;
        if ((mfgData[0] & 0xFF) != 0x02 || (mfgData[1] & 0xFF) != 0x15) return null;
        StringBuilder sb = new StringBuilder(36);
        for (int i = 0; i < 16; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-');
            sb.append(String.format(Locale.US, "%02x", mfgData[2 + i] & 0xFF));
        }
        return sb.toString();
    }

    /** Returns {saltHex, hashHex} using salted PBKDF2-HMAC-SHA256. */
    public static String[] hashPin(String pin) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = derive(pin, salt);
        return new String[]{toHex(salt), toHex(hash)};
    }

    public static boolean verifyHash(String pin, String saltHex, String expectedHashHex) {
        if (pin == null || saltHex == null || expectedHashHex == null) return false;
        byte[] salt = fromHex(saltHex);
        byte[] expected = fromHex(expectedHashHex);
        if (salt == null || expected == null || salt.length == 0 || expected.length == 0) return false;
        byte[] actual = derive(pin, salt);
        return MessageDigest.isEqual(actual, expected);
    }

    private static byte[] derive(String pin, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERS, KEY_BITS);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } catch (Throwable t) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(salt);
                return md.digest(pin.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xFF));
        return sb.toString();
    }

    private static byte[] fromHex(String s) {
        if (s == null || (s.length() & 1) != 0) return null;
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
