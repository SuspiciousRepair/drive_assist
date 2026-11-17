# Car data: what is available, and when

Every car value the app uses goes through the **hub**: `CarActor`'s cache,
with change-only delivery on `EntityBus` (rule 7 in `CLAUDE.md`). This page
lists the hub keys, where each value comes from, how often it updates, and
when it is available.

**Blast radius:** each key is one string constant. To find every reader of a
value, search the code for that key or its constant.

Property IDs and scales of the car's own (VHAL) properties are in
[DATA-CATALOG.md](DATA-CATALOG.md). This page is about availability.

## 1. When anything updates at all

- The app must be running and the **head unit awake**. The head unit
  suspends instead of shutting down; while it is suspended, nothing below
  updates, and every value keeps its last state until the next wake.
- On the trusted home Wi-Fi, modehelper's `WifiGuardReceiver` holds a wake
  lock, so the head unit stays awake there.
- A value that is not available reads `NOT_AVAILABLE` in the hub. Consumers
  must handle that; it is not zero.

## 2. Hub keys

### Car properties (VHAL), always while the car service is connected

| Key | What | Update |
|---|---|---|
| `car.gear` | gear (1 N, 2 R, 4 P, 8 D) | on change (push) |
| `car.park_mode` | parking mode | on change |
| `car.drive_mode`, `car.regen_mode` | drive mode, regen level | on change |
| `car.avas_mode` | AVAS (pedestrian warning sound): 0 off, 1.. sound type. Reported by modehelper (`AvasState`): Drive Assist may not read it (`CAR_CONTROL_AUDIO_VOLUME`) | on change, read every 4 s by modehelper; on request |
| `car.door_pos`, `car.window_pos` | doors, windows (per area) | on change |
| `car.hvac_recirc`, `car.hvac_direction`, `car.hvac_rear_defrost` | climate state | on change |
| `car.hvac_fan` | climate fan level, 0 (climate off) to 8 (`HVAC_FAN`, 356517120) | on change |
| `car.hvac_front_defrost` | front-windshield defogger button (MAX_DEFROST, 354419975), true/false. Each change is a `comfort_event` row (`defrost`, `front on`/`front off`) and goes to MQTT/HA as `front_defrost` | on change |
| `car.charge_a` | charging current at the port, A | every 2 s |
| `car.plug_connected` | cable in the port (1/0) | every 2 s |
| `car.is_charging` | charging (derived: current AND plug) | when either input changes |
| `telemetry.outside_temp` | outside temperature, °C | every 15 s |
| `telemetry.ambient_color_set` | cabin light colour picked by hand (`0xRRGGBB`) | every 4 s |
| `telemetry.ambient_mode` | cabin light mode: `537526787` picked colour, `7` follow drive mode | every 4 s |
| `telemetry.ambient_color` | colour the cabin light really shows (derived, `AmbientLight`: the drive mode's colour in follow mode, else `ambient_color_set`) | when any input changes |
| `telemetry.ambient_brightness` | cabin light brightness, 0..20 | every 4 s |
| `telemetry.speed` | speed, km/h | every 1 s, **only while the Comfort screen is open** |
| `car.carplay_connected` | CarPlay link (from `CarplayState`) | every 4 s |
| `car.tire_warn_fl`, `car.tire_warn_fr`, `car.tire_warn_rl`, `car.tire_warn_rr` | soft-tyre warning per wheel (`TireWarning`), raw value: 0 = no warning. **No pressures**: this car's warning is indirect (ITPMS_TYRE_WARN, wheel speeds), the pressure properties are "not supported". The value of a real warning is not yet seen; anything not 0 counts as a warning | every 10 s |
| `car.tire_warn` | 1 when any wheel warns, else 0 (derived) | when a wheel changes |

### `telemetry.tick`: the 30-second snapshot

One map every 30 s with: `battery` (SoC, whole %), `battery_raw_pct`,
`odometer`, `range`, `speed`, `gear`, `gear_label`, `ac_on`, `trip_km`,
`charge_a`, `charge_v`, `is_charging`, `plug_connected`, `park_mode`,
`park_timer`, and the energy window: `instant_power_kw_est`,
`power_spent_kw`, `power_regen_kw`, `energy_spent_kwh`, `energy_regen_kwh`,
`energy_net_kwh`, `energy_measured`, `energy_quality`,
`energy_spent_est_kwh`, `energy_regen_est_kwh`, and `climate_kw` (only with
CAN). `charge_a`/`charge_v` exist only while plugged in. Saved to
`telemetry_sample` and sent to MQTT/Home Assistant.

A tick runs its due polls first and publishes `telemetry.tick` after, so a
listener that reads other hub keys gets this tick's values, also on the
first tick after a suspend. `ChargeSession` counts a tick's power for the
time since the last tick, but only up to 90 s
(`ChargeSession.MAX_SAMPLE_GAP_MS`): a longer gap (the head unit slept) adds
no energy.

### CAN (`CanHub`), only with "Exact energy" on

Needs: the switch on (Config > OBD2), modehelper installed, the roadcastd
reader running. Updated every 2 s, quantized. `NOT_AVAILABLE` when the
switch is off or the reader has not sent a heartbeat for 3 s.

| Key | What |
|---|---|
| `can.live` | reader live (1/0) |
| `can.pack_v`, `can.pack_a` | pack voltage, current (+ = discharge) |
| `can.power_kw` | pack power, V × I. `NOT_AVAILABLE` while volts or amps are not valid, also when `can.live` is 1 |
| `can.climate_kw` | climate (compressor + heater) power, raw `VCU_ThermalPwrAct`. Reads low (see `plan/active/CAN-ENERGY-ROADMAP.md`, "Power map") |
| `can.drive_kw` | drive motor power, raw `VCU_DrvPwrAct`. Not battery power: use `can.power_kw` for that |
| `can.charger_w` | on-board charger's mains input, only while AC charging |
| `can.brake` | brake pedal switch, 1 pressed / 0 released (`ESC_BrakePedalSwitchStatus`, frame 0x125). **Not polled: injected the moment it changes**, so it has no 2 s delay. `NOT_AVAILABLE` while the car flags it invalid or the reader is down |

A CAN value also needs its frame to have **changed once** since the reader
started. A frame that stays constant (for example climate power while the
climate is off) reads "never observed" and stays `NOT_AVAILABLE` until it
changes.

### OBD2 dongle (`ObdHub`), only with OBD2 on and the dongle connected

Needs: the OBD2 switch on, **car Bluetooth on**, the dongle paired and
plugged in. Updated every 2 s; a value older than 15 s becomes
`NOT_AVAILABLE`. The dongle itself answers about every 2–3 s.

| Key | What |
|---|---|
| `obd.connected` | dongle link up (1/0) |
| `obd.soc` | SoC with one decimal |
| `obd.pack_v`, `obd.pack_a`, `obd.power_kw` | pack voltage, current, power |
| `obd.batt_temp_c` | battery temperature, °C. **Only the dongle has it** |

### Best measured source (`EnergyHub`), derived in the hub

| Key | What |
|---|---|
| `energy.power_kw`, `energy.pack_v`, `energy.pack_a` | from CAN when `can.live` is 1 **and** CAN has the value, else from the dongle, else `NOT_AVAILABLE` |
| `energy.source` | `can` or `obd2`, by the same rule on the power value |
| `energy.climate_kw` | climate's real power: `can.climate_kw` through `ClimatePower` (1.19 × raw + 0.2 kW while on), plus the fan (`car.hvac_fan` through `FanPower`, up to 0.12 kW; a 12 V load CAN's climate signal leaves out). CAN only |
| `energy.other_kw` | 12 V loads and losses: `can.power_kw` − `can.drive_kw` − `energy.climate_kw`. All CAN, never mixed with the dongle, so the three add up. CAN only |

