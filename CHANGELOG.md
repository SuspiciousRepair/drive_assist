# Changelog

## [Unreleased]

## [v0.3.0] — 2026-09-21

### Fixed
- **A stuck charging-current sensor could hide most of a real fast charge.**
  `car.is_charging` is derived from the current sensor, which can latch at
  its last non-zero reading and never return to 0 on its own — so once a
  session had already been open, a genuinely new charge could start
  without producing the edge the app was waiting for. A real 30-minute,
  16.5 kWh DC fast charge was recorded as 2 minutes and 0.6 kWh this way.
  Fixed at the source: `is_charging` now cross-checks the car's own
  connector-engagement signal in the same poll tick, so every consumer —
  charging history, the MQTT/Home Assistant status, anything else — gets
  the corrected signal, not just charging tracking.
- **The main thread could spin at ~100% CPU indefinitely**, badly enough to
  get Spotify killed by the system (`Input dispatching timed out`). Six
  places waited for a view's real layout size by reposting themselves
  until `getWidth()` was nonzero — which never happens for a view inside a
  `View.GONE` container, since a `GONE` view is never measured. Each
  affected site now waits on a real layout pass instead of reposting
  blindly. Measured on-device: main thread CPU went from 93.5%, stuck
  Running, to ~0.4% average, Sleeping. (Reported as GitHub issue #5, with
  exceptionally clear thread-dump evidence — thank you.)
- **The update-available prompt could appear twice in a row.** Drive
  Assist and ModeHelper are always built and shipped together with
  matching versions, but the app checked each one for updates separately
  and only installed whichever one you accepted — leaving the other to
  prompt again right after. Accepting either prompt now installs both.
- **Weekly reports no longer split a weekend across two different weeks.**
  The week view started on Sunday, so a normal Saturday/Sunday always
  landed in two separate report cards instead of one. Weeks now start
  Monday and end Sunday, keeping a weekend joined to the workdays right
  before it.
- **A week's worth of driving (Sept 10–17) was mislabeled as
  "estimated," despite OBD2 being connected the whole time** — a data
  migration bug set the wrong flag on real, OBD2-measured samples. The
  underlying energy numbers were always correct; only the label was
  wrong. Historical data repaired in place.
- The Home screen's Turbo/Regen card could lose its spacing from the card
  above it if the car went from parked to driving while Config was open,
  then you came back to Home — a missed "did this actually change"
  check meant the screen never repacked to account for it.

### Changed
- The Config screen's Driving mode / Doors / Elements sections now show
  the OEM's own day/night vehicle render as their background instead of a
  plain gradient; every other section keeps a flat fill of the current
  theme's own color. The Default theme's light background color was also
  recalibrated to the exact tone measured from that render.
- The driving-mode cards (Eco/Comfort/Sport) now use real vector icons
  instead of emoji, laid out inline with their label instead of stacked,
  and the Turbo card's toggle and duration field now sit on one line
  instead of the duration field being a full-width box underneath.
- **Bluetooth OBD2 PIN fix is no longer part of installation.** `install.sh`
  used to automatically flip the head unit's Bluetooth pairing PIN from
  `0000` to `1234` on every install. It's now a separate, optional step you
  run yourself only if you're pairing an OBD2 dongle
  (`bt-pin-fix/apply-pin-1234.sh`). The docs now also point out that the PIN
  only needs to be changed for the pairing itself — once a dongle is paired
  the car remembers it by a stored bond key, not the PIN — so
  `bt-pin-fix/revert-pin-0000.sh` should be run right after pairing instead
  of leaving the PIN changed indefinitely.

### Docs
- Added an Open-source Head-unit Software Review
  (`docs/OPEN-SOURCE-HEAD-UNIT-REVIEW.md`), linked from the README, covering
  maintainability, architecture, performance, safety, and community
  recommendations for rooted sideloaded deployment.
- Called out clearly, in `docs/WELCOME.md` and `docs/INSTALL-GUIDE.md`, that
  the Bluetooth PIN change is the one change in the project that survives an
  uninstall or factory reset, since it lives under `/system` rather than
  `/data`.

## [v0.2.0] — 2026-09-15

### ⚠️ Upgrade note for existing installs
This release changes how ModeHelper accepts its own updates. If your
ModeHelper was installed by an **older** release, it cannot update itself
through the new automatic path — it doesn't yet recognize its own package
as a valid update target. Drive Assist detects this up front and tells you
so directly (instead of offering an update that would silently do nothing
once confirmed). One-time fix: re-download and re-run
`drive_assist_installer.apk` from this release. After that, both apps
update themselves automatically from then on. Brand-new installs from this
release are unaffected. Nothing else about ModeHelper — camera safety, the
ADB toggle, drive modes, Drive Assist's own updates — depends on this and
all keep working normally on an older ModeHelper in the meantime.

