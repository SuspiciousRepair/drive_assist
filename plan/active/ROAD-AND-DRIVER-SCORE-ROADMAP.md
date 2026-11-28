# Road Score and Driver Score (head-unit IMU)

Status (checked against the code 2026-11-27): Phases 1 and 2 are built
(#206): `ImuHub`, `ImuFilter`, `DriveScorer`, the scores saved for each trip
and Valet stay, and shown on the trip and Valet screens. Open: Phase 3
(roughness map layer, period trends) and Phase 4 (extras). The text below is
the design as it was noted on 2026-11-23.

Uses the head unit's built-in 3-axis accelerometer — already kept powered on
since boot by the OEM stack — together with existing CAN speed, motor power,
brake pedal, and GPS `trip_point` data to compute two separate scores:

1. **Road Score (0–100):** how smooth or rough the road surface was (vertical
   axis only, so driving style does not pollute road quality).
2. **Driver Score (0–100):** how smooth and efficient the driving was
   (horizontal acceleration, braking, cornering, jerk, and EV regen use).

---

## 1. Verified hardware facts (IHU629G, live `dumpsys sensorservice`)

Confirmed on the real car on 2026-09-22 (`archive/discover/accel-logger`) and
2026-11-23 (`adb shell dumpsys sensorservice`):

- **Sensor:** `ACCELEROMETER (MTK)`, `maxRange = 78.45 m/s²` (~8 g),
  `resolution = 0.0012 m/s²`, `minRate = 12.5 Hz`, `maxRate = 400 Hz`,
  hardware FIFO `(max = 4500, reserved = 3000)` events, normal app privilege.
- **Zero extra hardware wakeups at 16 Hz:**
  `com.njda.adapter.manager.SX11SensorManager` holds `ACCELEROMETER` open since
  boot at `80 ms` (`batching = 0 ms`), streaming every `62.5 ms` (**16 Hz**).
  Subscribing at `80_000 µs` rides the existing stream with no extra sensor
  power or HAL polling. (`25 Hz` / `40_000 µs` is optional if sharper pothole
  peak capture is needed.)
- **Quiet rest baseline:** Stationary readings sit around
  `(+0.10, -9.45, -2.31) m/s²` (`|g| ≈ 9.73 m/s²`, spread `±0.015 m/s²` ≈
  `0.0015 g`), mounted nearly level left–right and tilted ~13.7° in the dash
  plane.
- **No `SensorDirectChannel`:** `flags = 0x00000000` — use standard
  `SensorManager.registerListener(listener, accel, samplingPeriodUs, maxReportLatencyUs, bgHandler)`
  with a dedicated background `HandlerThread` and batching latency
  (`500_000`–`1_000_000 µs`) so callbacks stay off the main thread.
- **Dead / avoided paths:**
  - `TYPE_LINEAR_ACCELERATION` registers as `active` on MTK but delivers zero
    events — remove gravity in software instead.
  - **Never force-stop `com.njda.adapter`** while testing — it wedges the MTK
    sensor HAL flush handshake (`First flush pending`) until reboot.
- **Why earlier `AccelProbe` used ~20% CPU:** it ran at 400 Hz (later 50 Hz) on
  the main thread, allocating `Date`/`String`/`float[]` and writing every raw
  sample to disk. With a background `HandlerThread`, 16–25 Hz, zero-allocation
  primitive math, and in-memory window aggregation (no raw sample log), CPU
  overhead should stay well under **1% of one core** while driving and **0%**
  while parked.

---

## 2. Architecture: low-cost `ImuHub`

Add a lightweight sensor component (`sensors/ImuHub.java`) following the same
lifecycle pattern as `LocationHub` and `CanHub`:

- **Gear-gated registration:** Subscribe to `car.gear` via `EntityBus`.
  Register `TYPE_ACCELEROMETER` only when out of Park (or when a trip / Valet
  session is open); unregister when parked and idle.
- **Zero-alloc per-sample loop (background `HandlerThread`):**
  - Keep a 3-float IIR gravity estimate (`gx = α·gx + (1 - α)·x`, τ ≈ 1.5–2 s)
    and subtract it to get linear acceleration `(lx, ly, lz)`.
  - Project `(lx, ly, lz)` through the calibrated 3×3 vehicle basis into car
    frame: `a_z` (vertical/road), `a_x` (longitudinal accel/brake),
    `a_y` (lateral cornering).
  - Update running window accumulators using primitive fields only (`sumSqZ`,
    `peakAbsZ`, `peakPosX`, `peakNegX`, `peakAbsY`, `sumSqJerk`, `bumpCount`,
    `sampleCount`). Only compute `Math.sqrt()` when finalizing a window or
    crossing a squared-threshold check.
- **No raw sample persistence:** Flush compact summaries only into:
  1. `RoutePoints` (~every 5 s / 25 m while moving) — per-segment roughness and
     peak bump.
  2. `TripSession` — per-trip aggregate scores and event counts at trip close.
  3. `ValetSession` — driving score and harsh-event counts for Valet Mode.

---

## 3. Zero-config auto-calibration (head-unit -> car axes)

Because head-unit tilt varies slightly across trims or installs, derive the car
axes automatically and persist the 3×3 orthonormal basis in `SharedPreferences`:

1. **Vertical (`Z_car`):** Average raw accelerometer vectors whenever
   `speed == 0` (stationary, `|a| ≈ 9.8 m/s²`, low variance) → unit gravity
   vector `ĝ`. Works immediately on the very first drive; **Road Score only
   needs `Z_car`**, so it is accurate from minute one.
2. **Forward (`X_car`):** Collect horizontal linear acceleration samples during
   clean straight-line acceleration/braking events confirmed by CAN speed delta
   (`|Δv/Δt| > 1.2 m/s²`) and steady GPS bearing (or small `SAS_SteerWheelAngle`
   when CAN is active). Align the sign with `Δv > 0` and orthogonalize against
   `Z_car`.
3. **Lateral (`Y_car`):** Cross product `Y_car = Z_car × X_car`.
4. **Fallback before `X_car` locks:** Driver Score still tracks total
   horizontal G (`√(lx² + ly²)`) and jerk; once `X_car` converges (typically
   within the first few minutes of normal driving), braking/launch and
   cornering split cleanly. Allow a "Recalibrate" action in Config if needed.

---

## 4. Scoring definitions

Keep the formulas transparent, deterministic, and easy to tune after real
drives (and fully testable with pure Java unit tests, matching
`TEST-COVERAGE-PLAN.md`).

### Road Score (0–100)

Uses **vertical linear acceleration `a_z` only** while moving (`speed > 5 km/h`):

- **Roughness index (IRI-style proxy):** For each moving window (aligned with
  `trip_point`, ~5 s), compute speed-normalized vertical RMS:
  `roughness = rms(a_z) / max(speed_mps, 5.0)^p` (start with `p = 0.5`, tune on
  known smooth highway vs. cobblestone/pothole drives).
- **Bump / pothole events:** Count distinct vertical peaks `|a_z| > threshold`
  (initial candidate `0.40 g` ≈ `3.9 m/s²`, with a ~1 s refractory window so
  one pothole is not double-counted).
- **Trip Road Score:** Map distance-weighted roughness + bumps-per-km onto
  `0–100` (e.g. `100` = smooth fresh asphalt, `70–85` = typical city street,
  `< 50` = rough/potholed).
- **Confidence gate:** Require a minimum moving distance (e.g. `≥ 0.3 km`) so a
  parking-lot maneuver does not produce a noisy score.

### Driver Score (0–100)

Combines **horizontal IMU (`a_x`, `a_y`)** with **existing CAN signals**:

| Sub-score | Signals | What it rewards |
| --- | --- | --- |
| **Smoothness (jerk)** | Horizontal `Δa/Δt` RMS | Progressive pedal and steering inputs ("water-cup" smoothness) |
| **Braking & launches** | `a_x` peaks & time above soft threshold | Avoiding hard stops (`< -0.35 g`) and abrupt launches (`> +0.35 g`) |
| **Cornering** | `a_y` lateral G peaks | Calm cornering (`< 0.30 g`) without abrupt swerves |
| **EV Regen discipline** | `CanBrake` + `CanPower` / `EnergyIntegrator` | Using regenerative braking to slow down rather than late friction braking (optional weight when CAN/OBD is present; re-normalized cleanly when absent) |

- **Per-trip score** = weighted blend of sub-scores (e.g. 30% smoothness, 25%
  braking/launches, 25% cornering, 20% regen when available).
- **Event counters:** `hard_brakes`, `hard_accels`, `hard_corners`,
  `road_bumps` stored alongside the scores so the UI can explain *why* a trip
  scored 78 instead of showing an opaque number.

---

## 5. Data model (`CarDb` migration)

Extend existing tables with nullable columns (older trips remain `NULL` and
render cleanly without scores):

- **`trip_point`** (per ~5 s / 25 m segment):
  - `road_rms_z REAL` — vertical linear accel RMS (`m/s²`) over the segment
  - `road_peak_z REAL` — peak vertical spike (`m/s²`) in the segment (enables
    pothole pins on the map)
- **`trip`** (frozen at trip finalize):
  - `road_score INTEGER` (0–100, nullable)
  - `driver_score INTEGER` (0–100, nullable)
  - `road_bumps INTEGER`, `hard_brakes INTEGER`, `hard_accels INTEGER`,
    `hard_corners INTEGER`
  - Optional sub-score JSON or compact integer columns
    (`score_smooth`, `score_brake`, `score_corner`, `score_regen`) if we want
    per-trip breakdowns without re-scanning raw points.
- **`valet_session`**:
  - `driver_score INTEGER`, `hard_brakes INTEGER`, `hard_accels INTEGER`,
    `hard_corners INTEGER`, `road_bumps INTEGER` — lets the owner immediately
    see how the car was treated in `ValetDetailActivity`.
- **`daily_stat`** (later phase):
  - Distance-weighted daily `road_score` and `driver_score` for week/month
    trends once `TelemetryRollup` freezes a day.

---

## 6. UI and integration touchpoints

- **`TripRouteActivity`:**
  - Show Road Score + Driver Score badges and short event counts in the right
    stats column (*e.g. "Driver 91 · Smooth · 0 hard brakes"*,
    *"Road 76 · 4 rough spots"*).
  - Add a map coloring toggle on `RouteView` (**Speed** vs. **Road roughness**),
    coloring segments green → amber → red by `road_rms_z` and marking pothole
    peaks.
- **`AllRoutesActivity`:**
  - Optional "Road roughness" overlay across all recorded trips — builds a
    personal map of which streets in your city are smooth vs. terrible.
- **`ValetDetailActivity`:**
  - Surface Driver Score + harsh-event counts right next to max speed and peak
    power.
- **Dashcam synergy (optional later):**
  - High vertical/horizontal shock threshold (`> 1.5–2.0 g`) can trigger
    `DashRecorder.saveCurrentSegmentForEvent()` automatically on impact or
    severe pothole strikes.
- **Home Assistant & LLM synergy:**
  - Publish latest trip Road/Driver scores over MQTT, and expose them to future
    `LLM-INTEGRATION-ROADMAP.md` trip digests.

---

## 7. Phased rollout

1. **Phase 1 — `ImuHub` + calibration + unit tests (no UI yet):**
   - Implement `ImuHub` at 16 Hz on a background `HandlerThread`, gear-gated,
     with auto-calibration (`Z_car` + `X_car`/`Y_car`) and a small diagnostics
     readout / debug verification on the car.
   - Verify real-world CPU impact (`top` / `dumpsys cpuinfo`) and calibrate
     thresholds on 2–3 real drives (smooth highway, rough city streets, one
     deliberate hard brake + corner).
2. **Phase 2 — `CarDb` persistence + Trip & Valet scores:**
   - Add nullable columns to `trip_point`, `trip`, and `valet_session`.
   - Wire `ImuHub` window snapshots into `RoutePoints`, `TripSession`, and
     `ValetSession`.
   - Display Road Score & Driver Score in `TripRouteActivity` and
     `ValetDetailActivity`.
3. **Phase 3 — Roughness map layer & period trends:**
   - Add Speed / Road roughness mode switch to `RouteView` (`TripRouteActivity`
     and `AllRoutesActivity`).
   - Roll distance-weighted averages into `daily_stat` and `DailyStatsView`.
4. **Phase 4 — Optional extras:**
   - Severe-impact dashcam clip lock trigger.
   - Evaluate whether `SIGNIFICANT_MOTION` / `MOTION_DETECT` actually fire on
     the MTK HAL (could serve as an ultra-low-power parked bump detector for
     `PARKED-MONITORING-PLAN.md`) and whether `PRESSURE` (barometer) provides a
     cleaner relative elevation delta than noisy GPS altitude.

---

## Open questions

- **16 Hz vs. 25 Hz:** Start at 16 Hz (free ride on `SX11SensorManager`) and
  check whether short pothole impulses alias too much before bumping to 25 Hz.
- **Speed normalization exponent (`p`):** Needs 1–2 logged drives over the same
  rough street at 20 km/h vs. 50 km/h to pick a normalization curve where a road
  gets roughly the same Road Score regardless of speed.
- **Opt-in toggle vs. always-on:** Because it uses no location/network/cloud
  and near-zero CPU when driving (0% parked), default-on with a Config toggle
  to disable scoring/IMU sampling seems cleanest.
- **Bonus sensor check (`PRESSURE` & `SIGNIFICANT_MOTION`):** Worth a quick
  live test on the car — if `PRESSURE` is real hardware (not a dead stub like
  `LINEARACCEL`), it could also dramatically clean up `TripSession`'s
  `ascent_m` / `descent_m` without GPS altitude jitter.