### GPS position (`LocationHub`), always while the app runs; live fixes while driving

| Key | What | Update |
|---|---|---|
| `location.fix` | `LocationHub.Fix`: lat, lon, altitude m, bearing, speed km/h (GPS), accuracy m. Altitude is NaN when the fix has none (saved as NULL, never 0). Source: `GpsReader`: the live GPS fix from `GpsOwner` when it is 10 s old or less, else the last known one (GPS, then passive, then network). `NOT_AVAILABLE` with no fix | polled every 2 s; published only when the car moves to another ~5 m cell. The cache always holds the newest fix |

MQTT/HA, ABRP, the 30 s sampler (altitude), trip ascent / descent, trip
routes and charge places all read this key. `GpsReader.read` is
package-private.

`GpsOwner` holds the one location listener (GPS every 2 s). It does not
need MQTT. It listens while a consumer is on (trip routes, MQTT telemetry
with a broker set, or ABRP with "send location") and the car is out of
Park, or in Park for less than 2 minutes. After that it removes the
listener, so the GPS is not held on in a parked car, and the key holds the
last known fix until the car leaves Park. Only GPS fixes become the live
fix; a network fix never replaces one.

### Sessions (built on the hub thread from `telemetry.tick`)

| Key | What | Update |
|---|---|---|
| `trip.live` | the open trip (`LiveTrip`): start, driving time, distance, spent / regen / net kWh, SoC start → now, energy source. `LiveTrip.NONE` when no trip is open | on each tick while a trip is open, only when a shown value changes (0.1 km, 0.01 kWh, whole minutes) |
| `parking.temp_trail` | outside temperature during the stay in Park (`TempTrail`): 10-min buckets, empty while the head unit sleeps, plus the true lowest and highest reading of the stay (`min()` / `max()`) | on each tick while parked, when the trail changes |

