# VHAL Property Investigation History & Rationale

Technical record of reverse-engineering findings, decompile analyses, and empirical hardware validations establishing the property mappings documented in `field-catalog.md`.

---

## 1. Climate Control (HVAC) Actuation Mechanics

### Airflow Direction Damper Validation

Testing across physical damper modes verified the standard AOSP bitmask representation on property `557846560` (and alias `356517121`):

```text
FACE = 1
FLOOR = 2
FACE | FLOOR = 3
DEFROST = 4
DEFROST | FLOOR = 6
```

* **Bitmask Combination `5` (DEFROST \| FACE)**: Actively rejected by the vehicle HVAC controller. Writes commanding `5` are refused or coerced to adjacent modes. The valid operational set is strictly `{1, 2, 3, 4, 6}`.
* **Area Parameter Independence**: The area parameter is ignored by the underlying controller; reads and writes across areas `0`, `1`, `75`, and `117` access the same physical actuator register.

---

## 2. Cruise Control & Driver Assist State Decoding

### Dash Cluster State Progression

Directed differential state sweeps comparing stationary baseline vs. active highway driving established the state values on `557884450` (float) and `557887630` (int):

| Vehicle State | Property Value | Cluster Indicator |
|---|---|---|
| **OFF** | `0.0` / `0` | Cruise indicator dark |
| **ARMED** | `1.0` / `1` | Cruise icon illuminated (white) |
| **ACTIVE** | `7.0` / `7` | Cruise icon illuminated (green), vehicle regulating speed |

* **Cruise Target Speed**: The configured target speed (e.g. 30 km/h, 40 km/h) is not reflected in `557884450` or `557887630`. The values remain constant across speed transitions, confirming they represent operational states rather than target velocity registers.
* **AOSP ACC Status (`289408009`)**: Returns constant `4` across all driving states; unwired on this firmware build.

---

## 3. High-Voltage Energy Flow & Instantaneous Power Analysis

### VHAL Energy Flow Properties (`ITripData`)

Investigation of candidate properties `559982313`, `559982315`, and `559982316` via decompilation of `com.flyme.auto.energy` resolved them to:
* `TRIP_ED_DRIVING_ENERGY_FLOW` (`612385024`)
* `TRIP_ED_BATTERY_ENERGY_FLOW` (`612385536`)
* `TRIP_ED_OTHER_ENERGY_FLOW` (`612385792`)

#### Percentage Normalization
Empirical testing confirmed that these properties represent a **percentage breakdown** of cumulative energy consumption rather than instantaneous power:
$$	ext{Driving \%} + 	ext{Battery Thermal \%} + 	ext{Other \%} pprox 100.0\%$$
Reads cross-checked identically with the factory "Trip Statistics" UI card (`com.flyme.auto.energy.MILEAGESTATISTICS`).

#### Lack of Native VHAL Instant Power Register
Decompilation of third-party telemetry services and OEM energy management binaries confirmed that instantaneous powertrain power (kW) is not exposed as a native VHAL property on the IHU629G. Real-time powertrain power must be acquired via the direct OBD2 CAN diagnostic interface (`0x7E2`) or estimated from rolling battery SoC differentials.

---

## 4. Property Sweep Coverage & Accessor Architecture

### Transition from Generic to Typed Property Accessors

Initial discovery sweeps using `CarPropertyManager.getProperty(Object.class, ...)` dropped properties failing `STATUS_AVAILABLE`, falsely reporting populated registers as dead.

Refactoring the discovery harness (`Discovery.snapshot`) to fall back to typed accessors (`getFloatProperty`, `getIntProperty`) expanded detected active properties from 179 to 1,700 out of 1,909 declared candidates:

```text
Discovery Pass Resolution:
  Initial Generic Scan:   179 ok / 1631 fail (of 1810)
  Typed Accessor Scan:   1700 ok /  209 fail (of 1909)
```

*Lesson*: Certain vehicle registers (including `DOOR_POS` and seatbelt switches) answer validly through typed getters despite failing generic `Object` deserialization.

---

## 5. Seat & Occupant Sensor Harness Isolation

### Front Seat Telemetry
* **Driver Belt (`557884820`)**: Reports `1.0` (buckled) and `0.0` (unbuckled) on Area `0`.
* **Passenger Belt (`557884821`)**: Reports `2.0` (buckled) and `1.0` (unbuckled) on Area `0`.
* **Passenger Occupancy (`557885046`)**: Reports `1` (occupied) and `0` (vacant) on Area `0`.

### Rear Seat Hardware Topology
Exhaustive testing across all area bitmasks (`1` to `16777216`) with physical occupants verified that rear seat occupancy sensors and seatbelt buckles do not route to the Android head unit:
* Low-level system inspection (kernel input devices via `getevent -lp`, `/sys/class` hardware nodes, and kernel `dmesg` logs) revealed no seat switch event devices.
* Rear occupant and belt state are wired directly from the Body Control Module (BCM) to the physical instrument cluster over dedicated harness lines, bypassing the infotainment bus entirely.

---

## 6. Body Electronics & Windows

### Door Position and Locking

* **`DOOR_POS` (`373295872`)**: Reports binary door latch status across areas `1` (FL), `4` (FR), `16` (RL), and `64` (RR). Value `1` = Open, `2` = Closed. Areas `256` (hood) and `512` (trunk) return `null`.
* **`DOOR_LOCK` (`641736874` / `0x264020AA`)**: Reliable Geely adaptation property reporting `1` (locked) and `0` (unlocked). The standard AOSP `DOOR_LOCK` (`371198722`) remains inert.

### Window Actuation & Automated Pressure Relief

Writing directly to `WINDOW_POS` (`322964416`) actuates the mechanical window motor without requiring auxiliary commands:
* **Door-Open Pressure Relief Automation**:
  * Monitored via edge-triggered callback on `DOOR_POS`.
  * When a door opens (`pos == 1`), `WINDOW_POS` commands position `10` (10% ventilation crack) to eliminate cabin pressure slam.
  * When the door latches (`pos == 2`), `WINDOW_POS` commands position `0` (fully closed).
  * Safety override: If manual window position is already $>7\%$ prior to door opening, the automated cycle is aborted.

---

## 7. Bluetooth OBD2 Pairing & RFCOMM Architecture

### Pairing PIN Override
The factory Android 9 Bluetooth stack on the IHU629G enforces an automated background pairing sequence hardcoded to PIN `"0000"` in `/system/etc/bluetooth/btDefSetting.json`. Because standard ELM327 adapters require PIN `"1234"`, pairing attempts initiated from the stock UI fail silently with `UNBOND_REASON_AUTH_FAILED`.

Modifying `/system/etc/bluetooth/btDefSetting.json` to `"pairingCode": "1234"` or dispatching `modehelper`'s privileged `BtPairReceiver` broadcast enables successful pairing without user prompts.

### RFCOMM Socket Architecture
Android's standard SDP-based `device.createRfcommSocketToServiceRecord(MY_UUID)` fails on this head unit due to non-standard Bluetooth middleware shims. Communication requires direct channel-1 RFCOMM socket allocation via reflection:

```java
Method m = device.getClass().getMethod("createRfcommSocket", int.class);
BluetoothSocket socket = (BluetoothSocket) m.invoke(device, 1);
```