### Added
- **Dashcam recording limit**: a new "Recording limit (GB)" field in the
  Recordings panel lets you set how much storage the dashcam's ring buffer
  is allowed to use, instead of a fixed 10 GB.
- **OTA updates now cover ModeHelper too**, not just Drive Assist itself.
  The privileged helper used to require a physical `adb install` for every
  fix — including the camera-safety fix below. It now gets its own real,
  build-to-build version number, its own update channel, and the same
  Park-gated confirmation dialog Drive Assist's own updates already use.
  Updating it delivers the standalone installer rather than trying to have
  it narrowly "self-update" — the installer already bundles fresh copies
  of both apps and installs them together, which is simpler and more
  robust than tracking per-package version state through a restricted
  self-check. Verified live on-device, twice: once end to end, and once
  again after finding and fixing a real race condition where ModeHelper's
  own cleanup logic could kill the installer mid-run.
- **Automatic update checks**: Drive Assist now checks for new versions of
  itself and ModeHelper once a day on its own, against your configured
  update server or — if you never set one — GitHub Releases directly.
  Previously this only ever ran when someone pressed "Check Update" or an
  MQTT command told it to, so an install with no private server behind it
  had no way to find out about a new release without being told to look.
  Still fully silent unless something is actually newer, and still
  Park-gated before ever showing a prompt.
- **"Skip this version"**: the update dialog can now dismiss one specific
  version for good, not just "ask again later" — for anyone who's decided
  to pass on a particular build. A newer release still prompts normally.
- **Battery-range chart context**: session bars in Charging Statistics now
  sit on a faint full 0–100% track instead of floating on blank space —
  light blue for the charge already held before the session, light grey
  for the headroom left after it.

