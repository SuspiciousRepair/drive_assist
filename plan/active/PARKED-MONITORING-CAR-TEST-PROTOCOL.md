# Parked Monitoring — On-Car Test Protocol

Status: required before enabling any parked-monitoring runtime path. This is a
diagnostic protocol, not a driver-facing feature guide.

## Safety boundary

- Perform tests only while stationary in a safe, open location; never while
  driving or where a temporary loss of the factory camera view would be unsafe.
- Do not test during an unattended charging session or overnight in the first
  stages.
- Keep the existing continuous dashcam behaviour unchanged unless a test step
  explicitly says otherwise.
- Stop immediately on any factory camera error, blank/repeated frame, reverse
  guideline issue, sustained CPU load, unexpected screen behaviour, or 12 V
  warning. Rebooting the head unit is prohibited.
- Every test APK is a local, explicitly installed diagnostic build. No OTA,
  retained command, automatic start, or persistent feature preference is used.

## Preconditions

1. Record installed `modehelper` version/signature and retain the known-good
   APK for immediate reinstall.
2. Capture baseline evidence: factory surround view, reverse camera/guidelines,
   existing dashcam start/stop, available storage, helper logs, and 12 V value.
3. Confirm the test build exposes no default camera attachment. A test starts
   only through a local, purpose-built diagnostic command.
4. Establish a fixed observation window and an operator who watches the factory
   display throughout every camera-attachment step.

## Stage 0 — no camera attachment

Install the diagnostic build only if its package/signature match the baseline.
An APK replacement can cause the pre-existing `ModeHelperService` lifecycle to
start the normal DVR recorder. Before starting the probe, explicitly stop that
service and verify its DVR attachment has ceased. Then confirm the diagnostic
service is not running and that no `EvsClient.openCamera` or `EvsClient.attach`
call occurs. Reinstall the known-good build if this condition is not met.

The sole Stage-1 entry point is a dedicated `DUMP`-permission-protected
foreground service with explicit start/stop actions. It is metadata-only: it
creates no clips, starts no classifier, and has no boot/parking/charging
trigger. While it is active it refreshes the core helper's watchdog heartbeat:
the core helper is intentionally stopped for isolation, and without this
temporary diagnostic heartbeat the watchdog restarts its recorder after a
stale-heartbeat check, creating an invalid second camera consumer.

Existing on-car evidence retained in `/data/local/tmp/evshold.dex` establishes
the correct initial analysis-consumer shape: an `RGBA_8888` `ImageReader` at
1920×800 with three images. It received approximately 25 fps for over two and
a half minutes while the operator was invited to use Reverse. The diagnostic
downscales that proven input to 480×200 only after reception. This validates
factory-camera coexistence, not simultaneous operation with the existing
hardware encoder.

## Stage 1 — analysis-surface compatibility

The purpose is solely to establish whether `evsengine` accepts the reduced
analysis surface while `dvr` and factory UI remain healthy.

1. Run one short, manually initiated attachment attempt while parked.
2. Limit it to 60 seconds. Record surface dimensions, image cadence from the
   metadata-only heartbeat counter, and any Binder/EVS errors. Add explicit
   format/plane-stride logging before treating a longer test as conclusive.
3. Do not start object classification, write clips, or retain frames to disk.
4. Repeat the factory surround/reverse-camera baseline checks immediately after
   stopping the attachment.
5. If the display changes or the existing recorder loses frames, stop work on a
   simultaneous consumer. The fallback design is an explicit attach switch,
   preserving only reduced-resolution pre-event evidence.

## Stage 2 — idle analysis budget

Only after Stage 1 is clean, run the analysis tap at its 2 fps cap for 15
minutes while parked and attended. Measure CPU, memory, temperature, frame
cadence, dropped frames, and 12 V voltage. Motion classification remains off;
the purpose is to validate the gate and its 10-second in-memory pre-event ring.

Pass criteria: stable factory camera UI, no process restart, no growing memory
trend, and no unacceptable 12 V or thermal change relative to the baseline.
An automatic core-helper/recorder restart invalidates the run rather than
counting as simultaneous-consumer evidence.

Current status: not yet passed. The short attended run established about
1.94–2.13 fps and a roughly 21 MB fixed graphics allocation for the three
RGBA buffers, but it did not include the required 15-minute voltage and
temperature record. The initial run was invalidated by the watchdog restart;
the diagnostic heartbeat and forced-watchdog check now guard against that
specific test interference.

The diagnostic emits one structured `parked-metric` logcat record per 60
analysis frames (about 30 seconds): session duration, sampled-frame count,
cadence, process CPU time, PSS, Park-gate state, ambient temperature, and
available charging telemetry. `charge_v` and `charge_a` are charging-system
readings, not a verified auxiliary 12 V measurement. Preserve the matching
ModeHelper logcat interval after a later soak test for analysis.

## Stage 3 — event path

Use a controlled walking target around the vehicle. The Park timer must settle
for thirty seconds before the motion gate can arm. Validate:

- no event before arming;
- event begins only after motion-gate debounce;
- pre-event frames are ordered and retained in memory;
- DVR recording begins/promotes only after the event;
- repeated motion extends one event rather than creating a clip storm;
- leaving Park disarms immediately and stops event recording safely.

Start with one short event and review clips/logs before extending the test.

## Stage 4 — CPU classification and charging

Enable the quantized CPU model only after motion-gate behaviour is accepted.
Test nearby people, vehicles, animals where feasible, shadows, headlight
changes, rain, and static scenery. Then repeat attended tests during a charging
session. Overnight testing is a separate approval decision after energy and
thermal measurements are reviewed.

## Required evidence for each stage

- Start/end timestamps and vehicle state.
- Helper log excerpt and diagnostic counters.
- Factory camera/reverse-camera observations before and after.
- Frame cadence, CPU/memory/temperature, and 12 V readings.
- Any generated clip/pre-event metadata and its outcome.
- Clear pass, fail, or inconclusive decision before proceeding.
