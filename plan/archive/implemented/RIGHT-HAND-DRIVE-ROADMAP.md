# Right-hand drive layout roadmap

**Status:** Implemented on `next` across three PRs (foundation & primitives, home screen & floating overlay, settings/stats/detail screens & docs). Hardware follow-up on a real RHD vehicle remains open (see bottom).

**Scope (updated from earlier draft):** Full driver-side mirroring across the
app controlled by a single resolved boolean (`Style.RHD`) and a small set of
shared layout primitives — including the home screen, internal card controls
and `effortScale()`, Konami gesture zone, full-bleed art gradients, floating
overlay, settings shell, stats card rows, and two-pane detail screens. The
setting is **Auto / Left / Right** (default **Auto**, reading `CarCaps.rhd`
written by ModeHelper). Text direction, charts/maps/calendars/date arrows,
and the physical 2×2 window grid stay unmirrored on purpose.

## Why full mirroring instead of reversing only home columns

The app's interface is anchored to the driver's side on left-hand drive (LHD)
cars: the status rail, climate card, primary action buttons inside cards,
floating overlay anchor, settings navigation rail, and detail screen back
buttons all sit near the left edge, while the open art third (and Konami
gesture zone) sits on the passenger's right third.

In a right-hand drive (RHD) car, simply reversing the home columns leaves
internal controls, the settings rail, the floating overlay, and the art veil
fighting the layout. Organizing the UI around **one resolved flag (`Style.RHD`)
and six tiny primitives** makes flipping LHD ↔ RHD a single-variable change
and keeps future screens automatically RHD-safe.

## Single-variable architecture

### 1. State, auto-detection, and settings
- Store a 3-way preference in `drivemem` SharedPreferences (alongside theme
  and appearance): `DRIVE_SIDE_AUTO` (default), `DRIVE_SIDE_LEFT`,
  `DRIVE_SIDE_RIGHT`.
- Resolve `public static boolean RHD = false;` once in `Style.load(Context)`:
  - `LEFT` → `false`
  - `RIGHT` → `true`
  - `AUTO` → reads `CarCaps.read(c).rhd` (`"1"` → `true`; missing, empty, or
    `"0"` → `false`). ModeHelper already reads the car's equipment row and
    writes `rhd=0|1` to `car.caps`.
- Include the driving-side preference in `SettingsFile` / `AppBackup` backup
  and restore (with `PhoneRestoreKeysTest` coverage).
- Add a 3-tile **Auto / Left / Right** selector in `TelemetryLookSection`
  alongside the Light / Dark / Auto theme picker (strings in all active
  locales: `values`, `values-pt`, `values-es`, `values-th`), calling
  `activity.recreate()` on change.
- Track `rhdAtBuild` in `ComfortActivity.onResume()` so returning from
  Settings recreates the home screen cleanly.

### 2. Shared mirroring primitives (in `Style` or `ui/LayoutMirror`)
Every screen builds through these helpers instead of scattering `if (Style.RHD)`:

| Primitive | LHD (`Style.RHD == false`) | RHD (`Style.RHD == true`) |
|---|---|---|
| `driverGravity()` / `passengerGravity()` | `START` / `END` | `END` / `START` |
| `setDriverPadding(v, driver, t, pass, b)` | `(driver, t, pass, b)` | `(pass, t, driver, b)` |
| `setDriverMargin(lp, driver, pass)` | `left=driver, right=pass` | `left=pass, right=driver` |
| `addMirrored(LinearLayout row, int gapDp, View... items)` | adds `0..n-1` with gap between | adds `n-1..0` with gap between |
| `headerRow(ctx, backBtn, title, trailing...)` | `[Back, Title, ...]` | `[..., Title, Back]` |
| `mirrorX(float frac)` | `frac` | `1f - frac` |

## Complete surface map

### Core layout builders (`Style`, `ConfigCards`)
- `Style.packIntoColumns(...)` (both overloads, including the 2-column
  expanded Music Lyrics callback): pack cards into columns in priority order
  (`col[0]` = Clima), then in RHD insert columns right-to-left, right-align
  the column row inside its `HorizontalScrollView`, and start scrolled to the
  right edge if columns overflow.
- `Style.effortScale(...)`: flip pill order (`W5..W1 | 0 | C1..C5`) when
  `Style.RHD` so cooler/warmer stays aligned with the mirrored `[❄️ | ☀️]`
  buttons on both the home card and floating overlay.
- `Style.configScreenBg(Context)`: horizontally flip `config_bg_day.webp` /
  `config_bg_night.webp` in code when `Style.RHD`.
- `ConfigCards.columns(...)`: reverse column order and right-align
  single-column rows in RHD; migrate `TelemetryDriveSection` onto
  `ConfigCards.columns()` so every settings page shares one path.

### Home screen (`ComfortActivity`, `GateCard`, `ChargeSessionCard`)
- Move `statusSidebar()` to `driverGravity()` + `setDriverMargin(...)`; apply
  `setDriverPadding(..., sidebarClearance, ...)` to `band`, `statsContainer`,
  and `chargeStatsContainer`.
- Move `konamiZone` to `passengerGravity()` (physical left third in RHD,
  matching the open art third).
