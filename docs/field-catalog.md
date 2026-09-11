# Vehicle Property & Hardware Signal Catalog

Exhaustive engineering reference of verified Vehicle Hardware Abstraction Layer (VHAL) properties, Geely adaptation function IDs, and OBD2 diagnostic signals on the Geely IHU629G head unit platform.

---

## 1. Probing Methodology & Verification Framework

To systematically verify signals without false positives or synthetic defaults, property investigation follows a disciplined eight-step methodology:

1. **Origin Identification**: Classifies the candidate source:
   * *Symbol match*: Exported constants in `android.car.jar` (proves symbol existence only).
   * *Directed diff*: Comparing pre/post `Discovery.snapshot` state captures across isolated hardware operations.
   * *OEM decompile*: Extracting exact `functionId`, `areaType`, and resolution logic from disassembled OEM applications.
   * *Direct OBD2*: Querying ECU registers directly via UDS commands over CAN.
2. **Registration Verification (`CONFIGCHECK`)**: Queries `CarPropertyManager.getPropertyList()` and range declarations. A degenerate range (`min=max=0`) indicates unpopulated hardware. Note that certain valid properties (e.g. `SEAT_OCCUPANCY`, `DOOR_POS`) bypass `getPropertyList()` enumeration but answer validly via typed accessors (`getFloatProperty`, `getIntProperty`).
3. **Property Scope (Area-Indexed vs. Scalar)**:
   * *Area-Indexed*: Requires querying explicit bitmasks (doors: 1, 4, 16, 64; windows: 16, 64, 256, 1024; seats: 1, 4, 16, 32, 64).
   * *Scalar*: Global powertrain and trip metrics operate on Area `0`. Querying additional areas on scalar properties yields redundant results.
4. **Resolution via Wrapper (`WRAPREAD`)**: Geely adaptation layer `functionId`s must be resolved via `CarAccess.wrapFuncId(funcId, areaType)` prior to invoking `CarPropertyManager`. Direct reads using raw function IDs query incorrect registers.
5. **Predefined Semantics & Units**: Physical units, quantization steps, and scaling formulas must be established before reading to differentiate genuine telemetry from uninitialized memory buffers.
6. **Cross-Validation with OEM UI**: Comparing readouts against live OEM dashboard indicators (`com.flyme.auto.energy`, `com.flyme.auto.hvac`, cluster displays) confirms calibration alignment.
7. **Physical Actuation for Writable (RW) Properties**: Software write verification requires:
   * Baseline read.
   * Property write dispatch.
   * Settling delay for mechanical or electrical transition.
   * Independent follow-up read and direct physical confirmation (actuator movement, relay click, dash icon).
8. **Dynamic State Logging**: Properties such as power flow, energy consumption, and cruise states report `0.0` or `null` while stationary. Validation requires continuous logging during active driving or fast charging.

### Diagnostic Command Interface

```bash
# Full property sweep snapshot
adb shell am broadcast -n com.geely.drivemem/.BootReceiver -a com.geely.drivemem.DISCOVER

# Direct property write
adb shell am broadcast -n com.geely.drivemem/.BootReceiver -a com.geely.drivemem.WRITEPROP --ei prop <id> --ei area <area> --ei val <int_val>
adb shell am broadcast -n com.geely.drivemem/.BootReceiver -a com.geely.drivemem.WRITEPROP --ei prop <id> --ei area <area> --ef fval <float_val>

# Read-only query
adb shell am broadcast -n com.geely.drivemem/.BootReceiver -a com.geely.drivemem.READPROP --es props "<id1,id2>" --es areas "<a1,a2>"
```

Status Key:
* ✅ **Confirmed**: Verified live on vehicle hardware with observable effect or validated telemetry.
* 🟢 **Validated Read**: Confirmed state reporting, write actuation pending further verification.
* 🟡 **Candidate**: Theoretical or decompile lead, unverified on hardware.
* 🔴 **Dead / Unwired**: Declared in software tables but unpopulated or rejected by physical vehicle hardware.
* ⚪ **NOOP / Inert**: Accepts commands without error but produces no physical actuation.

---

## 2. Powertrain & Driving Telemetry

