# Hardware Dashcam Architecture

Technical specification for the continuous multi-camera hardware dashcam subsystem, including video pipeline architecture, WebVTT telemetry sidecar generation, crash resilience mechanisms, storage lifecycle, and Home Assistant integration.

---

## 1. System Architecture & Components

The dashcam is implemented as a headless, privileged background service running inside `modehelper` (`android.uid.system`). It attaches directly to the vehicle's Extended View System (EVS) rendering engine without intermediate user-interface dependencies.

```text
┌──────────────────────────────────────────────────────────────┐
│ modehelper (android.uid.system)                              │
│                                                              │
│  ┌─────────────┐     IGraphicBufferProducer     ┌─────────┐  │
│  │  EvsClient  │ ─────────────────────────────> │ EVS HAL │  │
│  └──────┬──────┘                                └─────────┘  │
│         │ Surface                                            │
│         ▼                                                    │
│  ┌──────────────┐                                            │
│  │ DashRecorder │                                            │
│  └──────┬───────┘                                            │
│         │ FragmentedMp4 (1 fragment per key frame, synced)   │
│         ▼                                                    │
│    dash_*.mp4                                                │
│                                                              │
│  ┌─────────────┐   CarPropertyManager   ┌──────────────────┐ │
│  │  VttWriter  │ <───────────────────── │ Telemetry Poll   │ │
│  └──────┬──────┘                        └──────────────────┘ │
│         │ Cues (1 Hz)                                        │
│         ▼                                                    │
│    dash_*.vtt                                                │
└──────────────────────────────────────────────────────────────┘
```

### Core Components

* **`EvsClient.java`**: Interfaces with `bdstar.render.engine` over Binder, requesting the `dvr` function and supplying a surface.
* **`DashRecorder.java`**: Configures `MediaCodec`, receives raw video frames, manages dual-stream encoding (MP4 + raw Annex-B H.264), and handles file rotation.
* **`Vtt.java`**: Samples vehicle telemetry once per second and formats synchronized WebVTT subtitle tracks.
* **`DashReceiver.java`**: BroadcastReceiver handling external start/stop triggers and system lifecycle events.

### Control Interface

The recorder is controlled via explicit broadcast intents:

```bash
# Start recording
am broadcast -a com.geely.modehelper.DASHCAM --ei on 1

# Stop recording
am broadcast -a com.geely.modehelper.DASHCAM --ei on 0
```

### Operational Lifecycle

1. **Auto-Start**: Triggers once on system boot after the first successful vehicle property read.
2. **Continuous Operation**: Records continuously across all vehicle states, including `PARK` (monitoring parked vehicle surroundings).
3. **Close Before Sleep**: The unit suspends rather than shutting down. When the screen goes off, or CarPowerManager announces `SUSPEND_ENTER`, the open segment closes at the next key frame and a new one starts, so a power cut during the sleep cannot orphan a long segment. `SHUTDOWN_ENTER` stops the recorder cleanly.
4. **Self-Restart**: If the recording loop stops on its own (EVS not ready, a codec error), ModeHelper restarts it while recording is wanted: at once, then after 30 s, 1, 2, 4, 8 and at most 10 minutes. Turning the dashcam off is never undone.
5. **Status File**: `dashcam/recorder.state` holds `state`, the open segment's `stem`, a `beat` refreshed every 5 s, and the last `error`. Drive Assist reads it to show the live segment as recording and to refuse recovering it.
6. **Graceful Teardown**: Intercepts `Intent.ACTION_SHUTDOWN` to finalize active segments, close `MediaMuxer`, write the `moov` atom, and remove temporary stream buffers. `adb reboot` bypasses the framework shutdown, so it never sends this broadcast and always leaves the active segment as an orphan.

---

## 2. Video Pipeline & Storage Budget

The native EVS engine renders a composite 2x2 camera quad (front, rear, left mirror, right mirror) directly into the hardware encoder's input surface.

### Video Encoding Specifications

