# Car Diary & 12V Health Preview

Status: active design plan (supersedes the earlier v9 maintenance-log-only draft).

## Context

Drive Assist already records driving sessions (`trip`, `daily_stat`), charging
sessions (`charge_session`), HV battery SoH (`battery_health_reading`), soft-tire
warnings (`car.tire_warn_*`), IMU road roughness (`ImuHub`), and dashcam clips
(`Clips`). What is missing is a **Car Diary** — one place that unifies:

1. **Automatic car milestones** Drive Assist already knows (last 100% LFP
   calibration charge, HV SoH shifts, iTPMS warnings, 12V anomalies).
2. **Routine care & consumables** with due tracking (tire pressure checks,
   rotations, replacements, wipers, coolant, cabin filter, brake fluid/pads,
   reduction gear oil, 12V battery, bundled dealer service visits).
3. **Incidents & noises/symptoms** — low-friction 1-tap capture with automatic
   telemetry snapshots (odometer, temp, speed, HVAC, GPS, IMU roughness),
   `+1 happened again` recurrence tracking, and optional dashcam clip protection.
4. **Proactive 12V battery monitoring** to preview battery aging, parasitic
   drain, or DC-DC faults weeks before a dead 12V strands the car.

## Hardware Check Results — 12V Signals (EX2 / IHU629G, 2026-11-23)

Live read-only probe on the vehicle + analysis of saved `canrec` captures
(`/data/local/tmp/drive_assist/canrec/can-explore-*.tsv`):

| Source | Signal / ID | Observed on EX2 | Meaning & Caveats |
|---|---|---|---|
| **VHAL (no dongle, no CAN)** | `557875206` (`0x21408006`, `BODY_BATTERY_U_BATT_INFO`) | `128` parked (Park, gear 4); `139`–`140` previously in READY | **12V rail voltage in 0.1 V** (`raw × 0.1 V` → `12.8 V` parked, `13.9–14.0 V` READY). Works for **every user without OBD2 or CAN**. *(Note: `docs/field-catalog.md` currently mislabels `557875206` as "Intelligent High Beam status echo: 139/140" from an earlier READY-state toggle diff — confirm once against a multimeter / OBD2 `ATRV` across a Park↔READY transition and fix `field-catalog.md`.)* |
| **VHAL (no dongle, no CAN)** | `557883432` (`0x2140a028`, `VPOWERINFO_BATTERY_LOW`), `557887632` (`0x2140b090`, `PEPS_WARNING_BATTERY_SAVING`), `557875219` (`0x21408013`, `CAR_VOLTAGE_STATUS`), `557850671` (`0x2140202f`, `BODY_POWER_SAVE`) | All `0` normally | Factory low-12V / power-saving flags directly on VHAL. |
| **VHAL (dead / unused)** | `557885209` (`VehBattU`), `557875207` (`BODY_BATTERY_BCN_LEVEL`), `557875208` (`BODY_BATTERY_REMAINING_INFO`), `557875229` (`BCM_T_BATT_DATA`) | All `0` parked | Unpopulated or dormant while parked; re-check in READY before relying on them. |
| **VHAL (not 12V)** | `559982316` (named `AUX_12V_VOLTAGE` in `TeleProbe.java`) | — | Already proved in `docs/field-history.md` to be `TRIP_ED_OTHER_ENERGY_FLOW` (%), not voltage. |
| **CAN (`roadcast`, frame `0x176`)** | Byte 0 (0.1 V), Byte 1 (A), Byte 3 (temp), Byte 4 LSB (`0/1` DC-DC active), Byte 7 (`140` = 14.0 V target) | DC-DC ON (`b4=1`, 2,106 frames): `14.2 V`, `5–40 A`, target `14.0 V`. DC-DC OFF (`b4=0`, 35 frames): `14.0 → 13.1 → 12.8 → 12.3 V`, `0 A` | **Rich DC-DC + 12V telemetry with no OBD2 dongle!** Shows exact DC-DC ON/OFF transitions, 40 A→20 A→5 A inrush/absorption taper, and resting voltage decay. *(Caveats: `dbc.json` defines `IPU_DcDcActvd` at byte 4 MSB bit 31 which stays `0`; the live active flag is byte 4 LSB. Also saw brief single-frame glitches like `5.6 V` / `11.4 V` when `b4=0` that recovered 2 s later — **must debounce / require ≥ 2 consecutive samples**.)* |
| **CAN (`roadcast`, frame `0x315`)** | `VCU_DCDCPwrAct` (byte 4, `0..8`) | Correlates monotonically with `0x176` `V × A` (`1`≈150 W, `4`≈360 W, `7`≈490 W) | Confirms `0x176` scaling; useful cross-check for DC-DC load. |
| **CAN (`roadcast`, frame `0x272`)** | `BCM_Warning_LOW_BATTU`, `PEPS_Warning_BatterySaving` | `0` in normal captures | Factory CAN low-12V warning flags. |
| **Android sysfs / `dumpsys battery`** | `/sys/class/power_supply/*`, `hwmon` | Empty / not exposed | No Android battery power_supply node on IHU629G. |
| **OBD2 (`Obd2Reader`)** | ELM327 `ATRV` (pin 16 ADC) | Available whenever Bluetooth OBD2 dongle is connected | Optional verification & calibration reference against `557875206` / `0x176`. |

