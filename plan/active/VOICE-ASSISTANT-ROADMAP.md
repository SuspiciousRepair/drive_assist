# "Hey Geely" voice assistant roadmap

## Idea

Say **"Hey Geely"**, then a short command in Portuguese: "liga o ar",
"vinte e dois graus", "abre o portão", "qual a autonomia?", "salva o
vídeo". Everything runs **offline on the head unit**. pt-BR first;
other languages come later as **swappable language packs**.

## Decisions already made (owner, 2026-09-26)

- Speech to text runs offline on the car. No cloud in the base design.
- pt-BR first. Language models must be swappable.

## What the car has (measured 2026-09-26)

- Android 9 (API 28), MediaTek, 8 cores, 6 GB RAM (~1.9 GB free).
- **No voice assistant is installed.** 88 packages, none for voice or
  speech; `voice_interaction_service`, `assistant` and
  `voice_recognition_service` are all `null`. No TTS engine either.
- Microphones: the audio policy lists `AUDIO_DEVICE_IN_BUILTIN_MIC` and
  `AUDIO_DEVICE_IN_BACK_MIC` on the primary input. Nothing was recording at
  the time.
- Traces of a removed OEM voice stack: an `init` service `vr_res` and
  `vendor.vr.resavailable=1`; the HVAC app exposes an `IAiCarControl` AIDL
  (`docs/OEM-MODULES.md`). Steering-wheel keys reach Android through the MCU
  and `com.flyme.auto.keyserver`, not a Linux key layout.
- CarPlay is present (`state/CarplayState.java`). Siri over CarPlay and
  Bluetooth phone calls also need the microphone.

## Design

### Where it runs

A new `com.geely.drivemem.voice` package in **drivemem**, not modehelper.
modehelper runs the dashcam; a native crash in a speech model must never
stop the recording. The model runtime runs in its own process
(`:voice`), like `:cliprecovery`, so a native abort kills only that
process. A foreground service keeps the microphone (Android 9 silences
microphone capture for background apps without one).

Commands run through the code that already exists:

| Say (pt-BR) | Runs |
|---|---|
| "liga / desliga o ar", "vinte e dois graus", "ventilação três" | `hvac/ComfortHub` |
| "recirculação" | `hvac/ComfortHub` |
| "abre o portão" | `ui/GateCard` path (MQTT to Home Assistant) |
| "modo turbo" | `controls/TurboMode` |
| "qual a bateria / autonomia?" | `CarDataHub` read, shown and (later) spoken |
| "salva o vídeo" | broadcast to modehelper, `DashRecorder.saveCurrentSegmentForEvent()` |

### Pipeline

1. **Microphone**: `AudioRecord`, `VOICE_RECOGNITION` source, 16 kHz mono.
   Use `AcousticEchoCanceler` / `NoiseSuppressor` when available.
2. **Wake word**: a small always-on model for "Hey Geely". Candidate:
   **openWakeWord** (Apache-2.0; a custom word is trained from synthetic
   speech, so pt-BR pronunciations — "rei jíli", "ei jíli" — can be in the
   training set). Fallback: Vosk keyword spotting (no training, more false
   wake-ups). Porcupine is easier to train but needs a license key that
   phones home — against the offline decision.
3. **Command recognition**: **Vosk** with a small pt-BR model (~30-50 MB)
   in **grammar mode**: only the phrases the command table allows. Grammar
   mode is fast and very accurate for a fixed command set.
4. **Intent**: a per-language JSON file maps phrases and slots (numbers,
   on/off) to an action id. Code knows only action ids, never words.
5. **Feedback**: an earcon plus a non-modal overlay line (reuse
   `services/OverlayService`). Spoken replies come later (Phase 4).
6. **Yield the microphone**: stop capture during a phone call, while a
   CarPlay session is active, and when the screen is off or the unit
   suspends. Duck media briefly after the wake word
   (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`).

### Language packs

One folder per language: `voice/<lang>/` with

- `manifest.json` (language, version, SHA-256 of each file)
- `wakeword.tflite` (optional; the "Hey Geely" model can be shared)
- `stt/` (Vosk model)
- `intents.json` (phrases, slots, replies)
- `tts/` (optional, Phase 4)

Packs are **not** in the APK. They download from the same Home Assistant
`/config/www` host the OTA uses, are checked against the manifest hash,
and live in the app's external files directory. Changing the language in
Config switches packs without an app update.

### Safety

- Voice is the safer control while driving, so it works in any gear.
- It never opens a modal. The Park-only rule for modals stays.
- No voice control for AEB, AVAS or drive mode.
- Actions that move something outside the cabin (gate, windows) repeat
  back what they will do before doing it.
- Audio never leaves the car and is never written to disk, except an
  opt-in debug capture.

### Budget

Always-on wake word: target under 5 % of one core. Command recognition
runs only for the few seconds after a wake-up. Measure both on this unit
in Phase 0; drop the idea of always-on if the wake word costs more than
the dashcam's `VideoTrackEncod` thread (~5 %).

## Phases

**0. Probe (no user-facing change)**
A throwaway probe APK, like `dvrprobe/`:
- Record 10 s from each microphone source as drivemem's uid. Confirm
  audio is real (not silence) and which mic hears the driver.
- Check `AcousticEchoCanceler.isAvailable()` with music playing.
- Start a CarPlay Siri request and a phone call while capturing: who
  wins, and does our capture come back afterwards?
- Find what `vr_res` is and whether it holds usable resources.
- Try binding `IAiCarControl`: which permission does it demand?
- Time openWakeWord and Vosk (small pt model) on this SoC: CPU, memory,
  latency.
- Find out whether the steering-wheel voice key reaches an app (watch
  `keyserver` broadcasts while pressing it).

**1. Push-to-talk MVP (pt-BR)**
A mic button in ComfortActivity and the overlay (and the steering-wheel
key if Phase 0 finds it). Vosk grammar mode, the eight commands above,
overlay feedback. Ship behind a Config switch, default off.

**2. "Hey Geely" wake word**
Train the openWakeWord model with pt-BR synthetic voices plus recordings
from this car (engine, road, music, passengers). Acceptance, measured on
real drives: under 1 false wake-up per 8 hours of driving with music on;
over 90 % of spoken wake words caught.

**3. Language packs**
The pack format above, the downloader, and a language picker in Config.
Add `en-US` as the second pack to prove the swap.

**4. Spoken replies and questions**
A bundled offline TTS voice (Piper, pt-BR) for answers such as battery,
range and trip statistics.

**5. Later, optional**
Free-form questions through a cloud model, off by default. Outside the
offline decision; revisit only if the owner asks.

## Open questions

- Which microphone faces the driver (`BUILTIN_MIC` or `BACK_MIC`)?
- Can drivemem hold the microphone while CarPlay is connected but idle?
- Does the unit's echo canceller work with its own speakers?
