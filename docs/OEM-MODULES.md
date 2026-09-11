# Decompiled OEM Modules Specification

Reverse-engineering specification of factory Android applications and background services on the Geely IHU629G head unit (FlymeAuto / ECARX platform). Documents IPC interfaces, broadcast intents, hidden settings, property mappings, and UI asset architectures.

---

## 1. Architectural Scope & Platform Sharing Model

The software running on the IHU629G is compiled from a shared Geely/FlymeAuto platform codebase deployed across multiple vehicle models and trim levels.

> [!IMPORTANT]
> **Platform Capability vs. Trim Hardware**:
> Function tables, IPC interfaces, and property declarations in OEM binaries reflect platform-level capabilities. On the Geely EX2 / Geometry E (IHU629G), several declared features lack physical hardware wiring (e.g., multi-zone climate, seat heating/ventilation/massage, steering wheel heating, PM2.5 air sensors, fragrance dispensers, and motorized charge port flaps).

---

## 2. Module Specifications

### 2.1 `com.flyme.auto` (`FlymeAutoService.apk`)

Headless background daemon running with `persistent="true"` under `android.uid.system`. Enforces vehicle driving restrictions and application lockouts based on movement and gear state.

#### Global Settings (`Settings.Global`)

| Key | Type | Description |
|---|---|---|
| `car_restriction_switch` | `int` | Master driving restriction override (`1` = active, `0` = disabled) |
| `car_restriction_state` | `int` | Current restriction state: `0` = inactive, `1` = unrestricted (parked), `2` = restricted (driving) |

#### Broadcast Intents (No Permissions Required)

* `net.easyconn.drivemode.opened`: Broadcast when vehicle enters restricted driving state.
* `net.easyconn.drivemode.closed`: Broadcast when vehicle exits restricted driving state.
* `net.easyconn.drivemode.checkstatus`: Query broadcast; the service immediately replies with one of the two intents above.

#### Restriction Configuration

The per-package lockout policy is loaded from:
* Primary: `/data/misc/security_data/restriction/restriction_black_list.xml`
* Factory Fallback: `/system/etc/restriction_black_list.xml`

---

### 2.2 `com.flyme.auto.hvac` (`GeelyAutoClimate.apk`)

Privileged climate control application running under `android.uid.system` with `CONTROL_CAR_CLIMATE` and `CAR_VENDOR_EXTENSION` permissions.

#### Exported Broadcasts (`FHvacCarService`)

* `ACTION_MAX_CLOUD`: Triggers Max AC / Max Cool mode.
* `ACTION_MAX_HOT`: Triggers Max Heat mode.
* `ACTION_WINDOW_CLOSE_TIP`: Prompts the "Please close windows" UI notification dialog.

#### AIDL Services

* **`IAiCarControl` (`AiCarControlSDK`)**: Generic voice assistant execution bridge. Directly modifies climate setpoints, fan speeds, air direction, and window positions via voice commands without UI interaction.
* **AI Eco System**: Automatic geofenced climate optimization logic monitoring vehicle proximity to home.

#### UI Asset & Animation Pipeline

Decompilation reveals hand-coded micro-interactions using native Android animation primitives (no Lottie JSON or GIF assets):

1. **Recirculation Transition (`component/Cycle.java`)**:
   * Uses animated WebP resources (`cycle_inner.webp`, `cycle_outer.webp`, 64x64, 60–61 frames; `ic_cycle_on.webp`, `ic_cycle_off.webp`, 80x80).
   * Loaded via Android's native `AnimatedImageDrawable`.
   * Enforces single playback (`setRepeatCount(0)`), swapping to static drawable on completion.
   * Debounced by a 1000 ms `ViewFroze` cooldown.
2. **Rear Defrost Animation**:
   * `HvacTabViewModel.mRearWindshieldIcon` binds `R.drawable.electric_defrosting` (80x80, 33-frame animated WebP).
   * Front defrost (`ic_climate_front_defrost`) is a static image.
3. **Airflow Indicator (`component/BlowAnimation.kt`)**:
   * Translates a static 256x8 px sliver (`blow_animate_indicator.webp`) horizontally across its parent container using `ValueAnimator` (3000 ms, linear, infinite loop) driving `leftMargin`.
