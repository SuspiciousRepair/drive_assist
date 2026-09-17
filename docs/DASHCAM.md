# Hardware Dashcam Architecture

Technical specification for the continuous multi-camera hardware dashcam subsystem, including video encoding, WebVTT telemetry, recovery, storage lifecycle, and the recording gallery/player. The settings and player improvements are implemented in this repository; no third-party APK code or assets are embedded.

---

## 1. System Architecture & Components

The recorder is a headless, privileged service inside `modehelper` (`android.uid.system`). It attaches directly to the vehicle's Extended View System (EVS) rendering engine. Drive Assist remains a separate, ordinary app responsible for settings and recorded-video playback.

```text
EVS / bdstar.render.engine
        │ one composite camera surface
        ▼
EGL / SurfaceTexture fanout
        ├── encoder output
        │       ▼
        │   MediaCodec hardware H.264 encoder
        │       ├── MediaMuxer ──> dash_*.mp4.tmp ──> dash_*.mp4
        │       └── write-ahead ─> dash_*.h264
        └── bounded texture handoff ──> optional live preview
                                      (separate EGL thread/context)

Vehicle properties + GPS bearing
        │ sampler, approximately 1 Hz
        ▼
Vtt writer ──────────────> dash_*.vtt.tmp ──> dash_*.vtt
```

### Core Components

* **`EvsClient.java`**: Interfaces with `bdstar.render.engine` over Binder, requesting the `dvr` function and supplying a surface.
* **`EvsFrameFanout.java`**: Receives one EVS input, renders to the encoder, and supplies an optional preview through a bounded two-texture handoff. Preview output runs on a separate shared EGL context/thread.
* **`DashcamPreviewActivity.java`**: Displays the helper stream in a normal-UID TextureView; it does not open another camera.
* **`DashRecorder.java`**: Configures `MediaCodec`, writes encoded samples to both MP4 and raw H.264, samples telemetry, and rotates segments.
* **`Vtt.java`**: Formats sampled telemetry as WebVTT cues.
* **`DashReceiver.java` / `ModeHelperService.java`**: Handle recording/configuration broadcasts, persisted options, auto-start, and shutdown.
* **`DashcamOptions.java`, `DashcamStorage.java`, `DashcamBudget.java`**: Validate options, resolve the next segment's destination, and evict eligible old clips on that volume.
* **`DashcamSettings.java` / `Clips.java`**: Discover app-accessible storage and list/protect clips without moving them between volumes.
* **`DashcamPanel.java` / `ClipPlayerActivity.java`**: Provide recording settings, the asynchronous gallery, and recorded-video playback/framing controls.

### Control Interface

The app targets the helper's explicit receiver, so configuration can reach it even when its service was stopped:

```bash
# Start recording
am broadcast -n com.geely.modehelper/.DashReceiver -a com.geely.modehelper.DASHCAM --ei on 1

# Stop recording
am broadcast -n com.geely.modehelper/.DashReceiver -a com.geely.modehelper.DASHCAM --ei on 0

# Save options without issuing a recording start/stop command
am broadcast -n com.geely.modehelper/.DashReceiver -a com.geely.modehelper.DASHCAM \
  --ei on -1 --ei dashcam_segment_minutes 3 --es dashcam_storage internal \
  --ei dashcam_limit_gb 10
```

Configuration-only requests preserve the recording on/off preference and do not implicitly start a cold recorder. The service also accepts existing `SET_MODE` option extras while its dynamic receiver is registered. The main app and privileged helper must both include this implementation for the new settings to affect recordings.

### Operational Lifecycle

1. **Auto-Start**: On a normal helper startup, attempts recording once when the car connection is ready. The persisted `dashcam_on` preference defaults to true; an explicit Off survives restart.
2. **Continuous Operation**: While enabled and the head unit/camera engine are running, records successive clips in all gear states, including `PARK`. This does not guarantee recording during vehicle sleep or power loss.
3. **Segment Rotation**: Requests a keyframe after the selected duration, then closes the old segment and opens the next at a keyframe. Duration and destination are captured when each segment opens. A settings change does not interrupt, relocate, or shorten the current clip. Valet transitions or a protected event can also request earlier rotation.
4. **Graceful Teardown**: `ACTION_SHUTDOWN` requests stop and allows a short interval for finalization. Success depends on remaining shutdown time and storage availability; interrupted output is retained for recovery.
5. **Single Recorder Ownership**: On received during teardown waits until the previous worker releases its encoder resources; a later Off cancels that restart.

