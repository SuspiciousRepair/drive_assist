# openWakeWord Phase 0 benchmark

This microphone-free APK measures openWakeWord's three-stage streaming
pipeline on the target Android 9 head unit. It uses the official v0.5.1
models, one inference thread, synthetic zero-valued input, and the same model
shapes as an 80 ms stream frame. `hey_jarvis` is the representative classifier;
a custom `Hey Geely` classifier has the same compute shape.

The official TFLite mel-spectrogram has a dynamic audio dimension. It failed
tensor allocation on this ARM64/API 28 device with both TensorFlow Lite 2.14
and LiteRT 1.4. This benchmark freezes it to the streaming shape
`[1,1760] -> [1,1,8,32]`; the other two models are already fixed-shape.

The freeze changes only the input/output tensor shapes in the FlatBuffer.
Host validation against the official dynamically resized model produced
bit-identical output (`max_abs_error=0`) for seeded random input. The Android
loop includes mel normalization and the rolling feature buffers.

Models are downloaded at build time, hash-checked, transformed with a pinned
isolated LiteRT tool, gitignored, and excluded from the repository:

```bash
source ./build-env.sh
./owwprobe/build-owwprobe.sh
adb install -r owwprobe/owwprobe.apk
adb shell am start -n com.geely.owwprobe/.OpenWakeWordProbeActivity
adb shell cat /sdcard/Android/data/com.geely.owwprobe/files/openwakeword-result.txt
```

The APK requests no microphone or network permission.

For comparison, the previous official ONNX fallback measured 20.17% of one
core (16.149 ms per 80 ms frame) and about 48–52 MiB PSS on the head unit.

## Head-unit result

The fixed model allocates and runs, but it is not suitable for always-on use
on this unit. Over 2,000 frames (160 seconds of audio-equivalent work), LiteRT
1.4.0 used 47.622 CPU seconds: 29.76% of one core. Mean pipeline time was
23.832 ms per 80 ms frame (mel 4.697 ms, embedding 18.310 ms, classifier
0.818 ms), with about 38 MiB PSS. This exceeds the roadmap's 5% CPU budget and
is also slower than the ONNX fallback on this hardware.