`trip.live` uses the same `DrivingConsumption` rules as the stats pages, so
the home card and the finished trip agree. After an app restart mid-trip it
replays the trip's rows once, then adds one tick at a time.

### Events

`trip.completed`, `charge.completed`, `charge.cost_updated`,
`charge.dismissed`, `parking.changed`, `valet.changed`, `valet.progress`.

## 3. Which value exists in which setup

| Value | Neither | Dongle only | CAN only | Both |
|---|---|---|---|---|
| Pack power, V, A | no | dongle (~0.5 Hz) | CAN (10 Hz) | **CAN** |
| Driving energy (trip kWh) | SoC estimate | measured (dongle) | measured (CAN) | measured (**CAN**) |
| Battery-side charge kWh (`charge_session.kwh_battery`) | no | dongle | CAN | **CAN** |
| Wall-side charge kWh (`charge_session.kwh`) | port A × V | same | same | same |
| Battery temperature | no | dongle | no | dongle |
| SoC with a decimal | no | dongle | no | dongle |
| SoC, whole % | VHAL | VHAL | VHAL | VHAL |
| Climate power | no | no | CAN | CAN |
| Charger mains input (W) | no | no | CAN | CAN |
| ABRP `power`, `voltage`, `current` | not sent | dongle | CAN | **CAN** |
| ABRP `batt_temp` | not sent | dongle | not sent | dongle |

"No" means nothing reports it: the app never puts an estimate in its place,
except the SoC-based driving energy, which is marked "estimated".

## 4. Paths outside the hub (allowed exceptions)

- `EnergyIntegrator` gets **every** CAN sample (10 Hz) and every OBD2
  reading directly, because it integrates each one and the hub delivers
  changes only. CAN wins while `energy.source` is `can` (`EnergyHub` decides,
  once); the dongle's samples are dropped then, so energy is never counted
  twice. When CAN is live but has no power value, the dongle's samples count.
  CAN samples also carry the drive and climate values held at that moment,
  for the trip's energy split.
- `AbrpUploader` reads `EnergyIntegrator.meanPowerKw`, the mean battery
  power over the last 6 s of those samples, because the hub keeps only the
  latest value. It is from the same source as `energy.power_kw` (the mean
  starts over when the source changes); with too few samples ABRP gets
  `energy.power_kw` instead.

## 5. What is saved on the car

| Where | What | When |
|---|---|---|
| `car.db` `telemetry_sample` | the 30 s snapshot, incl. `battery_temp_c` (dongle), `climate_kw` (CAN), and `drive_mode` / `regen_mode` (from `car.drive_mode` / `car.regen_mode`) | every 30 s; kept 90 days |
| `car.db` `trip`, `charge_session` | per trip / per charge totals; on a trip CAN measured (≥ 90 % of its time), where the energy went, raw: `can_net_kwh`, `drive_spent_kwh`, `drive_regen_kwh`, `climate_raw_kwh`, `climate_on_s`, `fan_kwh` (read through `sensors.EnergySplit`); `charge_session.lat` / `lon` where it charged (with "Record trip routes" on) | when the trip or charge ends |
| `car.db` `trip_point` | the route (`state.RoutePoints`): time, lat, lon, altitude, GPS speed, bearing, accuracy, `drive_mode`, `regen_mode`, `power_kw` (`energy.power_kw`), and the trip's `spent_kwh` / `regen_kwh` so far. Raw, for later analysis | every 5 s while out of Park, only after 25 m of movement, with "Record trip routes" on (default on). `trip_id` is NULL until the trip is saved; points of a trip too short to keep are deleted. Kept for good |
| `files/obd2-reading.log` | every dongle reading | while the dongle is connected (5 MB, then restarts) |
| `files/abrp-attempt.log` | every ABRP send and its payload | while ABRP is on (5 MB, then restarts) |

`files/` is `Android/data/com.geely.drivemem/files/` on the car's storage.
