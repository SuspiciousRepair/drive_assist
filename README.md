# Drive Assist

**English** · [Português (Brasil)](README.pt-BR.md)

[![CI Build](https://img.shields.io/badge/build-passing-brightgreen.svg)](#building)
[![JaCoCo Coverage](https://img.shields.io/badge/coverage-5.55%25%20%28131%20tests%29-blue.svg)](docs/CODE-QUALITY-REPORT.md)
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Geely%20IHU629G%20%7C%20Android%209-orange.svg)](docs/ARCHITECTURE-SAFETY-AUDIT.md)

**A better everyday dashboard for the Geely EX2 / Geometry E.**

Drive Assist is an app for the Geely EX2 / Geometry E. It installs on the car's
own screen and gives you the things the factory software leaves out: controls
that stay the way you set them, a clear record of driving and charging, and
useful vehicle data on the car screen and in services you already use.

If you want to see the app before reading about installation or engineering,
start with the illustrated [Guide for Drivers](docs/DRIVER-GUIDE.md).

> [!WARNING]
> Root ADB gives administrator access to the head unit and can expose location,
> credentials, cameras, vehicle data, and your home network. Read the
> [plain-language root ADB safety notice](docs/ADB-ROOT-SAFETY.md) before enabling it.

### What it fixes

- **The car forgets your settings.** Pick Eco, Comfort or Sport and it goes back
  to default the next time you switch on. Drive Assist remembers your choice and
  puts it back for you every time.
- **The climate controls are fiddly.** Instead of juggling temperature, fan speed
  and the A/C button, there is a single slider that runs from cold to warm. Slide
  one way if you feel too hot, the other way if you feel too cold. The app works
  out the rest.
- **Your car's data is locked away.** Battery level, range, charging, and location
  can be sent to your own [Home Assistant](https://www.home-assistant.io/). Drive
  Assist sends data only to the services you configure, including Home Assistant
  and, when enabled, A Better Routeplanner.
- **Charging costs disappear into memory.** Every recharge becomes part of a
  history with energy, duration, battery change, and price. Earlier prices can be
  corrected later, and free charging is recorded properly.
- **Daily driving deserves more than one number.** See trips, parking intervals,
  recharges, energy use, regeneration, elevation change, and efficiency by speed
  range. Parked climate use does not distort driving efficiency.
- **Long trips are a guessing game.** Live battery and elevation data can be fed
  to [A Better Routeplanner](https://abetterrouteplanner.com/), so its charging
  stops are based on how your car is actually doing right now.
- **OBD2 ready.** You can connect the head unit directly to an OBD2 dongle for
  fresh battery-management data.

### What you see on the screen

The main screen gives you a simple cooler-to-warmer comfort control, outdoor
temperature, and large climate shortcuts that work at a glance.

The statistics screens keep a diary of your driving and charging: how far you
went, where energy was spent or recovered, how much you climbed and descended,
what every recharge delivered, and what it cost. There is also an optional
dashcam that records with telemetry subtitles and can copy clips to a USB stick.

A connection to Home Assistant opens up a whole new world of automations.
You can open your gate and send timely information from Home Assistant to the
car. If someone rings your doorbell, for example, the dashboard can show the
doorbell image.

### What it will not do

Drive Assist only touches comfort and convenience: climate, drive mode, cabin
lights, windows, charging. It has no ability to steer, brake, accelerate, lock or
unlock your doors, or open the boot — those signals are read and displayed only,
never commanded. The full, plain-language rundown of everything the app can see
and do is in the [Welcome & Transparency Guide](docs/WELCOME.md).

### Who it is for

Owners of a Geely EX2 / Geometry E who are comfortable installing an app on the
car themselves. It is a spare-time project, not an official Geely product, and it
comes with no warranty — please read the
[Vehicle Security & Sideloading Guide](docs/SECURITY-SAFETY.md) before you start.

*Technical details, for those who want them: the head unit is an IHU629G running
Android 9 (API 28, ECARX 250060) with a 1920x1080 screen at 160 dpi. Everything
below this point is written for developers.*

---

### Live Vehicle Interface Previews

Captured directly from the physical vehicle head unit display via ADB (`1920x1080` native resolution):

| Comfort View & Thermal Effort Ruler | Daily Statistics & Altimetry Feed |
| :---: | :---: |
| [![Comfort View](docs/screenshots/comfort-dashboard-live.png)](docs/screenshots/comfort-dashboard-live.png) | [![Daily Statistics](docs/screenshots/daily-statistics-live.png)](docs/screenshots/daily-statistics-live.png) |

| Charging history & energy balance | Drive mode and regeneration |
| :---: | :---: |
| [![Charging Statistics](docs/screenshots/charging-statistics-live.png)](docs/screenshots/charging-statistics-live.png) | [![Drive Mode Settings](docs/screenshots/settings-overview-live.png)](docs/screenshots/settings-overview-live.png) |

---

## Quick Start

Getting Drive Assist running on your vehicle head unit:

1. **One-Shot Wireless Installation (Recommended)**:
   Connect your laptop to the same Wi-Fi network as the vehicle and run:
   ```bash
   ./install.sh <CAR_IP>
   ```
   *Provisions `modehelper.apk`, installs `drive_assist.apk`, and applies the Bluetooth OBD2 PIN fix automatically.*

2. **Standalone USB Installer (`drive_assist_installer.apk`)**:
   Copy the single installer APK to a FAT32 USB flash drive, insert it into the vehicle USB port, open the vehicle's File Manager, and tap to install. The installer bundles both packages, installs them with platform privileges, and self-uninstalls on completion.

3. **Interactive Configuration Wizard (`configure-car.sh`)**:
   Configure MQTT broker credentials, mTLS certificates, ABRP tokens, and defaults via interactive terminal:
   ```bash
   ./tools/configure-car.sh
   ```

For detailed step-by-step instructions and troubleshooting, see the [Post-Unlock Installation Guide](docs/INSTALL-GUIDE.md).

---

## Documentation

Full architectural documentation and reverse-engineering guides are located in the [docs/](docs/README.md) directory:

### User & Operation Guides
- [Guide for Drivers](docs/DRIVER-GUIDE.md) — An illustrated explanation of the app
- [Welcome & Transparency Guide](docs/WELCOME.md) — What the app sees, does, controls, risks, and privacy safeguards
- [Short Installation Guide](docs/QUICK-INSTALL.md) — The one-file USB method for owners
- [Technical Installation Guide](docs/INSTALL-GUIDE.md) — ADB setup, scripts, verification, and troubleshooting
- [Home Assistant Guide](docs/HOME-ASSISTANT.md) — Connection, permissions, verification, and privacy
- [A Better Routeplanner Guide](docs/ABRP.md) — Live-data connection, location choice, and optional OBD2
- [MQTT Technical Reference](docs/MQTT-GUIDE.md) — Broker setup, mTLS security, topics, and automations
- [ABRP & OBD2 Technical Reference](docs/ABRP-GUIDE.md) — Payloads, generic tokens, and Bluetooth BMS
- [Vehicle Security & Sideloading Guide](docs/SECURITY-SAFETY.md) — Sideloading risks, platform test keys, APK repackaging, and data exposure

### Audits, Quality & Governance
- [Release Readiness Matrix](docs/RELEASE-READINESS-MATRIX.md) — Comprehensive Go/No-Go release audit and remediation roadmap
- [Software Architecture & Vehicle Safety Audit](docs/ARCHITECTURE-SAFETY-AUDIT.md) — Multi-perspective audit of UID separation, Park invariants, and VHAL safety
- [Code Quality & Test Coverage Report](docs/CODE-QUALITY-REPORT.md) — JaCoCo test metrics (5.55% / 131 tests), Lint tuning, Checkstyle, and SpotBugs catalog
- [Contributing Guidelines](CONTRIBUTING.md) — Branching rules (`dev`/`master`), Conventional Commits, and pull request checklist
- [Changelog](CHANGELOG.md) — Full version history, release notes, and unreleased enhancements
- [License](LICENSE) — GNU General Public License v3.0 terms

### Engineering References
- [Vehicle Data Catalog](docs/DATA-CATALOG.md) — Confirmed properties and function IDs
- [Field Catalog](docs/field-catalog.md) — Exhaustive hardware signals, probes, and VHAL references
- [Climate & Comfort Ruler](docs/COMFORT-TABLE.md) — Thermal effort scale and HVAC state machine
- [Cameras & EVS HAL](docs/EVS-CAMERA.md) — Extended View System architecture and binder interface
- [Dashcam Subsystem](docs/DASHCAM.md) — Continuous recording, telemetry subtitles, and streaming
- [Status Bar Icons](docs/STATUS-ICONS.md) — System overlay mechanism and icon IDs
- [ADB Setup Guide](docs/GUIA-ADB-IHU629G.md) — Sideloading, networking, and root environment
- [ModeHelper Companion App](modehelper/README.md) — Privileged system companion app (`android.uid.system`) for mode persistence, silent OTA, dashcam, and ADB guard

## Core Features

1. **Drive Modes & Regeneration**: Configures drive modes (Eco, Comfort, Sport) and regenerative braking levels (Low, Mid, High). On boot, mode reapplication is handled by the privileged helper app [`com.geely.modehelper`](modehelper/README.md).
2. **Comfort Ruler (Climate Control)**: The car hardware does not expose cabin temperature via VHAL (`HVAC_IN_OUT_TEMP` is `NOT_AVAILABLE`). Climate is controlled using an absolute thermal effort scale (`EffortTable` / `ComfortRuler`, C5..0..W5) targeting setpoints the car holds. The scale steps over 0 (the mode boundary between cooling and heating) to avoid reversing the HVAC state machine at the neutral point. See [COMFORT-TABLE.md](docs/COMFORT-TABLE.md).
3. **Telemetry & MQTT**: Publishes vehicle metrics (battery, odometer, range, speed, gear, climate, charging) to Home Assistant via MQTT with auto-discovery, and handles remote actions (smart gate trigger, climate commands, OTA updates).

## How It Works

The vehicle controls use **functionIds from a Geely adaptation layer** (`VehicleModules.getAdaptValue`), not direct VHAL property IDs. `CarPropertyManager` inside an **installed** APK (via `Car.createCar`) resolves and translates these IDs; standalone binder clients (`app_process`) fail to resolve them. Drive Assist is therefore built and installed as an APK.

Car permissions (`CAR_POWERTRAIN`, `CAR_ENERGY`) are `signature|privileged`. They are granted to debug-signed APKs on this head unit because `ro.control_privapp_permissions` is non-enforcing (empty).

## Vehicle Data Catalog

A consolidated reference of confirmed vehicle properties and Geely adaptation layer function IDs on the IHU629G. All properties are accessed via `CarPropertyManager` inside the installed APK. For detailed probing methodologies, logs, and historical investigations, see [field-catalog.md](docs/field-catalog.md) and [DATA-CATALOG.md](docs/DATA-CATALOG.md).

## Architecture: CarActor & EntityBus

All vehicle I/O is centralized in `CarActor`, which manages a dedicated `HandlerThread` and a single `CarAccess` connection for the process lifecycle.

- **Cached Reads**: `CarActor.get(ctx).get(key)` returns the latest cached property value synchronously without blocking on vehicle binder I/O.
- **Subscriptions**: `EntityBus.subscribe(key, listener)` notifies in-app consumers when property values change. `EntityBus.publish()` is restricted to `CarActor`.
- **Thread Safety**: All car read/write calls must execute on `CarActor`'s thread.

### Polling Loops & Event Cadence

- **Always-On Keys**: Registered at startup and polled continuously:
  - `car.is_charging` (2s): Derived from `charge_a > 0.5A` rather than the charge switch read-back.
  - `telemetry.outside_temp` (15s): Outside ambient temperature.
  - `telemetry.ambient_color` & `telemetry.ambient_brightness` (4s): Cabin strip state.
  - `car.carplay_connected` (4s): Re-publishes OEM `com.njda.carplay.broadcast` state onto `EntityBus`.
- **Screen-Scoped Keys**: Polled only while requested by an active UI:
  - `telemetry.speed` (1s): Active during `ComfortActivity` foreground lifecycle (`onResume` → `onPause`) to drive visualizer animations.
- **Snapshot Cadence (`telemetry.tick`)**: Published every 15 seconds unconditionally (not diffed) carrying the full vehicle snapshot. This un-diffed tick prevents heartbeat starvation for MQTT and ensures periodic sampling for internal listeners (`ChargeSession` and `OdoStats`).

### Dedicated Subsystems
Stateful features requiring independent lifecycle management run dedicated listener interfaces seeded from `EntityBus` events: `ChargeSession`, `TripSession`, `ParkSession`, `CarState`, `GateState`, `Obd2Reader`, and `AbrpUploader`.

## Home Assistant & MQTT Integration

`TelemetryService` publishes vehicle metrics to Mosquitto with Home Assistant auto-discovery (`homeassistant/<comp>/drivemem_<vin>/<key>/config`), registering the vehicle device under `Geely EX2 (<last 6 of VIN>)` (deduced dynamically from `sys.ecarx.vin`, or `Geely EX2` as fallback).

- **Payload scaling**: Values from the Geely adaptation layer (battery %, odometer km) are pre-scaled; `div=1` is used across fields. `ac_on` is an adapted boolean.
- **Transports**: Supports TCP LAN (`tcp://<host>:1883`) or remote WebSocket (`wss://<host>:<port>` via Tailscale Funnel).
- **Validation**: `helpers/mqtt_test.py` validates discovery and telemetry payloads from an external machine:
  ```bash
  python3 helpers/mqtt_test.py <broker-ip> <user> <pass>
  ```

### Smart Gate Controller

Coordinates gate availability and triggers via Home Assistant over two MQTT topics:

```
drivemem/<vin>/gate/available   HA -> app, retained, "online"/"offline"
drivemem/<vin>/gate/open        app -> HA, QoS 1, NEVER retained
```

**State & Network Invariants:**
1. **HA computes proximity**: The app does not evaluate GPS boundaries. HA evaluates the vehicle's `device_tracker` location and publishes `gate/available`.
2. **Availability lifecycle**: Gate visibility requires `available && connected`. `connectionLost` immediately resets `available` to false to prevent displaying a stale gate button during reconnection before HA pushes fresh state.
3. **Replayed retained messages are ignored**: On reconnect/resubscribe, the MQTT broker replays retained messages with the `RETAIN` flag set. `MqttReporter` drops replayed retained `"online"` messages and waits for a live push to prevent triggering gate visibility from pre-trip state.
4. **Immediate fix on reconnect**: `subscribePanel()` publishes an immediate GPS position upon reconnection so HA can evaluate proximity without waiting for the next telemetry interval.
5. **Actuation delivery**: `gate/open` is published with QoS 1 and `retained=false` (retained actuation commands would re-trigger physical gates upon broker restarts or reconnects). UI taps are debounced for 3 seconds.

Testing gate topics manually:
```bash
mosquitto_pub -r -t drivemem/<vin>/gate/available -m online    # Show gate button
mosquitto_pub -r -t drivemem/<vin>/gate/available -m offline   # Hide gate button
mosquitto_sub -t 'drivemem/<vin>/gate/#' -v                    # Observe gate open commands
```

*Note on LAN isolation: Access Point / Client Isolation on the local Wi-Fi router blocks TCP between the head unit and broker on port 1883.*

## UI & Screens

- **`ComfortActivity`** (LAUNCHER): Three-column dashboard:
  - **Climate**: Outside temperature display, comfort ruler (`ComfortRuler`), and warmer/colder buttons.
  - **Controls**: Recirculation toggle and smart gate opener.
  - **Visualizer & Context**: Full-bleed art view (`SkylineArtView`) framing the Home Assistant WebView card (`PanelCardView`).
- **`TelemetryActivity`**: Settings screen for MQTT parameters, drive mode/regen defaults, status bar icons, and theme selection.
- **`HvacProbe` / `HvacSet`**: Diagnostic activities for HVAC reverse engineering and property testing.

## Styling & Display

### Display Geometry & Sizing
- **Native Resolution**: 1920x1080 at density 1.00 (`1 dp = 1 px = 1 sp`). Screen width is 1920 dp (~5x standard phone width), viewed at ~75 cm.
- **Layout Implications**: Typography and touch targets must account for the 1.00 density and viewing distance. Gesture thresholds and touch boundaries must be defined as fractions of the view or screen dimensions, not fixed dp values (e.g. 44 dp represents only 44 pixels).

### Vehicle Hardware Traps
1. **Property Write Confirmation**: `setIntProperty` can return success even if the ECU drops the write. `setAmbientColor` sweeps candidate area IDs `{5, 0, 1}`, reads the property back to confirm execution, and caches the working area.
2. **Ambient Light Order of Operations**: Brightness `0` is the ECU's off state. Setting color while brightness is 0 causes the ECU to discard the color command. Brightness must always be set to non-zero before updating color.
3. **`CarAccess.connect()` Threading**: Never invoke `CarAccess.connect()` on the Android main thread. It blocks waiting for `ServiceConnection`, whose callbacks are dispatched on the main thread, resulting in a deadlock.

## Lifecycle & Boot

- **`BootReceiver`**: Listens for `BOOT_COMPLETED` (handled via `goAsync()`). Re-enables Wi-Fi and starts configured background services.
- **Head Unit Sleep Behavior**: The head unit suspends to RAM rather than performing full shutdowns on short stops. `BOOT_COMPLETED` does not fire on resume from sleep, and `SCREEN_ON` is not delivered to manifest receivers.
- **Watchdog Timer**: A 10-minute `ELAPSED_REALTIME_WAKEUP` alarm counts suspended time and triggers `ensureAll` upon wake-up to verify Wi-Fi connectivity and ensure services are running.
- **Package Updates & Process Kills**: APK installation and force-stops cancel pending alarms. `BootReceiver` listens for `MY_PACKAGE_REPLACED`, and `ComfortActivity.onResume` invokes `scheduleWatchdog` / `ensureAll` to restore watchdog alarms.
- **Drive Mode Persistence**: Persistent drive mode and regen reapplication is executed by the privileged companion app [`com.geely.modehelper`](modehelper/README.md) (`android.uid.system`). Drive Assist broadcasts preference updates to `modehelper`.

## Build & Installation

The build environment requires JDK 17, Android SDK (build-tools 34.0.0, platform-28), hand-crafted compilation stubs (`car-stubs/car-stubs.jar`), and `debug.ks`.

### Building

To run Gradle directly or run unit tests, execute from the repository root:

```bash
./gradlew :drivemem:assembleRelease   # compile only from repo root
./gradlew test                        # run unit tests
```

- **Version Code**: `versionCode` is computed from wall-clock minutes since the epoch and injected by `aapt2` to ensure monotonic increases, preventing `pm install -r` downgrade errors.
- **OTA Publication**: `build.sh` uploads `drive_assist.apk` to Home Assistant's `/config/www` directory and broadcasts the update notification on `drivemem/<vin>/update/set`.

### Installation

```bash
adb connect <CAR_IP>:5555
adb install -r drive_assist.apk
```

### Vehicle Configuration CLI Wizard (`configure-car.sh`)

Instead of typing MQTT credentials, mTLS certificates, ABRP tokens, and Spotify keys on the car touchscreen, use the interactive terminal wizard:

```bash
./tools/configure-car.sh
```

- **Interactive Prompts**: Enter values or press `[Enter]` to retain existing values.
- **Multi-Broker**: Configures up to 3 MQTT URIs with automatic fallback.
- **mTLS Integration**: Automatically pushes `.p12` client certificates and `.crt` CA certificates to the app's protected storage with correct Unix permissions (`UID 10048`).
- **Safety**: Automatically creates timestamped XML backups in `../prefs-backup/` and force-stops the app before writing to prevent Android's in-memory SharedPreferences cache from overwriting changes.
- **CLI Commands**:
  ```bash
  ./tools/configure-car.sh --show            # Display current active settings on vehicle
  ./tools/configure-car.sh --backup-only     # Export current car settings to a timestamped file
  ./tools/configure-car.sh --restore <file>  # Restore previous preferences XML backup
  ```

### Testing Boot Flow Without Rebooting

Do not run `adb reboot` (it restarts the audio amplifier and causes a loud speaker pop). Simulate boot broadcast instead:

```bash
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -n com.geely.drivemem/.BootReceiver
```

---

## Contributing & Governance

Drive Assist welcomes contributions from automotive software developers and the Geely community!

- **Contributing Guidelines**: Review [CONTRIBUTING.md](CONTRIBUTING.md) for branching policy (`dev` vs. `master`), Conventional Commits conventions, local test pipeline (`./gradlew test jacocoTestReport`), and vehicle safety rules.
- **Release Readiness & Audits**: See [docs/RELEASE-READINESS-MATRIX.md](docs/RELEASE-READINESS-MATRIX.md) for the objective release assessment matrix, [docs/ARCHITECTURE-SAFETY-AUDIT.md](docs/ARCHITECTURE-SAFETY-AUDIT.md) for vehicle safety invariant analyses, and [docs/CODE-QUALITY-REPORT.md](docs/CODE-QUALITY-REPORT.md) for test coverage and static analysis findings.
- **Changelog**: See [CHANGELOG.md](CHANGELOG.md) for release notes across all versions and unreleased staging features.
- **License**: Drive Assist is licensed under the [GNU General Public License v3.0](LICENSE). Any distributed modified version must also stay open source under GPLv3.