---

## 2. Video Pipeline & Storage Budget

The native EVS engine supplies one 2x2 composite containing front, rear, left-mirror, and right-mirror views. A single SurfaceTexture input feeds the encoder and optional live preview; opening or closing the preview does not reattach EVS.

### Video Encoding Specifications

| Parameter | Configuration | Notes |
|---|---|---|
| **Resolution** | 1920 × 800 | Four cells of approximately 960 × 400 |
| **Codec** | H.264 / AVC | `video/avc`; no explicit profile or bitrate mode is requested |
| **Bitrate** | 16.0 Mbps target | Actual rate depends on the encoder and scene |
| **Frame Rate** | 25 fps requested | Actual EVS frame delivery requires hardware verification |
| **Keyframe Interval** | 1 second | Rotation requests another keyframe when needed |
| **Segment Length** | 1 / 3 / 5 / 10 minutes | Default 5; rotation occurs at a subsequent keyframe |
| **Timestamp Model** | Segment-relative `uptimeMillis()` | Strictly increasing PTS; suspended time is excluded |

At 16 Mbps, nominal video payload is 2,000,000 bytes/second:

| Segment | Approximate completed video size |
|---|---|
| 1 minute | 120 MB / 114 MiB |
| 3 minutes | 360 MB / 343 MiB |
| 5 minutes | 600 MB / 572 MiB |
| 10 minutes | 1.20 GB / 1.12 GiB |

These estimates exclude container/sidecar overhead and actual bitrate variation. The UI budget labelled GB is implemented as GiB (`1024³` bytes): 10 GiB holds roughly 89 minutes (1.5 hours) of completed video before protected footage, recovery fragments, and temporary files reduce capacity. During recording, the MP4 and write-ahead H.264 both occupy disk space; a full 5-minute segment can temporarily use about 1.20 GB before the raw copy is removed.

### Storage Directory & Permissions

Primary device storage retains the existing shared path:

```text
/sdcard/Android/data/com.geely.drivemem/files/dashcam/
```

* **Internal** means the app's external-files directory on primary storage, not its private `/data` directory. Drive Assist uses Android's external-files API; the helper retains `/sdcard/...`. Their mapping must be verified for the vehicle's Android user configuration.
* On the intended Android 9 head unit, the privileged helper writes here and Drive Assist reads its own files without requesting broad runtime storage permissions.
* The dropdown discovers mounted, writable removable volumes from `Context.getExternalFilesDirs(null)`. A volume ID maps only to `/storage/<id>/Android/data/com.geely.drivemem/files/dashcam/`. Arbitrary folder paths are not accepted.
* At the next segment, the helper checks that the preferred removable volume is still mounted, writable, and confined to the expected app directory. Missing, read-only, or inaccessible removable storage falls back to primary storage. The saved preference remains, allowing a later segment to use the drive after reconnecting.
* Removing USB during an open segment is different: the current file cannot be moved transparently. A write/finalization failure stops that worker and retains available recovery files. Restart after storage is available; uninterrupted failover of an already-open file is not guaranteed.
* Held clips live in the neighboring `keep/` directory. Hold, Release, subtitle/thumbnail moves, and pending `.hold` markers stay on the clip's original volume even after the recording preference changes.
* The gallery includes primary storage and all currently mounted eligible removable volumes. Disconnected files reappear after reconnection. Selecting a new destination does not migrate or delete old recordings.
* These are app-specific files: uninstalling Drive Assist may remove them. Copy footage that must survive an uninstall elsewhere first.

### FAT32, exFAT, and Read-only Drives