| Property ID | Type | Area | Access | Semantics & Formulas | Status |
|---|---|---|---|---|---|
| `557885165` | `float` | 0, 1 | R | High-voltage battery SoC (%) | ✅ |
| `289407492` | `float` | 0, 1 | R | Cumulative vehicle odometer (km) | ✅ |
| `289407752` | `float` | 0, 1 | R | Estimated remaining range (km) | ✅ |
| `291504647` | `float` | 0 | R | Vehicle road speed (`PERF_VEHICLE_SPEED`, km/h) | ✅ |
| `289408001` | `int` | 0, 1 | R | Transmission gear (`VehicleGear`: `1`=N, `2`=R, `4`=P, `8`=D) | ✅ |
| `0x2140800f` | `int` | 0 | R | Adapted gear selection alias | ✅ |
| `658548345` | `float` | 0, 1 | R | Current trip distance (km) | ✅ |
| `557884279` | `int` | 0 | R | Outside ambient air temperature: `(raw - 80) / 2 = °C` | ✅ |

---

## 3. Climate Control (HVAC)

| Property ID | Type | Area | Access | Semantics & Values | Status |
|---|---|---|---|---|---|
| `354419984` | `boolean` | 75, 0, 1 | RW | Master HVAC power toggle | ✅ |
| `354419973` | `boolean` | 75 | RW | A/C compressor inverter enable (`HVAC_AC_ON`) | ✅ |
| `354419978` | `boolean` | 75, 0, 1 | RW | `HVAC_AUTO_ON`. **NOOP** (accepts writes, changes nothing) | ⚪ |
| `354419975` | `boolean` | 75 | RW | Max windshield defrost (`MAX_DEFROST`) | ✅ |
| `354419988` | `boolean` | 2 | RW | Rear window electric defroster (`HVAC_ELECTRIC_DEFROSTER_ON`) | ✅ |
| `354419976` | `boolean` | 75, 0, 1 | RW | Cabin air recirculation flap (`true`=recirculate, `false`=fresh air) | ✅ |
| `0x15600503` | `float` | 31, 44, 1, 4, 0 | RW | Target setpoint (16..32 °C, whole degree quantization). Crossing the midpoint triggers heat-pump cycle reversal | ✅ |
| `356517120` | `int` | 75, 0, 1 | RW | Blower fan speed (`0` = stopped, `1..8` = active levels) | ✅ |
| `557846559` | `int` | 0 | RW | Blower fan speed (Geely adaptation alias `0x2140101F`) | ✅ |
| `557846560` | `int` | Any | RW | Airflow direction bitmask: `1`=FACE, `2`=FLOOR, `3`=FACE\|FLOOR, `4`=DEFROST, `6`=DEFROST\|FLOOR. Value `5` is rejected | ✅ |
| `356517121` | `int` | Any | RW | Airflow direction standard alias (`0x15400501`) | ✅ |
| `0x2140105b` | - | - | - | `HVAC_WAKE_REQ` (Remote climate wake request) | 🟡 |
| `0x2140a371` | - | - | - | `AC_REMOTE_SET_STS` (Remote climate status set) | 🟡 |
| `0x2140a369` | - | - | - | `AC_REMOTE_CONTROL_STS` (Remote climate execution status) | 🟡 |

---

## 4. High-Voltage Battery & Charging

| Property ID | Type | Area | Access | Semantics & Values | Status |
|---|---|---|---|---|---|
| `605029888` | `int` | 0, 1 | RW | AC charge current limit (5..32 A) | ✅ |
| `605291008` | `float` | 0, 1 | R | Live charging current (A). Measures mains AC during AC charging, battery pack DC during DCFC | ✅ |
| `605290752` | `float` | 0, 1 | R | Live charging voltage (V). Measures mains AC (~240V) during AC charging, battery pack DC (~400V) during DCFC | ✅ |
| `605028608` | `int` | 0 | RW | `CHARGE_SWITCH`: `609`=STOP, `610`=RESTART, `611`=START_NOW | ✅ |
| `557887621` | `int` | 0 | R | Physical charging port connection: `3`=plugged in, `0`=unplugged | ✅ |
| `287310603` | `boolean` | 0 | R | AOSP `EV_CHARGE_PORT_CONNECTED`. Unwired (returns `false` during active charging) | 🔴 |
| `606098432` | `float` | 0 | R | `CHARGE_FUNC_BATTERY_CHARGING_CURRENT_POWER`. Dead (returns `0.0` at 50A DCFC) | 🔴 |
| `606109184` | `float` | 0 | R | `DRIVE_MAXIMUM_ELECTIC_POWER_LIMIT`. Dead (returns `0.0` during active power taper) | 🔴 |

### Fast Charging (DCFC) Detection Logic

To distinguish DC fast charging from AC charging reliably across power tapering curves:
* **Mains AC Charging**: Current $\le 32	ext{ A}$, voltage $pprox 220	ext{--}245	ext{ V}$.
* **DC Fast Charging**: Pack voltage $\ge 250	ext{ V}$ (consistently $380	ext{--}415	ext{ V}$), current up to $50	ext{ A}$. Pack voltage threshold cleanly separates modes even when DCFC current tapers at high SoC.

