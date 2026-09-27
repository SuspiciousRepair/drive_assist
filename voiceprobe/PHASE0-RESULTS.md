# Voice assistant Phase 0 results

Status: vehicle probing completed 2026-09-26; engine benchmarks still pending
runtime/model artifacts.

| Probe | Result | Evidence |
|---|---|---|
| Driver-facing input/routing | `VOICE_RECOGNITION`, `bottom`, device 23 | 10 s: RMS 1173.5, peak 14234; explicit bottom RMS 1058.3 vs back 757.1 |
| Real audio rather than silence/noise | Confirmed, 160,000 samples per 10 s | Every completed route had zero read errors |
| AEC with music playing | Available, created and enabled; NS unavailable | Voice+music -27.5 dB RMS; music-only -37.4 dB RMS |
| CarPlay idle capture | Works | Multiple complete captures while CarPlay was connected |
| Siri ownership and recovery | Capture continued across Siri | `siri_active`/`siri_inactive`, no read errors |
| Phone-call ownership and recovery | Call wins; old recorder does not recover; a new recorder works | `mic_request` caused read `-2`; recreate after `mic_release` captured full streams immediately |
| `vr_res` identity/resources | Stopped Baidu voice-resource installer | 177 MB Chinese/OEM archive: ASR, offline NLU, wake word, AEC and TTS; `/ivres` mount absent; not reusable for pt-BR |
| `IAiCarControl` bind permission | No service exists to bind | SDK targets absent `com.geely.aicarcontrol/.service.AiCarControlService`; HVAC exports are unrelated and require no bind permission |
| Steering voice key app delivery | Keyserver emits an implicit broadcast | `ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT`, DEFAULT category, event `200231`, action `0` short/`1` long; ordinary receiver accepts an equivalent broadcast |
| openWakeWord CPU/RSS/latency | **No-go for always-on:** both runtimes exceed budget | Fixed TFLite: 23.832 ms/frame, 29.76% of one core, PSS ~38 MiB; ONNX: 16.149 ms/frame, 20.17%, PSS ~48–52 MiB |
| Vosk CPU/RSS/latency | Pending | benchmark outputs |

Record the exact APK commit, model versions and hashes, corpus hash, engine
arguments, number of runs, and whether each run was cold or warm. Do not add
private addresses, identifiers, phone numbers, or raw cabin speech to Git.

## Phase 1 consequences

- Use `VOICE_RECOGNITION` on the default/bottom input. Do not use
  `VOICE_COMMUNICATION` or `CAMCORDER`.
- Subscribe to CarPlay's `mic_request`/`mic_release` and call-active state.
  Stop and release `AudioRecord` before a call, then create a fresh instance
  after release; continuing to read the old instance spins on errors.
- Add the steering broadcast action and `DEFAULT` category to the manifest or
  dynamic filter. Preserve the OEM phone behavior unless the voice feature is
  explicitly enabled; Phase 1 should decide whether to consume or coexist.
- Continue through `ComfortHub`; the dormant AI-control SDK is not an available
  integration path on this unit.
- Do not implement this openWakeWord pipeline as an always-on listener. ONNX
  exceeds the 5% budget by 4x. Fixed-shape TFLite solves the allocation failure
  but is slower, using 29.76% of one core. Keep push-to-talk as the low-idle-cost
  activation path unless a materially different wake-word engine is proven.

## openWakeWord benchmark details

- Official upstream v0.5.1 ONNX models and SHA-256 hashes are pinned in
  `owwprobe/download-models.sh`.
- Runtime: ONNX Runtime Android 1.17.3, ARM64, one intra-op and one inter-op
  thread, 50 warm-up frames, then 2,000 measured frames.
- Synthetic input avoids recording or corpus-dependent variation. Per 80 ms
  frame: mel 1.987 ms, embedding 13.331 ms, classifier 0.831 ms, total
  16.149 ms. Thread CPU was 32.270 s for 160.0 s of audio-equivalent work.
- PSS was 51,515 KiB before and 48,248 KiB afterward; treat this as roughly
  50 MiB steady state, not a precise peak measurement.
- Fixed-TFLite follow-up: `owwprobe/freeze-melspectrogram.py` freezes the
  official dynamic mel input to the 1,760-sample streaming shape. Host LiteRT
  output is exactly equal to dynamic-resize output for seeded random input.
  On-car LiteRT 1.4.0 measured 47.622 CPU seconds over 160 seconds of audio
  (29.76% of one core): mel 4.697 ms, embedding 18.310 ms, classifier 0.818 ms,
  total 23.832 ms per 80 ms frame. PSS settled near 38 MiB.
