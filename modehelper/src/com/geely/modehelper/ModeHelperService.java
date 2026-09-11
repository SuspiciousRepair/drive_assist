package com.geely.modehelper;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

// System helper (uid system), no UI. Runs continuously, watching drive mode and
// WiFi state. Polls at 4-second intervals to:
// 1) Enforce saved drive/regen mode while parked (P), overriding car defaults
// 2) Keep WiFi on when charging/connected (to maintain telemetry/HA/adb)
// The preferred mode defaults are received from drivemem via SET_MODE broadcast.
public class ModeHelperService extends Service {
    static final String TAG = "ModeHelper";
    static final String SET_MODE = "com.geely.modehelper.SET_MODE";
    public static final String ACTION_DASHCAM = "com.geely.modehelper.svc.DASHCAM";
    public static final String ACTION_BT_PAIR = "com.geely.modehelper.svc.BT_PAIR";
    private boolean btRxRegistered = false;
    private final CarMode car = new CarMode();
    // Off unless asked. Recording is not something to start behind somebody's
    // back, and it is not free: ~6 Mbit/s of disk for as long as it runs.
    private DashRecorder dash;
    private volatile boolean running = false;
    private Thread poll;
    private BroadcastReceiver rx;
    private int lastGear = -999;
    private long lastLog = 0;

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        startAsForeground();
        if (i != null && ACTION_DASHCAM.equals(i.getAction())) {
            int on = i.getIntExtra("on", -1);
            if (dash == null) dash = new DashRecorder(getApplicationContext(), car);
            // The car connection is the poll loop's, and it may not be up yet on
            // a cold start — the recorder tolerates that for telemetry (cues just
            // go quiet) but the ENGINE binder is separate and always available.
            if (on == 1) dash.start();
            else if (on == 0) dash.stop();
            Log.i(TAG, "dashcam: now " + (dash.isRunning() ? "RUNNING" : "stopped"));
        }
        if (ACTION_BT_PAIR.equals(i != null ? i.getAction() : null)) {
            ensureBtListener();
            pairByAddress(i.getStringExtra("addr"), i.getStringExtra("pin"));
        }
        if (rx == null) {
            rx = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent it) {
                    if (SET_MODE.equals(it.getAction())) {
                        int d = it.getIntExtra("drive", CarMode.DRIVE_ECO);
                        int r = it.getIntExtra("regen", CarMode.REGEN_MID);
                        getSharedPreferences("modehelper", MODE_PRIVATE).edit()
                            .putInt("drive", d).putInt("regen", r).apply();
                        Log.i(TAG, "default from drivemem: drive=" + d + " regen=" + r);
                    }
                }
            };
            registerReceiver(rx, new IntentFilter(SET_MODE));
            IntentFilter down = new IntentFilter(Intent.ACTION_SHUTDOWN);
            down.addAction(Intent.ACTION_REBOOT);
            registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent it) {
                    stopForShutdown(String.valueOf(it.getAction()));
                }
            }, down);
        }
        cleanupInstaller();
        if (!running) { running = true; poll = new Thread(this::pollLoop, "helper-poll"); poll.start(); }
        return START_STICKY;
    }

    /** Cleans up the temporary Drive Assist installer if it was left installed. */
    private void cleanupInstaller() {
        try {
            getPackageManager().getPackageInfo("com.geely.installer", 0);
            Log.i(TAG, "cleanupInstaller: found leftover com.geely.installer, uninstalling...");
            Installer.uninstall(getApplicationContext(), "com.geely.installer");
        } catch (PackageManager.NameNotFoundException ignored) {
            // Not installed, clean
        } catch (Throwable t) {
            Log.w(TAG, "cleanupInstaller: " + t);
        }
    }

    private void pollLoop() {
        while (running) {
            try {
                if (!car.isReady()) car.connect(getApplicationContext());
                Integer g = car.readGear();
                boolean parked = (g != null && g == CarMode.GEAR_PARK);
                maybeAutoStart();
                if (g != null) {
                    if (g != lastGear) Log.i(TAG, "gear " + lastGear + " -> " + g);
                    lastGear = g;
                    if (parked) enforceModeParked();  // hold the saved default while parked
                }
                // AGGRESSIVE Wi-Fi while parked (~= at home, charging/paused): keep it
                // on, switching it back on if it drops. While driving we do not force
                // it -> let it drop naturally when out of range. (This also fixed the
                // "Wi-Fi never came back".)
                if (parked) ensureWifiOn();
                nudgeWifiScan();
            } catch (Throwable t) { Log.w(TAG, "poll: " + t); }
            try { Thread.sleep(4000); } catch (InterruptedException e) { break; }
        }
    }

    /** Dashcam auto-start flag: records continuously from boot until shutdown.
     * Started once on first poll tick when car is ready; thereafter controlled
     * only by user action. This avoids interfering with user-initiated stop/start. */
    private boolean dashAutoStarted = false;

    private void maybeAutoStart() {
        if (dashAutoStarted || !car.isReady()) return;
        dashAutoStarted = true;
        if (dash == null) dash = new DashRecorder(getApplicationContext(), car);
        Log.i(TAG, "dashcam: system up — starting");
        dash.start();
    }

    // Stop on the way down, so the last segment gets its moov atom and its
    // thumbnail instead of being left as a .h264 to recover by hand. ACTION_SHUTDOWN
    // is a protected broadcast; this app is uid system, so it receives it.
    //
    // If the unit suspends WITHOUT announcing it, nothing is lost either: the
    // write-ahead .h264 is exactly the safety net for that case.
    private void stopForShutdown(String why) {
        if (dash != null && dash.isRunning()) {
            Log.i(TAG, "dashcam: " + why + " — closing the segment");
            dash.stop();
            // Give the encoder thread a moment to write the moov atom. Not a
            // guarantee, just better than none — which is why the .h264 exists.
            try { Thread.sleep(1200); } catch (InterruptedException ignored) { }
        }
    }

    // PARKED: read the mode and, if it differs from the default, correct it with ONE write.
    private void enforceModeParked() {
        SharedPreferences p = getSharedPreferences("modehelper", MODE_PRIVATE);
        int drive = p.getInt("drive", CarMode.DRIVE_ECO);
        int regen = p.getInt("regen", CarMode.REGEN_MID);
        Integer cd = car.readDrive(), cr = car.readRegen();
        if (cd != null && cd != drive) { car.writeDrive(drive); Log.i(TAG, "modo: drive " + cd + " -> " + drive); }
        if (cr != null && cr != regen) { car.writeRegen(regen); Log.i(TAG, "modo: regen " + cr + " -> " + regen); }
    }

    // Command-line pairing: `adb shell am broadcast -a com.geely.modehelper.BT_PAIR
    // --es addr AA:BB:CC:DD:EE:FF -n com.geely.modehelper/.BtPairReceiver`.
    // Exists because the car's own Bluetooth screen was found to silently hide a
    // device the radio itself already discovered (via btsnoop HCI log, not
    // assumed) - this goes straight to BluetoothDevice.createBond(), skipping
    // that screen entirely. Being uid system means BLUETOOTH_PRIVILEGED answers
    // the pairing prompt programmatically instead of needing a tap.
    private void pairByAddress(String addr, String pin) {
        if (addr == null) { Log.w(TAG, "btpair: no address"); return; }
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null) { Log.w(TAG, "btpair: no adapter"); return; }
            BluetoothDevice dev = ad.getRemoteDevice(addr);
            Log.i(TAG, "btpair: " + addr + " current bond state = " + bondName(dev.getBondState()));
            if (pin != null) pendingPin = pin.getBytes();
            boolean started = dev.createBond();
            Log.i(TAG, "btpair: createBond(" + addr + ") = " + started);
            // Race against the framework's own auto-guessed PIN (observed via
            // btsnoop: it silently tries "0000" before ever asking us or the
            // user, and this device rejects it). Calling setPin() right away,
            // before the native PIN_REQUEST event even arrives, is the only
            // shot at winning that race through public API.
            if (pin != null) {
                boolean setOk = dev.setPin(pin.getBytes());
                Log.i(TAG, "btpair: speculative setPin(" + pin + ") = " + setOk);
            }
        } catch (Throwable t) { Log.w(TAG, "btpair: " + t); }
    }

    private volatile byte[] pendingPin = null;

    private void ensureBtListener() {
        if (btRxRegistered) return;
        btRxRegistered = true;
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent it) {
                BluetoothDevice dev = it.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String addr = dev != null ? dev.getAddress() : "?";
                if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(it.getAction())) {
                    int state = it.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                    int prev = it.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
                    // EXTRA_REASON is @hide but the string key is stable across AOSP -
                    // read it directly rather than reflecting into the hidden field.
                    int reason = it.getIntExtra("android.bluetooth.device.extra.REASON", -1);
                    Log.i(TAG, "btpair: " + addr + " bond " + bondName(prev) + " -> " + bondName(state)
                        + (state == BluetoothDevice.BOND_NONE ? " reason=" + reason : ""));
                } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(it.getAction())) {
                    int variant = it.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                    Log.i(TAG, "btpair: " + addr + " pairing request, variant=" + variant);
                    try {
                        if (variant == BluetoothDevice.PAIRING_VARIANT_PIN) {
                            byte[] p = pendingPin != null ? pendingPin : "1234".getBytes();
                            boolean ok = dev.setPin(p);
                            Log.i(TAG, "btpair: setPin(" + new String(p) + ") = " + ok);
                        } else {
                            boolean ok = dev.setPairingConfirmation(true);
                            Log.i(TAG, "btpair: setPairingConfirmation(true) = " + ok);
                        }
                    } catch (Throwable t) { Log.w(TAG, "btpair: pairing response: " + t); }
                }
            }
        }, f);
    }

    private static String bondName(int state) {
        switch (state) {
            case BluetoothDevice.BOND_NONE: return "NONE";
            case BluetoothDevice.BOND_BONDING: return "BONDING";
            case BluetoothDevice.BOND_BONDED: return "BONDED";
            default: return "?(" + state + ")";
        }
    }

    // Turns Wi-Fi on while the car is CHARGING (switch == CHARGE_ON). Logs the raw
    // value periodically to map the remaining states (plugged in / paused).
    // Aggressive Wi-Fi while parked: switches it back on if it drops.
    private void ensureWifiOn() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && !wm.isWifiEnabled()) {
                boolean ok = wm.setWifiEnabled(true);
                Log.i(TAG, "parked -> turning Wi-Fi on = " + ok);
            }
        } catch (Throwable t) { Log.w(TAG, "wifi: " + t); }
    }

    // Request WiFi scans when not associated. Android 9's disconnected-scan backoff
    // (20s, 40s, 80s, 160s, 320s...) can cause long delays reconnecting to saved
    // networks. By requesting a scan every 30s, we ensure the framework checks for
    // available networks and avoids extended delays. This lives in modehelper (not
    // in Drive Assist/drivemem) because Android 9 throttles startScan() to once per 30
    // minutes for background apps, while system-uid apps are exempt from that limit.
    static final long SCAN_EVERY_MS = 30_000;
    private long lastScanMs = 0;

    private void nudgeWifiScan() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return;
            WifiInfo info = wm.getConnectionInfo();
            boolean associated = info != null && info.getNetworkId() != -1
                              && info.getSupplicantState() == SupplicantState.COMPLETED;
            // Associated: nothing to look for, and clearing the stamp means the
            // first scan after a drop goes out immediately instead of waiting.
            if (associated) { lastScanMs = 0; return; }
            long now = SystemClock.elapsedRealtime();
            if (lastScanMs != 0 && now - lastScanMs < SCAN_EVERY_MS) return;
            lastScanMs = now;
            boolean ok = wm.startScan();
            Log.i(TAG, "wifi adrift -> startScan = " + ok);
        } catch (Throwable t) { Log.w(TAG, "wifi scan: " + t); }
    }

    private void startAsForeground() {
        try {
            String chn = "modehelper";
            android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            // Portuguese strings: the head unit's locale is pt, and this app builds
            // no resources (only manifest linked). Use hardcoded strings; for more
            // than a few, convert to proper string resources instead.
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new android.app.NotificationChannel(
                    chn, "Modo de condução", android.app.NotificationManager.IMPORTANCE_MIN));
            }
            android.app.Notification n = new android.app.Notification.Builder(this, chn)
                .setContentTitle("Modo de condução")
                .setContentText("Mantém seu modo e o Wi-Fi na carga")
                .setSmallIcon(android.R.drawable.ic_menu_preferences)
                .build();
            startForeground(51, n);
        } catch (Throwable t) { Log.e(TAG, "startForeground: " + t, t); }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        stopForShutdown("service destroyed");
        running = false;
        if (rx != null) { try { unregisterReceiver(rx); } catch (Throwable ignored) {} rx = null; }
        car.disconnect();
        super.onDestroy();
    }
}