### Architectural Takeaway for 12V
Because **`557875206` (`BODY_BATTERY_U_BATT_INFO`) is native VHAL**, 12V monitoring does **not** require an OBD2 dongle or CAN daemon to work out of the box:
- **Baseline (all cars):** Poll `557875206` (`car.aux_12v_v`, 0.1 V quantized, every 10–15 s) + low-voltage warning flags on `CarActor` (hub rule 7).
- **Enhanced (CAN on):** Also read `0x176` (`can.dcdc_active`, `can.dcdc_a`, `can.aux_12v_v`) via `CanHub` so we know unambiguously whether DC-DC is ON vs. OFF and can track inrush/absorption current.
- **Optional (OBD2 on):** `ATRV` on `ObdHub` (`obd.aux_12v_v`) for cross-check/calibration.
- Only introduce a small derived combiner (like `EnergyHub`) if multiple sources need unified arbitration + a shared `aux12v.health` status for Diary, MQTT/HA, and Parked Monitoring.

---

## 1. Three-Stream Diary Model

### A. Automatic Milestones (Zero Effort — Computed from Existing Tables & Hub)
- **Last 100% Charge (LFP Calibration):** Latest `charge_session` with `soc_end >= 100` (fallback `daily_stat.max_battery_pct >= 100`). Shows days ago, km driven since, AC vs. DC icon/color (per `docs/STYLE-GUIDE.md`), and whether current tapered at 100% (true cell balancing) vs. unplugged immediately. Gentle nudge past 30 days.
- **HV Battery SoH Shifts:** Surfaced from `battery_health_reading` (`BatteryHealth`) whenever SoH steps by ≥ 0.2%.
- **Soft-Tire (iTPMS) Warnings:** Auto-logged when `car.tire_warn` flips `0 → 1` (`TireWarning`), recording corner(s), odometer, outside temp, GPS, and days since last tire pressure check.
- **12V Anomalies:** Auto-logged when 12V health transitions to `WATCH`, `WEAK`, or `FAULT`, or when a factory low-12V flag fires.

### B. Two-Level Category Hierarchy (Care, Cosmetic & Notes)

Instead of a long flat list of chips, the **Add entry** dialog uses a single
**`+ Add entry`** button (placed on the **left** beside the dock rail, closest
to the driver) and a 2-level **Group → Item** selector. Every entry supports an
optional **Cost** (using `Prefs.getCurrency(ctx)`), enabling total ownership
cost tracking alongside charging costs.

Fixed default due intervals in code (editable intervals deferred until needed).
Whichever limit (km or months) hits first triggers: **Due soon** = within 10%
of limit; **Overdue** = past limit; untracked types never warn.

