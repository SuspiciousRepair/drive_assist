# Runtime efficiency review

Scope: what Drive Assist costs the head unit while it runs (CPU, memory,
flash writes, log volume). Code structure is out of scope here.

## How it was measured

2026-09-26 ~08:45, car on, parked, dashcam recording, Drive Assist in the
background. Per-thread CPU from `/proc/<pid>/task/*/stat` over 10 s; log
volume from `logcat -b all` over 10 s. Head unit: MediaTek, 8 cores, 6 GB
RAM, Android 9 (API 28).

Not measured yet: Drive Assist **in the foreground** (ComfortActivity art,
cards) and **while driving**. Repeat this in both states before acting on
the UI side.

## Results

| Process / thread | CPU (% of one core) |
|---|---|
| `com.geely.modehelper` total | ~19 |
| - `VideoTrackEncod` (MediaMuxer writer) | 5.4 |
| - `HeapTaskDaemon` (garbage collector) | 5.1 |
| - `dashcam` (drain loop) | 5.0 |
| - `MediaCodec_loop` + `CodecLooper` | 3.2 |
| - `dashcam-tele` | 1.3 |
| `com.geely.drivemem` total | ~3 (`obd2-reader` 1.0, `car-actor` 0.7) |
| `logd` | 29 |

Memory: drivemem ~15 MB PSS and modehelper ~10 MB PSS in the background.
No problem there.

Log volume: ~350 lines/s. Most is vendor code: the VHAL
(`CmdSmdManager`, pid 293) ~195/s, and `MDP` + `MtkOmxVenc` 25/s each (one
line per frame our encoder produces). Each property read by drivemem makes
the vendor stack log two lines (`CarPropertyValue` E, `VehicleModules` D),
~19/s in total.

The main app is lean in the background. The cost is in the dashcam.

## Findings, largest first

### E0. Our apps run entirely in the interpreter (fixed in v0.4.1)

This head unit has `dalvik.vm.usejit=false`. Apps are installed with
`speed-profile`, which compiles only the methods named in a usage profile,
and the profile is collected by the JIT. With the JIT off there is never a
profile, so `speed-profile` compiles nothing: every line of drivemem and
modehelper, including the dashcam drain loop, runs interpreted, forever.

Measured: clip recovery read a 347 MB segment at ~0.4 MB/s (15 minutes).
After `cmd package compile -m speed -f com.geely.drivemem`, a 316 MB
segment took 19 seconds.

The compile is reset by every install, including OTA. Fix: after an
install, compile to `speed`. Options, to test in this order:
1. modehelper (uid system) calls the hidden
   `IPackageManager.performDexOptMode(pkg, false, "speed", true, true, null)`
   by reflection, from `InstallResultReceiver` after each install, and once
   for itself at boot.
2. Failing that, `build.sh` runs the compile over adb after its install,
   and the OTA path stays interpreted until the next adb session.

Do not change `dalvik.vm.usejit` itself: it is a system-wide setting that
the OEM apps were tuned with.

### E1. Every video frame is written to flash twice

`DashRecorder.Seg.write()` sends each frame to `MediaMuxer` (`.mp4.tmp`)
and to the raw `.h264`. At 16 Mbit/s that is ~4 MB/s, ~14 GB per hour of
driving, on the head unit's eMMC. It doubles wear and doubles the space of
the live segment.

Fix: `DASHCAM-RELIABILITY-ROADMAP.md` Phase C (fragmented MP4: one write,
crash-safe, no sidecar).

### E2. One heap allocation per frame drives the garbage collector (fixed)

`Seg.write()` does `new byte[i.size]` for every frame to copy it into the
raw stream: ~25 allocations/s, ~2 MB/s of garbage. That is most of the
5 % in `HeapTaskDaemon`.

Fix: write the `ByteBuffer` directly with `FileChannel.write(buf.duplicate()
.position(off).limit(off+size))` — no copy, no garbage. Tiny change;
ship it with the dashcam Phase B.

### E3. The bitrate may be higher than the picture needs

16 Mbit/s for a 1920x800 composite of four cameras. At that rate the
10 GB budget holds ~85 minutes. Record the same drive at 8, 10 and 16
Mbit/s and compare plates and signs. 8 Mbit/s would double the history and
halve flash writes.

### E4. Work on the encoder thread drops frames at every rotation (fixed)

At each 5-minute rotation, `Seg.finish()` runs `MediaMetadataRetriever` on
a 600 MB file (thumbnail) and `enforceBudget()` lists and deletes files,
all on the thread that must drain the encoder. Move both to a single
worker thread.

### E5. Property reads trigger vendor log spam

The vendor VHAL logs every read. Our reads are modest, but each one costs
log CPU in `logd`. Where a property has a change callback, subscribe
instead of polling; where polling stays, share one read between consumers
(`CarDataHub` is the natural place). Low priority: the bulk of `logd` load
is the VHAL talking to the MCU, which we do not control.

### E6. Telemetry sampler reads three speed-limit properties every second

`DashRecorder.sample()` reads camera limit, nav limit and nav speed each
second even when parked. Skip the three when speed is 0. Minor.

## Documentation that disagrees with the code

Fixed on 2026-09-26 in `docs/DASHCAM.md` and the code comments: bitrate
mode, segment size, budget duration, `keep/` in the budget, the `.h264`
kept on a failed close, the sidecar format, the "nothing is lost on
suspend" comment, and the HTTP server / MQTT VTT section (now marked
planned).

The `docs/OEM-MODULES.md` voice-bridge entry now says that no voice
assistant is installed on this unit.
