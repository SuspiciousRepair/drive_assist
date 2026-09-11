# Extended View System (EVS) & Camera Subsystem

Technical reference for the camera hardware topology, Extended View System (EVS) HAL, native Binder protocol (`bdstar.render.engine`), frame capture pipeline, and 3D vehicle assets on the Geely IHU629G.

---

## 1. Hardware Architecture & Sensor Topology

The vehicle is equipped with four wide-angle exterior surround cameras and one auxiliary input:

```text
┌─────────────────────────────────────────────────────────────┐
│ Physical Sensors                                            │
│                                                             │
│  Camera 0 (Front)   ──┐                                     │
│  Camera 1 (Rear)    ──┼──> MAX9286 4-Channel GMSL ────────> │ 5120 x 800 YUYV
│  Camera 2 (L Mirror)──┤    Deserializer                     │ (MIPI CSI-2)
│  Camera 3 (R Mirror)──┘                                     │
│                                                             │
│  Auxiliary Analog   ─────> RN6752 CVBS Decoder ───────────> │ 708 x 232 YUV
└─────────────────────────────────────────────────────────────┘
                                                              │
                                                              ▼
                                               /dev/seninf (Kernel Driver)
                                                              │
                                                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Linux Userspace Daemons (Root)                                          │
│                                                                         │
│  android.hardware.automotive.evs@1.0-service  (EVS HAL Enumerator)      │
│  evsengine  (Publishes bdstar.render.engine Binder)                     │
│  evsapp     (Factory Rear-View & 360 Surround Display UI)               │
└─────────────────────────────────────────────────────────────────────────┘
```

### Linux / Android Userspace Interface

* **No V4L2 Device Nodes**: There are no `/dev/video*` devices exposed in userspace.
* **Camera Framework Bypassed**: Standard Android camera services (`dumpsys media.camera`) report `Number of camera devices: 0`.
* **Forward ADAS Camera Isolation**: The windshield ADAS camera (lane departure / traffic sign recognition) is managed by an independent ECU and communicates high-level telemetry over CAN bus (e.g. `DHU_ROAD_CAMERA_LIMIT_SPEED`). No raw video stream reaches the head unit.

---

## 2. Service & Configuration Stack

### Daemon Processes

```text
PID   User            Process Name                                Role
287   root            evsengine                                   EVS compositor (bdstar.render.engine)
288   root            evsapp                                      Factory reverse camera UI
289   automotive_evs  android.hardware.automotive.evs@1.0-service AOSP EVS HAL daemon
```

### Engine Configuration (`engine_config.json`)

Configuration file: `/system/etc/automotive/evs_engine/engine_config.json`

| Function ID | Target Resolution | Declared Status | Description |
|---|---|---|---|
| `avm` | 1920 x 936 | `available: 1` | Around View Monitor (3D surround bowl projection) |
| `rvc` | 1176 x 720 | `available: 0` | Rear View Camera (direct reversing display) |
| `dvr` | 1920 x 800 | `available: 0` | Digital Video Recorder (2x2 quad-camera feed) |

*Note: The `available: 0` entry in configuration is not enforced as an access gate by `evsengine`. Requests for the `dvr` surface succeed regardless of this setting.*

---

## 3. Native Binder Protocol: `bdstar.render.engine`

Video frames are obtained by interfacing directly with the native C++ `evsengine` service via Android Binder.

### Service Acquisition

```java
IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
    .getMethod("getService", String.class)
    .invoke(null, "bdstar.render.engine");
```

### Transaction Interface

The interface descriptor token is `"bdstar.render.engine"`. Strings are written as null-terminated UTF-8 byte arrays padded with zeros to 4-byte boundaries, packed into 32-bit little-endian integers.

#### Transaction 1: `openCamera`

Initializes the camera stream.

```text
Parcel Data:
  writeInterfaceToken("bdstar.render.engine")
  writeCString("avm")                          // Camera ID

Parcel Reply:
  Empty (0 bytes)
```

#### Transaction 2: `attachSurface`

Attaches a consumer surface to receive rendered frames.

```text
Parcel Data:
  writeInterfaceToken("bdstar.render.engine")
  writeStrongBinder(igbp)                      // IGraphicBufferProducer Binder
  writeCString("dvr")                          // Target function (previewType)
  writeCString("avm")                          // Camera ID

Parcel Reply:
  Empty (0 bytes)
```

### `IGraphicBufferProducer` Extraction

On this Android 9 build, `Surface.getBinder()` throws `NoSuchMethodException`. The `IGraphicBufferProducer` must be extracted by parceling the `Surface` and reading the strong binder reference located at byte offset **12**:

```java
Parcel p = Parcel.obtain();
surface.writeToParcel(p, 0);
p.setDataPosition(12); // SURFACE_PRODUCER_OFFSET
IBinder igbp = p.readStrongBinder();
p.recycle();
```

---

## 4. Output Stream Characteristics & Multi-Client Concurrency

### Frame Specifications

* **Output Format**: 1920 x 800, `RGBA_8888` (row stride exactly 7680 bytes, zero row padding).
* **Frame Rate**: Constant 25.0 fps.
* **Composite Geometry**: 2x2 grid containing all four cameras in a single frame (~960 x 400 per camera quadrant):
  * **Top Left**: Rear camera (physically mounted upside-down, 180° rotation required).
  * **Top Right**: Front camera (physically mounted upside-down, 180° rotation required).
  * **Bottom Left**: Left wing mirror camera.
  * **Bottom Right**: Right wing mirror camera.

