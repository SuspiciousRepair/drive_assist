# Panel Art Architecture

Technical specification and rendering pipeline for the Drive Assist panel art subsystem across themes, including 3D camera projection, full-bleed layout composition, Home Assistant card integration, and animation lifecycle management.

---

## 1. System Overview & Component Structure

The art subsystem renders dynamic visual backdrops behind the dashboard controls in `ComfortActivity`. The implementation is divided between shared infrastructure (`ArtView`, `Skyline`) and theme-specific renderers:

* **`ArtView.java`**: Abstract base class establishing the rendering contract, animation step clock, layout measurements, raw cabin lighting ingestion, and Home Assistant window chrome scaffolding.
* **`Skyline.java`**: Reusable component providing horizontal city skyline generation, 1x bitmap caching, seamless looping drift, and dynamic color-filter transitions.
* **`SkylineArtView.java`**: Full-bleed 2D skyline backdrop used by the **Geely**, **Claro**, and **Neon** themes. Features reflection planes and ambient brightness-to-alpha mapping.
* **`VaporArtView.java`**: Full-bleed pseudo-3D scene used by the **Noturno** theme. Features a perspective camera, infinite animated grid, retro sun/starfield, dynamic vehicle wireframe with acceleration/regen breath, and an intro ceremony.
* **`KonamiView.java`**: Hidden touch-sequence gesture pad positioned at z-index 0 in the right panel to toggle the Noturno theme.
* **`ComfortActivity.java`**: Top-level layout coordinator managing view hierarchy, panel bounds propagation, and card display.
* **`Style.java`**: Theme registry mapping theme identifiers to art types (`ART_SKYLINE`, `ART_VAPOR`) and color tokens.

### Architectural Matrix

| Feature | `SkylineArtView` (Geely / Claro / Neon) | `VaporArtView` (Noturno) |
|---|---|---|
| **Projection** | 2D horizon line with mirrored ground reflection | 3D perspective camera (vanishing point, focal length) |
| **Coverage** | Full-bleed (`fullBleed() == true`) | Full-bleed (`fullBleed() == true`) |
| **Reactivity** | Speed (drift), cabin RGB, cabin brightness (alpha) | Speed (grid/drift), pedal accel/regen (tail glow), cabin RGB |
| **HA Card Integration** | Framed window (`windowed() == true`), style-based chrome | Framed window (`windowed() == true`), neon bloom & corner marks |
| **Horizon Height** | `0.66 * height` | `0.46 * height` |
| **Veil Gradient** | Opaque to 0.45, transparent at 0.78 | Opaque to 0.34, transparent at 0.72 |

---

## 2. Base Contract: `ArtView`

All art renderers extend `ArtView`, which enforces standard contracts across themes:

```java
public abstract class ArtView extends View {
    protected float speedKmh;      // km/h, updated from vehicle telemetry poll (1 Hz)
    protected float accel;         // -1.0..1.0, derived acceleration (negative = braking/regen)

    public void setSpeed(float kmh);
    public abstract void setAmbient(int rgb);        // Raw RGB cabin light color
    public void setAmbientBrightness(int level);     // Raw level 0..20 (0 = off)

    public boolean fullBleed();                      // Returns true if art covers entire screen
    public boolean windowed();                       // Returns true if art frames HA card

    public void setPanelBounds(int l, int t, int r, int b); // Right third panel coordinates
    public void windowRect(RectF out);                      // Content bounding box for embedded WebView
    public void setWindowOpen(boolean open);                // Animates window open/closed

    protected void drawWindowChrome(Canvas canvas, float alpha); // Render theme-specific window frame
    protected void onWindowLayout();                             // Hook for frame-dependent shader construction
    protected int steps();                                       // Fixed 60 Hz time steps elapsed since last frame
}
```

### Key Behavioral Invariants

1. **Fixed Physics Time Step (`steps()`)**: `steps()` returns elapsed time in discrete 1/60 s increments regardless of frame rate. On automotive hardware throttling under thermal load (e.g., dropping to 30 fps), animation speed remains constant rather than slowing down.
2. **Derived Acceleration (`accel`)**: Derived from speed rate-of-change over time. Decouples art reactivity from specific powertrain sensor implementations.
3. **Raw Ambient Ingestion**: `setAmbient` and `setAmbientBrightness` receive raw hardware telemetry (`0..20` brightness, raw hex RGB). Each art implementation applies its own mapping policies independently of theme controls.

---

## 3. Layout Hierarchy & The Gradient Veil (Scrim)

When an art renderer declares `fullBleed() == true`, `ComfortActivity` places it directly inside the root `screen` container below the control columns:

```text
FrameLayout screen (Theme background)
├── ArtView (Full-bleed: covers 100% of display width and height)
└── LinearLayout outer (Transparent, weightSum = 3)
    ├── Climate Column    (weight 1) — Comfort ruler slider and setpoint controls
    ├── Commands Column   (weight 1) — Direct toggles (recirculation, gate, defrost)
    └── FrameLayout right (weight 1) — HA card container (holds PanelCardView)
```

