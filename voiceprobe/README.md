# VoiceProbe — Phase 0 discovery only

This is the throwaway APK for Phase 0 of `VOICE-ASSISTANT-ROADMAP.md`. It is
not part of Drive Assist, is not included by `settings.gradle`, and is never
installed by the normal installer. It records audio only after an explicit
button press, never uploads it, and stops automatically.

## Build and launch

Park the car before running these tests. From the repository root:

```bash
source ./build-env.sh
ANDROID_SERIAL=<adb-serial> ./voiceprobe/phase0-device.sh install
```

The APK uses a normal application UID, just like `drivemem`; it deliberately
does not use the platform key or `android.uid.system`.

## Test sequence

1. Tap **Record microphone matrix**. Speak the same phrase from the driver's
   seat throughout. The app records 10 seconds for `MIC`,
   `VOICE_RECOGNITION`, `VOICE_COMMUNICATION`, `CAMCORDER`, and every distinct
   built-in input device Android exposes. `probe.log` records routed device,
   RMS, peak, read failures, AEC and noise-suppressor status. Compare the WAVs
   by audibility and RMS; a nonzero RMS alone can be electrical noise.
2. Play music, repeat the matrix, and retain the log line showing whether AEC
   could be created and enabled. Compare speech/music leakage in the WAVs.
3. Tap **90s Siri / phone contention test**, invoke Siri or place a phone call,
   then release it before the 90 seconds end. Repeat once for Siri and once for
   a call. The log and WAV show whether capture became silent/errored and
   whether it recovered without recreating `AudioRecord`.
4. Tap **Probe HVAC binder permissions**. This enumerates exported services in
   `com.flyme.auto.hvac`, tries only `bindService` (no Binder transactions and
   therefore no vehicle command), and logs the declared or thrown permission.
5. Leave VoiceProbe focused and press the steering-wheel voice key. Normal
   Android key events appear in `probe.log`. In parallel run `watch-keys` to
   catch `keyserver` broadcasts/logs even if no key event reaches the app.
6. Run `inspect-vr` to collect read-only `vr_res`, package, property, process,
   and filesystem evidence. It never stops a vendor process or changes files.

```bash
ANDROID_SERIAL=<adb-serial> ./voiceprobe/phase0-device.sh watch-keys
ANDROID_SERIAL=<adb-serial> ./voiceprobe/phase0-device.sh inspect-vr
ANDROID_SERIAL=<adb-serial> ./voiceprobe/phase0-device.sh collect
```

Results land under `voiceprobe/results/` (gitignored). Uninstall when done:

```bash
adb -s <adb-serial> uninstall com.geely.voiceprobe
```

## Engine benchmarks

Models and runtimes are intentionally not checked in: the pt-BR Vosk model is
tens of MB, while openWakeWord needs a separately built Android-compatible
runtime and the candidate custom wake-word model. Once each benchmark binary
and model are pushed to the unit, run the exact same fixed WAV corpus through
`benchmark-engine.sh`. It captures wall/user/system time plus one-second CPU
and `/proc` memory samples. Include cold and warm runs, real-time factor,
first-result latency, peak RSS, and average CPU in the Phase 0 report.

```bash
ANDROID_SERIAL=<adb-serial> ./voiceprobe/benchmark-engine.sh vosk \
  /data/local/tmp/vosk-bench /sdcard/voice/pt-small /sdcard/voice/corpus.wav
ANDROID_SERIAL=<adb-serial> ./voiceprobe/benchmark-engine.sh openwakeword \
  /data/local/tmp/oww-bench /sdcard/voice/hey-geely.tflite /sdcard/voice/corpus.wav
```

Do not compare engines with different audio, thread counts, warm-up, or run
counts. The Phase 0 go/no-go threshold for always-on wake word remains under
5% of one core during real-time streaming.
