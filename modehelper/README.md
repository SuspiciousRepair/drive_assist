# ModeHelper (`modehelper`) — Geely IHU629G

Headless, privileged system companion application (`com.geely.modehelper`) for the Geely EX2 head unit (IHU629G, Android 9).

Runs as `android.uid.system` (UID 1000) and is signed with the AOSP public platform test key, providing the privileged OS capabilities that Drive Assist cannot hold directly.

---

## Architectural Role: Why a Separate Application?

Drive Assist (`drivemem`) and ModeHelper (`modehelper`) are deliberately separated into two distinct packages due to platform and security boundaries on Android 9:

1. **WebView Incompatibility in System Processes**:
   Drive Assist integrates an interactive Home Assistant WebView card (`PanelCardView`). On Android, instantiating a WebView inside a process sharing `android.uid.system` crashes the privileged system process. Therefore, UI rendering and system privilege cannot coexist within the same APK.
2. **SELinux & Local Root Constraints**:
   While the head unit's ADB daemon runs as root (`ro.secure=0`), SELinux is set to **Enforcing**. An ordinary application UID running in the app sandbox cannot execute `/system/xbin/su` (`error=13, Permission denied`) because the untrusted app domain cannot transition into the `su` domain. Autonomous privileged actions performed locally on the car must execute from an app holding platform privilege.
3. **Signature|Privileged Permissions**:
   Capabilities such as silent package installation (`INSTALL_PACKAGES`), secure settings modification (`WRITE_SECURE_SETTINGS`), and programmatic Bluetooth pairing (`BLUETOOTH_PRIVILEGED`) require `signature|privileged` protection levels granted by the platform signature and shared system UID.
4. **VHAL Property Visibility**:
   Reading vehicle drive mode and regenerative braking settings via `CarPropertyManager` as a standard application returns `null`. Running as `android.uid.system` allows reading these properties back from the VHAL, enabling "write only when state diverges" persistence logic.

```
┌──────────────────────────────────────────────────────────┐
│                   Drive Assist (`drivemem`)              │
│       Standard UID • Full UI / WebView • MQTT Client     │
└──────────────┬────────────────────────────▲──────────────┘
               │ Broadcasts (Intents)       │ Status / Telemetry
               ▼                            │
┌───────────────────────────────────────────┴──────────────┐
│                    ModeHelper (`modehelper`)             │
│        `android.uid.system` (UID 1000) • Headless        │
├──────────────────────────────────────────────────────────┤
│  • Drive Mode & Regen Memory (`CarMode`)                 │
│  • Silent OTA Package Installer (`Installer`)            │
│  • Hardware Dashcam Engine (`DashRecorder`, `EvsClient`) │
│  • Network ADB & Privileged Wi-Fi Guard (`AdbControl`)   │
│  • Wi-Fi Keep-Alive & Throttling Bypass                  │
│  • Programmatic Bluetooth Pairing (`BtPairReceiver`)     │
└──────────────────────────────────────────────────────────┘
```

---

## Core Subsystems

### 1. Drive Mode & Regeneration Memory
- **Components**: [`ModeHelperService.java`](src/com/geely/modehelper/ModeHelperService.java), [`CarMode.java`](src/com/geely/modehelper/CarMode.java)
- **Lifecycle**: Continuous background foreground service (`ModeHelperService`) polling every 4 seconds.
- **Behavior**:
  - Connects to the Car Service via `Car.createCar(..., ServiceConnection)`.
  - Reads gear status (`GEAR_PARK` vs `GEAR_DRIVE`).
  - While parked (`GEAR_PARK`), checks current drive mode (`PROP_DRIVE = 570491136`) and regen level (`PROP_REGEN = 537003264`) against saved preferences (`SharedPreferences "modehelper"`).
  - If the car's state has reverted to factory defaults (e.g. after ignition cycle or sleep resume), issues a single write to restore the target mode.
- **IPC**: Listens for `com.geely.modehelper.SET_MODE` broadcasts from Drive Assist when the driver updates preferences in the UI.