| Group | Subcategory (`category`) | km interval | time interval | Optional structured metadata (`meta_json`) |
|---|---|---|---|---|
| **Tires** | `TIRE_PRESSURE` | 1,500 km | 1 mo | `psi: [fl, fr, rl, rr]` or uniform, optional `cold: true/false` inferred from park/trip history (+ nudge on ≥ 10°C cold snap) |
| **Tires** | `TIRE_ROTATION` | 10,000 km | — | `pattern` (`front_rear` / `cross`), `tread_mm` |
| **Tires** | `TIRE_BALANCING` | 10,000 km | — | — |
| **Tires** | `TIRE_ALIGNMENT` | 10,000 km | 12 mo | — |
| **Tires** | `TIRES` (replaced) | 40,000 km | 60 mo | `brand`, `axle` (`all` / `front` / `rear`) |
| **Service** | `SERVICE` (scheduled revision) | 20,000 km | 12 mo | Official Geely Brasil EX2 cycle (20k/40k/60k/80k); `bundled: [...]` resets clocks for bundled items |
| **Service** | `CABIN_FILTER` | 20,000 km | 12 mo | `part_no` (Geely EX2 20k/12m) |
| **Service** | `BRAKE_FLUID` | 40,000 km | 24 mo | DOT 4 (Geely EX2 40k/24m) |
| **Service** | `BRAKE_PADS` | — | — | Inspected at every service; wear-based replacement |
| **Service** | `COOLANT` | 80,000 km | 48 mo | HV battery & motor thermal loop (Geely EX2 80k/48m) |
| **Service** | `GEAR_OIL` | 80,000 km | 48 mo | Reduction gearbox oil (Geely EX2 80k/48m) |
| **Electrical & Body** | `BATTERY_12V` | — | 48 mo | `brand`, `ah` — **resets 12V health baseline & marks chart divider** |
| **Electrical & Body** | `WIPERS` | — | 12 mo | `which` (`front` / `rear` / `both`), `size_or_brand` |
| **Electrical & Body** | `LIGHTS_FUSES` | — | — | — |
| **Cosmetic** | `WASH_DETAIL` | — | — | Wash / detailing |
| **Cosmetic** | `WASHER_FLUID` | — | — | Windshield wiper fluid |
| **Cosmetic** | `POLISHING` | — | — | Paint correction / polishing |
| **Cosmetic** | `WAX_CERAMIC` | — | — | Wax / ceramic coating |
| **Cosmetic** | `PAINT_DENT` | — | — | Scratch / dent / glass repair |
| **Notes** | `NOISE` | — | — | Open/resolved diagnostic symptom + `+1` recurrence |
| **Notes** | `INCIDENT` | — | — | Pothole, curb, scrape, warning; IMU prefill + held dashcam clip |
| **Notes** | `OTHER` | — | — | Free text, never warns |

### C. Noises / Symptoms & IMU-Prefilled Incidents
- **Noises (`kind = 'NOISE'`):**
  - Captures a live context snapshot: `odo_km`, `outside_temp_c`, `lat/lon`, recent 30 s peak/avg `speed_kmh`, `hvac_fan`, `ac_on`, `drive_mode`, `regen_mode`, and IMU roughness from `ImuHub` (when active).
  - Quick tag chips (*Where*: `Front-L`, `Front-R`, `Rear`, `Cabin`, `Brakes`, `Suspension`, `HVAC`; *When*: `Bumps`, `Braking`, `Turning`, `High speed`, `Cold`, `A/C on`).
  - **`+1 Happened again`** appends a child occurrence (`parent_id`) so Drive Assist computes a pattern summary (e.g. *"5× since 14,200 km · 4/5 on rough roads · avg 16°C"*) and feeds an **Open Dealer Checklist**.
  - **Resolve** closes the symptom (optionally linking the `SERVICE` entry that fixed it).
- **Incidents (`kind = 'INCIDENT'`) with Accelerometer (`ImuHub`) Prefill + 5-Second Clip:**
  - Rather than interrupting the driver or auto-logging every bump, `ImuHub` keeps a small ring buffer (max 3) of notable jolts from the last 24 h (`ts_ms`, calibrated car-frame `peakZ`/`peakX`/`peakY`, `speed_kmh`, `lat/lon`, `odo_km`, thresholds tuned from real `imu-summary.tsv` data).
  - While driving, a detected jolt simply puts a quiet **iOS-style notification dot** on the Diary dock icon (also used for overdue care / 12V Watch). No popup while moving.
  - Once **parked**, opening Diary shows a calm dismissible pending-review row (*"Hard jolt · 14 min ago · 52 km/h — Log incident / Dismiss"*).
  - **5-second clip (instead of locking a 600 MB segment):** Because `DashRecorder` uses `IFRAME_SEC = 1`, Drive Assist can extract a lossless **5-second clip** (`t − 3 s` to `t + 2 s`, ~3–5 MB) from the closed dashcam `.mp4` using Android's `MediaExtractor` + `MediaMuxer` (no re-encoding, < 0.5 s) into `files/diary_clips/<uuid>.mp4` — small enough to play instantly in Diary and download via Phone Companion, and automatically deleted if dismissed or expired after 24 h.

