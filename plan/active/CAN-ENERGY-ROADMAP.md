# Exact energy without a dongle (roadcastd)

Status: phases 1-4 built on `feat/can-energy` (2026-11-14), off by default.
Phase 0 items that need a drive or a night are still open (see below).

## Goal

Get pack voltage × current, drive power, climate power and charger input on
every EX2, with no OBD2 dongle, and keep ADB closed except for a few seconds.

Today, without a dongle, driving energy is a SoC-delta estimate (1 % ≈ 0.4
kWh) and AC charge energy is wall-side. This would make both exact.

## How it works (borrowed from Capy, Apache-2.0)

`roadcastd` (github.com/Timoteohss/roadcast) is a small C daemon. It reads the
vehicle HAL's memory, where the HAL keeps the last CAN frame per ID in globals
named `MMI_Rx_<id>`, finds them by the HAL binary's own symbol table, samples
8 bytes per ID with `process_vm_readv`, decodes them with a signal table, and
serves the values on the abstract socket `@roadcast`. It only reads.

It needs root in the `su` domain, which only adbd gives. Capy keeps ADB on
forever to start and watch it. We don't need to.

## Proven on the owner's car (2026-11-14, Enforcing)

| Question | Result |
|---|---|
| Does a root process survive ADB being turned off? | Yes, if it moves itself out of adbd's cgroup first (`echo $$ > /acct/cgroup.procs`). One left in `/acct/uid_0/pid_<adbd>` is killed. |
| Does our HAL have the labels? | Yes, 101 `MMI_Rx_*` in `/vendor/bin/hw/android.hardware.automotive.vehicle@2.0-service`, including 178, 17A, 221, 250, 315, 3A2. |
| Can `su` read the HAL's memory under Enforcing? | Yes (`xxd -s <base> /proc/<hal>/mem` returned the ELF header). |
| Does local adbd need a key? | No. A brand-new key got a root shell with no prompt (`ro.adb.secure=1`, yet no auth). |
| ABI | arm64-v8a, API 28. |

## Design

1. **Binary.** Vendor roadcast's source under `third_party/roadcast/` (keep
   LICENSE and NOTICE), build it for arm64 with the NDK in CI, and ship it as
   an asset of **modehelper**. We don't download binaries: a root daemon must
   be built from reviewed source.
2. **Launch, in modehelper.**
   - Add a minimal ADB client over TCP to 127.0.0.1:5555 (CNXN, then
     `shell,v2,raw:`), as in Capy's `LocalAdbShell.kt`.
   - Copy the binary to `/data/local/tmp/`, check its SHA-256, and start it
     with `setsid`. Its first act is to move itself to `/acct/cgroup.procs`
     (patch the daemon, or wrap it in a shell).
   - **At boot:** use the 5-minute ADB window that already exists, so there is
     no extra exposure.
   - **Watchdog** (on the existing 4 s poll, checked about every minute): if
     the socket is gone and ADB is closed, open ADB, start the daemon, and
     close ADB again within seconds. Log every opening.
3. **Data path.** Phase 0 decides who connects:
   - If an app uid can `connectto` the `su`-domain socket, drivemem reads it
     directly.
   - If not, modehelper reads it and relays the values to drivemem (broadcast
     or a bound service).
   - ~~Patch the daemon to allow only our uids~~. Decided 2026-11-14: **open
     to every app**, so apps share one reader instead of fighting over the
     car. Capy is unmaintained, so no protocol to agree on. See
     `docs/CAN-API.md`. The switch text warns that other apps can read.
4. **Capy coexistence.** If `@roadcast` already answers with protocol v3,
   connect to it and don't start a second daemon. Never kill theirs.
   Built: while ADB is on anyway, the helper also "adopts" it: moves it out
   of adbd's cgroup so ADB off does not kill it (tested on the car with a
   stand-in started like Capy starts it).
