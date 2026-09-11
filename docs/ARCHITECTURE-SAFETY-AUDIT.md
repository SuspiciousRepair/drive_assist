# Software Architecture & Vehicle Safety Audit: Drive Assist

**Target Platform**: Geely EX2 / Geometry E (IHU629G Head Unit, Android 9 / API 28, ECARX 250060)  
**Display Specification**: 1920x1080 @ 160 dpi (Density 1.0, 1 dp = 1 px)  
**Scope**: Privilege Separation, Vehicle State Invariants, Suspend Lifecycle, Database Compatibility, Telemetry Pipelines, UI Ergonomics, and Credential Sanitization  
**Audit Standard**: Automotive Software Safety, AOSP IPC Security, and Hardcoded Secret Remediation  

---

## 1. Executive Summary & Audit Scope

This document provides a comprehensive technical audit of the software architecture, vehicle safety invariants, system privilege boundaries, and data resilience mechanisms of the **Drive Assist** platform for the Geely EX2 (IHU629G head unit).

The system consists of three distinct modules designed to balance deep vehicle integration with strict platform isolation:
1. **`drivemem` (`com.geely.drivemem`)**: The primary user-facing application providing instrument dashboards, comfort controls, daily trip analytics, and network telemetry. Runs as an unprivileged application UID.
2. **`modehelper` (`com.geely.modehelper`)**: A headless, platform-signed system companion running under `android.uid.system` (UID 1000). Provides privileged package installation, secure system settings management, Bluetooth pairing, and persistent drive/regenerative braking mode restoration.
3. **`installer` (`com.geely.installer`)**: A transient, self-uninstalling bootstrap APK running under `android.uid.system` to automate the initial sideload deployment of both `drivemem` and `modehelper`.

The audit evaluated five core dimensions:
- **Privilege Separation & IPC Integrity**: Isolation between UID 1000 and user UID; defense-in-depth on exported components.
- **Vehicle Safety & State Invariants**: Park-only gating on updates and mode restoration, zero-reboot rules for audio hardware protection, and suspend/resume watchdog architecture.
- **Embedded Database Compatibility**: Strict adherence to SQLite 3.22 constraints on Android 9 (API 28), avoiding unsupported SQL window functions and native UPSERT.
- **Sensor Polling, Telemetry & UI Ergonomics**: Serialization of Vehicle Hardware Abstraction Layer (VHAL) calls via `CarActor`, decoupled pub/sub via `EntityBus`, offline-tolerant telemetry queues, and 1920x1080 @ 1.0 automotive typography.
- **Credential & Secret Sanitization**: Verification of tracked git files, remediating hardcoded tokens, broker IP addresses, and vehicle-identifying numbers.

---

## 2. UID Privilege Separation & IPC Security Architecture

### 2.1 Android UID 1000 vs. Unprivileged Application UID

In Android automotive environments, executing with system privileges (`android:sharedUserId="android.uid.system"`) grants direct access to privileged permissions such as `INSTALL_PACKAGES`, `DELETE_PACKAGES`, and `WRITE_SECURE_SETTINGS`. However, running the entire application as UID 1000 presents a critical platform conflict on Android 9:

- **The WebView UID 1000 Crash Restriction**: On Android 7.0+ through Android 9, AOSP intentionally blocks the Chromium WebView renderer from initializing inside processes running as `android.uid.system`. Attempting to load a `WebView` under UID 1000 triggers an uncatchable `AndroidRuntimeException` (`"WebView cannot be used by the system user"`).
- **Architectural Solution**: Because the right-hand panel of Drive Assist's main display renders rich, dynamic web context (custom HTML cards, Home Assistant context cards, parking occupancy), the main UI cannot run as `android.uid.system`.
- **Module Separation**:
  - `drivemem` (`com.geely.drivemem`) declares **no** `sharedUserId` in `drivemem/src/main/AndroidManifest.xml:1-3`. It runs as a standard sandboxed user UID (e.g. `u0_a...`).
  - `modehelper` (`com.geely.modehelper`) declares `android:sharedUserId="android.uid.system"` in `modehelper/AndroidManifest.xml:14` and is platform-signed with the AOSP public platform test-key (`c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`), matching the vehicle's unhardened firmware build.

