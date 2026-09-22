# AccelProbe — discovery only

Question this answers: does the IHU629G have an accelerometer, and does it
read anything real? If yes, does it line up with actual OBD2 power draw?

Not part of Drive Assist. Not installed by `install.sh`. Lives only on the
`discover/accel-logger` branch until it proves useful enough to fold into
the real app (or gets thrown away).

## Build and run

```bash
source ~/dev/geely/build-env.sh   # or export ANDROID_SDK/JAVA_HOME yourself
./build-accelprobe.sh
adb install -r accelprobe.apk
adb shell am start -n com.geely.accelprobe/.AccelProbeActivity
```

Leave it running, drive around a bit (accelerate, brake, corner), then:

```bash
adb pull /sdcard/Android/data/com.geely.accelprobe/files/accel.log
adb uninstall com.geely.accelprobe   # when done
```

## Correlating with OBD2 power

Don't reimplement OBD2 here -- Drive Assist's own `Obd2Reader` is already
logging real BMS pack voltage/current/power to `obd2-reading.log` whenever
the OBD2 dongle is connected and the app is running (see
`docs/DIAGNOSTICS.md`). Run AccelProbe *alongside* Drive Assist during the
same drive, then pull both files and join them by their `wall` column
(both are `yyyy-MM-dd HH:mm:ss`, same clock, second resolution):

```bash
adb pull /sdcard/Android/data/com.geely.drivemem/files/obd2-reading.log
```

```bash
join -t $'\t' -1 1 -2 1 \
  <(tail -n +2 accel.log | sort -k1,1) \
  <(tail -n +2 obd2-reading.log | sort -k1,1) \
  > correlated.tsv
```

`correlated.tsv` columns: `wall  epochMs  sensorNs  x  y  z  magnitude  soc
voltage  current  powerKw  battTempC  speedKmh`. Eyeball it, or plot
`magnitude` against `powerKw` -- if a hard accel/brake shows up as a spike
in both at the same second, the accelerometer is trustworthy.

Accelerometer readings are raw `TYPE_ACCELEROMETER` -- gravity is baked in,
this is a first pass. If it looks promising, worth revisiting with
`TYPE_LINEAR_ACCELERATION` and figuring out sensor orientation relative to
the car's own axes.