---

## 5. Drive Modes & Dynamics

| Property ID | Type | Area | Access | Values / Semantics | Status |
|---|---|---|---|---|---|
| `570491136` | `int` | 0, 1 | RW | Drive mode: Eco=`570491137`, Comfort=`570491138`, Sport=`570491139`. Holds persistently when vehicle is in ready/driving state | 🟢 |
| `537003264` | `int` | 0, 1 | RW | Regenerative braking: Low=`537003265`, Mid=`537003266`, High=`537003267` | ✅ |
| `557885172` | `int` | 0 | RW | Brake pedal feel: `1`=Comfort, `2`=Sport | ✅ |
| `557885465` | `int` | 0 | RW | Comfortable Stop (smooth braking deceleration): `1`=ON, `0`=OFF | ✅ |

---

## 6. Cruise Control & ADAS

| Property ID | Type | Area | Access | Values / Semantics | Status |
|---|---|---|---|---|---|
| `557884450` | `float` | 0 | R | Cruise control state: `0.0` = OFF, `1.0` = ARMED, `7.0` = ACTIVE | 🟢 |
| `557887630` | `int` | 0 | R | Cruise control state (integer alias, tracks identical enum values) | 🟢 |
| `289408009` | `int` | 0 | R | Standard ACC status (`0x11400409`). Constant `4` (unwired) | 🔴 |
| `557875204` | `float` | 0 | R | Radar target distance: drops to `-1.0` when no lead vehicle detected | 🟡 |
| `557884437` | `int` | 0 | RW | Lane Departure Warning: `1` = ON, `2` = OFF (requires modal confirmation on disable) | ✅ |
| `557884439` | `float` | 0 | RW | Lane Departure Warning status echo: `1.0` = ON, `0.0` = OFF | ✅ |
| `557858878` | `boolean` | 0 | RW | Forward Collision Warning (FCW): `true` = ON, `false` = OFF | ✅ |
| `557858879` | `int` | 0 | RW | FCW Sensitivity: `0`=Disabled, `0x200e0201`=Late, `0x200e0202`=Medium, `0x200e0203`=Early | ✅ |
| `557887557` | `int` | 0 | RW | Leading Vehicle Departing Alert: `1` = ON, `0` = OFF | ✅ |

---

## 7. Lighting & Body Electronics

### Exterior & Interior Lighting

| Property ID | Type | Area | Access | Values / Semantics | Status |
|---|---|---|---|---|---|
| `537528576` | `int` | 5 | RW | Cabin ambient LED color (Hex `0xRRGGBB`) | ✅ |
| `704708864` | `int` | 5, 0, 1 | RW | Cabin ambient LED brightness (`0` = off, `1..20` = active) | ✅ |
| `557885013` | `boolean` | 0 | RW | Ambient lighting master toggle (`true`=ON, `false`=OFF) | ✅ |
| `557883730` | `int` | 0 | RW | Interior illumination on door open: `1` = ON, `0` = OFF | ✅ |
| `557885462` | `int` | 0 | RW | Low Beam Headlight Height: `0` = Level 0; Levels 1..5 encoded as `0x2b020200 + level` | ✅ |
| `557884456` | `int` | 0 | RW | Intelligent High Beam switch: `2` = ON, `1` = OFF | ✅ |
| `557875206` | `int` | 0 | RW | Intelligent High Beam status echo: `139` = ON, `140` = OFF | ✅ |
| `557883682` | `int` | 0 | RW | Follow Me Home delay: `0` = Off, `537134849` = 30s, `537134850` = 60s, `537134851` = 90s | ✅ |

### Convenience & Peripheral Features

| Property ID | Type | Area | Access | Values / Semantics | Status |
|---|---|---|---|---|---|
| `557885463` | `int` | 0 | RW | Parking Mode duration: `0`=Off, `0x201B0102`=30m, `0x201B0104`=1h, `0x201B0106`=2h, `0x201B0108`=3h, `0x201B010A`=4h, `0x201B010C`=5h, `0x201B0113`=Continuous | ✅ |
| `557883700` | `int` | 0 | RW | Front wiper auto speed reduction when parking: `1` = ON, `0` = OFF | ✅ |
| `557850823` | `boolean` | 0 | RW | Center console Qi wireless phone charger: `true` = ON, `false` = OFF | ✅ |
| `557875221` | `int` | 0 | RW | Display theme mode: `0x20150101` = Light, `0x20150102` = Dark, `0x20150105` = AUTO | ✅ |

---

## 8. Doors, Windows & Latches

### Door State & Locking

