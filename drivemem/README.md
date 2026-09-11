# Drive Assist (`drivemem`) — Geely IHU629G

Android application for the Geely EX2 head unit (IHU629G, Android 9).

## Core Features

1. **Drive Modes & Regeneration**: Configures drive modes (Eco, Comfort, Sport) and regenerative braking levels (Low, Mid, High). On boot, mode reapplication is handled by the privileged helper app [`com.geely.modehelper`](../modehelper/README.md).
2. **Comfort Ruler (Climate Control)**: The car hardware does not expose cabin temperature via VHAL (`HVAC_IN_OUT_TEMP` is `NOT_AVAILABLE`). Climate is controlled using an absolute thermal effort scale (`EffortTable` / `ComfortRuler`, C5..0..W5) targeting setpoints the car holds. The scale steps over 0 (the mode boundary between cooling and heating) to avoid reversing the HVAC state machine at the neutral point. See [COMFORT-TABLE.md](../docs/COMFORT-TABLE.md).
3. **Telemetry & MQTT**: Publishes vehicle metrics (battery, odometer, range, speed, gear, climate, charging) to Home Assistant via MQTT with auto-discovery, and handles remote actions (smart gate trigger, climate commands, OTA updates).

## How It Works

The vehicle controls use **functionIds from a Geely adaptation layer** (`VehicleModules.getAdaptValue`), not direct VHAL property IDs. `CarPropertyManager` inside an **installed** APK (via `Car.createCar`) resolves and translates these IDs; standalone binder clients (`app_process`) fail to resolve them. Drive Assist is therefore built and installed as an APK.

Car permissions (`CAR_POWERTRAIN`, `CAR_ENERGY`) are `signature|privileged`. They are granted to debug-signed APKs on this head unit because `ro.control_privapp_permissions` is non-enforcing (empty).

## Vehicle Data Catalog

A consolidated reference of confirmed vehicle properties and Geely adaptation layer function IDs on the IHU629G. All properties are accessed via `CarPropertyManager` inside the installed APK. For detailed probing methodologies, logs, and historical investigations, see [field-catalog.md](../docs/field-catalog.md) and [DATA-CATALOG.md](../docs/DATA-CATALOG.md). Complete documentation index is in [docs/](../docs/README.md).

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
- **Drive Mode Persistence**: Persistent drive mode and regen reapplication is executed by the privileged companion app [`com.geely.modehelper`](../modehelper/README.md) (`android.uid.system`). Drive Assist broadcasts preference updates to `modehelper`.

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
adb connect 192.168.0.150:5555
adb install -r drive_assist.apk
```

### Testing Boot Flow Without Rebooting

Do not run `adb reboot` (it restarts the audio amplifier and causes a loud speaker pop). Simulate boot broadcast instead:

```bash
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -n com.geely.drivemem/.BootReceiver
```