---

## 2. 12V Predictive Health Rules

Raw voltage while driving (~14.2 V) hides a dying battery because the DC-DC converter holds the bus up. Failure prediction works by classifying every sample and tracking **resting / wake behavior** over weeks:

### Sample Classification (`aux_12v_reading.phase`)
1. **`RESTING`** — Parked, `dcdc_active == 0` (or VHAL voltage `< 13.3 V` with gear `P` and not charging), debounced (≥ 2 consecutive readings within 0.2 V to reject single-frame CAN spikes like `5.6 V`).
2. **`WAKE_SAG`** — Minimum debounced voltage in the first 30 s after waking from ≥ 4 h sleep (captures internal resistance under boot load).
3. **`DCDC_CHARGING`** — `dcdc_active == 1` or READY/charging (`>= 13.3 V`). Monitors DC-DC health and absorption current (`0x176` byte 1).
4. **`WARN`** — Any factory low-12V flag (`VPOWERINFO_BATTERY_LOW`, `PEPS_WARNING_BATTERY_SAVING`, `BCM_Warning_LOW_BATTU`).

### Predictive Health States (`GOOD` / `WATCH` / `WEAK` / `FAULT`)
- **Slow Aging (Baseline Drift):** Compare last 5 morning `RESTING` wakes (≥ 6 h parked) against the post-replacement (or first-30-day) baseline at comparable outside temperature:
  - `WATCH`: 3 consecutive wakes `< 12.25 V`, OR baseline dropped `≥ 0.25 V` in same temp bucket.
  - `WEAK`: 2+ wakes `< 12.05 V`, OR `WAKE_SAG < 11.6 V`.
- **Parasitic Drain vs. Aging:** Track `ΔV / parked_hours` between post-drive settled resting voltage and next wake. A sudden jump in overnight drop rate (e.g. right after enabling Parked Monitoring or Wi-Fi wake lock) flags *"Possible parasitic drain"* separately from gradual battery aging.
- **DC-DC Fault (`FAULT`):** In READY for > 2 min with voltage `< 13.2 V` (not charging) or `> 14.9 V` (overcharging), or any factory low-12V warning asserted.
- **Safety Tie-In with Parked Monitoring:** When `PARKED-MONITORING-PLAN.md` safety gates are active, `WEAK` / `FAULT` or resting voltage `< 12.1 V` disarms parked monitoring immediately and writes an `AUTO` diary note.

---

## 3. Database Schema (`CarDb` v35 → v36 + `DbMerge` / `AppBackup`)

Bump `CarDb.VERSION` `35 → 36` and add two permanent tables (never pruned like `telemetry_sample`):

```sql
CREATE TABLE diary_event (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uuid TEXT NOT NULL UNIQUE,          -- idempotent merge key for AppBackup / DbMerge
  ts_ms INTEGER NOT NULL,
  kind TEXT NOT NULL,                 -- 'CARE', 'NOISE', 'INCIDENT', 'AUTO'
  category TEXT NOT NULL,             -- TIRE_PRESSURE, TIRE_ROTATION, TIRES, WIPERS, ...
  status TEXT NOT NULL DEFAULT 'DONE',-- 'DONE', 'OPEN', 'RESOLVED'
  odo_km REAL,
  outside_temp_c REAL,
  lat REAL, lon REAL,
  cost REAL,
  title TEXT,
  note TEXT,
  tags TEXT,                          -- comma-separated quick tags
  clip_name TEXT,                     -- optional held clip filename in Clips.KEEP
  parent_id INTEGER,                  -- recurrence or linked repair
  meta_json TEXT                      -- PSI array, bundled items, telemetry snapshot
);
CREATE INDEX idx_diary_ts ON diary_event(ts_ms);
CREATE INDEX idx_diary_cat_ts ON diary_event(category, ts_ms);

CREATE TABLE aux_12v_reading (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts_ms INTEGER NOT NULL,
  odo_km REAL,
  voltage_v REAL,
  phase TEXT NOT NULL,                -- 'WAKE_SAG', 'RESTING', 'DCDC_CHARGING', 'WARN'
  dcdc_active INTEGER,                -- 0/1/NULL
  dcdc_amps REAL,                     -- from CAN 0x176 byte 1, nullable
  outside_temp_c REAL,
  parked_hours REAL,
  source TEXT NOT NULL                -- 'vhal', 'can', 'obd2_atrv'
);
CREATE INDEX idx_aux12v_ts ON aux_12v_reading(ts_ms);
```

