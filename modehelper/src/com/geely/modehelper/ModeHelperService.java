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
    // The head unit's own "car is going off" broadcast (vendor, not AOSP).
    static final String ACTION_SHUTDOWN_HU = "android.intent.action.ACTION_SHUTDOWN_HU";
    public static final String ACTION_PARKED_MONITORING = "com.geely.modehelper.svc.PARKED_MONITORING";
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
    private final ParkedMonitorPolicy parkedMonitorPolicy = new ParkedMonitorPolicy();
    // Saved settings are written only until this time (SystemClock.elapsedRealtime);
    // opened at service start and on every wake. See RestorePolicy.
    private static final long BEAT_WRITE_GAP_MS = 15_000L;
    private long lastBeatWriteMs = 0;
    private volatile long restoreUntil = android.os.SystemClock.elapsedRealtime() + RestorePolicy.WINDOW_MS;
    // A tap in Drive Assist's AEB/AVAS switch: write it once, now, whatever the window.
    private volatile boolean applyAebNow = false, applyAvasNow = false;
    // Deep-sleep time at the last tick (elapsedRealtime - uptimeMillis), for the
    // wake fallback when SUSPEND_EXIT is never delivered (review #3).
    private long lastSleepMs = sleepMs();

    private static long sleepMs() {
        return android.os.SystemClock.elapsedRealtime() - android.os.SystemClock.uptimeMillis();
    }

    private void openRestoreWindow(String why) {
        restoreUntil = android.os.SystemClock.elapsedRealtime() + RestorePolicy.WINDOW_MS;
        Log.i(TAG, "restore window open (" + why + ")");
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        startAsForeground();
        if (i != null && ACTION_DASHCAM.equals(i.getAction())) {
            int on = i.getIntExtra("on", -1);
            // Persisted BEFORE acting, so superviseDash() never sees an
            // owner's stop as a recorder that died on its own.
            if (on == 0 || on == 1) {
                getSharedPreferences("modehelper", MODE_PRIVATE).edit()
                    .putBoolean("dashcam_on", on == 1).commit();
            }
            if (dash == null) dash = new DashRecorder(getApplicationContext(), car);
            // The car connection is the poll loop's, and it may not be up yet on
            // a cold start — the recorder tolerates that for telemetry (cues just
            // go quiet) but the ENGINE binder is separate and always available.
            if (on == 1) dash.start();
            else if (on == 0) dash.stopAndWait(5_000);
            Log.i(TAG, "dashcam: now " + (dash.isRunning() ? "RUNNING" : "stopped"));
        }
        if (i != null && ACTION_PARKED_MONITORING.equals(i.getAction())) {
            boolean enabled = i.getIntExtra("on", 0) == 1;
            getSharedPreferences("modehelper", MODE_PRIVATE).edit()
                .putBoolean("parked_monitoring", enabled).apply();
            Log.i(TAG, "parked runtime: enabled=" + enabled);
        }
        if (ACTION_BT_PAIR.equals(i != null ? i.getAction() : null)) {
            ensureBtListener();
            pairByAddress(i.getStringExtra("addr"), i.getStringExtra("pin"));
        }
        if (rx == null) {
            rx = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent it) {
                    if (SET_MODE.equals(it.getAction())) {
                        // Each preference is independent and optional — drivemem may
                        // send drive/regen together (its existing "Save" action) and
                        // aeb/avas from a different screen entirely, on its own. Only
                        // touch what's actually present, so one doesn't clobber another.
                        SharedPreferences.Editor e = getSharedPreferences("modehelper", MODE_PRIVATE).edit();
                        StringBuilder log = new StringBuilder("default from drivemem:");
                        if (it.hasExtra("drive")) {
                            int d = it.getIntExtra("drive", CarMode.DRIVE_ECO);
                            e.putInt("drive", d); log.append(" drive=").append(d);
                        }
                        if (it.hasExtra("regen")) {
                            int r = it.getIntExtra("regen", CarMode.REGEN_MID);
                            e.putInt("regen", r); log.append(" regen=").append(r);
                        }
                        if (it.hasExtra("aeb")) {
                            boolean aeb = it.getBooleanExtra("aeb", true);
                            e.putBoolean("aeb", aeb); log.append(" aeb=").append(aeb);
                        }
                        if (it.hasExtra("avas")) {
                            int avas = it.getIntExtra("avas", 1);
                            e.putInt("avas", avas); log.append(" avas=").append(avas);
                        }
                        if (it.hasExtra("parked_monitoring")) {
                            boolean enabled = it.getBooleanExtra("parked_monitoring", false);
                            e.putBoolean("parked_monitoring", enabled);
                            log.append(" parked_monitoring=").append(enabled);
                        }
                        for (String k : new String[]{"restore_drive", "restore_regen", "restore_aeb", "restore_avas"}) {
                            if (it.hasExtra(k)) {
                                boolean on = it.getBooleanExtra(k, false);
                                e.putBoolean(k, on); log.append(' ').append(k).append('=').append(on);
                            }
                        }
                        if (it.hasExtra("dashcam_limit_gb")) {
                            int gb = it.getIntExtra("dashcam_limit_gb", DashRecorder.DEFAULT_BUDGET_GB);
                            e.putInt("dashcam_limit_gb", gb);
                            log.append(" dashcam_limit_gb=").append(gb);
                        }
                        e.apply();
                        // A user tap (not a watchdog sync): write this one value now.
                        // Raised only AFTER apply(), so the poll thread can never
                        // consume the tap and read the previous value (review #7).
                        if (it.getBooleanExtra("apply_now", false)) {
                            if (it.hasExtra("aeb"))  applyAebNow = true;
                            if (it.hasExtra("avas")) applyAvasNow = true;
                            log.append(" apply_now");
                        }
                        Log.i(TAG, log.toString());
                    }
                }
            };
            registerReceiver(rx, new IntentFilter(SET_MODE));
            // Car going off, earliest first: the head unit's own
            // ACTION_SHUTDOWN_HU (the cameras stop at that moment), then screen
            // off three seconds later, then the suspend. Close the segment at
            // the first of them. Seen on the car 2026-09-26 22:20:20/22:20:23.
            IntentFilter off = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            off.addAction(ACTION_SHUTDOWN_HU);
            registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent it) {
                    String why = ACTION_SHUTDOWN_HU.equals(it.getAction()) ? "head unit shutdown" : "screen off";
                    if (dash != null && dash.isRunning()) dash.closeSegmentNow(why);
                }
            }, off);
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

    // How long a just-launched installer gets before it's treated as a
    // stale leftover instead of still legitimately running. Observed a
    // full run (extract + install two APKs + launch drivemem + delete
    // itself) finish in well under a minute; this is a generous margin,
    // not a tight deadline.
    private static final long INSTALLER_GRACE_MS = 3 * 60_000L;

    /** Cleans up the temporary Drive Assist installer if it was left
     * installed from an old, abandoned run -- but NOT if InstallResultReceiver
     * just launched it as part of the installer-delivery OTA path (see its
     * own comment): this runs on every service start, including the one
     * caused by THIS install replacing modehelper itself, so without the
     * grace window it would race the installer it just launched and kill
     * it mid-flight, before it ever gets to install drivemem. */
    private void cleanupInstaller() {
        try {
            getPackageManager().getPackageInfo("com.geely.installer", 0);
            long launchedMs = getSharedPreferences("modehelper", MODE_PRIVATE)
                .getLong("installer_launched_ms", 0);
            long age = System.currentTimeMillis() - launchedMs;
            if (launchedMs > 0 && age < INSTALLER_GRACE_MS) {
                Log.i(TAG, "cleanupInstaller: com.geely.installer launched " + (age / 1000)
                    + "s ago, still within grace -- leaving it alone");
                return;
            }
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
                superviseDash();
                maybeWatchPower();
                updateParkedMonitoring(parked);
                if (g != null) {
                    if (g != lastGear) Log.i(TAG, "gear " + lastGear + " -> " + g);
                    lastGear = g;
                    // Saved settings are restored only in the window after a wake
                    // (RestorePolicy), and only those whose "Restore at start" switch
                    // is on. Outside it, a change made in the car or another app stays
                    // (issue #12). Drive/regen also need Park; AEB/AVAS do not, so the
                    // car re-arming AVAS just after wake is still beaten.
                    long slept = sleepMs();
                    if (RestorePolicy.sleptBetween(lastSleepMs, slept)) openRestoreWindow("slept");
                    lastSleepMs = slept;
                    boolean window = RestorePolicy.inWindow(
                        android.os.SystemClock.elapsedRealtime(), restoreUntil);
                    if (parked) enforceModeParked(window);
                    boolean tapAeb = applyAebNow, tapAvas = applyAvasNow;
                    applyAebNow = false; applyAvasNow = false;
                    enforceAeb(window, tapAeb);
                    enforceAvasParked(window, tapAvas);
                }
                // AGGRESSIVE Wi-Fi while parked (~= at home, charging/paused): keep it
                // on, switching it back on if it drops. While driving we do not force
                // it -> let it drop naturally when out of range. (This also fixed the
                // "Wi-Fi never came back".)
                if (parked) ensureWifiOn();
                nudgeWifiScan();
                maybeDexopt();
            } catch (Throwable t) { Log.w(TAG, "poll: " + t); }
            // Liveness heartbeat — a timestamp in SharedPreferences (survives process
            // death), so BootReceiver's watchdog can tell whether this loop is still
            // actually running instead of just assuming a START_STICKY restart happened.
            // Marked even if the try block above threw, so a stuck car connection
            // doesn't also look like a dead poll loop.
            // Written at most every BEAT_WRITE_GAP_MS, not every 4 s tick: each
            // write rewrites the prefs file on flash (performance review
            // finding 3). The watchdog's limit (STALE_POLL_MS) is 60 s.
            long beatNow = System.currentTimeMillis();
            if (lastBeatWriteMs == 0 || beatNow < lastBeatWriteMs
                    || beatNow - lastBeatWriteMs >= BEAT_WRITE_GAP_MS) {
                getSharedPreferences("modehelper", MODE_PRIVATE).edit()
                    .putLong("beat_poll", beatNow).apply();
                lastBeatWriteMs = beatNow;
            }
            try { Thread.sleep(4000); } catch (InterruptedException e) { break; }
        }
    }

    // A recorder that stopped on its own (EVS not ready at boot, a codec
    // error, a failed segment open) used to stay stopped until the process
    // restarted, and nothing said so. Restart it while recording is wanted,
    // backing off if it keeps failing; RecorderState tells Drive Assist.
    private final RestartBackoff dashBackoff = new RestartBackoff();
    private volatile boolean shuttingDown;

    private void superviseDash() {
        if (!dashAutoStarted || shuttingDown || dash == null) return;
        long now = SystemClock.uptimeMillis();
        if (dash.isRunning()) { dashBackoff.running(dash.startedUptimeMs(), now); return; }
        if (!getSharedPreferences("modehelper", MODE_PRIVATE).getBoolean("dashcam_on", true)) return;
        if (!dashBackoff.due(now)) return;
        Log.w(TAG, "dashcam: recorder stopped on its own — restarting");
        dash = new DashRecorder(getApplicationContext(), car);
        dash.start();
    }

    // The car's own power states, once the car connection is up: a suspend
    // is announced here even when the screen was already off. One attempt
    // per process — a refusal will not change on retry.
    private boolean powerWatchTried;

    private void maybeWatchPower() {
        if (powerWatchTried || !car.isReady()) return;
        powerWatchTried = true;
        try {
            boolean ok = PowerWatch.register(car.powerManager(), state -> {
                Log.i(TAG, "power state " + state);
                if (state == PowerWatch.SUSPEND_ENTER) {
                    if (dash != null && dash.isRunning()) dash.closeSegmentNow("suspend");
                } else if (state == PowerWatch.SHUTDOWN_ENTER) {
                    stopForShutdown("car shutdown");
                } else if (state == PowerWatch.SHUTDOWN_CANCELLED) {
                    shuttingDown = false;
                } else if (state == PowerWatch.SUSPEND_EXIT) {
                    // The car woke: restore saved settings for one window (RestorePolicy).
                    openRestoreWindow("suspend exit");
                }
            });
            Log.i(TAG, "power watch: " + (ok ? "registered" : "no power manager"));
        } catch (Throwable t) { Log.w(TAG, "power watch: " + t); }
    }

    // Once a minute, on its own thread: a compile takes tens of seconds and
    // must not stall the poll loop's heartbeat. See Dexopt.
    private long lastDexoptCheck;
    private volatile boolean dexoptRunning;

    private void maybeDexopt() {
        long now = SystemClock.elapsedRealtime();
        if (dexoptRunning || now - lastDexoptCheck < 60_000) return;
        lastDexoptCheck = now;
        dexoptRunning = true;
        new Thread(() -> {
            try { Dexopt.ensure(getApplicationContext()); }
            finally { dexoptRunning = false; }
        }, "dexopt").start();
    }

    /** Dashcam auto-start flag: records from boot until shutdown, UNLESS the
     * owner has explicitly turned it off (ACTION_DASHCAM persists that choice
     * to "dashcam_on" — see its own comment). Checked once on first poll tick
     * when car is ready; thereafter controlled only by user action. Default
     * true keeps existing installs recording as before an explicit choice is
     * ever made. */
    private boolean dashAutoStarted = false;

    private void maybeAutoStart() {
        if (dashAutoStarted || !car.isReady()) return;
        dashAutoStarted = true;
        boolean wanted = getSharedPreferences("modehelper", MODE_PRIVATE)
            .getBoolean("dashcam_on", true);
        if (!wanted) { Log.i(TAG, "dashcam: system up — recording turned off, not starting"); return; }
        if (dash == null) dash = new DashRecorder(getApplicationContext(), car);
        Log.i(TAG, "dashcam: system up — starting");
        dash.start();
    }

    // Parked motion watch (the "Park monitoring" switch): on after thirty
    // uninterrupted seconds in Park, off the moment Park is left. The work is
    // DashRecorder's (KeyframeMotion, from its own key frames); nothing here
    // opens a camera.
    private boolean motionArmed;

    private void updateParkedMonitoring(boolean parked) {
        boolean enabled = getSharedPreferences("modehelper", MODE_PRIVATE)
            .getBoolean("parked_monitoring", false);
        ParkedMonitorPolicy.State state = parkedMonitorPolicy.update(
            SystemClock.elapsedRealtime(), enabled && parked && car.isReady());
        boolean armed = state == ParkedMonitorPolicy.State.ARMED;
        if (armed != motionArmed) {
            motionArmed = armed;
            Log.i(TAG, "parked motion: " + (armed ? "armed" : "disarmed " + state));
        }
        if (dash != null) dash.setMotionWatch(armed);
    }

    // Stop on the way down, so the last segment gets its index and its
    // thumbnail now rather than from the repair pass later. ACTION_SHUTDOWN is
    // a protected broadcast; this app is uid system, so it receives it.
    //
    // A suspend is not a shutdown: the head-unit-shutdown, screen-off and
    // power-state hooks close the open segment before it (closeSegmentNow).
    // `adb reboot` and a power cut send nothing at all; they leave an
    // unclosed .mp4.tmp, playable up to its last one-second fragment, which
    // SegmentFiles.repair finishes.
    private void stopForShutdown(String why) {
        shuttingDown = true;
        if (dash != null && dash.isRunning()) {
            Log.i(TAG, "dashcam: " + why + " — closing the segment");
            dash.stopAndWait(5_000);
        }
    }

    // PARKED: read the mode and, if it differs from the default, correct it with ONE write.
    // Only a setting with a saved value AND "Restore at start" on is written —
    // no more forcing Eco/Mid on a car whose owner never picked anything.
    private void enforceModeParked(boolean window) {
        SharedPreferences p = getSharedPreferences("modehelper", MODE_PRIVATE);
        if (shouldWrite(p, "restore_drive", "drive", window, false)) {
            int drive = p.getInt("drive", CarMode.DRIVE_ECO);
            Integer cd = car.readDrive();
            if (cd != null && cd != drive) { car.writeDrive(drive); Log.i(TAG, "modo: drive " + cd + " -> " + drive); }
        }
        if (shouldWrite(p, "restore_regen", "regen", window, false)) {
            int regen = p.getInt("regen", CarMode.REGEN_MID);
            Integer cr = car.readRegen();
            if (cr != null && cr != regen) { car.writeRegen(regen); Log.i(TAG, "modo: regen " + cr + " -> " + regen); }
        }
    }

    /** RestorePolicy.shouldWrite for one setting, from this app's stored values. */
    private static boolean shouldWrite(SharedPreferences p, String flagKey, String valueKey,
                                       boolean window, boolean tap) {
        return RestorePolicy.shouldWrite(p.contains(valueKey),
            RestorePolicy.restoreOn(p.contains(flagKey), p.getBoolean(flagKey, true)), window, tap);
    }

    // Same shape as enforceModeParked(), for AEB, but not Park-gated. Called only
    // in the post-wake window (RestorePolicy) or for a user tap. No preference
    // yet (no "aeb" extra ever sent) means "don't touch it", not "force it on".
    // tap = a user tap in Drive Assist: written even if "Restore at start" is off.
    private void enforceAeb(boolean window, boolean tap) {
        SharedPreferences p = getSharedPreferences("modehelper", MODE_PRIVATE);
        if (!shouldWrite(p, "restore_aeb", "aeb", window, tap)) return;
        boolean want = p.getBoolean("aeb", true);
        Boolean have = car.readBoolProp(CarMode.AEB_PROP, CarMode.AEB_AREA);
        if (have != null && have != want) {
            car.writeBoolProp(CarMode.AEB_PROP, CarMode.AEB_AREA, want);
            Log.i(TAG, "modo: aeb " + have + " -> " + want);
        }
    }

    // AVAS mute. 0 = muted, >=1 = active mode (see AvasMuteTestReceiver). Same
    // "no preference yet, don't touch it" rule as enforceAeb().
    //
    // The car re-arms AVAS on wake/power-on, and the head unit suspends instead
    // of restarting this process — so the saved choice is held for the whole
    // post-wake window (opened on SUSPEND_EXIT), not applied once. After the
    // window a change made in the car stays (issue #12). Nothing is mirrored
    // back from the car. See AvasPolicy, RestorePolicy, plan/AVAS-MUTE-ROADMAP.md.
    private void enforceAvasParked(boolean window, boolean tap) {
        SharedPreferences p = getSharedPreferences("modehelper", MODE_PRIVATE);
        if (!shouldWrite(p, "restore_avas", "avas", window, tap)) return;
        Object cur = car.audioCall("getAVASMode");
        if (!(cur instanceof Integer)) return;
        int want = p.getInt("avas", 1);
        Integer write = AvasPolicy.toWrite((Integer) cur, want);
        if (write != null) {
            car.audioCall("setAVASMode", write);
            Log.i(TAG, "modo: avas " + cur + " -> " + write);
        }
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
        if (!WifiKeepOnReceiver.enabled(getApplicationContext())) return;   // issue #14
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
