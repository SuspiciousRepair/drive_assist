# AccelProbe — discovery only

Question this answers: does the IHU629G have an accelerometer, and does it
read anything real? If yes, does it line up with actual OBD2 power draw?

Not part of Drive Assist. Not installed by `install.sh`. Lives only on the
`discover/accel-logger` branch until it proves useful enough to fold into
the real app (or gets thrown away).

**Status (2026-09-22): yes, it works.** Confirmed on the real head unit --
`ACCELEROMETER (MTK)`, `maxRange=78.4532`, `resolution=0.0012`,
`minDelayUs=2500` (400Hz). At `SENSOR_DELAY_FASTEST`, over 5,000 real
rows landed in `accel.log` in under a minute, values consistent with
gravity for a stationary car (~9.76 m/s²). No `TYPE_LIGHT` sensor exists
on this device -- `light.log` will simply never be created here.

Getting a real reading took three fixes, in order -- worth knowing before
touching this again:

1. **Lifecycle, not privilege.** The car's launcher steals focus right
   after any app launches, firing `onPause()` within a fraction of a
   second. Registering the listener in `onResume()`/unregistering in
   `onPause()` (the normal Android pattern) meant the listener was almost
   always torn down before a single event arrived. Fixed by registering
   once in `onCreate()` and only unregistering in `onDestroy()`.
2. **Not a privilege wall.** Genuinely tried signing this as
   `android.uid.system` (same platform key as `sysprobe`) on the theory
   that the sensor only fed `com.njda.carplay`'s privileged
   `SensorService`. `dumpsys sensorservice` proved that wrong -- the
   sensor was already delivering real data to a system listener at
   ordinary registration, and our own connection worked identically once
   (1) was fixed, at normal app privilege. Reverted back to a throwaway
   debug key -- least privilege that works.
3. **A one-time HAL landmine, self-inflicted, now avoided.** While
   debugging, force-stopping `com.njda.adapter` (the system component
   that had held this sensor open since boot) to test an exclusivity
   theory left the HAL's flush handshake permanently stuck at "First
   flush pending" in `dumpsys sensorservice` -- for every subsequent
   client, including `com.njda.adapter`'s own respawned process. Neither
   waiting nor physically shaking the car un-stuck it. Only a reboot
   did. **Don't force-stop `com.njda.adapter` (or likely `com.njda.carplay`
   /`com.njda.aauto`) to test sensor behavior** -- it's apparently not
   safe to kill and restart mid-session on this vendor HAL.

4. **Leaving the service running for hours silently destroys its own
   data.** `writeLine()` rotates (deletes and restarts) any log file
   once it crosses 5MB -- fine for a bounded test drive, fatal for a
   service left running afterward: a real ~1 hour drive test
   (2026-09-22, `AccelLoggerService` survived the whole drive without
   being killed, confirming the foreground-service fix works) was left
   running another ~8 hours unattended and rotated itself dozens of
   times over, destroying the drive's own `accel.log` and leaving only
   the last couple minutes of stationary noise. Stop the service (`adb
   shell am stopservice -n com.geely.accelprobe/.AccelLoggerService`)
   as soon as the drive/test is over, not "whenever I get back to it."

**`TYPE_LINEAR_ACCELERATION` is a dead end -- confirmed for real, not
just a quick test: registered `active` for the full ~9 hour run above
and never delivered one single event.** `LINEARACCEL (MTK)` exists,
registers, and shows `status: active` in `dumpsys sensorservice`
immediately (no flush-stall like raw `ACCELEROMETER` above) -- but after
16+ seconds it had delivered zero actual events, confirmed live, while
`ACCELEROMETER` kept streaming the whole time. A sensor descriptor this MTK hub
advertises without actually wiring up. `AccelProbeActivity` still
registers it (harmless, and the finding is worth keeping visible), but
`linear_accel.log` will never be created. Don't spend more time on it
without a new lead.

