# Dashboard design and fonts

The Minimal theme is implemented with the app's existing Java Views and Canvas.
It uses a neutral light/dark palette, a compact navigation rail, larger control
targets, grouped settings, and a bundled Geely EX2 vehicle image.
The vehicle image is decorative and does not indicate door, lock, or charging state.

Design references reviewed:

- [cppqtdev/Tesla-Dashboard-UI-3](https://github.com/cppqtdev/Tesla-Dashboard-UI-3),
  Qt/QML dashboard; reference revision `070ab34ae56d81b9f6e36e35ffc1f563aaf2f19a`.
  Used as a visual reference for neutral surfaces, vehicle space, and simple controls.
  No QML code or raster vehicle renders were copied. Vector icons were adapted at the user's explicit request; see the asset map below. The repository has no top-level license file.
- [ismoilovdevml/tesla-model-x](https://github.com/ismoilovdevml/tesla-model-x),
  Flutter, MIT code license. Reviewed its separate climate, charging, trips,
  and light/dark presentation. No Flutter runtime or simulated vehicle data was imported.
- [vide/matedroid](https://github.com/vide/matedroid), native Android/TeslaMate,
  GPLv3. Reviewed as an alternative; no code imported.
- The supplied [Figma community link](https://www.figma.com/community/file/1382192547846546595/tesla-dashboard-ui-component-library)
  could not be retrieved in this environment; no Figma assets were imported.

## Fonts

**Unitext Regular** is included at the user's explicit request from
[this file](https://github.com/cppqtdev/Tesla-Dashboard-UI-3/blob/070ab34ae56d81b9f6e36e35ffc1f563aaf2f19a/Fonts/Unitext%20Regular.ttf).
The font metadata identifies Monotype Imaging Inc., copyright 2018, and a proprietary
license agreement. Inclusion here does not grant an app-embedding or redistribution
license. Verify the applicable Monotype license before distributing a build containing it.
It contains no Thai glyphs, so Thai screens use Noto Sans Thai instead.

**Noto Sans Thai** is bundled from
[Google Fonts](https://github.com/google/fonts/tree/main/ofl/notosansthai).
It is licensed under the SIL Open Font License 1.1. The full license and copyright
notice are retained in `drivemem/src/main/assets/licenses/NotoSansThai-OFL.txt`.
The variable face uses its default normal width and regular weight, with bold
text retaining the selected family. Switching language recreates each visible
screen so both native widgets and Canvas labels resolve the correct face.

## Adapted icons

These SVG paths from the same Qt/QML reference revision were converted to native
Android VectorDrawables at the user's request. Fill/stroke colours are normalized
for runtime tinting; rounded rectangles and ellipses are represented as paths.
Directions of the stepper arrows were checked by their actual geometry.
The source repository does not include an icon license; the source links do not
by themselves grant redistribution rights.

| Android drawable | Original SVG path |
| --- | --- |
| `ic_tesla_controls.xml` | `other_icons/Icon=controls, Color=white.svg` |
| `ic_tesla_vehicle.xml` | `icons/app_icons/model-3.svg` |
| `ic_tesla_trip.xml` | `other_icons/Icon=trip, Color=white.svg` |
| `ic_tesla_power.xml` | `other_icons/Icon=power, Color=white.svg` |
| `ic_tesla_steering.xml` | `other_icons/Icon=steering wheel, Color=white.svg` |
| `ic_tesla_lock.xml` | `other_icons/Icon=lock, Color=white.svg` |
| `ic_tesla_display.xml` | `other_icons/Icon=display, Color=white.svg` |
| `ic_tesla_software.xml` | `other_icons/Icon=software, Color=white.svg` |
| `ic_tesla_wifi.xml` | `network_icons/icon=wifi, color=dark.svg` |
| `ic_tesla_spotify.xml` | `icons/app_icons/spotify.svg` |
| `ic_tesla_recording.xml` | `driving_icons/Recording.svg` |
| `ic_bluetooth.xml` | `network_icons/icon=bluetooth, color=dark.svg` |
| `ic_hvac_rear_defrost.xml` | `icons/app_icons/rear-defrost.svg` |
| `ic_chevron_down.xml` | `other_icons/Icon=chevron down, Color=white.svg` |
| `ic_chevron_left.xml` | `icons/stepper_icons/right-arrow.svg` |
| `ic_chevron_right.xml` | `icons/stepper_icons/left-arrow.svg` |
| `ic_weather_sunny.xml` | `professional-vehicle-dashboard/icons/brightness.svg` |

## Additional requested references

- [cppqtdev/Tesla](https://github.com/cppqtdev/Tesla), revision
  `d119bef804d3b565486036fb6972a323b9275f43`: adapted the grouped, rounded
  selector presentation from `Component/LabelSelector.qml` into native driving
  and regeneration controls. MIT notice is bundled in
  `assets/licenses/cppqtdev-Tesla-MIT.txt`.
- [cppqtdev/professional-vehicle-dashboard](https://github.com/cppqtdev/professional-vehicle-dashboard),
  revision `91d07a4d757d5571ba6db3c7128062b3fabb3a4c`: used the climate screen's
  icon-with-caption layout, clearer heading hierarchy, and horizontal vehicle
  presentation. Adapted `icons/globe.svg` to `ic_dashboard_language.xml` and
  `icons/hotspot.svg` to `ic_tesla_wifi.xml` (replacing the earlier network glyph),
  and `icons/brightness.svg` to `ic_weather_sunny.xml`.
  Adapted `icons/moon.svg` and `icons/leaf.svg` for appearance and Eco controls.
  `ControlPage.qml` informs the central vehicle with two flanking control columns:
  the app's Eco / Comfort / Sport selection is on the left and its existing
  regeneration levels on the right. Mode taps apply immediately via `CarActor`
  and save the startup preference; Restore defaults selects Comfort / Medium.
  A manual driving selection cancels Turbo without allowing its timer to
  overwrite the new choice. The Controls / Energy /
  Display tabs open existing app panels. `SettingsView.qml` informs the responsive
  two-column switch-card grid; `ThemeStudioPage.qml` informs the preview and
  appearance-selector cards. These layouts are implemented in native Java Views.
  This repository has no license file. Only UI presentation is adapted; its
  demo telemetry, navigation, weather, and vehicle-command backends are not imported.

Primary headings are 30–32 sp, sidebar groups 24 sp, menu items 20 sp, and
control captions 20 sp. The selected language checkmark uses the same green
in light and dark mode.

## Geely EX2 MAX vehicle assets

- Source page: [Geely Auto Indonesia — EX2](https://www.geelyauto.id/models/geely-ex2).
- Thai colour catalogue: [GEELY EX2 manufacturer leaflet, page 2](https://brochoure.autoinfo.co.th/data/pdf/20251123_Geely%20EX2_Leaflef%20as%20of%2004112025_less%20than10mb.pdf).
  MAX has five exterior colours with black roofs: Moon White, Star Silver,
  Comet Gray, Nebula Beige, and Aurora Green. The Indonesia-only Aurora Pink
  preview is excluded from this Thai MAX selector.
- Downloaded 2026-09-17. Unchanged transparent PNGs (1919 × 1080), frame 3 of
  each colour sequence, are bundled in `res/drawable-nodpi/` for offline use:

  | Colour / asset suffix | Original image |
  | --- | --- |
  | Moon White / `moon_white` | [frame 3](https://www.datocms-assets.com/202757/1789358804-ex2-3.png) |
  | Star Silver / `star_silver` | [frame 3](https://www.datocms-assets.com/202757/1789358883-ex2-3.png) |
  | Comet Gray / `comet_grey` | [frame 3](https://www.datocms-assets.com/202757/1789358698-ex2-3.png) |
  | Nebula Beige / `nebula_beige` | [frame 3](https://www.datocms-assets.com/202757/1789358333-ex2-3.png) |
  | Aurora Green / `aurora_green` | [frame 3](https://www.datocms-assets.com/202757/1789357890-ex2-3.png) |

  The green render is labelled Aether Green on the Indonesian source site;
  the app uses the Thai catalogue name Aurora Green. These renders are visual
  previews, not calibrated representations of Thai production paint.
- The native Canvas displays the car and shadow within the image's transparent
  margins. Aspect ratio and original vehicle colour are preserved in both themes.
- Tapping the car on Home or in the driving settings updates the preview and
  caption immediately and persists the choice with SharedPreferences. Attached
  previews observe that same preference. No Park check, save button, reload,
  network fetch, or vehicle command is involved. A short fade respects the
  system animation setting; bitmap caching is bounded to 16 MiB.
- Copyright remains with its respective owner. The source does not state a
  redistribution license; inclusion in this local build does not grant one.

Driving-mode colours: Eco green (`#187C56`), Comfort yellow (`#F3C64D`),
and Sport red (`#C63C3C`). Selected yellow uses dark text; green and red use
white. Inactive pills retain a muted tint and icon, alongside text labels.

Vehicle Control is now the default Home view. `VehicleControlsView` is shared
with Settings so selection colours, reported-mode outlines, immediate mode
changes, and saved defaults have one implementation. The reference's
`ClimateBar.qml` informed the horizontal home strip; only the app's existing
Cooler / Warmer / Recirculation / Window controls are exposed. Other original
cards remain available through the left dock. No dual-zone or seat controls
were added without corresponding vehicle support.

`EnergyView.qml` informs the read-only Home battery overview beside the larger
driving card: the left column takes 68% of the row and the right Energy column
takes 32%, with a 20 dp gutter. Energy contains a prominent battery reading and
level bar above its metric grid. `icons/battery.svg` and `icons/road.svg` are adapted to native
`ic_dashboard_battery.xml` and `ic_dashboard_road.xml`. The app subscribes to
its own complete telemetry snapshots, charging/plug events and OBD2 readings;
it clears unavailable values and expires stopped telemetry after 45 seconds.
The source's fixed battery-health, arrival-charge, charging-limit, schedule and
cost examples are not copied. Home uses the larger driving controls and vehicle
preview while Energy and the fixed climate strip remain visible together.
Available content widths below 1,480 dp stack the cards in the same scroll area.

The vehicle identity keeps the selected model (Geely EX2 or Geely EX2 Max) in
the 32 sp heading, with a separate 22 sp nickname directly underneath. Tapping
this area opens two side-by-side model buttons and a nickname field; Save commits both local
preferences together and updates attached previews immediately. These labels
do not change the app's vehicle command mappings or telemetry behaviour.