### The Gradient Veil

To ensure text legibility and eliminate hard visual boundaries between the controls and the art, full-bleed renderers draw a horizontal alpha gradient veil (scrim) over the rendered scene before the control overlay:

```java
scrim = new LinearGradient(
    0, 0, width, 0,
    new int[] { bg, bg, bg & 0x00FFFFFF },
    new float[] { 0f, stopOpaque, stopClear },
    Shader.TileMode.CLAMP
);
```

* **`VaporArtView`**: Stops at `0.34` (opaque) and `0.72` (fully transparent).
* **`SkylineArtView`**: Stops at `0.45` (opaque) and `0.78` (fully transparent), accommodating the wider two-column control layout.

---

## 4. Home Assistant Window Integration

When Home Assistant delivers dynamic card content, `ArtView` frames the content inside a stylized window rather than blanking or cross-fading the underlying scene.

```text
FrameLayout screen
├── ArtView (Draws background scene, veil gradient, window frame chrome, and corner brackets)
└── LinearLayout outer
    └── FrameLayout right
        └── PanelCardView (Transparent WebView positioned exactly at windowRect())
```

### Window Architecture Rules

1. **Content Rect vs. Chrome Separation**: `windowRect(RectF out)` calculates the inner rectangle for the HTML content. The outer chrome (drop shadows, glowing borders, neon brackets, glass tint) is drawn exclusively by `drawWindowChrome()` inside the `ArtView`.
2. **Transparent Content Surface**: `PanelCardView.setGlass()` clears the WebView's HTML background so the art's procedural glass shader remains visible beneath the web card.
3. **Layout Measurement Decoupling**: The container dimensions are provided by `ComfortActivity` via `setPanelBounds()` exclusively when layout geometry changes (`onSizeChanged`), preventing recursive layout invalidation loops.
4. **Window Transition Sequencing**:
   * **Opening**: Window frame illuminates and scales from center (0.94 -> 1.0). Content alpha fades in 150 ms after frame expansion starts.
   * **Closing**: Content alpha fades to zero immediately; the window frame collapses afterward.
5. **Touch Dispatch & Z-Index Ordering**:
   * `rightPanel` is a `FrameLayout` dispatching events from top to bottom.
   * `KonamiView` is mounted at index 0 (bottom).
   * When no card is active, `PanelCardView` is `View.GONE`, allowing taps to fall through to `KonamiView`.
   * When an HA card is active, `PanelCardView` intercepts all touch events, disabling gesture recognition cleanly without state flags.
   * Tap coordinates are normalized against panel aspect ratio (1:1.7 height-to-width) to ensure equal hit distribution across directional axes.

---

## 5. 3D Camera Projection (`VaporArtView`)

The retro grid, horizon, and vehicle in `VaporArtView` share a unified camera projection model to maintain spatial coherence:

```java
float horizon = height * HORIZON_Y;              // Horizon line (0.46 * height)
float groundH = height - horizon;                // Ground plane height
float cx      = width * VP_X;                    // Vanishing point X coordinate
float f       = groundH * (1.0f - CELL) / CAM_Y; // Focal length
float colStep = groundH * CELL;                  // First-row grid line spacing

// Project world coordinates (x, y, z) to screen coordinates (px, py):
float px(float x, float z) {
    return cx + (f * x) / z;
}

float py(float y, float z) {
    return horizon + (f * (CAM_Y - y)) / z;
}
```

### Grid Geometry Constraints

* **Constant Square Aspect Ratio**: Constructing `f = groundH * (1 - CELL) / CAM_Y` guarantees that the ground grid cells remain square at the projection baseline across any chosen `CELL` density.
* **Density Scaling**: `CELL` serves as the single knob controlling grid density; reducing `CELL` increases line density along both axes simultaneously while preserving square geometry.
* **Side-Edge Line Truncation**: Grid line generation calculates depth down to a minimum distance threshold (`T_MIN`) rather than clipping against the bottom screen boundary, preventing corner void artifacts.

---

## 6. Rendering Performance & Static/Dynamic Separation

To minimize CPU and GPU overhead on the automotive head unit, rendering tasks are strictly partitioned:

| Element | Pipeline Strategy | Rationale |
|---|---|---|
| **Sky, Sun, Ground** | Cached Android `Shader` instances | Gradient calculations are static per resolution |
| **City Skyline** | 1x Pre-rasterized bitmap plate | Eliminates per-frame path rendering of building silhouettes |
| **Perspective Grid** | Procedural line drawing per frame | Position is continuously modulated by vehicle speed |
| **Vehicle Geometry** | Procedural 13-face wireframe per frame | Real-time perspective projection during entry motion |
| **Wheel Energy Glow** | Procedural arcs / particles per frame | Driven directly by dynamic acceleration/regen values |