| Parameter | Configuration | Notes |
|---|---|---|
| **Resolution** | 1920 x 800 | 4 camera views in 2x2 layout (~960x400 per camera) |
| **Codec** | H.264 / AVC | `video/avc`, Baseline profile |
| **Bitrate** | 16.0 Mbps | Target only; no bitrate mode is set, so the encoder default applies |
| **Frame Rate** | 25.0 fps | Native EVS camera sensor stream rate |
| **Keyframe Interval** | 1.0 s (`IFRAME_SEC = 1`) | Enables fast seeking and bounds crash data loss |
| **Segment Length** | 300 seconds (5 min) | ~600 MB per segment (measured) |
| **Timestamp Model** | Monotonic segment elapsed | Eliminates EVS hardware timestamp jitter and muxer rejections |

### Storage Directory & Permissions

Video clips and subtitle sidecars are stored in Drive Assist's external files directory:

```text
/sdcard/Android/data/com.geely.drivemem/files/dashcam/
```

* On Android 9 (API 28), `modehelper` (running as `uid system`) writes directly to this path, while Drive Assist reads it with zero runtime storage permissions (`READ_EXTERNAL_STORAGE` is not required).
* Held clips protected from eviction are stored in `/files/dashcam/keep/`.

### Ring Buffer Budget Management

`DashRecorder.enforceBudget` runs after each segment closes, with no segment
open. The logic is `SegmentFiles.evict`:
1. A dead fragmented `.mp4.tmp` is repaired into a clip (see section 4). An
   older build's `.mp4.tmp` (no `moov`) is deleted once 10 minutes cold if a
   `.h264` beside it holds the same frames. Subtitles and thumbnails with no
   video left beside them are deleted once 10 minutes cold.
2. Every file in `dashcam/` and `keep/` counts against the budget (default
   **10 GB**, about 85 minutes at 16 Mbit/s; set from the Recordings panel).
3. Over budget, whole segments go oldest first: closed clips (`.mp4`) and
   orphans (a `.h264`, or a `.mp4.tmp` that could not be repaired, untouched
   for 10 minutes), each with all its sidecars.
4. Never evicted: anything in `keep/`, and any clip with a `<stem>.hold`
   marker beside it. Held clips still count against the budget.

A segment marked for keeping (a parked-monitoring event, or Hold on a clip
that is still recording) leaves a `<stem>.hold` marker. The recorder moves
that clip into `keep/` the moment the segment closes
(`SegmentFiles.keepIfHeld`).

---

## 3. WebVTT Telemetry Sidecar (`.vtt`)

Each video segment is accompanied by a synchronized WebVTT subtitle track containing frame-accurate telemetry sampled at 1 Hz.

### Sample Track

```webvtt
WEBVTT

00:00:00.000 --> 00:00:01.000
2026-09-26 09:03:32 · 62 km/h · D · 24.5° · NNE 22° · max 60

00:00:01.000 --> 00:00:02.000
2026-09-26 09:03:33 · 64 km/h · D · 24.5° · NNE 23° · max 60
```

### Data Schema & Sources

| Metric | Source | Property ID / Provider |
|---|---|---|
| **Time** | System clock | wall-clock time of the sample |
| **Speed** | VHAL | `291504647` (`PERF_VEHICLE_SPEED`, km/h) |
| **Gear** | VHAL | `289408001` (`GEAR_SELECTION`: 1=N, 2=R, 4=P, 8=D) |
| **Ambient Temp** | HVAC ECU | `557884279` (`AC_AMBIENT_TEMP`, °C) |
| **Heading** | Android GNSS | GPS bearing, only above 3 km/h |
| **Speed limit** | VHAL | camera sign, then navigation limit, then navigation speed; only plausible values (5-200) |

Coordinates are not written to the sidecar.

*Synchronization*: Video timestamps and cue times both come from the
segment's own clock (`SystemClock.uptimeMillis()` since the segment started),
not from encoder timestamps, so they stay in step. The file is flushed after
every cue, so a segment that never closes keeps all its telemetry.

---

## 4. Crash Resilience: Fragmented MP4

A normal MP4 keeps its index (`moov`) at the end, written only when the file
closes; a crash leaves nothing playable. The recorder therefore writes a
**fragmented MP4** (`FragmentedMp4`):

1. **Index first**: the `moov` is written at the start and describes no
   samples. The video follows as one `moof`+`mdat` fragment per key frame
   (one a second), each forced to storage as it is written. At every moment
   the `.mp4.tmp` is a playable video up to its last fragment. A crash or a
   power cut loses at most about one second. Each frame is written once.
