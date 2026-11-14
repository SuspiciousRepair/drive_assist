# Exact energy without a dongle (roadcastd)

Status: plan, 2026-11-14. Nothing built yet.

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
   - Patch the daemon to allow only our uids (`SO_PEERCRED`), so other apps
     can't read car data from it.
4. **Capy coexistence.** If `@roadcast` already answers with protocol v3,
   connect to it and don't start a second daemon. Never kill theirs.
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

- [ ] Socket access under Enforcing: can a normal app (`untrusted_app`)
      connect to a socket owned by `su`? Can `system_app`? Use a tiny probe
      APK, because a test from an adb shell runs as `su` and proves nothing.
- [ ] Can modehelper (`system_app`) open a TCP socket to 127.0.0.1:5555 and
      get a shell?
- [ ] CPU and battery cost of roadcastd at 10 Hz and at 60 Hz, parked and
      driving.
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