### The `Skyline` Component

`Skyline.java` manages city rendering via composition:
* Rasterizes building silhouettes once into an offscreen bitmap mask using a fixed random seed (ensuring deterministic pixel-identical output).
* Loops seamlessly across display width using two bitmap blit operations.
* Applies cabin ambient lighting using a single `PorterDuffColorFilter(SRC_IN)` operation, avoiding bitmap re-rasterization.
* Modulates transparency with an alpha floor of 20 (~8%) when ambient cabin lighting is disabled.
* Animates color transitions over 700 ms via an injected invalidation `Runnable`.

---

## 7. Power Throttling & Lifecycle Management

Automotive head units operate for hours in parked or standby states. Active canvas animations must not consume background CPU cycles:

```java
// Frame scheduling guard in onDraw():
if (gridV > 0.01f || speedKmh > 0.5f || Math.abs(accel) > 0.04f || ceremonyV > 0f) {
    postInvalidateOnAnimation();
}
```

### Lifecycle Constraints

1. **Stationary Freezing**: When the vehicle is stopped and no animation ceremony is pending, frame scheduling ceases completely.
2. **External Wakeup Filtering**: `setSpeed()` is invoked by the background telemetry poller every 1000 ms. To prevent periodic polling from waking a stationary scene, invalidation only triggers on active transitions:
   ```java
   boolean moving = kmh > 0.5f || Math.abs(accel) > 0.04f;
   if (moving || wasMoving) {
       postInvalidateOnAnimation();
   }
   wasMoving = moving;
   ```
3. **Visibility Teardown**: `onWindowVisibilityChanged` cancels all active `ValueAnimator` instances (skyline color fades, window chrome expansion). Animators immediately snap to their final target values to prevent stale intermediate states upon resumption.

---

## 8. Vehicle Entry & Ceremony Kinematics

When the application launches or transitions to Noturno, the vehicle recedes from camera perspective into cruising position:

```java
float vCar   = Math.max(speedKmh * CELLS_PER_KMH * CELL, ceremonyV);
float remain = ZR - carZ;
float vSep   = Math.min(vCar, INTRO_K * remain);

carZ  += vSep * dt;
gridV  = (vCar - vSep) / CELL;
```

### Transition Mechanics

* **Reference Frame Shift**: Vehicle movement is dynamically split between separation distance (`vSep`) and ground grid velocity (`gridV`). When cruising distance is reached, `remain = 0`, `vSep = 0`, and grid velocity matches vehicle speed.
* **Opacity Fade-in**: At zero distance, the car begins fully transparent (`carFade = 0.0`) and reaches full opacity over the first 35% of entry travel, preventing extreme perspective distortions while stationary.
* **Stationary Ceremony**: When triggered while parked (via `KonamiView`), `playIntro()` injects initial velocity (`V0 = 0.90`) that decays at 0.30/s over 3.0 seconds, smoothly settling within 2.8 pixels of target cruising depth.

---

## 9. Procedural 3D Mesh Engine

The vehicle wireframe is rendered as a lightweight solid using 13 polygonal faces:

```java
// Surface lighting calculation:
float lit  = Math.max(0f, ny) * 0.55f + facing * 0.45f;
int   fill = blendColors(CAR_DARK, CAR_LIT, lit);
```

### Rendering Rules

1. **Painter's Algorithm**: Faces are sorted strictly by depth (Z) back-to-front. Backface culling by winding order is disabled to prevent missing geometry on non-manifold polygonal assemblies.
2. **Coplanar Offset**: Sub-elements (such as wheels) are offset laterally by 0.012 units from parent body panels to eliminate Z-fighting.
3. **Mesh Geometry Comparison**:
   * **Dense CAD / OBJ Models**: Full-triangle meshes (~47,000 polygons) tested on this platform produce visual clutter when outlined and incur severe CPU sorting penalties (~600,000 comparisons/frame).
   * **Stylized Procedural Mesh**: 13 hand-tuned faces deliver clear silhouette definition, stable back-to-front sorting via lightweight insertion sort, and seamless integration with neon shader effects.

### Vehicle Proportions vs. OEM Dimensions

| Metric (Normalized to Half-Width = 1.0) | `VaporArtView` Wireframe | OEM Vehicle Geometry (EX2 / Geometry E) |
|---|---|---|
| **Length** | 4.448 | 5.269 |
| **Roof Height** | 1.000 | 1.785 |
| **Beltline Height** | 0.696 | 1.196 |
| **Ground Clearance** | 0.217 | 0.235 |
| **Cabin Half-Width** | 0.652 | 0.681 |
| **Wheel Radius** | 0.283 | 0.377 |

*Note: The wireframe model deliberately adopts a lowered wedge silhouette to fit retro vaporwave visual conventions while matching OEM ground clearance and cabin width proportions.*