### 2. Silent Background OTA Updates
- **Components**: [`Installer.java`](src/com/geely/modehelper/Installer.java), [`InstallReceiver.java`](src/com/geely/modehelper/InstallReceiver.java), [`InstallResultReceiver.java`](src/com/geely/modehelper/InstallResultReceiver.java)
- **Permission**: `android.permission.INSTALL_PACKAGES` (`signature|privileged`).
- **Mechanism**:
  - `InstallReceiver` receives an APK download URL via `com.geely.modehelper.INSTALL_APK`.
  - Downloads the APK to internal cache.
  - **Security Invariants**:
    - URL must use `https://`.
    - Downloaded package name must match `com.geely.drivemem`.
    - APK signature must match the cryptographic signature of the currently installed Drive Assist package (`PackageManager.GET_SIGNATURES`).
  - Commits the installation using Android's `PackageInstaller` session API.
  - Single-flight concurrency lock (`BUSY` atomic boolean) drops redundant requests triggered by retained MQTT messages.
  - **Success / Failure Handling**: Replacing the running Drive Assist process terminates it mid-flight. Failures are reported via `InstallResultReceiver`; success is signaled when Drive Assist restarts and re-establishes MQTT communication (`online`).

### 3. Multi-Camera Hardware Dashcam
- **Components**: [`DashRecorder.java`](src/com/geely/modehelper/DashRecorder.java), [`EvsClient.java`](src/com/geely/modehelper/EvsClient.java), [`Vtt.java`](src/com/geely/modehelper/Vtt.java), [`DashReceiver.java`](src/com/geely/modehelper/DashReceiver.java)
- **Documentation**: [docs/DASHCAM.md](../docs/DASHCAM.md), [docs/EVS-CAMERA.md](../docs/EVS-CAMERA.md)
- **Video Pipeline**:
  - Interacts directly with the vendor camera engine `bdstar.render.engine` via `EvsClient`.
  - Connects to binder transaction `openCamera("avm")` and attaches the recording surface using `attach(surface, "dvr", "avm")`.
  - Extracts the raw `IGraphicBufferProducer` binder directly from `android.view.Surface` parcel data.
  - Encodes 1920x800 at 25 FPS (16 Mbps H.264) directly through `MediaCodec` into 5-minute MP4 segments (`/sdcard/Movies/dashcam/`).
  - Simultaneously maintains a write-ahead `.h264` elementary stream so that interrupted recordings (e.g. sudden power off before `moov` atom muxing) can be reconstructed.
- **Telemetry Sidecars**:
  - `Vtt` writes WebVTT subtitle files (`.vtt`) synchronized to the microsecond timestamps of the video stream.
  - Subtitle cues record GPS speed, vehicle VHAL speed, coordinates, altitude, heading, ambient temperature, and gear once per second.
- **Ring Buffer**:
  - Automatically manages a 10 GB rolling storage budget, pruning the oldest segment and sidecar files when storage limits are exceeded.
- **Controls**:
  - Auto-starts on system boot once car service is connected.
  - Can be toggled manually via `com.geely.modehelper.DASHCAM` (`--ei on 1` or `--ei on 0`).

### 4. Network ADB Management & Privileged Wi-Fi Guard
- **Components**: [`AdbControl.java`](src/com/geely/modehelper/AdbControl.java), [`AdbReceiver.java`](src/com/geely/modehelper/AdbReceiver.java), [`WifiGuardReceiver.java`](src/com/geely/modehelper/WifiGuardReceiver.java)
- **Permission**: `android.permission.WRITE_SECURE_SETTINGS` (`signature|privileged`).
- **Mechanism**:
  - SELinux prevents non-root apps from writing property `persist.adb.tcp.port`. However, `modehelper` toggles `Settings.Global.ADB_ENABLED`.
  - **Auto-Off Window**: To prevent leaving network root ADB exposed indefinitely, enabling ADB schedules an `AlarmManager.ELAPSED_REALTIME_WAKEUP` timer (default 15 minutes, maximum 120 minutes) to automatically shut ADB off.
  - **Configurable Privileged Wi-Fi Guard (`WifiGuardReceiver`)**:
    - Polls every 2 minutes via exact wakeup alarms.
    - When connected to the user-configured privileged Wi-Fi network (stored in preferences, default fallback `"car"`), holds ADB open indefinitely and acquires a partial `WakeLock` to prevent the head unit from suspending during development sessions.
    - As soon as the car leaves the trusted network, the wakelock is released and ADB is disabled immediately.
    - **Configuration**: Receives `com.geely.modehelper.SET_TRUSTED_WIFI` (`--es ssid <SSID>`) broadcasts to update or clear the privileged network. Drive Assist also exposes a dedicated configuration field in its settings UI (`TelemetryActivity`).