The app uses Android-mounted volumes and does not restrict destinations by filesystem name. A FAT32 or exFAT USB drive can be used when the head unit firmware mounts it and exposes the app directory. This does not bundle an exFAT driver or make an unsupported drive mountable. Filesystem support depends on the device kernel and storage stack; Android 9 alone does not establish exFAT support. See [Android kernel filesystem support](https://source.android.com/docs/core/architecture/android-kernel-file-system-support) and [app-specific external storage](https://developer.android.com/training/data-storage/app-specific).

Read-only mounted drives remain available in the clip library for playback, while recording destinations require writable storage. Hold, Release, and Delete are disabled for read-only clips. A disconnected or unsupported drive is not treated as an empty, usable recording destination. Byte accounting uses `long`, including files larger than 4 GiB; this is separate from testing a physical exFAT drive or the head unit's ability to play a particular large video.

### Continuous Loop and Retention Budget

`DashRecorder.enforceBudget` runs before opening a segment and after segment completion/recorder teardown, on that segment's actual volume. The UI accepts 1–500 GiB, default 10.

1. Counts regular files in the recording directory and its `keep/` child, including completed clips, sidecars, thumbnails, temporary streams, and recovery fragments.
2. Deletes the oldest completed, unprotected `.mp4` clips first, by modification time, together with their `.vtt` and `.jpg` files. Cleanup stops when usage is at or below the budget or no eligible clips remain.
3. Never automatically deletes clips in `keep/` or completed clips with a pending `.hold` marker. Protected footage **counts toward** the budget and leaves less room for the continuous loop.
4. Does not evict open `.mp4.tmp` files, raw `.h264` recovery files, or other incomplete fragments. New segments use a distinct filename if a matching recording/protected/recovery filename already exists.

This is periodic retention, not a hard per-write disk reservation. Usage can exceed the target while a clip is open, especially with the duplicate write-ahead stream. If protected/recovery data leaves no erasable footage, cleanup cannot restore the target. New recording may exceed that target until the filesystem fills; failed writes stop recording rather than overwrite protected footage. Delete or export unwanted protected/recovery files and restart as needed.

---

## 3. WebVTT Telemetry Sidecar (`.vtt`)

When vehicle data is available, segments receive approximately one cue per second. A separate sampler prevents slow vehicle-property calls from blocking encoder output. This is sampled telemetry, not frame-accurate measurement.

### Sample Track

```webvtt
WEBVTT

00:00:00.000 --> 00:00:01.000
2026-09-17 12:00:00 · 62 km/h · D · 24.5° · NE 45° · max 80

00:00:01.000 --> 00:00:02.000
2026-09-17 12:00:01 · 64 km/h · D · 24.5° · NE 45° · max 80
```

### Data Schema & Sources

| Metric | Source | Property ID / Provider |
|---|---|---|
| **Wall-clock time** | System clock | `yyyy-MM-dd HH:mm:ss` |
| **Speed** | Vehicle property | `291504647`, interpreted as km/h by the existing helper |
| **Gear** | Vehicle property | `289408001`: 1=N, 2=R, 4=P, 8=D |
| **Ambient temperature** | Vehicle property | `557884279`; helper converts `(raw - 80) / 2` to °C |
| **Heading** | Android GNSS | Bearing, shown when available and reported speed exceeds 3 km/h |
| **Speed limit** | Vehicle-property candidates | Camera `0x2140b029`, navigation `0x2140303a`, then navigation-speed `0x2140a405`; accepts 5–200 |

Drive/regen, coordinates, and turn signals are not written by the current sampler. Unavailable speed is `?`; other unavailable fields are omitted. The most recent text can remain in use while the car connection is unavailable, so the presence of a cue does not guarantee fresh measurements.

Video PTS and cue boundaries share the segment-relative uptime clock; cue boundaries are rounded to whole seconds. This avoids depending on EVS timestamps but does not guarantee zero timing error. Video can exist without usable subtitles. Gallery duration is estimated from cue count rather than parsing the video container: missing/delayed telemetry can make it unknown or shorter than the actual clip. Playback uses the media timeline and renders available cues as a custom overlay.

---

## 4. Crash Resilience: Write-Ahead Stream

An MP4 needs a finalized `moov` index. Abrupt power loss or process termination can leave `.mp4.tmp` unplayable.

1. **Parallel H.264 Output**: Encoded access units are appended to a raw `.h264` companion as well as the muxer. Codec SPS/PPS data is written from the encoder output format.
2. **Successful Close**: Only after successful muxer finalization and the same-directory `.mp4.tmp` → `.mp4` rename is the raw stream removed. A thumbnail is generated once at close.
3. **Failed Close**: Raw and temporary files remain; failed output is not labelled as a completed MP4.
4. **Manual Recovery**: Copy a retained `.h264` to another machine before attempting remux, for example:

   ```bash
   ffmpeg -r 25 -i crash.h264 -c copy recovered.mp4
   ```

Recovery is best effort. Incomplete access units, filesystem buffers, abrupt power loss, and removable-drive errors can lose or corrupt the tail. Remuxing at an assumed 25 fps may not recreate exact capture timing. WebVTT is buffered, so an interrupted `.vtt.tmp` may contain fewer cues or no usable cues. Recovery does not recreate missing telemetry, thumbnails, or unwritten frames.

### Gallery State Representation

* **Recording**: A `.mp4.tmp` modified within 15 seconds provides evidence of recent recording. Playback/deletion are disabled. Hold writes a neighboring `.hold` marker without moving the open file. This age heuristic can briefly lag stop/failure; it is not a direct EVS health report.
* **Completed**: A finalized `.mp4` is playable. Hold moves the clip and sidecars into that volume's `keep/`; Release reverses the move. Pending markers are removed only when protection succeeds.
* **Incomplete**: A `.h264` without a completed MP4 or recently updated `.mp4.tmp` is listed as a recovery fragment. The gallery offers deletion, not an in-app remux action. Deleting the row also removes its incomplete temporary siblings.

---

## 5. Remote Access Scope

Clips and sidecars remain local. WebVTT can support an external browser `<track>` or a future export, but this dashcam implementation does not publish `.vtt` files through MQTT or host a port-8080 video server. Existing vehicle telemetry/Home Assistant features do not imply automatic dashcam transfer. Copy the clip and matching sidecar explicitly for playback or recovery elsewhere.

---

## 6. Dashcam Gallery & Playback UI

`DashcamPanel` is embedded in the Recordings section of `TelemetryActivity`:

* **Recording controls**: On/Off, 1/3/5/10-minute segment buttons, budget, and a popup destination list. Duration/destination choices save immediately and apply to the next segment. Saving a preference is not proof of successful recording; the UI checks files and indicates when ModeHelper is absent.
* **Gallery**: A recycled list with background scanning and cached thumbnail decoding. It refreshes while visible and offers manual refresh, Play, Hold/Release, and confirmed deletion. Usage totals cover the mounted library; the destination card reports free space for its resolved volume.
* **Playback**: The existing 2x2 composite, front/rear/left/right dewarped views, draggable orbit view, seek/pause controls, and custom WebVTT overlay remain available.
* **Per-camera framing**: In a single-camera view, Adjust view opens horizontal field of view (70–130°) and vertical angle offset (−20–+20°) controls. Changes affect playback immediately and persist separately for each camera. Restore defaults resets only that camera. Vertical field of view scales with zoom to preserve projection aspect; source orientation/lens parameters remain unchanged.
* **Original footage**: Framing affects display only; it does not rewrite the MP4, change encoder settings, or add image detail. The all-camera and orbit views keep their existing rendering and do not use single-camera sliders.

### Live Preview and Background Recording

Live preview opens from the Recordings header. Opening it only attaches a display surface; Start recording is an explicit action when the recorder is stopped. Returning Home, switching apps, or closing the preview detaches that display without sending a recorder Stop. The existing foreground ModeHelper service continues the loop while the head unit and camera engine remain awake. Stop recording remains a separate explicit control.

The preview receives the four-camera composite, scaled to 960 × 400 before display; the recorded output remains 1920 × 800. It is a full preview screen, not a floating window. Playback angle controls apply to completed clips only.

Preview requests target the helper receiver and carry a PendingIntent created by the installed Drive Assist app. The helper checks its creator package and UID before accepting a surface. Each surface lifecycle has a UUID; stale detach requests cannot remove a newer preview. Heartbeats renew a short lease, and a vanished UI eventually loses its preview without stopping recording. Surface/texture resources are released after detach acknowledgment, with bounded cleanup if the service disappears.

The UI requires actual TextureView updates and a helper frame acknowledgment before showing Live. A missing helper, missing camera frames, or renderer errors show a placeholder/status instead. Encoder and preview rendering use separate threads with bounded shared textures, so preview backpressure does not create an unbounded queue or block the encoder's render thread. EGL/codec failures remain reported failures, not evidence of a working camera.

### Validation Limits

Validation for these changes is limited to host-side tests, Android emulator UI/playback checks, and a synthetic EGL/encoder smoke test. An Automotive emulator does not supply the Geely `bdstar.render.engine` service or production vehicle telemetry. It cannot establish real four-camera capture, encoder quality/frame rate, physical USB throughput/removal recovery, shutdown durability, or compatibility of both apps' shared paths on the target head unit. Those require the matching helper build and vehicle hardware verification.