```
┌─────────────────────────────────────────────────────────────┐
│                 Geely EX2 Head Unit (API 28)                │
│                                                             │
│   ┌───────────────────────────┐ ┌───────────────────────┐   │
│   │     com.geely.drivemem    │ │  com.geely.modehelper │   │
│   │    (Standard App UID)     │ │   (android.uid.system)│   │
│   │                           │ │                       │   │
│   │ • ComfortActivity (UI)    │ │ • PackageInstaller    │   │
│   │ • WebView Dashboard Panel │ │ • SecureSettings      │   │
│   │ • CarActor & EntityBus    │ │ • Drive/Regen Mode    │   │
│   │ • Telemetry / ABRP / MQTT │ │ • WifiGuard & ADB Gate│   │
│   └─────────────┬─────────────┘ └───────────▲───────────┘   │
│                 │                           │               │
│                 └────── Explicit IPC ───────┘               │
│                    (Targeted Package/Class)                 │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 IPC Boundaries & Defense-in-Depth

Because `drivemem` and `modehelper` are signed with different keys (development key vs. platform test key), standard signature-permission checks cannot protect the boundary. Instead, the architecture uses **explicit, package-targeted intents** coupled with **strict payload and certificate validation**:

1. **Package-Directed Broadcasts**: All cross-boundary invocations from `drivemem` explicitly declare the target package or component:
   - `AdbGate.java:45, 93`: Dispatches `HELPER_SET_TRUSTED = "com.geely.modehelper.SET_TRUSTED_WIFI"` and `HELPER_SET = "com.geely.modehelper.SET_ADB"` using `intent.setPackage("com.geely.modehelper")`.
   - `Updater.java:267-269`: Dispatches `HELPER_INSTALL = "com.geely.modehelper.INSTALL_APK"` with `intent.setPackage("com.geely.modehelper")`.
   - `TelemetryActivity.java:337, 2137`: Targets `DashReceiver` and `SET_MODE` via `setClassName()`.

2. **OTA Payload & Signature Verification in `Installer.java`**:
   The privileged installer receiver in `modehelper` (`Installer.java:47-116`) enforces multiple defensive layers before handing an APK to Android's `PackageInstaller`:
   - **HTTPS Enforcement**: Rejects any update URL that does not begin with `https://`.
   - **Payload Size Cap**: Enforces a strict download ceiling of 40 MB (`MAX_BYTES = 40 * 1024 * 1024L`), preventing memory exhaustion or denial-of-service.
   - **Package Identity Check**: Extracts the downloaded archive's manifest and asserts `archiveInfo.packageName.equals("com.geely.drivemem")`.
   - **Signature Preservation**: Inspects the certificate array of the incoming APK and confirms byte-for-byte equality against the currently installed package (`sameSigner(cur.signatures, got.signatures)`). Third-party APKs or tampered binaries cannot be sideloaded via the helper.

3. **ADB Auto-Off Timer in `AdbControl.java`**:
   Network ADB is a major attack surface on vehicles. `AdbControl.java:45-113` mitigates this risk by making network ADB strictly ephemeral:
   - Every ADB enable event arms an exact hardware timer (`AlarmManager.ELAPSED_REALTIME_WAKEUP`).
   - The timeout defaults to 15 minutes and is strictly clamped to a maximum of 120 minutes.
   - **Fail-Safe Fallback**: If scheduling the auto-off alarm fails, ADB is immediately forced off (`AdbControl.java:105-112`).
   - `WifiGuardReceiver.java:85-130`: Restricts indefinite ADB strictly to the authorized home Wi-Fi SSID (`trusted_ssid`). Disconnecting from the home Wi-Fi instantly disables ADB and releases any held wakelocks.

---

## 3. Vehicle Safety & State Invariants

### 3.1 Park-Only (`P`) Constraints

In automotive user experience, presenting modal update dialogues or altering vehicle powertrain configurations while in motion is hazardous. Drive Assist enforces strict Park gating across all life-cycle states:

1. **Modal Update Dialog Gating**:
   - `UpdateDialog.java:43-47`: Prior to instantiating or displaying any update prompt, the dialogue checks vehicle gear state:
     ```java
     if (!CarState.isParked()) {
         Log.w(TAG, "Update dialog suppressed: vehicle is not parked (safety lock)");
         return null;
     }
     ```
   - `ComfortActivity.java:1178-1182`: `promptUpdateIfParked()` checks `!CarState.isParked()`. If the vehicle is in Reverse, Neutral, or Drive, the prompt is deferred.
   - **Dynamic Motion Interruption**: In `ComfortActivity.java:1217-1222`, the `carStateListener` monitors gear changes in real time. If the vehicle leaves Park while an `UpdateDialog` is currently showing, the dialog is **immediately dismissed**:
     ```java
     if (!parked) {
         if (activeUpdateDialog != null && activeUpdateDialog.isShowing()) {
             try { activeUpdateDialog.dismiss(); } catch (Throwable ignored) {}
             activeUpdateDialog = null;
         }
     }
     ```
   - When the vehicle safely returns to Park, deferred updates are presented automatically (`ComfortActivity.java:1225-1227, 1568-1570`).

2. **OTA Execution Guard in `Updater.java:234-239`**:
   The OTA download and package installation engine checks `CarState.isParked()` prior to initiating the update sequence. In-motion updates are rejected with `"bloqueado: veículo em movimento"` logged to the UI step progress.

3. **Drive Mode & Regenerative Braking Enforcement**:
   - On the Geely EX2, drive mode (ECO/SPORT) and regenerative braking strength (HIGH/LOW) reset to defaults on power cycles.
   - `ModeHelperService.java:105, 148-155` enforces stored driver preferences (`enforceModeParked()`).
   - Crucially, this restoration only executes when `gear == CarMode.GEAR_PARK`. The helper will **never** toggle drive or regen modes while the car is moving, preventing sudden regenerative torque changes.

4. **Gear State Default**:
   `CarState.java:21-27` defaults `parked = true` as a fail-safe state, updating from the VHAL property `car.gear` (`GEAR_SELECTION = 0x2140800f`, adapted PARK value `4`).

### 3.2 Head Unit Reboot Avoidance ("No-Reboot Rule")

A critical vehicle hardware finding on the IHU629G head unit is that issuing `adb reboot` or programmatic `PowerManager.reboot()` power-cycles the vehicle's audio amplifier DSP. This produces an abrupt, high-amplitude pop/crack through the vehicle speakers, which can damage speaker coils or startle vehicle occupants.

- **Strict Ban on Reboots**: Programmatic reboots are strictly prohibited throughout the codebase. There are zero calls to `PowerManager.reboot()` or shell `reboot` in `drivemem`, `modehelper`, or `installer`.
- **Subsystem Daemon Restarts**: Where configuration changes require daemon resets (e.g. `bt-pin-fix/apply-pin-1234.sh:43`), the scripts restart only the specific Linux daemon (`killall -9 bluetoothd` / `start bluetoothd`), preserving head unit and DSP uptime.
- **Boot Receiver Validation**: Development testing of boot behavior avoids reboots by broadcasting the intent directly:
  ```bash
  adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -n com.geely.drivemem/.util.BootReceiver
  ```

### 3.3 Suspend/Resume Lifecycle & Watchdogs

The Geely EX2 head unit does not shut down when the vehicle is turned off; instead, it enters an automotive deep suspend (suspend-to-RAM). Consequently:
- `android.intent.action.BOOT_COMPLETED` does **not** fire when the driver turns the vehicle back on.
- Standard Android timers and handlers (`postDelayed`, `HandlerThread`) pause while the SoC kernel is suspended.

To guarantee reliable background telemetry, climate observation, and connection management upon resume:

1. **`AlarmManager.ELAPSED_REALTIME_WAKEUP` Watchdog**:
   - `BootReceiver.java:28-35, 98-116, 167-185`: Registers a repeating watchdog alarm using `AlarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, pi)` with a 10-minute period (`WATCHDOG_MS = 600,000 ms`).
   - `ELAPSED_REALTIME_WAKEUP` counts hardware RTC time spent in deep sleep. When the IHU wakes up, any elapsed alarm fires immediately, waking `BootReceiver.onReceive()` to verify that all required foreground services are alive.
   - `ComfortActivity.onResume()` (`ComfortActivity.java:1500-1507`) re-arms the watchdog and invokes `BootReceiver.ensureAll(this)` on every display wake.
   - Handles `Intent.ACTION_MY_PACKAGE_REPLACED` (`BootReceiver.java:40-45`) to immediately re-arm alarms and restart services after an OTA APK upgrade.