4. **Blower Speed Tap Feedback**:
   * One-shot 0 to 270 deg rotation over 200 ms via `res/anim/hvac_fan_rotate.xml` (`PathInterpolator`).
5. **Proprietary PAG Files**:
   * Tencent PAG format used for seat massage programs (`res/raw/*.pag`) and fan button tap dynamics.

---

### 2.3 `com.flyme.auto.energy` (`AutoEnergy.apk`)

High-voltage energy management, charging dispatch, and trip consumption analysis.

#### Charging VHAL Properties

| Property ID | Type | Access | Function | Values / Semantics |
|---|---|---|---|---|
| `605028608` | `int` | RW | `CHARGE_SWITCH` | `609` = STOP, `610` = RESTART, `611` = START_NOW |
| `605028864` | `int` | RW | Target SoC Limit | User-defined charge percentage limit (slider) |
| `605029120` | `int` | R | Max SoC Limit | Upper bound for charge limit slider |
| `605029376` | `int` | R | Min SoC Limit | Lower bound for charge limit slider |
| `605029632` | `int` | R | SoC Step | Granularity for charge limit slider |
| `605029888` | `int` | RW | Current Limit | AC charging current limit in Amperes (5..32 A) |

#### Battery Preconditioning

* Properties `605094144` through `605095936`: Manages preconditioning target SoC, battery temperature status, scheduled start/end timestamps, and immediate actuation (`605095936`).
* Broadcast: `android.intent.action.ENERGY_CHARGE_START` initiates immediate battery thermal preconditioning.

#### Vehicle-to-Load (V2L / V2G) Discharge

* `605159680` / `605159936`: Master V2L discharge switches and minimum battery discharge floor percentage.

#### Super Energy Saving Mode

Managed via `SuperEnduranceActivity` across properties `604177408`–`604178944`:
* Single-action emergency range extension mode.
* Automatically cuts AC compressor power, turns off interior ambient lighting, forces regenerative braking to High, and caps vehicle road speed at 90 km/h.

---

### 2.4 `com.ecarx.parking` (`OneOSAvmApp.apk`)

User interface shell for camera management and ultrasonic parking distance control (PDC).

#### Architecture & Native Decoupling

* **Steering Angle Processing**: The dynamic trajectory guidelines displayed over camera feeds are computed directly by the native `evsengine` daemon from raw CAN bus data. Steering angle is not surfaced as an Android property (`PERF_STEERING_ANGLE` `291507140` returns `0.0`).
* **AVM Activation**: `AvmEnterService` toggles property `557885012` to command native camera stream start/stop.
* **PDC Ultrasonic Sensors**: Reports 8 independent zone distance properties (front/rear x inner/outer x left/right) and alert volume controls.
* **Unsupported Features**: Automated Parking Assist (APA) and software camera perspective switching return "not supported" on this vehicle trim.

---

### 2.5 `com.ecarx.eas.carservice` (`GeelyXSFCarService.apk`)

ECARX OpenAPI and AdaptAPI middleware service. Exposes internal vehicle state observers to higher-level applications.

* **ACC Status**: Exposes `IVehicleACCStatusObserver` with `getAccStatus()`. Standard property `289408009` (`0x11400409`) returns a constant value of `4` on this platform.
* **Cruise Setpoint Limits**: This service does not expose cruise set speed or target following distance properties.

---

### 2.6 `com.flyme.auto.settings` & `com.geely.controlcenter`

Coordinates seat adjustments, exterior mirror controls, and system preferences.

* **Settings Dialogs**: `SeatAdjustViewModel` and `MirrorAdjustViewModel` are housed in `com.flyme.auto.settings`.
* **Quick-Settings Tiles**: `com.geely.controlcenter` houses `SeatAdjustTile`, `MirrorAdjustTile`, and `MirrorFoldTile`, dispatching explicit intents into `com.flyme.auto.settings`.
* **Hardware Availability**: Mirror auto-tilt and power folding controls exist in bytecode but are not wired to physical actuators on the EX2 Max trim.
