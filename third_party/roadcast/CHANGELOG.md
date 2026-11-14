# Changelog

All notable changes to Roadcast are recorded here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Roadcast versions the daemon separately from the wire protocol and the catalog
schema. A release line names all three.

## Unreleased

### Added

- Automatic source rediscovery after VHAL exit or read failure, including
  startup before the VHAL is ready. Retries back off from 250 ms to 30 s
  without disconnecting clients or retrying source I/O on every sample tick.
- Linux/Android tests use disposable source processes to cover source
  restart, PID identity, fallback/cooldown and uninterrupted client recovery.

- Dense fake-source integration coverage at 1 and 60 Hz, including concurrent
  CLI/SDK readers, and canonical-state tests without delta subscribers.
- The ten-second diagnostic counters include `serialized_chunks`, counted
  once per encoded broadcast chunk rather than once per recipient.

- GitHub Actions now publishes `RELEASE_MANIFEST.json` with Android target,
  protocol/schema compatibility, source commit, and hashes for the daemon,
  CLI, and shared client library.

### Changed

- Match the source process in `argv[0]`, excluding shells and clients that
  merely mention its name in arguments. A retained `/proc/PID/stat` descriptor
  and start time validate each read before it can be published.
- Cache unavailable vectored-read capability: `ENOSYS` until source reopen,
  permission errors for 30 s. Successful `/proc/PID/mem` fallback remains
  connected; source failures close descriptors and discard partial batches.

- Size broadcast scratch buffers and the default bounded client queue from
  the generated catalog. The current worst-case update needs 19 messages;
  the queue now holds those messages plus one heartbeat, instead of 16.
- Skip broadcast serialization when no active delta subscriber can receive
  it. Canonical values, observation timestamps and sequences keep advancing.

- Eight climate and drivetrain state signals are now calibrated. Their decode
  is the identity, `scale=1` and `offset=0`, because each one is a state or a
  level and not a measured quantity: `AC_OnState`, `AC_ACCompReq`,
  `AC_BlowerLevel`, `BCM_RearDefrosterSts`, `BCM_DM_TargetModeReq` and
  `VCU_ePTRegencyLevInd` stay unitless, and
  `AC_LeftSetTemperature` reports the cabin setpoint in `degC` with
  `scale=0.5` and `offset=15.5`. The head unit offers 15.5 to 28.5 degC in
  half-degree steps, which is 27 values, and the signal is 5 bits wide, so raw
  `0` is the minimum and raw `26` the maximum. The five counts above that are
  unassigned and may carry special states. The flag tells
  a client that the raw count is the value, so a client no longer has to read
  these through a raw-count path that bypasses validation. It does not name
  the enumeration behind each count; a client keeps that mapping. Two of them
  are known from the car: `BCM_DM_TargetModeReq` is 0 for NORMAL, 1 for ECO
  and 2 for SPORT, and `VCU_ePTRegencyLevInd` is 1 for low regeneration, 2 for
  medium and 3 for high. The drive mode is on `BCM_DM_TargetModeReq` and not
  on the `VCU_ePTMode*` pair, which stays uncalibrated.
- Calibrated charger AC input measurements: `OBC_uInAct` reports voltage in
  `V`, and `OBC_iInAct` reports current in `A`; both use `scale=0.1` and
  `offset=0`. For example, `OBC_uInAct` raw value `2205` reports `220.5 V`.
- `ESC_VehicleSpeed` now reports its calibrated physical value in `km/h`, with
  `scale=1` and `offset=0`.
- `BMSH_BattCurr` reports discharge as positive and charge as negative. The
  scale changes from `-0.1` to `0.1` and the offset from `500.5` to `-500.2`.
  Clients that consumed this signal before got traction and pack current as
  mirror images of each other.
- `BMSH_BattCurr` zero moves from raw `5005` to raw `5002`, a correction of
  `0.3 A`. The zero comes from 22 recorded trips, comparing the integral of
  this signal against the pack energy the state of charge reports.
- `VCU_ThermalPwrAct` is calibrated in `kW`, with `scale=0.08` and `offset=0`.
  A parked bench run on 2026-08-08 moved the cabin heater alone, with the
  blower fixed and `VCU_DCDCPwrAct` fixed, and read the pack pair as the
  reference. Three heating levels over 89 samples give 80.61 +/- 0.64 W per
  count, R2 = 0.9945. The fitted intercept of 288.6 W reproduces the 297.4 W
  the blower drew in a separate state the fit never saw. The previous estimate
  of `0.1` was 25 percent high and violated conservation of energy: it made
  the car report 3.2 kW while the pack delivered 2.66 kW into a resistive
  heater, whose output cannot exceed its input.
- Two limits apply to `VCU_ThermalPwrAct` and neither changes the scale. The
  count quantises coarsely: at count 32 the pack ranged 2744 to 2954 W, and a
  real 210 W step moved the count by zero. Cooling costs more than the count
  reports: at count 6 the pack drew 799 W above the blower where the count
  implies 484 W, because the condenser fan ramps with the compressor on the
  12 V side and never enters this signal. Heating has no such discount.