2. **Heartbeat & Zombie Recovery in `Beat.java`**:
   - `Beat.java:16-77` maintains a persistent timestamp ledger for all active subsystems (`tele`, `outtemp`, `wifiicon`, `socicon`, `mqtt`).
   - If a background service hangs or enters an unrecoverable zombie state (e.g., telemetry silent for > 5 minutes, MQTT silent for > 20 minutes), the watchdog detects the stale heartbeat and triggers a clean restart.
   - **Flapping Protection**: Implements `MIN_RESTART_GAP_MS = 30 * 60 * 1000L` (30 minutes) to prevent aggressive crash-restart loops during prolonged network outages.

3. **Window Safety Management**:
   - `DoorWindow.java:32-40, 80-114`: Implements cabin pressure relief. When a vehicle door opens, the window automatically cracks down by 10% (`CRACK = 10`), closing when the door shuts.
   - **Manual Override Safety Check**: If the window position prior to door closing is `>= MORE_THAN_CRACK (14%)`, the system recognizes that the occupant intentionally opened the window and suppresses the auto-close.
   - `Purge.java:30-43, 58-102`: Cabin air purge button (50% drop). Records initial window positions and exclusively rolls up windows that were moved by the purge command, leaving previously open windows intact.

---

## 4. Database Architecture & SQLite 3.22 Compatibility

### 4.1 Android 9 SQLite Runtime Constraints

Android 9 (Pie / API 28) packages **SQLite version 3.22.0**. Modern SQLite features commonly used in Android development were introduced in later releases:
- **SQLite 3.24.0 (2018)**: Added native `UPSERT` syntax (`INSERT INTO ... ON CONFLICT DO UPDATE`).
- **SQLite 3.25.0 (2018)**: Added SQL Window Functions (`ROW_NUMBER() OVER (...)`, `LAG() OVER (...)`, `LEAD() OVER (...)`).

Using either window functions or `ON CONFLICT DO UPDATE` on Android 9 results in runtime `SQLiteException: near "ON": syntax error` or `near "OVER": syntax error`.

### 4.2 Drive Assist Compatibility Implementations

1. **Java-Side Chronological Aggregation vs. `LAG()`**:
   In `TelemetryRollup.java:170-174`, the daily telemetry aggregator computes energy delta and distance deltas across consecutive session rows. Rather than querying with `LAG()`, the codebase explicitly implements Java-side aggregation:
   > *"Not done in SQL: window functions (LAG) are needed for a 'previous row's timestamp' query, and this app's minimum Android version's bundled SQLite predates them (added in SQLite 3.25, 2018) -- one ordered pass in Java over exactly the days being frozen instead."*
   The Java engine performs a single sequential stream traversal in memory over pre-indexed records, eliminating any reliance on SQLite 3.25+.

2. **Native UPSERT Substitution**:
   `CarDb.java:143` avoids `ON CONFLICT DO UPDATE` by leveraging Android Framework SQLite API `insertWithOnConflict()` with `SQLiteDatabase.CONFLICT_IGNORE`, combined with explicit indexed `UPDATE` statements when mutation of existing rows is required.

3. **Single-Writer Thread Concurrency Model**:
   `CarDb.java:204-217` isolates all database write transactions onto a dedicated `HandlerThread("cardb")`. Multiple background services (`TelemetryService`, `CarActor`, `TripRecorder`) submit write runnables to the handler queue, completely preventing SQLite database lock contention (`database is locked` / `android.database.sqlite.SQLiteDatabaseLockedException`).

4. **Atomic Migration Transactions**:
   `DbMigration.java` executes upgrades from early flat-file logs (`charge.log`, `odo.log`) to SQLite tables wrapped in `beginTransaction()`, `setTransactionSuccessful()`, and `endTransaction()`. Partial or interrupted migrations roll back cleanly without corrupting the database.

---

## 5. Sensor Polling, Telemetry Pipeline & UI Ergonomics

### 5.1 VHAL Serialization & `CarActor`

Android Automotive's `CarPropertyManager` communicates with the underlying microcontroller via Vehicle HAL Binder calls. Multiple concurrent threads making synchronous binder queries to `CarPropertyManager` can exhaust binder thread pools or cause VHAL driver deadlocks.