2. **Seeking**: Android 9's `MPEG4Extractor` seeks a fragmented file only
   through a segment index (`sidx`) placed before the first fragment. Room
   for one is reserved after the `moov` (a `free` box). At close, the index
   and the total duration are filled in by scanning the fragment headers:
   small reads, no rewrite.
3. **Clean close**: the file is renamed `.mp4.tmp` -> `.mp4`, its subtitles
   `.vtt.tmp` -> `.vtt`, and a thumbnail is made.
4. **Self-repair**: a `.mp4.tmp` whose writer died (not the live segment, a
   minute cold) is repaired at the next ring-buffer pass: the torn last
   fragment is cut, the index is written, and it becomes a normal clip with
   its thumbnail (`SegmentFiles.repair`). There is nothing to recover by hand.

Checked on the car on 2026-09-26: a hard kill of the recorder mid-segment
left a file that was repaired into a 116 s clip with every frame decodable
and working seek.

### Older recordings (`.h264` orphans)

Builds before this change wrote a raw `.h264` beside a `MediaMuxer` file, and
a crash left an orphan `.h264`. Those are still handled:

* **In-app Recovery**: the Clips screen's **Recover** button remuxes an
  orphan's `.h264` into an `.mp4` without re-encoding, keeps its subtitles,
  and makes its thumbnail, in the separate `:cliprecovery` process. Android
  9's `MPEG4Writer` needs four-byte start codes (`00 00 00 01`) in both the
  samples and the SPS/PPS; `ClipRecovery.sample` and `ClipRecovery.csd`
  produce them.
* **Desktop Recovery**: `./tools/recover-dashcam.sh /path/to/dash_*.h264`
  (needs [FFmpeg](https://ffmpeg.org/)) writes `dash_….recovered.mp4` beside
  each orphan and never changes the source.

### Gallery State Representation

* **Active Recording**: Identified by recent mtime (<15 s). Displayed with an active indicator; deletion and playback actions are disabled.
* **Recoverable Crash Fragment** (older recordings only): an orphaned `.h264` file with no closed `.mp4` and not the live segment. Displayed with an "Unclosed / Recoverable" status and direct recovery/delete options.
* The live segment is named in `recorder.state`; it is always shown as recording, even if its files look cold.

---

## 5. Home Assistant Integration & Remote Access (planned, not implemented)

> [!NOTE]
> Nothing in this section exists in the code yet: there is no embedded HTTP
> server and no `drivemem/<vin>/dashcam/vtt` topic. It describes an intended
> design.

To prevent saturating Home Assistant server storage, video files remain on the vehicle's local ring buffer while metadata is offloaded over lightweight channels.

```text
Vehicle (IHU629G)                                Home Assistant
┌───────────────────────┐                        ┌────────────────────────┐
│ MqttReporter          │ ─── WebVTT Sidecar ──> │ File Sensor / Track    │
│                       │     (18 KB via MQTT)   │ (Searchable telemetry) │
│                       │                        │                        │
│ Embedded HTTP Server  │ <── HTTP GET Video ─── │ Browser Video Player   │
│ (Port 8080)           │     (Stream on demand) │ (<video> + <track>)    │
└───────────────────────┘                        └────────────────────────┘
```

1. **Lightweight Telemetry Sync**: Completed `.vtt` sidecars (~18 KB) are published to Home Assistant via MQTT topic `drivemem/<vin>/dashcam/vtt`.
2. **On-Demand Video Serving**: Drive Assist hosts a lightweight local HTTP daemon. When an operator selects a clip in Home Assistant, the browser streams video directly from the car over LAN/Wi-Fi.
3. **Network Gating**: Background offloading is permitted only when `AdbGate.isHome(ctx)` confirms connection to the driver's authenticated home Wi-Fi gateway.

---

## 6. Dashcam Gallery & Playback UI

The gallery is implemented as an embedded tab within `TelemetryActivity` (Settings/Config):

* **No Dedicated Activity Stack**: Embedded in the settings view to preserve system navigation bar behavior.
* **Clip Pinning (`keep/`)**: Operators can tap "Hold" to move clips to the protected directory, preventing automatic ring buffer eviction.
* **Custom WebVTT Rendering**: Android's native `MediaPlayer` does not render WebVTT subtitles directly. `ClipPlayerActivity` parses the `.vtt` sidecar and renders a custom HUD overlay matching Drive Assist styling.