- **Storage footprint:** `aux_12v_reading` writes only on wake, DC-DC state edge, anomaly, or once/day (~3–6 rows/day, < 2,000 rows/year).
- **Backup & Restore:** Register both tables in `DbMerge` (deduping `diary_event` by `uuid`, `aux_12v_reading` by `ts_ms`) so encrypted `.dabackup` restores preserve multi-year ownership history cleanly.

---

## 4. UI & Ecosystem Integration

### Car Display (1920×1080 @ 1.0 Density, following `docs/STYLE-GUIDE.md`)
- **Placement:** Either a dock tab alongside Driving Stats and Charging Stats (`Drive · Charge · Diary`), or `SEC_DIARY` (`16`, appended per `TelemetryActivity`'s never-renumber rule) under CAR nav — **recommended: dock tab** so it sits beside Driving & Charging as a first-class ownership view, with a small badge dot when overdue or 12V is in `WATCH`/`WEAK`.
- **Styling rules:** All numbers + units via `Style.valueWithUnit` / `Style.moneyWithUnit` (`Style.UNIT_SCALE_HERO` on top cards, `Style.UNIT_SCALE_ROW` on timeline rows), `Style.CARD_GAP_DP` (16) and `Style.COLUMN_GAP_DP` (24), ≥ 44 dp touch targets, Park-gated modals for full editing.

### Phone Companion (`PhonePages` / `PhoneServer`)
- `/diary` — Browse timeline, add/edit long notes, costs, invoice numbers, and 4-corner PSI using the phone keyboard (scan the existing QR code).
- `/diary/dealer` — Printable **Workshop Handoff Sheet** listing open noises (with auto-detected speed/temp/road patterns) + due care items.
- `/diary.csv` — Full service history export (also included in USB export).

### Home Assistant / MQTT (`MqttReporter`)
- Read-only discovery sensors: `days_since_100pct`, `next_care_due`, `open_diary_issues`, `aux_12v_voltage`, `aux_12v_wake_voltage`, `aux_12v_health`.

---

## 5. Phased Implementation Plan

### Phase 1 — Car Diary Core + Auto-100% + Noises/Incidents
1. `CarDb` v36 migration (`diary_event`) + `DbMerge` support + unit tests.
2. `state/CarDiary.java` — CRUD, due-status calculation, auto-computed `last100PctCharge()` (from `charge_session`/`daily_stat`), auto-linked `TireWarning` listener, noise recurrence summary.
3. Car UI (`DiaryView` / `DiaryActivity` or `TelemetryActivity` section) + `DiaryEventDialog` (1-tap quick chips + Park-gated full form + *"Hold last dashcam clip"* via `Clips`).
4. Locales (`values/`, `values-pt/`, `values-es/`, `values-th/`).

### Phase 2 — 12V Monitoring & Predictive Health
1. Confirm `557875206` (`BODY_BATTERY_U_BATT_INFO`) scaling across a live Park↔READY transition (and update `docs/field-catalog.md` line 202).
2. Add `car.aux_12v_v` + low-volt warning keys on `CarActor` (and `0x176` `can.dcdc_active` / `can.dcdc_a` in `CanHub` when CAN is live; optional `ATRV` in `ObdHub`). Update `docs/HUB-DATA.md`.
3. `sensors/Aux12vHealth.java` + `aux_12v_reading` table — debounced sample classifier, wake-up baseline tracker, parasitic-drain vs. aging rules, auto-diary logging on state change, unit tests with synthetic aging/drain curves.
4. 12V Health card + 30/90-day wake-voltage trend chart in Car Diary (with vertical marker on `BATTERY_12V` replacement events).

### Phase 3 — Phone Companion & Home Assistant
1. Phone Companion `/diary`, `/diary/dealer`, `/diary.csv` routes in `PhoneServer` / `PhonePages`.
2. MQTT auto-discovery sensors in `MqttReporter` + docs (`docs/HOME-ASSISTANT.md`, `docs/DRIVER-GUIDE.md`).
3. Hook `Aux12vHealth` into Parked Monitoring's safety cutoff.
