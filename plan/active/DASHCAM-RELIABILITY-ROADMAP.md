# Dashcam reliability roadmap

## Status (2026-09-26)

Phase A is done and verified on the car. D1-D5 are fixed, plus two more
problems found while testing on the car:

- **D1b.** The SPS/PPS in the track format (`csd-0`/`csd-1`) had the same
  three-byte start codes. Android 9 then copies them verbatim as the avcC
  box, so the first recovered clip did not decode ("non-existing PPS 0
  referenced"). Fixed in `ClipRecovery.csd`.
- **D1c.** Recovery took 15 minutes per segment, past the UI's 5-minute
  wait. The per-byte reader runs in the interpreter because the car has
  the JIT off (see `RUNTIME-EFFICIENCY-REVIEW.md`, E0). The reader now
  scans its own buffer. With the app fully compiled, a 5-minute segment
  recovers in about 20 seconds.

Both orphans on the car were recovered in the app and play without a
single decode error (3958 frames, checked with FFmpeg on a PC).

Phase B is done and verified on the car (2026-09-26):

- D6: `RawStream` syncs the `.h264` at every key frame and writes the
  encoder's buffer without a copy (E2 in the efficiency review).
- D7: the helper restarts a recorder that stopped on its own, with
  backoff (`RestartBackoff`). An owner's "off" is never undone: tested
  on the car.
- D8: `dashcam/recorder.state` names the live segment; Clips shows it as
  recording and recovery refuses it: tested on the car.
- D9: the segment closes on screen-off and on CarPowerManager
  `SUSPEND_ENTER` (`PowerWatch`), and the recorder stops on
  `SHUTDOWN_ENTER`. Screen-off tested on the car: new segment 55 ms
  later. A real suspend has not been observed yet.
- D10 (encoder-thread part): segments close on their own thread; tested
  on the car.

Not done: the recorder does not detect an EVS stall (running, no
frames). Whether EVS stops delivering while the screen is off without a
suspend is unknown, so a watchdog on frames could restart-loop; measure
first.

Phase C is done and verified on the car (2026-09-26):

- `FragmentedMp4` replaces `MediaMuxer` plus the raw `.h264`: one write per
  frame, each fragment synced, a playable file at every moment.
- A reserved `sidx` is filled at close, so Android 9's player seeks.
  Checked on the car with MediaExtractor, MediaMetadataRetriever and
  MediaPlayer, and with FFmpeg on real recordings.
- A dead `.mp4.tmp` is repaired automatically. Tested with a hard kill of
  the helper mid-segment: a 116 s clip, every frame decodable.
- Segment names no longer collide within one second; stray sidecars are
  cleaned up.

Still open:

- The bitrate A/B (E3 in the efficiency review) needs a person to compare
  plates and signs at 8, 10 and 16 Mbit/s.
- The recorder does not detect an EVS stall (see Phase B note).
- A real car suspend has not been observed with the new hooks yet.

## Problem

Segments break (an unplayable `.mp4.tmp` plus a `.h264` "orphan"), and the
in-app **Recover** button cannot turn the orphan back into a video. The
desktop tool (`tools/recover-dashcam.sh`, FFmpeg) works; the on-car path
does not.

## Evidence (measured on the car, 2026-09-26)

Dashcam directory `/sdcard/Android/data/com.geely.drivemem/files/dashcam`:

| Stem | Files | Note |
|---|---|---|
| `dash_20260925_175025` | 347 MB `.h264`, 347 MB `.mp4.tmp`, 8 KB `.vtt.tmp` | orphan |
| `dash_20260925_175410` | 316 MB `.h264`, 316 MB `.mp4.tmp`, 8 KB `.vtt.tmp` | orphan |
| `dash_20260915_080419.hold`, `dash_20260915_080949.hold`, `dash_20260919_215230.hold` | 0-byte markers, **no clip** | held event clips that were evicted |

- Closed segments are ~600 MB (16 Mbit/s x 300 s), not ~225 MB.
- Both orphans end right before a boot whose recorded reason is
  `reboot,adb` (`/data/system/dropbox/SYSTEM_BOOT@...`, `sys.boot.reason`).
  `adb reboot` sets `sys.powerctl` directly and skips the framework
  shutdown, so `ACTION_SHUTDOWN` is never delivered and the segment never
  closes. (CLAUDE.md already forbids `adb reboot` for the speaker pop.)
- Every `.vtt.tmp` in an orphan is exactly 8192 bytes: one
  `BufferedWriter` buffer. Everything after the first ~90 s of cues is lost.
- Tombstones `tombstone_04`..`07` (2026-09-25 11:21-11:31, process
  `com.geely.drivemem:cliprecovery`) and `tombstone_09` (2026-09-24, main
  process) all abort in the same place:

  ```
  #00 libc.so abort
  #01 libstagefright.so MPEG4Writer::addLengthPrefixedSample_l
  #02 libstagefright.so MPEG4Writer::addMultipleLengthPrefixedSamples_l
  ```

- The orphan's own bytes start `00 00 00 01 67 ...`: the recorder writes
  **4-byte** start codes.

## Bugs, most harmful first

### D1. In-app recovery always crashes (root cause of "cannot restore")

`AnnexB.Reader` normalizes every start code to 3 bytes (`00 00 01`), and
`ClipRecovery.writeAccessUnit` hands those to `MediaMuxer`. Android 9's
`MPEG4Writer` strips only a 4-byte `00 00 00 01` prefix
(`StripStartcode`). The 3-byte prefix stays in the sample, and
`addMultipleLengthPrefixedSamples_l` then computes a NAL size of
`nextNalStart - currentNalStart - 4` = `3 - 4`, which underflows `size_t`
and aborts. This matches the tombstones exactly.

Fix: write each NAL as `00 00 00 01` + payload when building the sample.
Pull the sample building out of `writeAccessUnit` into a pure function and
unit-test it (4-byte codes, multi-slice frames). Verify on the car against
the two orphans above.

(Reading of AOSP 9 `MPEG4Writer.cpp`; confirm the line on the car by
running the fixed recovery, not by trusting this note.)

### D2. Recovered clips lose their telemetry

- `Vtt` is a `BufferedWriter` with no flush until close, so an orphan keeps
  only the first 8 KB of cues.
- `ClipRecovery.doRecover` then **deletes** `.vtt.tmp` instead of renaming
  it to `.vtt`. The clip list reads duration from the `.vtt`, so a
  recovered clip shows `--:--` and "(no data)".

Fix: flush the VTT once per cue (one line per second; cheap). In recovery,
rename `.vtt.tmp` to `.vtt`.

### D3. The safety net is deleted when a close fails

`Seg.finish()` swallows a `muxer.stop()` failure, then renames `.mp4.tmp`
to `.mp4` whenever it is non-empty and deletes the `.h264`. A failed close
therefore produces a broken `.mp4` listed as playable, and the only good
copy is gone.

Fix: rename and delete the `.h264` only when `stop()` returned normally.
Otherwise leave the pair as an orphan.

### D4. Held event clips are evicted

`saveCurrentSegmentForEvent()` (parked monitoring) drops a `<stem>.hold`
marker. Only drivemem's `Clips.list()` promotes a marker into `keep/`, and
only when someone opens the Clips screen. `DashRecorder.enforceBudget()`
ignores markers, so the ring buffer deletes the clip first. The three
orphaned `.hold` files on the car are that bug.

Fix: in `Seg.finish()`, if `hold` exists, move the closed clip into
`keep/` right there (modehelper owns the files at that moment). Keep the
drivemem sweep as a fallback, and have it delete markers whose clip is gone.

### D5. Orphans are never evicted

`enforceBudget()` deletes only `*.mp4` but counts every file in `used`.
Orphans (`.h264` + a worthless `.mp4.tmp`) stay forever: ~1.3 GB of the
10 GB budget today, so real clips rotate out faster.

Fix: once a segment is an orphan, its `.mp4.tmp` is useless (no `moov`) —
delete it at the next recorder start. Let orphans age out after N days, or
put them in the same oldest-first queue as `.mp4`.

### D6. The raw stream is not durable

`rawOut` is a plain `FileOutputStream`, never synced. After a hard power
cut the kernel may drop the last dirty pages (typically 5-30 s) — the
seconds around an incident.

Fix: `rawOut.getChannel().force(false)` at each key frame (once a second).

### D7. The recorder dies and stays dead

Any exception in `loop()` (EVS not ready at boot, codec error, a failed
`new MediaMuxer` at rotation, full disk) sets `running = false`.
`maybeAutoStart()` runs once per process. Nothing restarts it, and
drivemem cannot tell.

Fix: a supervisor in `ModeHelperService` restarts it with backoff while
`dashcam_on` is true, and writes a status file (`state`, `stem`,
`last_error`, `beat`) that the Clips screen reads.

### D8. A live segment can be "recovered" out from under the recorder

`Clips` calls a segment live when its `.mp4.tmp` was modified in the last
15 s. If frames stop (EVS stall after resume), the live segment is listed
as an orphan; **Recover** then deletes `.mp4.tmp`, muxes into the same
path, and deletes the `.h264` the recorder still has open.

Fix: use the status file from D7 (`stem` of the live segment). Recovery
refuses that stem.

### D9. Suspend is not a close point

The unit suspends instead of shutting down. `CarMode.powerManager()`
already reaches `CarPowerManager` by reflection. Close the segment on
`SHUTDOWN_PREPARE` / `SUSPEND_ENTER` and start a new one on resume, so a
later hard cut cannot hit an open segment.

### D10. Smaller issues

- Recovery assumes a constant 25 fps. Dropped encoder frames make a
  recovered clip play fast and drift from its cues. Write a small `.idx`
  (pts per frame) beside the `.h264`, or drop the need via Phase C.
- Thumbnail extraction and `enforceBudget()` run on the encoder thread at
  every rotation, so output is not drained and frames are dropped at every
  segment boundary. Move both to a worker thread.
- Two segments started in the same wall-clock second share a stem; the
  second overwrites the first. Add a suffix on collision.

## Phases

**A. Make recovery work and stop losing clips (small, testable)**
D1, D2, D3, D4, D5. Each is a few lines, and each gets a unit test where
the logic can be pulled out of Android.

**B. Make breakage rarer**
D6, D7, D8, D9, and the encoder-thread part of D10.

**C. Remove the need for recovery**
Replace `MediaMuxer` + raw sidecar with a small fragmented-MP4 writer
(`moov` with `mvex` up front, then one `moof`+`mdat` per 1 s GOP). A crash
loses at most the last fragment, there is no second copy on disk, and there
is nothing to recover. Check first that `ClipPlayerActivity`'s player on
Android 9 plays fragmented MP4 (the platform `MPEG4Extractor` supports
`moof`). Pair it with a bitrate A/B (see
`RUNTIME-EFFICIENCY-REVIEW.md`, E1/E3).