- `ESC_RoadInclnRoadIncln` reports its unit as `%`, not `deg`. The scale, the
  offset and the raw decoding do not change; only the label was wrong. Road
  data from three trips on 2026-08-08 compared the signal against the road
  grade derived from GPS altitude over the distance travelled. Percent
  predicts a fit slope of 1.000 and degrees predicts 0.573. A first fit over
  4675 samples gave 0.556 to 0.621, which appears to confirm degrees, but the
  correlation was only 0.75 to 0.80 and GPS altitude is the noisy variable.
  Noise on that variable biases the slope toward zero. Progressively
  restricting the fit to longer runs at a steadier speed raised the slope
  monotonically through 0.664, 0.760, 0.806, 0.857 and 0.902 as the
  correlation rose to 0.992. Degrees fall outside the 95 percent interval at
  every level. The slope settles near 0.91 rather than 1.00; the remaining 9
  percent is not identified, but a unit error is a factor of 1.745 and cannot
  be a 9 percent bias.
- `VCU_DCDCPwrAct` stays unscaled. The same bench run stepped the blower
  through three levels with nothing else moving, and neighbouring counts cost
  21.5 W and 157.7 W, a factor of 7.3. No single scale fits, so the count is a
  coarse state indicator and not a power measurement. This replaces the
  earlier argument from recorded states with a controlled measurement.

## [0.1.0] - 2026-07-27

First public pre-release. Protocol version 3, schema version 1.

This release is a validated tracer, not a stable contract. The wire protocol is
not frozen and may change without a compatibility path. See `Known limits`.

### Added

- `roadcastd`: libuv-based multi-client daemon reading OEM VHAL memory at a
  configurable rate, 60 Hz by default.
- Read-only VHAL source with process discovery, ELF symbol resolution, and
  bounded remote memory reads.
- Deterministic in-memory fake source so the full pipeline runs on a host with
  no vehicle attached.
- Wire protocol v3 with explicit binary framing, defined byte order, and
  validated magic, version, type, flags, payload length, and element counts.
- Generated catalog of 815 decoded CAN signals across 111 receive frames, built
  from `data/dbc.json` by `scripts/generate-catalog.py`.
- Paged schema discovery with stable signal IDs and a canonical schema hash
  (`ROADCAST_CAN_SCHEMA_HASH`).
- Consistent decoded-signal snapshots with subscription catch-up bounded by the
  client queue.
- Paged raw-frame snapshots with explicit observation state and timestamps.
- Per-client bounded queues: a slow client is disconnected or resynchronized
  and never stalls acquisition.
- Directional request/response limits and operational heartbeat metrics.
- `roadcastctl`: reference CLI client, including signal inspection by name
  (`--signal VehicleSpeed --seconds 10`).
- `libroadcast_client.a` with `include/roadcast_client.h`: reusable client SDK
  resolving signals by stable ID.
- Host test suite: protocol round trips, catalog integrity, malformed and
  truncated input, sequence-gap resynchronization, multi-client and
  connect/disconnect loops, and slow-client overflow.
- Android ARM64 / API 28 cross-compilation (`scripts/build-android.sh`,
  `scripts/build-libuv-android.sh`) and a development installer
  (`scripts/install-android.sh`).
- GitHub Actions host/integration tests, Android cross-build artifacts, and
  automatic GitHub Release assets for `v*` tags.
- `scripts/analyze-vhal-surface.py`: static ELF/DWARF analysis that diffs the
  VHAL binary's CAN surface against the catalog and exits non-zero on
  divergence, for use as a post-OTA regression check.
- Documentation: architecture, protocol, decisions, validation, catalog
  surface, and Android deployment.

### Validated

- Protocol v3 confirmed against the vehicle on 2026-07-26 (IHU629G, Android 9,
  ARM64).
- `VCU_DrvPwrAct` on CAN frame `0x315` confirmed on the vehicle with
  `0.1 kW` scale and `-204.8 kW` offset and marked as calibrated.
- Source reads confirmed with SELinux Enforcing from `u:r:su:s0`.
- Catalog confirmed complete against the analyzed VHAL build: all 111 receive
  buffers and all receive-side DWARF signals are covered, with zero overlapping
  bit assignments.

### Known Limits

- The wire protocol is experimental. Message layouts, numeric values, and names
  are not a stable contract at 0.x.
- `valid` currently means "symbol resolved", not "observed". Per-frame freshness
  timestamps do not exist yet.
- 812 of 815 signals carry `scale=1.0`, `offset=0`, and no unit. Three
  signals are calibrated.
- 61 percent of the bits in the frames already being read carry no
  DWARF-defined signal and cannot be named by static analysis.
- The 44 transmit buffers and their 580 statically defined signals are not
  exposed. Reading them is read-only, but the schema cannot yet distinguish an
  observed transmit buffer from an observed receive frame.
- App-domain access to the `@roadcast` abstract socket and the full local-ADB
  bootstrap path are not yet validated together under SELinux Enforcing. Both
  remain gates for a 1.0 release.
- All symbol offsets and counts are pinned to one VHAL BuildID. A vehicle OTA
  invalidates them and can strip the symbol table entirely.
- The daemon resolver silently ignores unknown `MMI_Rx_*` symbols, so catalog
  drift must be detected with `scripts/analyze-vhal-surface.py` rather than
  inferred from a successful startup.

[0.1.0]: https://github.com/roadcast/roadcast/releases/tag/v0.1.0
