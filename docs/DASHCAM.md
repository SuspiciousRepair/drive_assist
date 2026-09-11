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
│  ┌──────────────┐     H.264 ES     ┌──────────────────────┐  │
│  │ DashRecorder │ ───────────────> │ Write-Ahead (.h264)  │  │
│  └──────┬───────┘                  └──────────────────────┘  │
│         │ Muxer                                              │
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
3. **Graceful Teardown**: Intercepts `Intent.ACTION_SHUTDOWN` to finalize active segments, close `MediaMuxer`, write the `moov` atom, and remove temporary stream buffers.

---

## 2. Video Pipeline & Storage Budget

The native EVS engine renders a composite 2x2 camera quad (front, rear, left mirror, right mirror) directly into the hardware encoder's input surface.

### Video Encoding Specifications

| Parameter | Configuration | Notes |
|---|---|---|
| **Resolution** | 1920 x 800 | 4 camera views in 2x2 layout (~960x400 per camera) |
| **Codec** | H.264 / AVC | `video/avc`, Baseline profile |
| **Bitrate** | 6.0 Mbps CBR | Optimized balance between storage footprint and clarity |
| **Frame Rate** | 25.0 fps | Native EVS camera sensor stream rate |
| **Keyframe Interval** | 1.0 s (`IFRAME_SEC = 1`) | Enables fast seeking and bounds crash data loss |
| **Segment Length** | 300 seconds (5 min) | ~225 MB per segment |
| **Timestamp Model** | Monotonic segment elapsed | Eliminates EVS hardware timestamp jitter and muxer rejections |

### Storage Directory & Permissions

Video clips and subtitle sidecars are stored in Drive Assist's external files directory:

```text
/sdcard/Android/data/com.geely.drivemem/files/dashcam/
```

* On Android 9 (API 28), `modehelper` (running as `uid system`) writes directly to this path, while Drive Assist reads it with zero runtime storage permissions (`READ_EXTERNAL_STORAGE` is not required).
* Held clips protected from eviction are stored in `/files/dashcam/keep/`.

### Ring Buffer Budget Management

`DashRecorder.enforceBudget` executes at each segment completion:
1. Calculates cumulative byte size of all completed `.mp4` clips in the active directory.
2. If total size exceeds **10 GB** (~3.7 to 4.0 hours of footage), segments are sorted by last modification timestamp (oldest first).
3. Oldest `.mp4` and paired `.vtt` files are deleted until disk usage falls below the 10 GB limit.
4. Clips located in `keep/` are excluded from budget calculation and are never automatically deleted.

---

## 3. WebVTT Telemetry Sidecar (`.vtt`)

Each video segment is accompanied by a synchronized WebVTT subtitle track containing frame-accurate telemetry sampled at 1 Hz.

### Sample Track

```webvtt
WEBVTT

00:00:00.000 --> 00:00:01.000
62 km/h · D · 24.5 °C · 40.7580, -73.9855

00:00:01.000 --> 00:00:02.000
64 km/h · D · 24.5 °C · 40.7582, -73.9853
```

### Data Schema & Sources

| Metric | Source | Property ID / Provider |
|---|---|---|
| **Speed** | VHAL | `291504647` (`PERF_VEHICLE_SPEED`, km/h) |
| **Gear** | VHAL | `289408001` (`GEAR_SELECTION`: 1=N, 2=R, 4=P, 8=D) |
| **Ambient Temp** | HVAC ECU | `557884279` (`AC_AMBIENT_TEMP`, °C) |
| **Drive / Regen** | VHAL | `570491136` (Eco/Comfort/Sport) / `537003264` (Regen) |
| **Coordinates** | Android GNSS | `LocationManager` (Latitude, Longitude) |

*Synchronization*: Cues are keyed to the hardware encoder's presentation timestamp (PTS) rather than system wall clock, guaranteeing zero subtitle drift over extended durations.

---

## 4. Crash Resilience: Write-Ahead Stream

Standard MP4 containers store the metadata index (`moov` atom) at the end of the file. If power is lost or the process is killed abruptly, an unfinalized `.mp4.tmp` file cannot be parsed or decoded.

### Dual-Stream Recording Mechanism

1. **Simultaneous Annex-B Output**: While streaming to `MediaMuxer`, `DashRecorder` simultaneously appends every raw H.264 access unit to a companion `.h264` file.
2. **Header Injection**: SPS and PPS parameters from `csd-0` and `csd-1` are written to the head of the `.h264` stream upon encoder initialization.
3. **Clean Teardown**: Upon normal segment rotation, the MP4 is finalized and renamed atomically (`.mp4.tmp` -> `.mp4`), and the temporary `.h264` stream is deleted.
4. **Crash Recovery**: If the system resets abruptly, the `.h264` elementary stream remains complete and decodable up to the exact second of termination. It can be remuxed cleanly:
   ```bash
   ffmpeg -r 25 -i crash.h264 -c copy recovered.mp4
   ```

### Gallery State Representation

* **Active Recording**: Identified by recent mtime (<15 s). Displayed with an active indicator; deletion and playback actions are disabled.
* **Recoverable Crash Fragment**: Orphaned `.h264` file whose companion `.mp4.tmp` has not been updated for >15 s. Displayed with an "Unclosed / Recoverable" status and direct recovery/delete options.

---

## 5. Home Assistant Integration & Remote Access

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