- Mirror internal card controls via `addMirrored(...)`:
  - **Clima:** `[❄️ | ☀️]`, `[Recirc | Purge]`, footer icons (`windDirIcon`,
    `rearDefrostIcon`).
  - **Turbo:** slot order (`T | R | A` → `A | R | T`).
  - **Music (small, large, and expanded 2-column Lyrics card):** `[⏯ | ⏭]`,
    expanded lyrics bottom bar (`[Play, Skip, Collapse]`), OEM sound gear
    corner.
  - **Charging & Journey/Parked:** active charging `[Info | Badge]` →
    `[Badge | Info]`; `ChargeSessionCard` `[body | costTile]` →
    `[costTile | body]` (covers both the home card and Charging stats).
  - **PanelCard & GateCard:** `PanelCard` close button (`✕`) on
    `Gravity.TOP | passengerGravity()`; `GateCard` `[Icon, Title, Status]` →
    `[Status, Title, Icon]`.

### Full-bleed art (`SkylineArtView`, `VaporArtView`)
- `SkylineArtView.buildVeil()`: flip gradient endpoints (`0 → w` vs `w → 0`).
- `VaporArtView`: `cx = w * mirrorX(VP_X)` and flip scrim gradient endpoints.

### Floating overlay (`OverlayService`)
- Anchor window to `Gravity.BOTTOM | driverGravity()`.
- Negate drag `dx` in RHD so horizontal dragging follows the finger.
- Mirror `askRow` and `turboSection` via `addMirrored(...)`.
- Sync window anchor in `rebuildContent()` if `Style.RHD` changed at runtime.

### Settings, stats, and secondary/detail screens
- `TelemetryActivity`: mirror `[side, scroll]` rail to the right, flip
  `selectedNavBg()` accent bar to `driverGravity()`, mirror `head` and
  `integrationHeader()`.
- `DailyStatsView` & `ChargeStatsView`: mirror card rows (`dashboardRow`,
  `bottomRow`, `overview`) while keeping charts, maps, calendars, and date
  navigation arrows strictly LTR.
- Two-pane detail screens (`TripRouteActivity`, `AllRoutesActivity`,
  `ValetDetailActivity`, `ChargeDetailActivity`): mirror header (`headerRow`)
  and `[left, right]` body panes.
- Utility screens: `ClipPlayerActivity` (camera picker + back button to
  `driverGravity()`), `CleanupActivity` (mirror `[root, spacer]` and header).

### Deliberately left unmirrored
- **2×2 window grid** in `TelemetryDoorsSection`: physical vehicle plan view
  (FL/FR/RL/RR stays left/right of the car).
- **Charts, maps, calendars, and date arrows** (time flows left-to-right;
  maps stay north-up).
- **Text direction** (LTR languages stay LTR; no `forceRtl`).

## Phased implementation plan

Deliver as **3 focused PRs against `next`**, with an independent review pass
after each PR before merge:

1. **PR 1 — Foundation, primitives, Settings toggle & backup**
   - `Style.RHD`, `DRIVE_SIDE_{AUTO,LEFT,RIGHT}`, `CarCaps.rhd` auto-resolution,
     backup/restore integration.
   - Mirroring primitives (`driverGravity`, `passengerGravity`,
     `setDriverPadding`, `setDriverMargin`, `addMirrored`, `headerRow`,
     `mirrorX`).
   - `Style.packIntoColumns()`, `Style.effortScale()`, `Style.configScreenBg()`,
     `ConfigCards.columns()` (+ `TelemetryDriveSection` migration).
   - `TelemetryLookSection` **Auto / Left / Right** selector + 4-locale
     strings + unit tests.
2. **PR 2 — Home screen, cards, full-bleed art & floating overlay**
   - `ComfortActivity` (`rhdAtBuild`, sidebar, clearance padding, `konamiZone`,
     internal card controls including Music Lyrics & `ChargeSessionCard`),
     `GateCard`.
   - `SkylineArtView`, `VaporArtView`.
   - `OverlayService` anchoring, drag math, internal rows, and live rebuild.
3. **PR 3 — Settings shell, Stats, detail/utility screens & docs**
   - `TelemetryActivity`, `DailyStatsView`, `ChargeStatsView`.
   - `TripRouteActivity`, `AllRoutesActivity`, `ValetDetailActivity`,
     `ChargeDetailActivity`, `ClipPlayerActivity`, `CleanupActivity`.
   - Update `docs/DRIVER-GUIDE.md`, `docs/DRIVER-GUIDE.pt-BR.md`,
     `CHANGELOG.md`, and archive this plan to `plan/archive/implemented/`.

## Review & verification checklist

For each PR:
- `./tools/check-pii.sh` and `./tools/verify.sh` pass cleanly.
- Zero layout regressions when `Style.RHD == false` (LHD output identical).
- Audit every touched screen for leftover hardcoded `Gravity.START`/`LEFT`,
  `leftMargin`/`rightMargin`, padding, or `LinearGradient` directions.
- Verify `HorizontalScrollView` right-alignment and initial scroll in RHD
  when the 2-column Music Lyrics card is expanded or >3 columns overflow.
- Verify `OverlayService` drag clamping and live LHD ↔ RHD toggle behavior.

## Hardware follow-up (requires a real RHD vehicle)

- Confirm on an actual RHD car whether `CarAccess.readSetpoint()` still reads
  the active HVAC setpoint from `area = 1` or from `area = 4`, and confirm
  that `car.caps` reports `rhd=1`. Do not claim vehicle validation until
  checked on hardware.