### Fixed
- **Cold-weather heating (W1)**: The gentlest heat setting no longer recirculates cabin air on cold days — it now uses outside air, like every other heat setting. Recirculated air was blowing straight at the windshield, which risked fogging it instead of clearing it.
- **Trip energy**: idle power draw after you've actually parked no longer counts toward that trip's consumption, so a short trip's efficiency no longer gets worse just because it took a while to close out.
- **Dashcam recording toggle**: turning recording off in the Recordings panel now actually stays off across a restart. Before, the "Record" control was a momentary button — the car always started recording again on its own at the next boot regardless of what you'd chosen.
- **Home screen card spacing**: a hidden card (like Portão when the gate isn't available) no longer leaves an extra gap behind it — one card-to-card spacing no longer looks wider than the others depending on which card happens to be hidden.
- **"Estacionado" (parked) timer stuck for hours**: opening the app's main screen was silently unregistering the listeners that reset the park timer, record park sessions, and track charging — a `setListener` call was replacing the whole list instead of adding to it, so simply looking at the screen after the first time broke tracking for the rest of the drive. Verified against real trip data: the timer had been stuck on the same 10+ hour-old timestamp across five separate drives in one day before this fix. Fixed by adding to the list instead of replacing it.
- **Parked-monitoring camera safety**: disabled the parked-monitoring probe — it was opening its own independent connection to the same camera the dashcam already uses. On its first real use this froze the factory reverse-camera display on a stale image after a Park-then-Reverse transition, a real safety issue while backing up. Nothing in the app now opens that camera except the dashcam recorder itself.
- **Weekly/Monthly stats hour-of-day chart**: was left blank on Week and Month views ("doesn't apply across a week" per the old logic) even though every other card on the same screen already shows real totals for the period. Now sums each day into one chart, same as those other cards already do.
- **Config screen selected-item style**: now matches this car's own OEM settings menu — a thin accent bar and tinted label — instead of a solid filled pill.
- **Dashcam settings page layout**: recording settings and the clip list below them read as one undifferentiated column; now separated by a clear divider. The recording-limit field and its Save button no longer stretch to the full row width. Each clip row is narrower instead of spanning the whole screen. The clip count/storage line now sits with the clip list it describes instead of up with the settings.

## [v0.1.6] — 2026-09-14

### Added
- **Overlay shortcut**: User can now enable a shortcut that stays on top of every other app, including Carplay or Android Auto. It shows Climate Control, Turbo Mode/Regen and a shortcut to the app.
- **New App Icon**: The app is now identified by the mdi:view-dashboard-varient icon.

### Fixed
- **AVAS (pedestrian warning sound)**: Drive Assist no longer fights a sound picked in the car's own Settings — it applies its saved choice once when the car starts, then leaves whatever is chosen in OEM Settings alone until the next restart. The Config toggle also now reads the car's real state every time the screen opens, instead of showing a value that could be out of date.
- **Gate card**: now shows a real open/closed garage icon that reflects the actual gate state, instead of a plain colored dot.

## [v0.1.5] — 2026-09-14

### Fixed
- A charging session that reached 100% could stay marked "in progress" indefinitely, even after driving away — the car's own charging signal can stay latched at its last reading instead of dropping to zero once the battery is full. The app now closes any open charging session the moment a real trip starts, instead of relying only on that signal.

## [v0.1.4] — 2026-09-13

### Added
- **ADAS override**: Config → Sistema can now turn off AEB (automatic emergency braking) and mute AVAS (the pedestrian warning sound), independent of the car's own menus. AEB asks for confirmation first, since it's the more consequential of the two; both settings persist across restarts until changed back here, and neither requires being parked to change.
- **Valet mode**: a new "Ativar modo manobrista" button on the home screen, for handing the car to a valet or attendant. While active it tracks distance, top speed, and duration separately from your normal trips, shown live on the same card; tap "Encerrar modo manobrista" to end it.
- **Charging power curve**: tap into a past charging session to see its power (and outside/battery temperature, where available) plotted over the whole session, instead of only the session's summary numbers.
- Daily Statistics has a new Day/Week/Month dropdown. Week and Month show the recent weeks or months side by side — by week number or month name — so you can compare periods at a glance instead of only drilling into one day's own hours. Loads quickly even over months of history, and stays on whichever period you pick.
- Dashcam subtitles now include the date and time alongside speed, gear, and temperature, so a clip is timestamped without cross-referencing its filename.
- Config → Sistema now shows the car's Wi-Fi IP address next to the Device ID, so it can be read directly off the screen instead of guessed or hardcoded.

### Fixed
- Recharge session cards: better font-size balance — larger overall text with the added-energy figure de-emphasized relative to the battery-percentage change so the two don't compete for attention on the same line; proper padding on both sides so numbers no longer crowd the card edge; the charge duration now sits next to the start/end time instead of on its own line below.
- The Recharge Stats page's energy-balance chart now sits directly under the battery-range chart at the same width, instead of spanning the full page width disconnected from it.
- Regenerated energy is now shown in purple in the energy-balance chart, distinct from AC charging's blue.
- Charging Statistics: consistent AC/DC colors and icons throughout, in-bar durations on the session list, and de-emphasized units next to the numbers that matter.
- Smaller text throughout Daily Statistics — session rows, chart legends, axis labels, chip labels — was hard to read at a glance from the driver's seat; increased across the board.
- The home screen's third card could get its bottom edge clipped off intermittently, if its content grew after the screen had already laid out the cards. The layout now double-checks itself after settling and re-flows automatically if anything still doesn't fit.

## [v0.1.3] — 2026-09-11

### Added
- New "Show skyline art" toggle in Settings → Appearance, for anyone who prefers a plain background.
- New skyline seed controls: set a fixed seed by hand, cycle to a random one, or turn on "Random every drive" for a fresh skyline each time you shift into Drive.
- Charging Statistics now includes a rolling 30-day energy balance: driving use below zero, with regeneration, AC charging, and DC charging stacked above it for each day. Overnight charging is split across the days it spans.
- Daily Statistics now includes a 24-hour distance chart, split into speed buckets, alongside the day’s drive and recharge event timeline.
- Completed and historical recharge sessions can be given a price, including an explicit R$ 0,00 price.

### Fixed
- Average speed on the Daily Stats screen was averaging in every parked/idle moment of the day, making it look far lower than the car was actually driven. It now reflects real driving speed (distance over driving time).
- Drive and regen mode tiles, the turbo controls, and the appearance row on the config screen all stretched across the whole panel width. They now share one consistent, capped width.
- Turning off "Show skyline art" while the app was already running didn't actually hide it until a full restart.
- Ascent/descent totals could spike wildly: the location reader could pick a fresher but far less accurate network-based position over a real GPS fix, and a bad altitude reading from it would read as a huge fake climb or drop.
- Daily driving consumption and speed-bucket efficiency now exclude energy recorded while the car is in Park, such as parked HVAC use. Zero-speed samples in a driving gear still count, preserving stop-and-go traffic.
- The daily event timeline and hourly speed chart use separate, compact cards so a long event list does not stretch the chart.

### Security
- Hardened several app components against common Android exposure issues (component isolation, query handling).