### 5. Wi-Fi Keep-Alive & Scan Throttling Bypass
- **Components**: [`WifiKeepOnReceiver.java`](src/com/geely/modehelper/WifiKeepOnReceiver.java), [`ModeHelperService.java`](src/com/geely/modehelper/ModeHelperService.java)
- **Problem**: Android 9 restricts background apps from initiating Wi-Fi scans (`startScan()` throttled to once every 30 minutes) and aggressively disconnects Wi-Fi on head unit sleep/resume cycles.
- **Solution**:
  - Applications with `android.uid.system` are exempt from Wi-Fi scan throttling.
  - When disconnected from an access point, `ModeHelperService` triggers `startScan()` every 30 seconds to bypass the OS exponential backoff (20s -> 40s -> 80s -> 160s...).
  - When parked (`GEAR_PARK`), `ensureWifiOn()` actively turns Wi-Fi back on if the system disabled it.
  - `WifiKeepOnReceiver` runs a self-rescheduling alarm check every 2 minutes across suspend/resume boundaries.

### 6. Programmatic Bluetooth Pairing
- **Components**: [`BtPairReceiver.java`](src/com/geely/modehelper/BtPairReceiver.java), [`ModeHelperService.java`](src/com/geely/modehelper/ModeHelperService.java)
- **Permissions**: `BLUETOOTH_ADMIN`, `BLUETOOTH_PRIVILEGED` (`signature|privileged`).
- **Mechanism**:
  - The stock infotainment Bluetooth settings screen was found to silently suppress or hide certain third-party devices (e.g. OBD2 BLE adapters).
  - Invokes `BluetoothDevice.createBond()` directly by MAC address.
  - Listens for `ACTION_PAIRING_REQUEST` and answers pairing confirmation or enters PINs programmatically (`setPairingConfirmation(true)`, `setPin()`), winning the race against default framework timeouts without requiring manual on-screen taps.

### 7. VHAL Diagnostic Probing
- **Components**: [`ProbeReceiver.java`](src/com/geely/modehelper/ProbeReceiver.java)
- **Usage**: Diagnostic utility to safely probe raw VHAL property IDs over ADB. Reads property definitions as integer, float, and boolean values without risking accidental ECU write corruption.

---

## Inter-Process Communication (IPC) Reference

| Intent Action | Component | Exported | Key Extras / Parameters | Description |
|---|---|---|---|---|
| `com.geely.modehelper.SET_MODE` | Dynamic (`ModeHelperService`) | Internal | `int drive`<br>`int regen` | Informs helper of user-preferred drive mode and regen level |
| `com.geely.modehelper.INSTALL_APK` | `InstallReceiver` | **Yes** | `String url` | Initiates silent HTTPS package update for `com.geely.drivemem` |
| `com.geely.modehelper.SET_ADB` | `AdbReceiver` | **Yes** | `boolean enable`<br>`int minutes` | Toggles network ADB with auto-off timer |
| `com.geely.modehelper.SET_TRUSTED_WIFI` | `WifiGuardReceiver` | **Yes** | `String ssid` | Configures the privileged Wi-Fi network (cleared if empty) |
| `com.geely.modehelper.DASHCAM` | `DashReceiver` | **Yes** | `int on` (1=start, 0=stop) | Controls hardware dashcam recording |
| `com.geely.modehelper.BT_PAIR` | `BtPairReceiver` | **Yes** | `String addr`<br>`String pin` (optional) | Initiates headless Bluetooth pairing with specified device |
| `com.geely.modehelper.READPROP` | `ProbeReceiver` | **Yes** | `String ids`<br>`int area` (optional) | Safely reads raw VHAL properties by decimal/hex ID |
| `android.intent.action.BOOT_COMPLETED` | `BootReceiver` | **Yes** | — | Starts service, opens 5-min ADB window, initializes guards |