| Property ID | Type | Areas | Access | Values / Semantics | Status |
|---|---|---|---|---|---|
| `373295872` | `int` | 1, 4, 16, 64 | R | `DOOR_POS`: `1` = Open, `2` = Closed. Areas: `1`=Driver, `4`=Front Passenger, `16`=Rear Left, `64`=Rear Right | ✅ |
| `641736874` | `int` | 1, 4, 16, 64 | R | `DOOR_LOCK`: `1` = Locked, `0` = Unlocked (`0x264020AA`) | ✅ |
| `371198722` | `int` | 1, 4, 16, 64 | R | AOSP `DOOR_LOCK`. Inert (returns `0` regardless of lock state) | ⚪ |

### Power Windows

Writing to `WINDOW_POS` directly drives the mechanical window regulator:

```text
Window Position Encoding:
0 = Fully Closed
100 = Fully Open
```

| Property ID | Type | Areas | Access | Values / Semantics | Status |
|---|---|---|---|---|---|
| `322964416` | `int` | 16, 64, 256, 1024 | RW | `WINDOW_POS` (AOSP): Areas: `16`=FL, `64`=FR, `256`=RL, `1024`=RR | ✅ |
| `591405227` | `int` | 16, 64, 256, 1024 | RW | `WINDOW_POS` (Geely adaptation alias `0x234020AB`) | ✅ |
| `537396224` | `int` | 0 | R | Auto-close windows on vehicle lock setting (`1` = ON) | ✅ |
| `320867268` | - | - | - | AOSP `WINDOW_LOCK`. Unwired across VHAL; does not restrict VHAL writes | ⚪ |

---

## 9. Seat Occupancy & Belts

| Property ID | Type | Area | Access | Semantics & Values | Status |
|---|---|---|---|---|---|
| `557884820` | `float` | 0 | R | Driver seat belt buckle: `1.0` = Buckled, `0.0` = Unbuckled (`0x2140A594`) | ✅ |
| `557884821` | `float` | 0 | R | Front passenger seat belt buckle: `2.0` = Buckled, `1.0` = Unbuckled (`0x2140A595`) | ✅ |
| `557885046` | `int` | 0 | R | Front passenger seat occupancy: `1` = Occupied, `0` = Vacant | ✅ |
| `356518832` | `float` | All | R | AOSP `SEAT_OCCUPANCY`. Unwired (returns constant `0.0` across all areas) | 🔴 |
| `354421634` | `float` | All | R | AOSP `SEAT_BELT_BUCKLED`. Unwired (returns constant `0.0` across all areas) | 🔴 |
| `641736873` | `float` | 64 | R | Rear seat occupancy candidate. Unwired (constant `2.0`) | 🔴 |

*Architectural Note*: The physical rear seat occupancy and seatbelt switches are routed directly from the Body Control Module (BCM) to the instrument cluster over dedicated harness wiring; signals do not cross into the Android head unit.

---

## 10. Direct OBD2 UDS Parameter Identifiers (BMS ECU `0x7E2`)

Diagnostics querying the high-voltage Battery Management System directly over CAN via a paired Bluetooth ELM327 adapter:

### Bus Initialization Sequence

```text
ATE0         # Echo off
ATSP6        # Protocol 6: ISO 15765-4 CAN (11-bit ID, 500 kbit/s)
ATM0         # Memory off
ATS0         # Spaces off
ATAL         # Allow long frames
ATAT1        # Adaptive timing on
ATH1         # Headers on
ATST64       # Response timeout (64 * 4 ms = 256 ms)
ATSH7E2      # Set target ECU address to 0x7E2 (BMS ECU)
```

### Parameter Identifiers (Service `0x22` - ReadDataByIdentifier)

| Metric | PID Command | Response Length | Decoding Formula (Bytes A, B, C...) | Units / Range |
|---|---|---|---|---|
| **State of Charge (SoC)** | `22 4B 36` | 2 bytes | `(A * 256 + B) / 10.0` | 0.0 to 105.0% (0.1% precision) |
| **Pack Voltage** | `22 4B 21` | 2 bytes | `(A * 256 + B) / 10.0` | 200.0 to 500.0 V |
| **Pack Current** | `22 4B 22` | 2 bytes | `((A * 256 + B) - 5000) / 10.0` | Amperes (+ discharge, - charge) |
| **Battery Core Temp** | `22 4B 3C` | 1 byte | `A - 40` (or `A` depending on BMS offset) | -40 to +85 °C |
| **Vehicle Speed** | `22 DF 01` | 1 byte | `A` | 0 to 255 km/h |
| **Instantaneous Power** | *(Calculated)* | - | `(Voltage * Current) / 1000.0` | Kilowatts (kW) |
