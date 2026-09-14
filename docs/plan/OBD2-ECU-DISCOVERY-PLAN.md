# Read-only OBD2 ECU discovery plan for Geometry E / IHU629G

Status: proposed  
Scope: discover **read-only** diagnostics and signals that are absent from the
head-unit VHAL, then promote only reproducible, understood signals into Drive
Assist. This is not a plan to code, configure, actuate, unlock, or reprogram
any ECU.

## Goal and non-goals

The objective is to learn whether the diagnostic connector and gateway expose
useful live data from the vehicle-control, body, charging, parking, climate, or
instrument systems. Candidates include battery health, charging limits/status,
12 V system state, TPMS, parking sensors, doors/locks, lights, wipers, and
possibly rear-seat/belt information.

The project must not send diagnostic writes or state-changing services. In
particular, it must not use UDS write-by-identifier (`0x2E`), ECU reset,
security access, session changes, routine control, actuator tests, coding,
memory access, clear-DTC, programming, or any service aimed at restraint,
braking, steering, propulsion, or immobilizer ECUs. Do not use unknown
manufacturer tool commands merely because they are visible in a capture.

All vehicle testing is parked, attended, and uses a read-only hardware/software
configuration. Disconnect the adapter when the capture is complete; it must not
become an unattended permanent network bridge. NHTSA treats third-party OBD
devices as a safety/cybersecurity risk, so least privilege is a requirement,
not an optional hardening step. [NHTSA guidance](https://www.nhtsa.gov/document/cybersecurity-best-practices-2020-update)

## What is already known

| Confidence | Request / response CAN ID | ECU | Evidence | Use now |
|---|---:|---|---|---|
| Confirmed | `0x7E2` / response observed by the existing reader | BMS | The app successfully reads UDS DIDs `4B36`, `4B21`, `4B22`, `4B3C`, and `DF01`; see [field catalog](../field-catalog.md#10-direct-obd2-uds-parameter-identifiers-bms-ecu-0x7e2). | Keep as the reference transport and baseline. |
| Candidate only | `0x7E0` / `0x7E8` | Typical first diagnostic ECU; public Geely-family listings label it ECM | This is a common 11-bit diagnostic pairing, not Geometry E confirmation. A public Geely-family list also labels `7E0` / `7E8` ECM, but cites a Geometry C BMS separately. [Listing](https://carcoding.app/ecu/marka/geely/) | Identity-read only. Do not assume an ICE/ECM exists on this BEV. |
| Candidate only | `0x7E1`--`0x7E7` / `0x7E9`--`0x7EF` | Other physically addressed diagnostic ECUs | This is the usual 11-bit OBD/UDS address range; the actual Geometry E gateway may filter it or map modules differently. [ISO-TP/UDS addressing overview](https://uds.readthedocs.io/en/v2.0.0/pages/knowledge_base/can.html) | Probe only with the allowlisted identity/read sequence below, slowly and one address at a time. |
| Unknown | Any raw broadcast frame | Any ECU reachable beyond the gateway | A passive trace may be empty or may contain only diagnostic-side traffic. The existing ELM family supports monitor-all, but that does not bypass a gateway. [ELM327 monitoring reference](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327L_Data_Sheet.pdf) | Capture; never infer that silence means the feature is absent from the car. |

The existing app fixes the BMS transport at 500 kbit/s, 11-bit CAN and `0x7E2`
(`Obd2Reader.java`). Retain those settings as the first baseline; do not vary
bitrate, frame format, or connector wiring on a production vehicle until a
separate bench-validated tool and model-specific documentation justify it.

## Why there may be more data

The available Geometry E repair-manual index explicitly lists a diagnostic
interface, gateway, HBCAN, IFCAN, CFCAN, CSCAN, and LIN networks, as well as
BMS, vehicle control, motor, high-voltage distribution, AC/DC charging, BCM,
TPMS, parking assistance, airbag, TBOX, and infotainment systems. This proves
the vehicle has separate modules and networks; it does **not** prove they are
diagnostically reachable from the OBD port or disclose their CAN IDs.
[Geometry E manual index](https://en.17vin.com/automotive_repair_manual_wiring_diagrams/Geely-Geometry/4988.html)

The highest-value and lowest-risk targets are therefore non-safety body and EV
telemetry modules that are likely to possess diagnostics:

1. **BMS / charging / vehicle control:** pack health, cell statistics if
   exposed, isolation/thermal state, AC/DC charge state, onboard-charger state,
   and 12 V charging. Read only; never alter charge configuration through UDS.
2. **BCM / instrument cluster / parking / TPMS:** door/lock/lamp/wiper state,
   tyre warnings, parking distance/status, and possibly rear-seat/buckle state.
   A BCM response is plausible, but it is not evidence that the rear seat signal
   is bridged to diagnostics.
3. **HVAC / heat management:** heat-pump, compressor, coolant, and defrost
   telemetry that VHAL may omit.
4. **TBOX and infotainment:** inventory/version and *passively observed*
   status only. Do not access accounts, provisioning, cellular controls, or
   cloud credentials.

**Excluded:** airbag/SRS/restraint, ABS/ESC/one-box braking, EPS, ADAS cameras
and radar, immobilizer/PEPS, and high-voltage service operations. The Geometry
E manual confirms that safety-belt and airbag systems are distinct diagnostic
systems; that makes them a reason for extra exclusion, not a target. The
existing head-unit investigation also found rear-seat/buckle signals bypass the
infotainment path ([field history](../field-history.md#5-seat--occupant-sensor-harness-isolation)).

## Phased verification

### Phase 0 — establish a safe, reproducible baseline

- Record vehicle variant, firmware versions, VIN only in a local untracked test
  record, adapter chipset/firmware, Bluetooth transport, date, and the car's
  parked/ready/charging state. Do not place identifying data in Git or logs.
- Use an adapter known to report headers and ISO-TP multi-frame responses. The
  current ELM327-style reader is adequate for the already-proven BMS DIDs but
  may drop high-rate monitor traffic; verify buffering before treating a capture
  as complete.
- Verify the existing five BMS reads first. Their values must agree with VHAL/
  OEM UI within documented rounding and timing tolerance. If this fails, stop:
  transport quality, not new ECU discovery, is the problem.
- Store captures outside the app's normal telemetry DB. A capture is sensitive:
  it can contain identifiers and driving/location-related state.

Exit criterion: repeatable BMS read log, one clean baseline capture, and no
unexplained warning lamps/DTCs. Any new warning, network fault, or unexpected
vehicle behavior ends testing until inspected by qualified service personnel.

### Phase 1 — passive observation and head-unit correlation

- Run passive capture only while parked and change **one observable condition at
  a time**: each door, lock, hazard/indicator, exterior lamp, wiper, HVAC mode,
  charge cable, charge state, reverse/parking indication, TPMS display state,
  and rear-seat occupancy/buckle if safe to observe.
- Capture a fixed baseline, a transition window, and a return-to-baseline window
  for every trial. Repeat each trial at least three times in a randomized order.
- In parallel, use the existing head-unit read-only tooling:
  `CONFIGCHECK`, typed `READPROP`/`READGENERIC`, and `PROPLIST`, then compare
  the timestamped VHAL result with the OBD capture. The methodology and area
  handling are documented in [field catalog](../field-catalog.md#1-probing-methodology--verification-framework).
- Build a local signal worksheet: frame ID or DID, byte/bit, observed values,
  trial IDs, VHAL/OEM UI comparison, confidence, and whether the signal is
  merely correlated or causally understood.

Exit criterion: a candidate must change consistently for one physical state and
remain stable through negative controls. Never promote a signal based on one
drive or one byte-pattern match.

### Phase 2 — conservative ECU inventory

Implement a small, separate **read-only discovery tool**, not changes to the
production polling loop. Its allowlist contains only:

- passive monitoring; and
- an ECU identity/read-data request already verified by a commercial diagnostic
tool or official manual for that exact module.

Start with known BMS `0x7E2`; then test one physical candidate request address
at a time from `0x7E0` through `0x7E7`, recording positive response IDs and
negative responses. Do not infer the module from its address. If an ECU permits
a non-invasive identity response, record the raw bytes, ECU address, response
address, timestamp, and exact request in an encrypted local research archive.
ReadDataByIdentifier (`0x22`) is the relevant read service, but DID payload
meaning is OEM-defined; a successful response is **not** a decoded feature.
[UDS DID reference](https://uds.readthedocs.io/en/latest/pages/knowledge_base/did.html)

Rate limits:

- One request in flight; no broadcast or address sweep while moving.
- Back off on timeout/negative response; stop an ECU after a small, pre-set
  failure budget per session.
- Do not enumerate arbitrary DIDs. Add a DID only after it is sourced from an
  OEM tool capture, a Geometry E manual, or a repeatable passive observation.

Exit criterion: an ECU inventory with evidence-based names or explicit
`unknown`, no safety-module probes, and a captured response for each claimed
address.

### Phase 3 — decode only evidence-backed DIDs

For every source-backed or captured read-only DID:

1. Record raw response length and bytes before assigning a type, scale, or
   byte order.
2. Run a directed-difference experiment with a ground truth independent of the
   candidate signal (OEM display, measured temperature, charge station display,
   or physical state).
3. Test boundary conditions and stale/invalid response handling.
4. Cross-check VHAL, OBD, and OEM UI. Prefer the already verified VHAL value
   where they disagree until the difference is explained.
5. Document negative results, access restrictions, and gateway filtering. These
   prevent the next investigation from repeating unsafe or fruitless probes.

For rear seats specifically, first look for a **BCM or cluster diagnostic
response already present while toggling rear buckle/occupancy states**. If none
exists, record “not exposed through the diagnostic gateway” rather than trying
to reach the restraint network. That is a useful result.

### Phase 4 — productionize a verified feature

Only a candidate with reproducible semantics, a stated freshness contract, and
no safety implications can enter Drive Assist.

- Add it to a typed `Obd2Reader` data model with an explicit `null`/unavailable
  state; do not silently reuse a stale value.
- Keep the request in a fixed allowlist with a per-ECU rate limit and feature
  toggle. The production app must contain no generic “send hex” interface.
- Log raw data only in opt-in diagnostics with rotation/redaction, not in MQTT
  by default. Publish a documented, scaled value only after validation.
- Add parser fixtures containing positive, multi-frame, negative, timeout, and
  malformed responses. Test Bluetooth reconnect, gateway silence, and VHAL/OBD
  disagreement.
- Update `field-catalog.md`, `VEHICLE-INTEGRATION.md`, and the driver privacy
  documentation with source, units, accuracy, and limitations.

## Decision gates and expected outcomes

| Finding | Decision |
|---|---|
| Only `0x7E2` responds | Keep BMS-only OBD2. Improve VHAL/OEM-service discovery instead of broadening OBD access. |
| Additional ECU responds, but no sourced DID | Retain a private inventory entry; do not scan its DID space. Seek an OEM-tool capture/manual first. |
| Read-only DID correlates across trials | Add a provisional research decoder; do not surface it to drivers until independent validation passes. |
| Rear-seat state is absent from both passive capture and permitted BCM/cluster diagnostics | Conclude it is not available through supported head-unit/OBD routes. Do not target SRS or physical bus tapping. |
| Any fault, warning lamp, unstable adapter, or unexpected actuation | Stop testing, remove the adapter, preserve the log, and have the vehicle assessed before resuming. |

## Deliverables

1. A privacy-safe ECU inventory: exact request/response IDs, confidence,
   evidence, and negative results.
2. Versioned parser fixtures and a no-write transport policy test.
3. A field-catalog entry for each verified value, including scale, bounds,
   freshness, and independent validation evidence.
4. A short feature proposal per value, showing why it is useful to the driver
   and why VHAL cannot already provide it.
