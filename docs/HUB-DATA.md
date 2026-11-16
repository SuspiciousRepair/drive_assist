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

### CAN (`CanHub`), only with "Exact energy" on

Needs: the switch on (Config > OBD2), modehelper installed, the roadcastd
reader running. Updated every 2 s, quantized. `NOT_AVAILABLE` when the
switch is off or the reader has not sent a heartbeat for 3 s.

| Key | What |
|---|---|
| `can.live` | reader live (1/0) |
| `can.pack_v`, `can.pack_a` | pack voltage, current (+ = discharge) |
| `can.power_kw` | pack power, V × I. `NOT_AVAILABLE` while volts or amps are not valid, also when `can.live` is 1 |
| `can.climate_kw` | climate (compressor + heater) electrical power |
| `can.drive_kw` | drive power |
| `can.charger_w` | on-board charger's mains input, only while AC charging |

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
| `energy.power_kw`, `energy.pack_v`, `energy.pack_a` | from CAN when `can.live` is 1, else from the dongle, else `NOT_AVAILABLE` |
| `energy.source` | `can` or `obd2` |

### GPS position (`LocationHub`), always while the app runs

| Key | What | Update |
|---|---|---|
| `location.fix` | `LocationHub.Fix`: lat, lon, altitude m, bearing, speed km/h (GPS), accuracy m. Source: `GpsReader` (the live fix from `TelemetryService`, else the last known one). `NOT_AVAILABLE` with no fix | polled every 2 s; published only when the car moves to another ~5 m cell. The cache always holds the newest fix |

MQTT/HA, ABRP, the 30 s sampler (altitude), trip ascent / descent, trip
routes and charge places all read this key. `GpsReader.read` is
package-private.

### Sessions (built on the hub thread from `telemetry.tick`)

| Key | What | Update |
|---|---|---|
| `trip.live` | the open trip (`LiveTrip`): start, driving time, distance, spent / regen / net kWh, SoC start → now, energy source. `LiveTrip.NONE` when no trip is open | on each tick while a trip is open, only when a shown value changes (0.1 km, 0.01 kWh, whole minutes) |
| `parking.temp_trail` | outside temperature during the stay in Park (`TempTrail`): 10-min buckets, empty while the head unit sleeps | on each tick while parked, when the trail changes |

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
  changes only. CAN wins while it is live; the dongle's samples are dropped
  then, so energy is never counted twice.

## 5. What is saved on the car

| Where | What | When |
|---|---|---|
| `car.db` `telemetry_sample` | the 30 s snapshot, incl. `battery_temp_c` (dongle), `climate_kw` (CAN), and `drive_mode` / `regen_mode` (from `car.drive_mode` / `car.regen_mode`) | every 30 s; kept 90 days |
| `car.db` `trip`, `charge_session` | per trip / per charge totals; `charge_session.lat` / `lon` where it charged (with "Record trip routes" on) | when the trip or charge ends |
| `car.db` `trip_point` | the route (`state.RoutePoints`): time, lat, lon, altitude, GPS speed, bearing, accuracy, `drive_mode`, `regen_mode`, `power_kw` (`energy.power_kw`), and the trip's `spent_kwh` / `regen_kwh` so far. Raw, for later analysis | every 5 s while out of Park, only after 25 m of movement, with "Record trip routes" on (default on). `trip_id` is NULL until the trip is saved; points of a trip too short to keep are deleted. Kept for good |
| `files/obd2-reading.log` | every dongle reading | while the dongle is connected (5 MB, then restarts) |
| `files/abrp-attempt.log` | every ABRP send and its payload | while ABRP is on (5 MB, then restarts) |

`files/` is `Android/data/com.geely.drivemem/files/` on the car's storage.
