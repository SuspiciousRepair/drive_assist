# Parked Monitoring and Resilient Background Operation

Status: research draft only. This document makes no implementation decision and
contains no vehicle-vendor assumptions beyond the hardware evidence noted here.

Implementation note: a default-off **Park monitoring** switch
is now wired through Drive Assist to the normal privileged helper. When enabled,
the helper starts the metadata-only analysis tap after thirty continuous seconds
in Park and stops it immediately on disarm. The normal dashcam may already be
running, so the upcoming logged soak is intentionally a simultaneous-`dvr`
consumer test. It remains experimental: no inference, event clips, or deep-sleep
keepalive is enabled.

## Goal

Offer optional activity monitoring only while the vehicle is demonstrably
awake—in parking mode or while charging. It should capture useful evidence and
send a local-network alert without compromising the 12 V battery, privacy, or
factory camera behaviour. Deep-sleep operation is out of scope for the first
version.

## Product modes

1. **Driving / resume only (default).** Existing services recover after head
   unit resume, but camera capture and analytics are not retained while parked.
2. **Awake parking / charging monitoring.** Opt-in mode. After thirty
   uninterrupted seconds in Park, arm the motion gate; do not continuously
   record while idle. Run camera capture and CPU inference without attempting
   to defeat vehicle sleep; disarm immediately when Park is left or the awake
   state ends.
3. **Awake parking recording without detection.** An optional continuous or
   ring-buffer variant. It has simple semantics but higher storage and energy
   cost, so it should not be the first shipped mode.

The UI should show selected mode, camera status, last successful check-in,
current 12 V reading, and the exact reason a mode stopped.

## Architecture boundaries

- **Lifecycle controller:** A small persisted state machine owns transitions:
  `driving -> settling -> parked armed -> event capture -> battery protect ->
  stopped`. Persist state and transition generation before side effects so a
  killed process cannot re-arm after a deliberate stop.
- **App liveness:** Keep using foreground services, durable heartbeat, and the
  suspend-aware `ELAPSED_REALTIME_WAKEUP` watchdog. A watchdog may restart a
  failed service; it must not itself keep the car awake.
- **Network liveness:** Treat Wi-Fi, MQTT, and remote access independently.
  Reconnect with bounded backoff; queue alerts and clips locally rather than
  holding systems alive indefinitely for a network.
- **Camera/hardware liveness:** This is separate from Android liveness. First
  release runs only while the confirmed awake-state signal is true; do not use
  a wakelock to preserve EVS, camera power, or the render engine through
  suspend.
- **Safety controller:** One owner for 12 V, temperature, storage, and timeout
  gates; fail closed if readings are absent or stale.

## Activity detection

Implement in stages to control risk and power use:

1. **Safe analysis source.** Use the existing `dvr` composite supplied by
   `evsengine` (1920×800, 25 fps) as the only candidate analysis source. It is
   lower resolution than direct EVS's 5120×800 raw composite and, unlike direct
   EVS, is documented as safe alongside factory camera UI. Do not use direct
   EVS: it is single-client and preempts factory camera behaviour. First
   validate whether `dvr` can feed an analysis consumer and an encoder at once,
   or whether a tee/decoder is required.
2. **Cheap change gate.** Sample reduced-resolution `dvr` frames and calculate a
   per-quadrant frame-change score, masking static vehicle/body regions. Use a
   debounce window and minimum changed-area threshold before ML work.
3. **CPU object classification.** Run a small quantized on-device model only
   on gate-positive frames—for example person, vehicle, and animal. Start at
   1–2 fps and establish a measured CPU and thermal budget. Record confidence,
   class, camera quadrant, and timestamp; retain the clip as the source of
   truth.
4. **Event policy.** Retain a small reduced-resolution pre-event ring in
   memory. On a qualifying detection, retain that preview evidence and begin
   or promote DVR recording for the continuing event. The exact path depends on
   the result of the safe-consumer test: a simultaneous `dvr` tee permits an
   encoded pre-roll; an attach switch preserves only the reduced-resolution
   pre-roll. Extend while activity persists, apply cooldown and deduplication,
   create a thumbnail, and publish a local alert. Detections are probabilistic,
   never a security guarantee.