---

## Build System

ModeHelper does not use Gradle. It is built using a lightweight, direct toolchain script [`build-modehelper.sh`](build-modehelper.sh).

### Prerequisites
- JDK 17 (`JAVA_HOME`)
- Android SDK Build-Tools 34.0.0 (`aapt2`, `d8`, `zipalign`, `apksigner`)
- Android API 28 platform (`android.jar`)
- Geely car stubs (`car-stubs/car-stubs.jar`)

### Platform Key Signing
ModeHelper requires the AOSP public platform test key to run as `android.uid.system`. The build script automatically downloads the test key into `platkey/` if not present:
- `platform.pk8`
- `platform.x509.pem`

The script enforces that the key matches the test key SHA256 fingerprint on the head unit:
```
c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8
```

### Compiling
Run the build script from within the `modehelper/` directory:

```bash
cd modehelper
./build-modehelper.sh
```

**Build steps performed:**
1. Links resources and manifest with `aapt2 link`.
2. Compiles Java source files against `android.jar` and `car-stubs.jar` with `javac`.
3. Converts `.class` files to `classes.dex` with `d8`.
4. Packages APK and aligns 4-byte boundaries with `zipalign`.
5. Signs package with the platform key via `apksigner`.
6. Produces `modehelper/modehelper.apk`.

---

## Installation & Verification

### Install to Vehicle via ADB

```bash
adb connect 192.168.0.150:5555
adb install -r modehelper/modehelper.apk
```

### Verify System UID and Permissions

Verify that the package was granted `sharedUserId="android.uid.system"` (UID 1000) and platform-level permissions:

```bash
adb shell dumpsys package com.geely.modehelper | grep -E "userId=|sharedUser|grantedPermissions"
```

Expected output includes:
```
userId=1000
sharedUser=SharedUserSetting{.../android.uid.system}
  android.permission.INSTALL_PACKAGES: granted=true
  android.permission.WRITE_SECURE_SETTINGS: granted=true
  android.permission.BLUETOOTH_PRIVILEGED: granted=true
```

---

## ADB Command Cheat Sheet

### Control Dashcam Recording
```bash
# Start recording
adb shell am broadcast -a com.geely.modehelper.DASHCAM --ei on 1

# Stop recording
adb shell am broadcast -a com.geely.modehelper.DASHCAM --ei on 0
```

### Manage Network ADB
```bash
# Enable ADB for 30 minutes
adb shell am broadcast -a com.geely.modehelper.SET_ADB --ez enable true --ei minutes 30

# Disable ADB immediately
adb shell am broadcast -a com.geely.modehelper.SET_ADB --ez enable false
```

### Configure Privileged Wi-Fi
```bash
# Set privileged Wi-Fi SSID
adb shell am broadcast -a com.geely.modehelper.SET_TRUSTED_WIFI --es ssid "MyHomeNetwork"

# Clear privileged Wi-Fi (disables indefinite guard)
adb shell am broadcast -a com.geely.modehelper.SET_TRUSTED_WIFI --es ssid ""
```

### Bluetooth Device Pairing
```bash
# Pair with device (with PIN)
adb shell am broadcast -a com.geely.modehelper.BT_PAIR \
  --es addr "AA:BB:CC:DD:EE:FF" --es pin "1234" \
  -n com.geely.modehelper/.BtPairReceiver
```

### Probe VHAL Property IDs
```bash
# Read gear and ambient temperature properties
adb shell am broadcast -a com.geely.modehelper.READPROP --es ids "289408001,557884279"