### Reverse Gear Concurrency (`dvr` vs. `rvc`)

The `dvr` function operates independently from the primary display pipeline:
* When the transmission shifts into Reverse (`GEAR_SELECTION` changes from `4` [PARK] to `2` [REVERSE]), `evsapp` takes ownership of the display screen to render the reversing guidelines.
* The background `dvr` Binder stream continues delivering frames at an uninterrupted 25.0 fps without dropped buffers or pipeline stalls.
* No yield/reclaim state machine is required when consuming the `dvr` function.

---

## 5. Optical Geometry & Fisheye Dewarping

All four camera sensors output unstitched, raw equidistant fisheye images. The front and rear sensors are mounted with 180° physical rotation.

### Dewarping Shader (GLSL)

The factory polynomial coefficients and dewarping transform:

```glsl
// Factory polynomial correction constants
const float K1 =  0.0641;
const float K2 =  0.0130;
const float K3 = -0.0062;
const float K4 =  0.00025;

// Coordinate transformation within a 2x2 quad
vec2 c = (local * 2.0 - 1.0) * uCellScale;
c.x *= uFlipX;                                    // Mirror horizontal axis if required
c = mat2(uRot.x, uRot.y, -uRot.y, uRot.x) * c;    // 180 deg rotation: uRot = vec2(-1.0, 0.0)

float rr    = length(c);
float theta = atan(rr * uTanHalfFov);
float t2    = theta * theta;
float rd    = theta * (1.0 + t2 * (K1 + t2 * (K2 + t2 * (K3 + t2 * K4))));

vec2 cellLocal = (c / rr * rd * uFishScale) * 0.5 + 0.5;
vec2 globalUV  = (vec2(quadX, quadY) + cellLocal) * 0.5; // Remap back to 2x2 quadrant
```

---

## 6. Vehicle 3D Model Assets & Configuration

The head unit stores the manufacturer's 3D vehicle assets on `/system`:

* **Asset Directory**: `/system/etc/automotive/ecarxp/avmdata/asset_3d/vehicle/`
* **Mesh Format**: Plain-text Wavefront OBJ (`body.obj`, `interior.obj`, `fr_door.obj`, `fl_door.obj`, `rr_door.obj`, `rl_door.obj`, `trunk.obj`, `hood.obj`, `wheels.obj`).
* **Texture Format**: PKM ETC1 compressed textures (`diffuse.pkm`, `diffuse_blue.pkm`, `diffuse_red.pkm`, `diffuse_silver.pkm`, `diffuse_transparent.pkm`).

### Factory Dimensions (Centimetres)

| Component | Width (X) | Height (Y) | Length (Z) |
|---|---|---|---|
| **Body Shell** | 173.89 | 20.43 to 155.17 | 458.18 |
| **Interior** | 144.03 | 30.30 to 149.71 | 352.77 |
| **Hood** | 156.69 | 73.88 to 103.95 | 105.28 |
| **Trunk Lid** | 118.39 | 67.74 to 118.28 | 49.33 |
| **Rear Wheel** | 21.63 | -0.48 to 65.16 | 65.60 |

### AVM Configuration Properties

* **Config File**: `/mnt/mtkdata/avmdata/config.txt` (writable ext4 partition)
  ```ini
  avmpgs=0
  avmtrunning=0
  3dsurround=0
  3dRadar=0
  carTransparent=0
  avmzoom=0
  carColor=white
  ```
* **System Properties**:
  * `persist.sys.avm.3d_surround`: Enables 3D perspective surround rendering.
  * `persist.sys.avm.car_alpha`: Integer alpha transparency (`0..100`).
  * `persist.sys.avm.DAY_NIGHT_MODE`: Toggles daytime/nighttime rendering palette.
  * `persist.sys.avm.turn_enable`: Enables automatic camera activation on turn signal.

---

## 7. Resolution Pipeline & Hardware Constraints

```text
Sensor Hardware:     5120 x 800 (4x 1280x800 sensors, 1.02 MP each)
                           │
                           ▼
EVS Engine Downscale: 1920 x 800 (4x  960x400 quadrants, 0.38 MP each)
                           │
                           ▼
Hardware Encoder:     1920 x 800 @ 6 Mbps H.264 (OMX.MTK.VIDEO.ENCODER.AVC)
```

* **Encoder Limits**: `/vendor/etc/media_codecs_mediatek_video.xml` caps `OMX.MTK.VIDEO.ENCODER.AVC` at a maximum resolution of `1920x1080`. The full 5120-wide raw sensor composite cannot be encoded directly as a single frame without downscaling or multi-instance splitting.
* **Direct EVS HAL Access**: Direct binding to `android.hardware.automotive.evs@1.0::IEvsEnumerator` grants access to the full 5120 x 800 stream. However, the EVS HAL enforces single-client exclusivity (`Killing previous camera because of new caller`). Direct attachment preempts `evsengine`, temporarily disabling the factory reverse camera UI. Consuming the `dvr` function from `evsengine` avoids client contention entirely.