The DIY substitute works and is worth using: `correlate.py` computes a
`lin_magnitude` column by estimating gravity as a rolling average of the
raw accelerometer and subtracting it. `AccelLoggerService` runs the exact
same algorithm live on-device too (same `GRAVITY_WINDOW` = 200 samples,
~0.5s at 400Hz) -- but deliberately does NOT log it to its own file.
Every sample the algorithm needs is already in `accel.log`, so a
`linear_accel_computed.log` would just be a byte-for-byte-derivable
duplicate, doubling the accelerometer write rate for zero new
information (tried it, then reverted -- see the field's own comment on
`AccelLoggerService.latestLinMag`). The on-device copy exists only to
back a live readout in `AccelProbeActivity` -- a real-time "does this
look like near-zero at rest / spike under motion" check that pulling and
post-processing logs can't give you. Measured over a real drive: the
post-processed `lin_magnitude` roughly doubles the correlation with OBD2
power (0.053 ->
0.126) versus
plain `|magnitude - 9.8|`, and cleans up which events rank as "biggest"
-- see `correlate.py`'s own docstring for the exact numbers.

**No ambient light sensor is exposed to Android at all, anywhere --
confirmed both ways.** Neither the standard Sensor framework
(`TYPE_LIGHT`, see above) nor the car's own vehicle-property layer
exposes a raw light/lux reading: `dumpsys car_service` has
`HEADLIGHTS_STATE`, `HEADLIGHTS_SWITCH`, and `BODY_LIGHT_HEAD_AUTO_ON`
(the auto-headlight feature's own enable toggle), but no property
carrying an actual light level. Whatever physical light/rain sensor
triggers the OEM's automatic headlights (there clearly is one -- the
car does it) lives entirely on the body-control side and only ever
surfaces to Android as the already-decided `HEADLIGHTS_STATE`
on/off -- never the raw signal. That also answers the earlier ABRP/CarPlay
question: `com.njda.carplay` most likely reads this same
`HEADLIGHTS_STATE` as its day/night proxy, which is why a few seconds
of shade under a bridge is enough to flip it.

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
same drive, then pull both files:

```bash
adb pull /sdcard/Android/data/com.geely.drivemem/files/obd2-reading.log
```

**Don't join on the `wall` column by exact match** -- that was the first
draft of this doc and it's wrong. `Obd2Reader.POLL_MS` is 2000, so OBD2
logs once every 2 seconds while the accelerometer logs ~50 rows/second; an
exact-string `join` only keeps the handful of accel rows that happen to
land in the same clock-second as one of those rare OBD2 lines and silently
drops the rest -- the vast majority of the data, with no warning.

Use `correlate.py` instead: it matches every accel row to the *nearest*
OBD2 reading in time and reports `match_dt_ms`, how far off that match
actually was, so a bad pairing is visible instead of hidden.

```bash
./correlate.py accel.log obd2-reading.log > correlated.tsv
```

`correlated.tsv` columns: `wall  epochMs  x  y  z  magnitude  lin_magnitude
match_dt_ms  soc  voltage  current  powerKw  battTempC  speedKmh`. Filter
out rows where `match_dt_ms` is large (over ~1000) before trusting a
comparison -- those accel samples simply don't have a nearby OBD2
reading. Even a good match is only accurate to about +-1s:
`obd2-reading.log` only stores whole seconds (`Obd2Reader.LOG_FMT`), it
has no millisecond column, so that's the real ceiling on how tightly
these two logs can ever line up -- not something a smarter join can fix.

`magnitude` is raw `TYPE_ACCELEROMETER` -- gravity baked in.
`lin_magnitude` is the software gravity-removed version (see "Status"
above for why -- `TYPE_LINEAR_ACCELERATION` itself is a dead end on this
unit) and correlates noticeably better; prefer it. Sensor mounting
orientation relative to the car's own axes is still unknown either way
-- `x`/`y`/`z` individually aren't yet meaningful as "forward" or
"lateral", only the magnitude is trustworthy so far.