- **Single-Threaded Actor**: `CarActor.java` encapsulates all VHAL binder interactions inside a dedicated single-threaded `HandlerThread("car-actor")`.
- **Scheduled Polling Intervals**:
  - High-frequency charging parameters: Polled every **2 seconds** during active charging sessions.
  - Ambient lighting and CarPlay active state: Polled every **4 seconds**.
  - Exterior temperature, HVAC state, and broad telemetry: Polled every **15 seconds**.
- **`EntityBus` Decoupling**: Raw hardware sensor reads received by `CarActor` are published to an in-memory publish-subscribe event bus (`EntityBus.java`). UI components, logging services, and MQTT reporters subscribe to `EntityBus` events without holding direct references to VHAL binder objects.

### 5.2 Network Resilience & Offline Telemetry

1. **MQTT Telemetry (`MqttReporter.java`)**:
   - Multi-Broker Fallback: Supports a prioritized list of broker URIs (`uriList`), enabling automatic failover from a primary local LAN broker (`tcp://homeassistant.local:1883`) to a secondary Tailscale VPN or remote broker.
   - Reconnect Backoff & Grace Periods: Implements a 60-second connection cooldown to prevent CPU spikes when the vehicle is out of Wi-Fi / LTE range.
   - Last Will and Testament (LWT): Publishes retained offline state (`drivemem/<vin>/available = "offline"`) on ungraceful disconnection.
   - Home Assistant Auto-Discovery: Publishes MQTT discovery definitions for battery SOC, odometer, tire pressures, charging power, gear state, and climate controls.

2. **A Better Routeplanner Telemetry (`AbrpUploader.java`)**:
   - Decoupled Sampling: Telemetry is sampled every 6 seconds, with live coordinates uploaded to ABRP once per minute.
   - Batch Aggregation: The remaining 6-second intermediate samples are compressed and uploaded via ABRP's `/bulk` telemetry endpoint.
   - Bounded Offline Buffer: Telemetry samples are buffered in an in-memory queue capped at **200 samples** (`QUEUE_CAP = 200`). If mobile connectivity drops in tunnels or underground garages, telemetry is retained without risking `OutOfMemoryError`. Once connection is restored, the buffer flushes sequentially.

### 5.3 UI Ergonomics for 1920x1080 @ 1.0 Density

The IHU629G display has unique ergonomic requirements:
- **Physical Specifications**: 1920x1080 pixels at 160 dpi (density factor 1.0, where `1 dp = 1 px = 1 sp`).
- **Viewing Distance**: Approximately 70–80 cm from the driver's eye line, significantly farther than a handheld mobile device (30 cm).
- **Automotive Typography**:
  - Primary outside temperature: **112 sp** (readable at a glance without driver distraction).
  - Secondary status labels: **26 sp**.
  - Card padding: **26 dp**; navigation sidebar width: **84 dp**.
- **Dynamic Column Repacking (`repackColumns()`)**:
  In `ComfortActivity.java`, the dashboard layout dynamically measures the rendered heights of individual feature cards (Climate, Door/Window Status, Power/Efficiency, Garage Gate) and redistributes them across vertical columns to ensure full screen utilization without clipping or vertical scrollbars.
- **Daily Statistics (`DailyStatsView.java`)**:
  Renders interactive 14-day energy consumption bar charts using MPAndroidChart, formatted for single-touch automotive selection, with elevation gain/loss profiles and charging session summaries.

---

## 6. Credential & Secret Sanitization Audit

An exhaustive audit of tracked files across the git tree was conducted to locate and remediate hardcoded secrets, private network addresses, and personal identifiers.

### 6.1 Findings & Remediations

| Finding ID | Location | Original Content | Remediation Applied | Status |
|:---|:---|:---|:---|:---|
| **SEC-01** | `tools/configure-car.sh:322` | Hardcoded ABRP API Key:<br>`DEF_ABRP_KEY=$(get_pref "abrp_api_key" "<ABRP_KEY>")` | Replaced default value with empty string:<br>`DEF_ABRP_KEY=$(get_pref "abrp_api_key" "")` | **REMEDIATED** |
| **SEC-02** | `tools/configure-car.sh:275, 278` | Private Home LAN Broker IP:<br>`RAW_URIS=$(get_pref "mqtt_uri" "tcp://<PRIVATE_BROKER_IP>:1883")`<br>`DEF_URI1="${URI_ARRAY[0]:-tcp://<PRIVATE_BROKER_IP>:1883}"` | Replaced private IP with standard mDNS hostname:<br>`RAW_URIS=$(get_pref "mqtt_uri" "tcp://homeassistant.local:1883")`<br>`DEF_URI1="${URI_ARRAY[0]:-tcp://homeassistant.local:1883}"` | **REMEDIATED** |
| **SEC-03** | `docs/MQTT-GUIDE.md:12, 14-16, 190, 201...` | Specific Partial VIN `<PARTIAL_VIN>` in documentation and Home Assistant YAML automations | Scrubbed all 16 occurrences of `<PARTIAL_VIN>`, replacing with standard placeholder `123456` | **REMEDIATED** |

