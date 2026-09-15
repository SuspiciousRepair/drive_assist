# Diagnostic Logging

What actually exists on-device for diagnosing Drive Assist and modehelper —
corrected 2026-09-15 after the previous version of this document described a
`diag.log` ring buffer, a `DriveLog.event` call site, and an HTTP log-upload
webhook. None of that exists in the code. There is no `DriveLog` class, no
`diag.log` file, and no `diag_upload_url` setting anywhere in this repo. That
architecture was either planned and never built, or removed at some point
without this doc following — either way, do not rely on it.

---

## 1. The default: Android logcat

The overwhelming majority of logging in both apps is a plain `Log.i/w/e(...)`
call, tagged `"ModeHelper"` (modehelper) or the calling class's own tag
(drivemem). This goes to **logcat only** — the OS's own circular buffer,
cleared automatically (and definitely on reboot). Nothing in either app
persists it, and nothing needs to: `adb logcat` while reproducing an issue is
the normal way to read it.

```
adb logcat | grep -E "ModeHelper|drivemem"
```

## 2. On-disk diagnostic files — developer-triggered only

A handful of `Diagnostics.java` broadcast probes (started by hand over `adb`,
not from any Settings UI) write a plain-text file to
`getExternalFilesDir(null)` — i.e.
`/sdcard/Android/data/com.geely.drivemem/files/`:

| File | Written by | Lifetime |
|---|---|---|
| `obd2-reading.log` | `Obd2Reader` | Capped at 5 MB — deletes and restarts itself once exceeded. |
| `abrp-attempt.log` | `AbrpUploader` | Capped at 5 MB, same self-rotation. |

Both are read with `adb pull` after reproducing whatever is being diagnosed;
neither uploads anywhere.

## 3. Legacy files — dead, migration-only

`charge.log` and `odo.log` are pre-SQLite flat-file formats. `DbMigration`
still knows how to parse them **once**, on first upgrade past the SQLite
switch, to import old history. Nothing writes either file anymore.

## 4. What used to exist and doesn't anymore

Two exploration-only probes were removed on 2026-09-15, once the question
each was built to answer had one:

- **`limits.log`**: `modehelper`'s `DashRecorder` briefly kept this file
  inside the dashcam folder, logging the raw camera/nav speed-limit readings
  it was evaluating for the subtitle overlay's "max NN" display. It had no
  cap and grew forever. Which candidate property to trust is now answered
  (see `plausible()` and the fallback order in `DashRecorder`), so the
  logging was removed rather than capped — the speed-limit reading itself is
  still live in the dashcam subtitles, only the standalone log is gone.
- **`power-probe.log`** / `PowerProbe.run()`: a manually-triggered probe
  (`com.geely.drivemem.POWERPROBE` broadcast) that sampled power/energy
  properties over a whole drive to a file, for validating which car
  properties the real energy integrator should trust. Removed along with its
  `Diagnostics` dispatch entry once that validation work was done.

## 5. Security note

No third-party analytics, crash-reporting, or telemetry SDK is embedded in
either app. Nothing above leaves the device on its own; a file only travels
anywhere when pulled by hand over `adb`.