5. **App.**
   - New energy tier: CAN, then OBD2, then the SoC estimate.
     `EnergySource.MEASURED` covers CAN.
   - Feed `EnergyIntegrator` with V×I from CAN.
   - Battery-side charge kWh from CAN replaces the 0.90 AC guess (PR #27).
   - Climate power (`VCU_ThermalPwrAct`) becomes a new field.
6. **Switch.** Settings > Advanced > "Exact energy (experimental)", off at
   first. The text says it opens ADB for a few seconds at start.
7. **Failure.** If the labels are missing (HAL update) or the daemon won't
   start, fall back silently to OBD2 or the SoC estimate, and show why in
   Diagnostics.

## Phase 0, before writing the feature (car day)

- [x] Socket access under Enforcing: **a plain `untrusted_app` can connect
      to `@roadcast` and read it** (probe APK, uid 10033, 2026-11-14). So
      drivemem reads the daemon directly; no relay through modehelper.
      `system_app` also works (Capy runs as system; modehelper's probe).
- [x] Can modehelper (`system_app`) get a shell on 127.0.0.1:5555? Yes:
      Capy does it as system, and drivemem's own `LocalAdb` already does it
      from `untrusted_app`. Confirmed end to end by the feature itself.
- [x] Daemon outlives ADB: started with the `/acct/cgroup.procs` move, it
      kept its pid while ADB was switched off and adbd restarted.
- [x] Labels: 111/111 frames resolved on the owner's VHAL.
- [x] Allow-list (`--allow-uid`) refused another app's uid on the car.
      Then dropped by decision: the daemon is open to all apps.
- [x] CPU, parked (charging): 0.4 % of one core at 10 Hz, 2.2 % at 60 Hz,
      RSS 5 MB. We run 10 Hz. Driving: still to measure.
- [ ] Accuracy: one drive with the OBD2 dongle connected, comparing CAN V×I
      kWh with OBD2 kWh. The owner's car has both.
- [ ] Suspend: is the daemon still alive and reading after a night in
      suspend?

## Phases

1. Phase 0 probes (above).
2. Vendor and build roadcastd in CI. Add the uid allowlist and the cgroup
   move.
3. Launcher and watchdog in modehelper.
4. Data path and the energy tier in drivemem. Add tests for the tier choice.
5. Nightly to the #21 / #12 reporter. Then on by default if it holds up.

## Risks

- **This is root code.** Every change to the vendored daemon needs review.
- **We read another process's memory.** It is read-only, but a HAL update
  can move or rename the labels. Labels are resolved at each start, so a
  rename means fallback, not wrong data.
- **ADB opens briefly.** Only when the daemon died while ADB was closed. It
  is a few seconds, and logged.
- **Unauthenticated local adbd is already a hole on this unit.** Any app can
  get root through it whenever ADB is on. This plan does not make that worse,
  but the "Keep ADB always on" switch (PR #28) does.

## What was built (2026-11-14)

- `third_party/roadcast/` (upstream 22153ec), code unchanged;
  `VENDORED.md` lists what was dropped. Open API doc: `docs/CAN-API.md`. Built by `tools/build-roadcastd.sh`
  (libuv from a pinned, checksummed source tarball, no cmake), in CI by
  `verify.yml` and `build.yml`, packed into modehelper as an asset.
- modehelper: `LocalAdbShell` (port of Capy's), `CanReader` (launcher and
  1-minute watchdog), `CanReaderPolicy` (commands, tested). Binary lives at
  `/data/local/tmp/drive_assist/roadcastd`, not Capy's path.
- drivemem: `RoadcastClient` (pure-Java protocol 3), `CanPower`,
  `CanEnergyReader`; tier CAN > OBD2 > SoC in `EnergyIntegrator` and
  `Telemetry.tick`; `telemetry_sample.climate_kw` and
  `charge_session.kwh_battery` (DB v26); switch in Config > OBD2;
  `CANSTATUS` diagnostic broadcast.
- Not done: swapping PR #27's 0.90 AC derate for `kwh_battery` (that code
  is not on `next` yet), the accuracy drive, the suspend night, the driving
  CPU number.

## Power map (2026-11-17)

Goal: split the battery's power into drive, climate and 12 V, live, and send
ABRP the right number. Data: the 2026-11-14 recording (3 short trips, 50 min
of AC charge, hours parked) and a parked step test on 2026-11-17. Every
signal was decoded from the raw frames (`tools/canrec`, branch
`feat/can-explore-tools`).

### What each signal is

| Signal | Frame | What it is, from the data |
|---|---|---|
| `BMSH_BattVolt` × `BMSH_BattCurr` | 0x178 / 0x250 | Battery power. The reference on CAN. `BattCurr` raw 0 (−500.2 A) = not valid yet, at wake-up. |
| `VCU_DrvPwrAct` | 0x315 | Drive motor power. Not battery power. |
| `VCU_ThermalPwrAct` | 0x315 | Climate power. Reads low. |
| `VCU_DCDCPwrAct` (uncalibrated) | 0x315 | 12 V DC-DC load, coarse: about 0.05 kW per raw step. |
| `VCU_ChrgDischrgPwrAct` | 0x3A2 | Charger DC output while charging (−6.0 kW on a 6.8 kW wall). −12.8 = idle. Probably V2L too (not checked). |
| `OBC_uInAct` × `OBC_iInAct` | 0x221 | Mains power into the charger. |
| `TotDrvPwrAct`, `HvConvPwrAct` | 0x29B | Always raw 0 on this car. Not used. |

### Battery = drive + climate + 12 V

Driving, 10 s windows (136): battery ≈ 0.93 × drive + aux, r = 0.98.

- Pulling: (battery − climate − 12 V) / drive = 0.985. Drive power is right.
- Regen: same ratio = 0.81. Drive power shows ~20 % more regen than the
  battery gets.
- All driving: battery 0.742 kWh, drive 0.620 kWh. The rest is climate,
  12 V and losses (~0.3 kW on average).

This is why `can.drive_kw` does not match the dongle: it is a different
quantity, not a wrong reading.

### Climate reads low

Parked step test, 2026-11-17 (no charging, no driving; 0.04 kW base):

| Step | Battery, above base, minus the 12 V change | `VCU_ThermalPwrAct` |
|---|---|---|
| Air-con on | 0.58 kW | 0.32 kW |
| Big heat/cool step, compressor on (likely heat max) | 3.34 kW | 2.64 kW |
| Front defrost, compressor on | 0.32 kW | 0.08 kW |
| AC charging, air-con on vs off (2026-11-14) | 1.42 kW | 1.00 kW |

All four fit **real ≈ 1.19 × `VCU_ThermalPwrAct` + 0.2 kW** while the
compressor (`AC_ACCompReq`) is on. The 1.19 suggests the true scale is
0.1 kW/bit, not the catalog's 0.08. The 0.2 kW is likely pumps and
electronics that the signal leaves out.

Two 12 V-only steps (+0.13 kW and +0.23 kW) were also seen. Which loads
they were is not known: a phone was charging from the car at the same time.
The car has no heated seats.

### CAN vs the OBD2 dongle

- The dongle log is ~1.25 s late. Aligned, r = 0.91 while driving.
- CAN battery power = 0.95 × dongle while driving. CAN volts 1.2 V lower,
  amps 0.2–0.4 A lower. Parked and charging, r = 0.997.
- Which one is right is open. On short trips the SoC drop sat between them.

### ABRP

ABRP gets `energy.power_kw` = battery V × I (CAN, else dongle), + = out,
− = charging. That is the right quantity. Drive power is not sent, which is
correct. Weak point: it is one instant value per 6 s sample, up to 2 s old.
A mean over the sample interval (CAN gives 10 Hz) would be cleaner.

### Built (2026-11-17)

- `ClimatePower`: the climate fit, in one place.
- Hub: `energy.climate_kw` and `energy.other_kw` (CAN only, they add up to
  `can.power_kw`). Shown in Config > OBD2, CAN block.
- Per trip (DB v29): `can_net_kwh`, `drive_spent_kwh`, `drive_regen_kwh`,
  `climate_raw_kwh`, `climate_on_s`. All integrated from the SAME CAN
  samples. Saved when CAN covered ≥ 90 % of the trip's measured time and
  the trip was not recovered after a restart. `EnergySplit` scales the three
  parts to the trip's `net_kwh`, so they add up to the trip's number with
  or without the dongle. The trip screen shows "Energy by use".
- ABRP: mean battery power over the last 6 s (`EnergyIntegrator.meanPowerKw`).

### Next

1. Check the split on a real drive.
2. Long drive (30+ km) with CAN and the dongle on: which source matches
   SoC drop × 39.6 kWh.
3. A cleaner 12 V test: phone unplugged, one load at a time.