### 6.2 Evaluation of Tracked Public Test Keys

The audit verified two sets of cryptographic keys tracked in the repository:
1. **`keystore/debug.ks`**:
   - Tracked RSA 2048-bit debug keystore with password `android`.
   - **Audit Assessment**: **BENIGN / INTENTIONAL**. Fully documented in `keystore/README.md`. Android's package manager requires matching certificates to permit `pm install -r` package updates. Sideloaded automotive apps distributed in open source require a deterministic signing key so end users can install updates without data wipes.
2. **AOSP Platform Test-Key in `modehelper` & `installer`**:
   - Base64-encoded `platform.pk8` and `platform.x509.pem` in `build-modehelper.sh` and `build-installer.sh`.
   - **Audit Assessment**: **BENIGN / INTENTIONAL**. These are the standard public Google AOSP test keys (`c8a2e9bccf...`) used to sign development builds of AOSP. The Geely IHU629G firmware was released with open AOSP test keys, allowing platform-level companion sideloading without OEM private key compromise.

### 6.3 Automated Grep Scan Verification

Following remediation, automated scans confirmed zero occurrences of sensitive patterns across all tracked repository files:
- `git grep "<PARTIAL_VIN>"`: **CLEAN (0 results)**
- `git grep "<ABRP_KEY>"`: **CLEAN (0 results)**
- `git grep "<PRIVATE_BROKER_IP>"`: **CLEAN (0 results)**
- `git grep -i "BEGIN RSA PRIVATE KEY"`: **CLEAN** (only parser string literals in `CertImporter.java`)
- `.gitignore` verification: `.ota-env`, `local.properties`, `keystore.properties`, `mqtt-certs/`, `*.p12`, `*.crt`, `*.pem` are properly gitignored.

---

## 7. Verification & Compliance Matrix

| Audit Requirement | Verification Command / Target | Result | Compliance |
|:---|:---|:---:|:---:|
| **UID Separation** | `modehelper` runs as `android.uid.system`; `drivemem` runs as standard UID | Verified | **PASS** |
| **WebView Protection** | `drivemem` isolated from UID 1000 to prevent Chromium renderer crash | Verified | **PASS** |
| **IPC Defense** | Explicit package intents + HTTPS, 40MB cap, package check, signature match | Verified | **PASS** |
| **Park Safety Gate** | `UpdateDialog` checks `isParked()`; auto-dismisses when gear leaves Park | Verified | **PASS** |
| **No-Reboot Rule** | Zero reboot calls in codebase; audio amplifier pop prevented | Verified | **PASS** |
| **Suspend Resiliency** | `AlarmManager.ELAPSED_REALTIME_WAKEUP` (10m) + `Beat.java` zombie monitor | Verified | **PASS** |
| **Window Protection** | 10% pressure relief; manual override preserved if position > 14% | Verified | **PASS** |
| **SQLite 3.22 Compat** | Java-side rollup avoids SQL `LAG()`; `CONFLICT_IGNORE` avoids UPSERT | Verified | **PASS** |
| **VHAL Serialization** | Single-threaded `CarActor` + decoupled `EntityBus` pub/sub | Verified | **PASS** |
| **Telemetry Resiliency**| Multi-broker MQTT failover + ABRP 200-item bounded offline queue | Verified | **PASS** |
| **Display Ergonomics** | 112sp typography, dynamic column repacking on 1920x1080 @ 1.0 display | Verified | **PASS** |
| **Secret Sanitization** | Hardcoded ABRP key, broker IP, and partial VIN completely sanitized | Verified | **PASS** |
| **Unit Test Suite** | `./gradlew test` executes with zero regressions | Verified | **PASS** |

---
*Audit completed on 2026-09-10. All software architecture and vehicle safety invariants verified.*