The `dvr` feed is a 2×2 composite, so each camera quadrant is about 960×400.
It is suitable for a gate and conservative nearby-object classifier, not
reliable distant, small, or low-light identification. The gate should downscale
its own analysis copy; per-camera cropping and model evaluation are explicit
   quality gates.

   A gate pulse is not itself a clip boundary. Merge repeated pulses into one
   event and finish only after a conservative quiet tail; this prevents
   changing light or an intermittently visible person from creating a clip
   storm.

## Power, safety, and privacy gates

- Require thirty uninterrupted seconds in Park, a verified stationary state,
  and confirmed awake parking-mode or charging state before arming. Disarm
  immediately if Park is left, state ends, is stale, or becomes unavailable.
- Do not equate door lock with vehicle power state. Lock state may be an extra
  user policy condition, not the primary lifecycle signal.
- Enforce configurable maximum armed duration and a hard 12 V low-voltage
  cutoff with hysteresis. Persist the stop reason and require a safe recovery
  condition.
- Begin with conservative low-rate capture and inference; increase briefly
  only after motion. Measure full-session energy use, not just CPU percentage.
- Maintain storage quota, rolling retention, atomic clip finalisation, and
  recovery for interrupted recording.
- Keep clips on-device by default. Remote access and alerts must be opt-in,
  authenticated, encrypted, and restricted to a trusted local network or
  explicitly configured private tunnel.

## Validation milestones

### Current on-car evidence

- An attended, isolated `dvr` analysis attachment was accepted at 1920×800 and
  delivered 111 reduced analysis samples at approximately 1.94–2.13 fps.
  The observer kept the factory 360° camera application visible throughout and
  reported no glitch.
- The `ImageReader`'s three full-resolution RGBA buffers account for roughly
  21 MB of graphics allocation (about 29 MB process PSS in the short run).
  No images, clips, or classifier outputs were written.
- A first longer run was intentionally invalidated when the existing core
  service watchdog restarted the normal recorder after its heartbeat became
  stale. The attended diagnostic now refreshes that heartbeat only while it is
  actively isolating the test; a forced watchdog check confirmed no restart.
- Compatibility is therefore provisionally passed. The energy/thermal/12 V
  budget is not passed: it still requires a separate attended 15-minute run,
  with vehicle voltage and temperature readings recorded, before any automatic
  runtime path is enabled.

1. **Awake-state and consumer characterisation:** Identify and validate
   authoritative parking-mode and charging signals. With explicit on-car
   approval, test whether `dvr` supports simultaneous consumers without
   disrupting factory camera UI; log frame delivery, DVR start latency, process
   survival, Wi-Fi, CPU load/temperature, and 12 V voltage for 15 min, 2 h,
   and a full charging session. Verify clean disarm at state exit.
2. **Non-interference:** Prove the DVR path does not preempt or degrade reverse
   camera and factory surround-view operation.
3. **Energy budget:** Compare baseline, foreground-service-only, low-rate
   frame gate, and gated object detection. Define a measured voltage/time
   budget before extended operation.
4. **Detection quality:** Test day/night, rain, headlights, pedestrians,
   cyclists, passing traffic, shadows, and static scenes. Track false alerts
   and missed events by camera quadrant.
5. **Failure injection:** Force process death, network loss, camera errors,
   low storage, stale voltage values, and state transitions. Confirm safe
   disarm, clip integrity, and no uncontrolled restart loops.
6. **Limited pilot:** Default disabled; expose diagnostics and logs before any
   broad enablement.

## Hardware feasibility assessment

The existing evidence supports a feasible awake-state prototype, not a promise
of reliable overnight or deep-sleep object detection:

- The current camera path delivers a hardware-produced 1920×800, 25 fps DVR
  composite and already feeds a hardware H.264 encoder. It is the safe source
  for both analysis and capture. Whether it can supply both consumers at once
  is not yet documented and must be proven before feature design depends on it.
- No in-repository evidence establishes an NPU or GPU inference API. CPU
  inference on sparse, gate-positive frames is the intended first approach;
  benchmark it before selecting another accelerator or model.
- The decisive first-release dependency is an authoritative signal that the
  parking-mode or charging session remains awake. EVS/render-engine availability
  is tested only in that state. Deep-sleep camera operation stays out of scope.

## Explicit non-goals for first iteration

- No continuous 25 fps ML inference.
- No attempt to prevent deep sleep using indefinite wakelocks.
- No vehicle-control actions from detections.
- No cloud account or mandatory upload.
- No claim that this is a certified security system.
