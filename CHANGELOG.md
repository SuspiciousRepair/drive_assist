# Changelog

## [Unreleased]

### Added
- **Song lyrics on the Spotify card.** Turn on **Config → Integrations →
  Spotify → Lyrics search** (off by default). The **Lyrics** button on the
  music card grows it to two columns and the full height of the screen: at
  the top the art, song, artist and album, in a soft shade of the album
  art's main colour; under them the line sung now in large type, the line
  before it and the next two, in time with the song; and Play, Skip and a
  close button at the bottom. The card remembers the last choice, open
  or closed, from song to song. The card keeps its normal size when the Home
  Assistant panel is up, or when the other cards need more than one
  column. The lyrics come from LRCLIB (lrclib.net), a free lyrics site:
  the car sends it the song, artist and album names, once per song, and
  keeps the answer; with the switch off it sends nothing. The next song in
  the Spotify queue is looked up ahead, so its lyrics are ready when it
  starts (this needs **Connect** in Config → Spotify once more, for the
  queue permission). Some songs have no timed lyrics there; for those the
  Lyrics button is dim. After a jump inside a song, the lines can be up to
  6 seconds behind, until the next Spotify check.
- **Phone music on the music card.** Turn on **Config → Integrations →
  Spotify → Phone music** and the card also shows what the phone plays
  through the car (Bluetooth, Apple CarPlay or Android Auto), from any app:
  title, artist, album, cover when the phone sends one, Play and Skip, and
  the lyrics when Lyrics search is on (they follow the player's own
  position, so they are exactly in time). When Spotify is playing, the card
  follows Spotify. Drive Assist reads this from the car's own music apps,
  which share it with any app; no permission is asked. Nothing leaves the
  car for this.
- **A gear on the Spotify card for the car's sound settings.** The small
  gear in the card's top-right corner opens the car's own sound settings,
  with the equalizer presets (Standard, Pop, Rock, ...). On a car whose
  settings app has no such screen it opens the Volume page, and with
  neither there is no gear.

### Changed
- **The dashcam asks the car for its 360 cameras.** The dashcam needs the
  four 360 cameras, and it was on by default on every car. On a car without
  them it tried to start again and again. Now ModeHelper reads the car's
  equipment row (the bytes the factory wrote to say which options the car
  has) and records only when the row reports the cameras. If it does not,
  or the row cannot be read, the dashcam stays off and the Dashcam page says
  why. **Settings → System** has a new **Car equipment** card with the values
  the car reported, and the phone page has them as text. If the answer is
  wrong for your car, send those lines in a GitHub issue: one car has
  confirmed the values so far.
- **No AEB switch on a car without AEB.** The same equipment row says if
  the car has automatic emergency braking. In the car's own list of trims,
  AEB is fitted exactly where the 360 cameras are. On a car without it,
  **Settings → Drive** no longer shows the AEB switch or its "Restore at
  start" row, and ModeHelper writes no AEB setting. The **Car equipment**
  card also shows the AEB value, the cabin ambient light value and the
  steering side, for reports.
- **No ambient light controls on a car without the ambient light.** On
  such a car, Home Assistant no longer gets an "ambient light" it cannot
  switch (one announced by an older version is removed), and the buttons
  use the theme's own colour, not a cabin light colour that is not there.
- **No Clips card on the phone page of a car without the 360 cameras.**
  There is no dashcam on that car, so there are no clips to list.
- **Shorter dashcam subtitle.** The line under a clip now shows only the
  time, the speed, the gear, and the speed limit when the car knows one.
  An arrow after the speed shows it rising (↑) or falling (↓), doubled for
  a hard stop or a hard start (↓↓, ↑↑). The date, the outside temperature
  and the compass heading are gone; the date is in the clip's name. The
  recorder no longer runs the GPS.
- **The dashcam subtitle shows the brake pedal.** `BRAKE` is on the line
  while the pedal is pressed. It needs the car's CAN reader, which is on
  by default (Config > System); with it off, the line shows no brake mark
  at all.

### Fixed
- **The Spotify card no longer stays up when the car is offline.** When
  the car could not reach Spotify, the card kept the last song forever.
  Now it stays only until that song would have ended, plus 30 seconds
  (30 seconds after the last answer when the song was paused), then hides.
  It comes back with the next answer from Spotify.
- **Park monitoring: the first seconds of a drive are recorded.** When the
  car left Park, the dashcam started the drive's clip up to 5 s late. That
  clip now starts with the last 6 s kept in memory, so it begins before the
  shift.
- **Park monitoring: a drive is no longer labelled "parked: movement".** If
  something was moving when the car left Park, the parked clip went on into
  the drive for up to 5 minutes. It now closes there, and the drive gets
  its own clip.
- **Park monitoring: no old video at the start of a clip.** The first clip
  after the car woke could start with a second of video from before it
  slept, and carry that old time as its name.
- **Park monitoring cannot stop the dashcam.** If the motion detector
  stops working, the dashcam records all the time until the car leaves
  Park, as with the switch off. Before, it would have recorded nothing.

## [v0.10.0] — 2026-11-20

### Added
- **Car app translations (Config > Integrations).** Better text for the
  car's own Energy, Wi-Fi/Bluetooth and Settings apps in Portuguese,
  English, Spanish and Thai: lines left in Chinese, English left inside
  other languages, and wrong meanings (the maintenance tag "Saúde" now says
  "Saudável"). Tap Install. The text is the newest commit of
  `SuspiciousRepair/integration-oem-translations` on GitHub: ModeHelper
  checks for it on Install and at each car start, and downloads it only
  when it changed (with no network it uses the text it kept). An app is
  patched only if the SHA-256 of the car's own copy is the one the text was
  made for (today the apps of system 1111); any other build is left alone.
  ModeHelper patches a copy (only
  the text table changes) and puts it in front of the original with a bind
  mount, through the local ADB. The car's own files are never changed.
  Remove takes the copies away. After a reboot ModeHelper makes them again,
  with the same check, only while the integration is on, and a copy whose
  app crashes is taken away at once (if that fails, the next crash tries
  again). Install, Remove and the copies after a reboot happen only in
  Park, because they restart these three apps; out of Park they wait, and
  the page says so. The Cleanup screen removes it too. The
  Wi-Fi signal word ("强") is in the app's code, not its text, so it stays
  Chinese.
- **The ABRP log shows ABRP's answer.** `abrp-attempt.log` now keeps what
  ABRP replied, not only the HTTP code: a reply of 200 can still mean the
  data was refused.
- **The OBD2 log says why the dongle gave no reading (#144).**
  `obd2-events.log` records connect failures, a lost link and runs of
  rounds with no answer, with the dongle's raw answer, so a gap in a charge
  or a trip can be explained afterwards.

### Fixed
- **Combustion mode plays again.** The car would not play our lock sound:
  its player may read only the car's own sounds, not files from our app,
  so locking played nothing or the car's sound. ModeHelper now places our
  sound on top of the lock sound picked in the car's own Settings (a bind
  mount, through the local ADB). Picking another sound there takes ours
  away, and so does Car's sound. After a restart of the car, ModeHelper
  puts it back. The car's own files are never changed.
- **Drive mode and regeneration come back after a slow start (#130).**
  The saved choice was put back only in the 15 seconds after the screen
  woke. A driver who waited before pressing the brake started the car
  after that, and the car kept its own default. Now the car's on signal
  starts the restore again. Drive mode and regeneration are written only
  in Park, a few times, for up to 3 minutes, and the restore stops when
  you pick another mode in the car. AEB and AVAS keep the 15-second
  window. The on signal and the restore rules come from Funkyfal's fork.
  If the car puts a second default over our write while it is landing,
  that is not taken as your pick: the saved mode is written again.
- **The charging map fills in its blank pieces again.** A map piece that
  did not download (weak signal) stayed blank when you opened the screen
  again before it was tried once more. Now every open map is told to try
  again, and a network coming back is noticed even with an always-on VPN.
- **Cleanup says when the lock sound could not be put back.** Before, a
  failure was silent and our sound stayed mounted after the uninstall. It
  is now tried again once ADB is open, and a failure is written in the
  cleanup log.

### Changed
- **Extra dark is now Darkest mode, and it is darker (#130).** The
  screen light alone went down too little to see. Now a dark layer also
  covers the Dashboard while it is dimmed. The speed and the numbers stay
  readable. A touch or a gear change brings the light back, as before.
  Same switch, new name: Config → Look → Darkest mode. Its settings show
  only while it is on:
  - **Its own hours**, 21:00 to 06:00 unless you change them: the car's
    dark mode started it too early in the evening. Drag the two handles
    on a 24-hour line with midnight in the middle.
  - **On every screen**: the dark layer covers every app, not only the
    Dashboard. The car's own top and bottom bars get the low screen light
    only.
  - **Darkness**: Darkest mode always dims the screen to its lowest
    light. Darkness makes it even darker with the dark layer: 0 % is the
    lowest light only, 100 % turns the screen off (70 % unless you change
    it). If Drive Assist stops while the screen is dark, the helper turns
    the light back on by itself, in about half a minute at most. The
    screen also lights again when the mode's hours end or the car leaves
    dark mode. With ADB off, the helper opens it for a few seconds to set
    the screen light, then closes it again.
  - **Try it**: goes dark at once, in Park and by day too.
- **Energy by use: regen is a source now.** The chart starts with where
  the energy came from: the battery, and regen (what the motor sent back
  while braking, which the car used again). Both go into Used, and Used
  goes to drive, climate and other. Drive is now the motor's whole energy
  out, so it is larger than before. Regen in the chart is the motor's
  number. It is larger than what reached the battery: part of what the
  motor sends back never gets there. On the trip screen the chart
  replaces the net energy line (the chart's Battery is the same number);
  trips with no chart keep the line.
  Known issue: the stats page chart counts only the trips that have a
  split. A trip has none when the app was installed or restarted during
  it. On such a day the chart's Battery Wh/km can differ from the
  efficiency next to it, which counts every trip.
- **A log for "Exact energy" (CAN).** `can-events.log` (next to the other
  logs, see `docs/DIAGNOSTICS.md`) writes one line whenever something
  changes: the app connects to the CAN reader or loses it, modehelper
  starts the reader (and why) or cannot, and a trip ends with no split
  for its energy chart, with the reason (for example, the app was
  installed during the trip). It holds no readings.
- **Releases check their documents.** `tools/check-docs.sh` runs in CI and
  at release validation: broken links in the Markdown files, hub keys
  missing from `docs/HUB-DATA.md`, plans missing from the plan index, and
  an in-app changelog that is not the newest release notes. A tag with no
  section in `CHANGELOG.md` and `RELEASE-NOTES.md` is refused. The list a
  person goes through is in `docs/RELEASE-PROCESS.md`, "Documentation".

## [v0.9.1] — 2026-11-19

### Changed
- **Valet: a detail screen.** Tap a Valet card on the stats page to open
  it: a map of every move (one green dot where the first move started and
  one checkered dot where the last ended, so many moves do not crowd it),
  a timeline of the whole Valet time with each move where it happened,
  the dashcam clips recorded in that time (tap one to play it), and the
  numbers: moves, time moving and parked, distance, top speed (red above
  20 km/h), top power, battery and outside temperature low to high. Valet
  times from before 16 Nov 2026 saved no route, so they show no map.
- **Trip maps: green start, checkered finish.** The start of a trip is a
  green dot and the finish a round checkered flag, in place of a white
  dot and a white ring that were easy to mix up.
- **Trip maps are saved on the car while driving.** The trip screen's map
  is downloaded about once a minute during the drive and again at the
  end, so a trip that ends where there is no signal (a garage) still
  shows its map there. A trip end that could not get the whole map tries
  again when the car is online. Only the map the trip screen shows is
  downloaded, never an area around the car.
- **Trip maps fill their gaps.** A piece of map that did not load (for
  example while the car was joining a network) now loads again by itself
  a few seconds later, and at once when the car is back online. Before,
  the gap stayed until the screen was opened again.
- **Trip screen: the height chart shows at least 200 m.** Small hills no
  longer look like a roller coaster: 50 m of climb now fills about a
  quarter of the chart. Bigger climbs keep their own range.
- **Trip screen: speed is two numbers.** Average and top speed are two
  big numbers, each with its name small under it. Tap them for what they
  mean: the average counts only the time the car was moving.
- **Energy by use: one unit at a time, tap to switch.** Each part of the
  chart shows one number, in kWh, Wh/km or % of what was used, with the
  unit small after it. A tap switches the unit, and the choice is kept,
  on the trip screen and on the stats page.
- **Stats page: the day grid's colours explained.** The colour key has an
  (i): tap under the grid to see what each colour means in km. The colours
  follow your own driving, five groups of 20% of your driving days each.
  The selection box around a day, week or month now has rounded corners,
  like the squares.
- **Charging page: battery health.** The low-battery card is now
  "Battery health". It counts the hours spent below 10% and below 20%,
  not the days that dipped below them, and shows the battery's state of
  health read through the OBD2 dongle.
- **Charging page: kWh per charge.** The Charges card shows the kWh per
  charge under the count, which follows the numbers above it.
- **Charging page: outside temperature without a dongle.** With no OBD2
  dongle, the battery temperature card shows the car's outside air
  temperature in the same way, with an (i) that says which one it is.
- **Charging page: the places map is its own card**, with no frame or
  title, as tall as the cards next to it.

## [v0.9.0] — 2026-11-18

### Changed
- **Trip routes are on a map.** The trip route and All routes screens now
  draw the route over an OpenStreetMap map, with the streets under the
  line. No API key and no use limit. The map is kept on the car, so a
  place seen once shows again with no internet; with no internet and no
  saved map, the route shows on the plain background as before.
- **Charging page: where we charged.** A map card next to the battery
  range chart shows a pin at each place the car charged in the month or
  year shown, with the number of charges there. Touch a pin to select its
  newest charge in the list and the chart; selecting a charge lights its
  pin. Only charges from 16 Nov 2026 on saved their place, so older
  charges have no pin.
- **Config → System → Trip routes: clear the saved map.** Shows how much
  map is saved in the car, with a button to clear it. The map downloads
  again the next time it is shown.
- **Config → Drive mode: the regen buttons show the regen icon.** Low,
  Medium and High now show the same ring as the Turbo card and the
  floating panel, filled to their level, in place of the three dots.
- **Regen icon: the arrow always comes out of a lit segment.** The ring
  now fills from the segment that carries the arrow, so on Low and Medium
  the arrow no longer starts from a dim part. On the Turbo card and the
  floating panel too.
- **Config → Drive mode is calmer.** The Eco/Comfort/Sport and regen buttons
  are about half as tall, and the selected one is a quiet tint instead of
  green, amber, red or blue. When "Show the Turbo card" is off, its
  duration and button switches are hidden, and the "Regen button
  cycles" switch is hidden while the card has no Regen button. The "Restore defaults" button is
  gone: it only picked Eco and Média, the same as two taps, and its message
  showed raw placeholders. No button in Config stretches across the page
  any more (this also makes the MQTT page buttons small).
- **Stats page: "All routes" moved.** The loose "Todas as rotas" button in
  the top bar is gone. The "N trips · N charges" count on the day's trip
  list is now that button, with a map icon. In Day view it opens on that
  day; in Week and Month it shows all days. Each trip keeps its own map.
- **Stats page: charge and trip cards line up.** A charge's energy added
  now sits in the same column as a trip's net energy (the second one),
  next to the battery, instead of at the right of the title line.
- **Extra dark goes darker.** At its floor the screen now goes to 2 of 255
  instead of Android's lowest, 10 (1 turns the screen off).
- **Climate: C3 blows softer.** C3 now runs the fan at 3, not 4. Before, the
  step from C2 to C3 doubled the fan in the same tap that moved the air from
  the windshield to your face, which felt like too much. C1, C2, C4, C5 and
  all warm levels are unchanged.
- **Consumption is now shown in Wh/km, everywhere.** 15.2 kWh/100 km is now
  152 Wh/km: whole numbers, shorter, and the unit ABRP uses. The car's own
  screens still show kWh/100 km (ten times smaller). This changes the stats
  page, the trip cards, the trip screen, the live badge on the Dashboard and
  the CSV export (`efficiency_kwh_100km` is now `efficiency_wh_km`). Stored
  data is unchanged.
- **Stats page: new "Energy by use" card.** For trips measured by CAN, a
  Sankey chart shows where the energy went: used, what came back as regen,
  and the net split into drive, climate and other (12 V loads and losses),
  each part in kWh and Wh/km. Every band is to scale. It sits next to
  Consumption, which no longer repeats net / used / regen. The trip screen
  shows the same chart. The trip you are driving shows it too, live, and a
  trip keeps it after an app update mid-drive when CAN measured most of it.
- **Stats page: "Time & Speed" and "Relief" are one "Trip" card.** Driving
  time, average speed, and the net climb with the climb and descent under it.
  The distance is no longer repeated there: the chart above shows it.
- **Trip cards** show where each km went, under the consumption: drive ·
  climate · other, in Wh/km.

### Added
- **Charge page: four data cards and a month/year picker.** Recargas
  (count, AC/DC split, the charge to 100 %), Custo (total, per 100 km, per
  kWh for AC and DC), Bateria baixa (lowest daily battery, days under 10 %
  and 20 %) and Temperatura da bateria (highest, average, lowest, and time
  at 100 %). They replace the metrics card and the Energy balance chart.
  The period is a calendar month or year, picked like on the stats page.
  Each day's battery temperature is now kept for good. (#119, #120, DB v32)
- **Stats page: a year of days.** One square per day, coloured by its km,
  replaces the 14-bar chart. Tap a day, a week or a month; days with no
  driving show too. (#118)
- **Trip routes as GPX files**, one per day, to the USB stick (with the
  export) and to the phone page. The CSV export gets the trip and charge
  places. (#106)
- **Cleanup removes all of Drive Assist.** It now also removes the helper
  app and the CAN reader, restores the lock sound, and asks whether to keep
  or close ADB. It no longer offers to remove other apps. (#104)
- **The climate ruler's table is a file**, with one set of columns for each
  outside temperature band, so a level no longer moves inside one band.
  (#117)
- **Climate ruler learn mode.** Config → Climate (a new page). When you
  change the climate by hand on the car's panel after a ruler tap and keep it
  10 minutes, it is counted. Once a week, with the climate off, each level
  takes the setting you kept most often that week (at least 2 times): fan,
  temperature, air direction or recirculation. It learns per outside
  temperature band, keeps every level stronger than the one below it, and
  never changes 0, C5 or W5. The page lists what was learned and when.
  "Reset ruler to default" deletes it. On by default; the switch turns it off.
- **Charge spots on the route maps.** All routes shows every place the car
  charged, with the blue plug for AC and the green CCS2 plug for DC (a place
  with any DC charge shows DC; charges within 100 m are one place). A trip's
  map shows only the charge right before it at its start, or right after it
  at its end. A charge's place is where the last trip before it ended; charges
  saved since the route update (16 Nov) get it too.
- **Where a trip's energy went.** Trips measured by CAN save how much went to
  drive, to climate (air-con, heat pump and fan) and to everything else. Open
  a trip to see it. With or without the OBD2 dongle, the three parts add up to
  the trip's energy. (#96, #98)
- **Live power by use** in Config → OBD2, CAN block: drive, climate and other.
  (#96)
- ABRP now gets the battery's average power over each 6 s, not one instant
  reading. (#96)

### Fixed
- **The floating button shows after a new install.** Its draw-over-apps
  permission was granted only on an update, so a fresh install (after a
  reset, or a downgrade) never got it. Now the floating panel asks for it
  whenever it starts without it, opening ADB for the moment if needed.
  (#123, #124)
- **Review fixes** from the week of 16 Nov: settings restore, climate
  learn mode, Extra dark and more, 11 in all. (#116)
- **The AVAS switch no longer jumps from off to on** when you open Config →
  Drive. It drew from a saved copy that went stale whenever AVAS changed
  elsewhere (the Home AVAS button, the car's settings). The copy is gone:
  the AVAS and AEB switches show the car's own state, and appear a
  moment later if the car has not answered yet. The app now also asks
  for AVAS when it starts, so the answer is usually already there.
- **Charge stats: the Energy balance chart shows today.** It ended at
  yesterday, so an overnight charge that ended this morning showed only
  its evening part, and today's driving and charging were missing until
  the next day. The 30 bars now end with today.
- **Tapping a charge opens its details**, on the charge page and on the
  stats page: the cost in its own block with a clear "Change" button (or
  "+ Cost" when there is none yet), the battery % as a light line with
  its start and end value, and the charging power
  curve. In Park only, like before. On the stats page this replaces the cost
  window the tap used to open; on the charge page, while driving, the tap
  only selects the charge.
- **Charges saved no place.** The place was taken from the GPS when the
  charge ended, and by then the GPS is long off. It now comes from where the
  last trip before the charge ended (the car does not move while it charges),
  and older charges get theirs filled in once. (DB v31)
- **CAN battery current read 0.2 A low** (about 80 W at 400 V), so CAN power
  was always a little below the OBD2 dongle. (#97)

## [v0.8.0] — 2026-11-17

### Added
- **Extra dark at night.** New switch in Config → Look → Dark mode. When the
  car is in dark mode and you drive for a minute without touching the
  screen, the Dashboard goes as dark as the screen allows: the backlight
  goes to its lowest level. A touch or a gear change brings the light back; the first
  touch only lights the screen, it does not press a button. Only the
  Dashboard is dimmed, and the car's brightness setting is not changed.
  Off by default. (#89)
- **Front defogger in Home Assistant.** When you press the front-windshield
  defogger button, the app sees it at once: the climate ruler shows
  "defrosting" without the old wait of up to 30 s, the climate log saves each
  press with the outside temperature, and Home Assistant gets a
  "Desembaçador dianteiro" on/off sensor.
- **Soft tyre warning in Home Assistant.** The car's own flat-tyre warning,
  per wheel, as five Home Assistant problem sensors ("Pneu baixo" and one per
  wheel), so an automation can alert your phone. This car has no tyre
  pressure sensors: its warning compares wheel speeds, so there are no
  pressure numbers, only "this tyre is low".
- **Trips record their route.** While you drive, the app saves a GPS point
  every 5 s (only after 25 m of movement), with the drive mode, the regen
  level and the energy used so far. Each charge saves where it was. It all
  stays in the car and goes into the backup. Config → System → Trip routes.
  On by default. (#19)
- **Tap a trip to see its route.** On the stats page, tap a finished trip
  (in Park). The route is drawn as lines on a plain background, no map,
  north up. Each part has the colour of its speed, with the same buckets
  and colours as the stats page: 0–40, 40–80, 80–120 and 120+ km/h. Only
  trips driven with route recording on have a route. (#19)
- **All routes in one picture.** New **All routes** button on the stats page
  (in Park). It draws every recorded route together, in the speed colours,
  next to the list of those trips, with the height profile underneath.
  Step through the days with Previous / Next; Show all goes back to every trip. Touch a
  route or a trip to select it, then **Open this trip** to see its own
  route screen.
- **The route screen shows the height and the trip's numbers.** Under the
  route: the height along the trip, in the same speed colours. Touch it to
  mark that place on the route. On the right: distance, time (moving and
  stopped), average and top speed, energy, kWh/100 km, battery, climb and
  descent, lowest and highest point, outside temperature. (#19)

### Changed
- **The drive mode and regen level are kept as raw data only.** v0.6.0 also
  saved each trip cut into parts per mode. That table is removed; the modes
  stay on the 30 s samples and now on every route point. (#19)
- **The Regen and AVAS quick buttons follow the car.** The home card and
  the floating panel now share one controller and read the level from the
  hub, so a change made on the car's own screen shows at once. (#68)
- **Integrations tiles update while the page is open.** A tile no longer
  stays on "Connecting..." after the integration connected. (#75)
- **A restore sent from a phone cannot change ADB, phone access or the
  commands switch.** It also does not store the backup key. The update
  address can still be set from the phone. The car's own Restore is
  unchanged. (#80)
- **The lock sound link may be http or https.** The download follows at
  most 3 redirects, ends in 20 s, runs one at a time, and the file must be
  a WAV of 1 MB or less. (#78)
- **The route and height views and the home badges draw faster.** They
  build their shapes and text once, not on every frame. (#76, #81)

### Fixed
- **A home AC charge is no longer shown as a DC fast charge.** The app
  calls a charge "DC" when its highest port voltage passes a line. The line
  was 250 V, and home mains can go just above it: one reading of 250.1 V in
  a whole night turned that night into "DC". The line is now 300 V. DC on
  this car reads about 400 V. Old charges are fixed too. (#93)
- **The first Cooler or Warmer tap of a trip starts from what the car really
  runs.** When the car wakes it puts its last climate back by itself, and a
  tap in the first seconds moved from an old value: "Cooler" could make the
  cabin warmer. Now the first tap after a quiet minute reads the car first.
  (#87)
- **The dashcam Record switch stays on after a tap.** In Park with Park
  monitoring on, the recorder waits for movement before it writes, and the
  switch read that as off and flipped back. (#61)
- **A change you make right after the car starts now stays.** "Restore at
  car start" put your saved drive mode, regen, AEB and AVAS back for the
  whole first minute after the car woke, so turning AVAS on and then
  shifting out of Park within that minute muted it again. The car applies
  its own defaults when it wakes (AVAS on, regen Medium), sometimes a few
  seconds late, so Drive Assist still writes your choice for a short while:
  now 15 seconds instead of 60. After that, the car's screen, other apps
  and the home buttons win. (#88)
- **Trip energy stays measured when CAN is on but gives no power.** With
  "Exact energy" on and the OBD2 dongle connected, the app threw away the
  dongle's readings each time the CAN reader was alive, also when CAN had
  no battery volts or amps. The trip then used the estimate. Now the
  dongle's readings count until CAN really has a value.
- **The first 30 s sample after the app starts now has the drive mode and
  the regen level.** The app read them from the car only after it had saved
  that first sample, so it was empty after every app update or restart.
- **A charge no longer gains energy while the head unit sleeps.** After a
  long sleep, the first 30 s sample used the power from before the sleep
  and counted it for the whole sleep (8 h at 6.5 kW gave 52 kWh that never
  went in). Now the app reads the car first, and a sample that comes more
  than 90 s after the last one adds no energy.
- **CAN power no longer shows an old value.** When the battery volts or
  amps from CAN became invalid, the app kept the last power for as long as
  the CAN reader was alive. Now there is no CAN power until both are back.
  Also: the CAN reader stops for sure when "Exact energy" is switched off,
  and it connects again when the reader sends nothing for 5 s.
- **Trip routes get real GPS fixes without MQTT.** The GPS listener lived
  in the MQTT telemetry service, which is off by default. Now it runs while
  trip routes, MQTT or ABRP need it, and stops 2 minutes after Park. A fix
  with no altitude is saved as empty, not 0 m. (#67)
- **The average speed on the route screen is right after a GPS gap.** A
  30 km trip with few points showed 600 km/h. (#70)
- **The USB export always copies car.db and the CSV files.** A file with
  the same size as the old copy was skipped, so the stick kept old data.
  (#69)
- **A failed daily backup waits before it tries again** (1 h, then 2 h,
  4 h, up to one day), and a backup file is written to the drive before it
  gets its final name. (#73)
- **A restore that fails late still saves the trip ids and restarts the
  app.** Before, the open trip and charge could point to the wrong rows.
  (#77)
- **The dashcam Record switch no longer throws you back to Clips** 10 s
  after a tap, and no longer clears a GB limit you are typing. (#72)
- **The parked card keeps the true lowest and highest temperature** of the
  whole stay. On a long park the range got narrower. (#79)

## [v0.7.0] — 2026-11-15

### Added
- **Phone access: use your phone with the car, on the same Wi-Fi.** Config
  → System → Phone access → Show QR code. Scan it to pair the phone. The
  page on the phone downloads trips and charges (CSV), the newest backup
  and dashcam clips, restores a backup from the phone, and types the long
  settings (MQTT, ABRP token, Spotify client ID, update address) with the
  phone keyboard. Off by default: it runs only while the QR code shows.
  Auto also runs it on the home Wi-Fi. It works only in Park and only on
  private addresses. (#58)

### Changed
- **The backup keeps every setting.** It used a fixed list, so settings
  added later were left out (for example the grayscale cards, the lock
  sound and Keep ADB always on). Now only the app's own state is left out.
  After a restore, ModeHelper gets back its own settings too (drive, regen,
  dashcam). An imported custom lock sound is still not in the backup. (#56)
- **The floating button's panel shows the same quick buttons as the Turbo
  card.** Config → Drive → Turbo → "Buttons on the Turbo card" now picks
  Turbo, Regen and AVAS for both places. The AVAS button works the same way
  as on the card, with the sound and muted icons. (#15)

### Fixed
- **The floating panel's Regen button follows "Regen button steps through
  Low, Medium and High".** It was always a Strong-regen on/off button. The
  home card and the floating panel now share the same Regen code. (#15)
- **A new regen icon** on the Turbo card and the floating panel: a ring in
  three segments with a chevron at one tip and a bolt in the middle. One
  segment is filled for Low, two for Medium, three for High. (#15)
- **The floating panel updates as soon as you leave the app.** A button
  you switched off in "Buttons on the Turbo card" stayed on the panel until
  the next time you opened and left the app. (#15)
- **Long button names shrink to fit** on the Turbo card and the floating
  panel, so the Thai Regen name is no longer cut with three buttons.

## [v0.6.1] — 2026-11-15

### Fixed
- **The AVAS button on the Turbo card now turns AVAS on and off.** Drive
  Assist is not allowed to read AVAS (the car asks for a system
  permission), so the button always sent "on". ModeHelper now flips AVAS
  and reports the car's state back. The button shows a sound icon when
  AVAS is on and a muted icon when it is off. The AVAS switch in Config →
  Drive now shows the car's real state too. (#15)

## [v0.6.0] — 2026-11-15

### Added
- **Drive mode and regen level are saved for efficiency statistics.** Every
  30 s sample now records the drive mode and the regen level, and each trip
  is split into parts with one mode each (km, spent, regen and net kWh), kept
  for good. This is the data for kWh/100 km per mode; the screens come later.
  Only trips from this version on have it. (#19 plan, phase 0)
- **Copy dashcam clips to a USB stick.** Config → Recordings: each finished
  clip has a **USB** button, and **Copy held clips to USB** copies every held
  clip at once. Clips go to `Drive Assist/Dashcam/` on the stick, with their
  subtitles (speed and time, shown by players such as VLC) and thumbnail.
  A clip already on the stick is not copied again, and the copy stops early
  when the stick is too full. (#18)
- **Regen button can step through all three levels.** Config → Drive →
  Turbo → "Regen button steps through Low, Medium and High". Off by
  default: the Regen half of the Turbo card keeps toggling Strong regen.
  On: each tap moves Low → Medium → High → Low, and the mark shows the
  car's current level. (#15)
- **Pick the buttons on the Turbo card: Turbo, Regen and AVAS.** Config →
  Drive → Turbo → "Buttons on the Turbo card". Turbo and Regen are on by
  default, so the card looks as before. Turn on AVAS to get a button that
  turns the pedestrian warning sound on and off; its dot is lit when AVAS
  is on. It reads the car's real AVAS state, and it uses the same path as
  the AVAS switch in Config. With no button picked, the card is hidden. (#15)

## [v0.5.0] — 2026-11-14

### Added
- **Lock sound.** Settings → Doors → Lock sound. Pick the sound the car
  plays when you lock it with the key: the car's own, or your own WAV file
  (1 MB or less). Import it from a USB stick (`LockChime.wav` at the top
  of the stick, the same name Tesla uses) or from a link. **Combustion
  mode** is one switch: on, the car farts when you lock it; off, your pick
  comes back. It stays after a restart. Choosing a sound in the car's own
  Settings replaces it, and the card shows that. Needs the new ModeHelper.
- **Backup and restore in the app.** Settings → System → Backup. One file
  (settings, trips, MQTT certificates), locked with your backup password
  (AES-256), goes to the USB stick and to the car storage, by hand or by
  itself once a day in P. After a reinstall the app offers the newest backup
  and asks its password. A restore puts the settings back and adds the
  backup's trips to the ones on the car, never twice. `tools/car-backup.sh`
  drives the same backup over ADB and keeps the APKs; `tools/backup-open.sh`
  unlocks a backup on a computer.
- **Week and Month consumption without OBD2.** When a period has no driving
  energy at all, Consumption & Efficiency now shows kWh/100 km from the
  charges (kWh charged plus the battery drop across the period, over the km
  driven), labeled as charge-based. It includes energy used while parked.
  A charge that runs across the edge of a period is split by time. With
  "Exact energy" (CAN) on, the energy measured going into the battery is
  used; without it, AC charges count about 90 % of the wall energy (charger
  loss). Single days are unchanged. (#21)
- **"Keep ADB always on" (Wi-Fi & ADB > Advanced), off by default.** For
  apps that start a root helper through the car's own ADB, such as
  GeelyTelemetry (Capy). Before, the helper closed ADB 5 minutes after
  every wake and when the car left the home Wi-Fi, which stopped those
  apps. While on, anyone on the same network can get a root shell; the
  setting says so.
- **Exact energy from the CAN bus, no OBD2 dongle, on by default.**
  Settings → System → Car data (CAN). The battery's volts and amps
  are read from the car's own CAN bus through a small reader that
  ModeHelper starts. Energy comes from CAN first, then OBD2, then the
  SoC estimate. Charges also record the energy that went into the battery.
- **Export charges and trips as CSV to USB.** The Export to USB button on the
  charging screen now also writes `drive_assist_charges.csv` and
  `drive_assist_trips.csv` next to `car.db`. They open in Excel, Numbers or
  Google Sheets. (#7)
- **"Keep Wi-Fi on" switch** (Settings → System → Wi-Fi & ADB), on by
  default. Turn it off to let the car manage Wi-Fi itself. Wi-Fi and ADB
  settings moved from the MQTT page to System. (#14, #11)
- **"Restore at start" per setting** (Drive → Advanced), on by default.
  ModeHelper writes the saved drive mode, regen, AEB and AVAS only in the
  first minute after the car wakes, instead of every 4 s. A change made on
  the car's own screen or by another app now stays. The AEB and AVAS
  switches show the car's real value, and a tap applies at once.
  (#12, #13, #16)
- **One Integrations page.** Config has one Integrations item with a tile
  per integration (Home Assistant, OBD2, ABRP, Spotify) and its live state:
  off, searching, connected, needs setup, failing.
- **Two-line parked card** with the time parked, battery start → now, and a
  temperature range bar (lowest–highest while parked, dot = now).
- **Two-line driving card** with distance, time, battery start → now, net
  energy and a kWh/100 km tile on a fixed colour scale (≤12 green, ≥20 red).

### Changed
- **Day-session cards share one layout.** Trip, charge and valet cards show
  one main number on line 1 and four labelled columns on line 2. Trip
  energy now reads as net energy, with "spent − regen" under it.
- **Settings are grouped into fixed-width cards** instead of rows across the
  whole screen.
- **Config sidebar text is the size of the car's own settings menu.**
- **Car data goes through one hub** (CAN, OBD2, ABRP), so every reader sees
  the same values and the same source choice. (#33)

### Fixed
- **The buildings follow the cabin light in "follow drive mode".** With the
  cabin light set to follow the drive mode, the buildings stayed on the last
  colour picked by hand (often red). The car does not report the colour it
  shows in that mode, so the app now reads the light's mode and uses the
  drive mode's colour: Eco green, Comfort blue, Sport red. Home Assistant's
  light shows the same colour. (#17)
- **The clip-recovery process no longer starts a second copy of the app**
  (car reader, trip and charge tracking, ABRP and OBD2) in the background.
- **The no-OBD2 energy estimate runs again.** It read an unrounded battery
  value that nothing wrote.
- **Speed jitter no longer republishes** every tick.
- **Fewer flash writes.** Heartbeats write at most every 30 s (app) and
  15 s (ModeHelper). MQTT skips unchanged repeats and still sends every
  5 min.
- **Card buttons in the right third of the home screen work again.** The
  hidden theme pad took their taps.

## [v0.4.10] — 2026-11-09

### Fixed
- **Cooling no longer reads as heating on a cool evening.** The comfort
  slider decided its side by comparing the setpoint to the outside air, so
  at 18 °C or less outside, the two strongest cooling steps (setpoint 17–18 °C)
  were re-read as "heating · level 1" the next time the car was polled — often
  right after a fan change on the car's own panel. The side is now decided
  by the car's own heat/cool reversal point, which does not move with the
  weather. (#6)

## [v0.4.9] — 2026-10-08

### Fixed
- **The hidden Noturno switch works again.** Its tap area had no width, so
  the eight taps never registered.
- **AVAS no longer turns itself back on.** The car re-arms the pedestrian
  sound on wake, and the helper then saved the car's "on" over your "off".
  Your choice is now kept and written to the car again whenever it differs.
  A sound type picked in the car's own Settings is left alone while AVAS is on.

## [v0.4.8] — 2026-10-02

### Fixed
- **Turning AVAS off now works.** The switch saved your choice but the
  helper copied the car's old value back over it, so the sound never
  changed. The choice is now written to the car within a few seconds.
- **A charge left plugged in after full no longer adds energy.** At 100%
  the car keeps reporting the last current, so a night charge counted
  kWh that never flowed. Counting stops once the battery stays full.
  Past charges are corrected.
- **Week and Month speed chart now covers the whole period**, not one day.

## [v0.4.7] — 2026-09-30

### Fixed
- **A day no longer shows the whole odometer as its distance.** While the
  car wakes up, the odometer can read 0 for a moment. That 0 was saved as
  the day's first reading, so the day showed thousands of km driven and an
  impossible average speed. A 0 odometer is now ignored. On update, stored
  zeros are removed and the days already saved with them are repaired.

## [v0.4.6] — 2026-09-28

### Fixed
- **MQTT no longer flaps online/offline.** Since v0.4.2, each connection
  loss left the dropped MQTT client reconnecting on its own (Paho's
  automatic reconnect), under the same client id. The hidden clients kicked
  each other off the broker ("session taken over") several times a second.
  Paho's automatic reconnect is now off; the app reconnects with a fresh
  client itself.

## [v0.4.5] — 2026-09-28

### Fixed
- **A drive no longer vanishes after Valet is turned off while driving.**
  The rest of that drive started at the exact second Valet ended, and the
  day list counted that second as still in Valet, so the drive disappeared
  once the car parked. The trips were always stored; they show again.

## [v0.4.4] — 2026-09-28

### Fixed
- **OBD2 battery temperature is correct.** It is now read from the BMS
  average pack temperature (DID `4B48`, byte − 40). The DID used before,
  `4B3C`, is not a temperature: it read 67 °C on a 20 °C morning. ABRP,
  the database and the charge chart get the real value again, and the
  false values stored since 2026-09-13 are cleared.

### Changed
- **In Park with Park monitoring on, the dashcam records only when
  something moves.** Nothing is written while all is still; the last 6 s
  wait in memory, so each clip starts a few seconds before the motion and
  ends when it has been quiet for a while. The clips are labelled "parked:
  movement" and age out like any other; nothing is held automatically
  (holding every clip with motion kept every parked clip when plants were in
  view, 600 MB each, never deleted).
- Parked motion ignores spots that move all the time, such as leaves in
  the wind.

## [v0.4.3] — 2026-09-27

### Fixed
- **Config > Appearance no longer crashes the app.** The skyline seed field
  was added to the screen twice, which crashed it whenever the skyline was
  on (the default). Broken since v0.4.0.
- Dashcam: a clip with under two seconds of video, left by turning the car
  off right after a new clip started, is dropped instead of kept.


## [v0.4.2] — 2026-09-27

### Changed
- **Dashcam videos are written crash-safe.** Each video is now a fragmented
  MP4: one piece per second, each saved to storage at once. A crash or power
  cut loses at most about a second, a cut-off video repairs itself into a
  normal clip with its thumbnail, and every frame is written once instead of
  twice (about 7 GB less flash wear per hour of driving). Seeking works in
  the app's player. Older `.h264` leftovers can still be recovered.

### Added
- **Parked motion detection (experimental, off by default).** With "Park
  monitoring" on and the car awake in Park for 30 s, motion in the cameras
  keeps the video (and the one before it, if the motion starts in its first
  10 s) from being deleted. It reads the dashcam's own recording and never
  opens a camera itself, so the factory reverse camera is not affected.

### Fixed
- **Dashcam: the open video now closes when the car goes off**, at the head
  unit's own shutdown signal, instead of hours later when the car wakes.
- **Dashcam: a recorder that stopped on its own restarts** by itself, with
  a growing pause if it keeps failing. Turning the dashcam off is respected.
- **Dashcam: the video being recorded can no longer be "recovered"** and
  damaged by the Recover button; the recorder publishes which one is live.
- Dashcam: never two videos started in the same second; very short clips
  get a thumbnail; stray subtitle and thumbnail files are cleaned up; no
  dropped frames at each 5-minute video change.
- **MQTT fails over to the remote address promptly** after the connection
  drops (for example when leaving home), instead of retrying the home
  address until the next recovery window. The settings screen's connection
  test no longer knocks the running connection off the broker.

### Internal
- Shared SQL definitions for driving, measured and estimated rows, with a
  real-SQLite test.
- Voice assistant phase-zero probe tools and findings (`voiceprobe/`,
  `owwprobe/`), not part of the app.


## [v0.4.1] — 2026-09-26

### Added
- **Recover orphaned dashcam clips in-app.** A clip whose recording never
  closed (crash, power loss) used to sit labeled "unclosed — recoverable"
  with no actual way to recover it. A "Recuperar" button now remuxes the
  raw footage back into a normal playable clip, with its telemetry and
  thumbnail, no external tools needed. A 5-minute clip takes seconds.

### Changed
- **Both apps are now fully compiled after every install.** This head unit
  runs with the Java JIT off, so Android's default install mode compiled
  nothing and all of Drive Assist and ModeHelper ran in the interpreter.
  ModeHelper now asks for a full compile of both apps after each install
  (adb, OTA or installer). Measured: clip recovery ~50x faster.

### Fixed
- **Dashcam recovery aborted on every clip**, and once that was fixed, the
  recovered clip would not decode. Android 9's MP4 writer needs four-byte
  start codes in both the frames and the SPS/PPS header.
- **A failed segment close deleted the only recoverable copy**, and listed
  the broken file as a playable clip. It now stays an orphan you can recover.
- **Clips saved by a parked-monitoring event were evicted** by the ring
  buffer before they ever reached the protected `keep/` folder. The
  recorder now moves them there as the segment closes.
- **Crash leftovers filled the dashcam budget forever**, pushing real clips
  out sooner. They now age out with the clips; the worthless `.mp4.tmp`
  half is removed at once.
- **An interrupted clip kept only its first ~90 seconds of telemetry.** The
  subtitle track is now written every second, and recovery keeps it.
- **EX2 battery temperature could be reported as an impossible sub-zero
  value.** The BMS temperature byte is now kept in its observed Celsius
  form, and unexpected values are logged for diagnosis.
- ABRP could drop a data point entirely instead of sending it: an
  unknown charging reading (a brief sensor hiccup, not just cold boot)
  was being treated as a flat "not charging," which could silently skip
  the whole sample if the car happened to be stationary at that moment.
  Now sent as unknown, never guessed as false.
- ABRP could merge two drives separated by a stop into one continuous
  drive, when the "parked" point was sent late from a garage with no
  signal. A live point now waits out ABRP's bulk-processing window.

### Docs
- `tools/recover-dashcam.sh` recovers orphaned clips on a PC with FFmpeg.
- `docs/DASHCAM.md` now matches the recorder's real behavior.

## [v0.4.0] — 2026-09-24

### Added
- Energy figures now carry a real measured/estimated/mixed data-quality
  flag end to end, surfaced in Home Assistant and marked in the UI.

### Fixed
- Gate button could show "Connected" and do nothing when pressed.
- A charge session could truncate early on a brief signal glitch.
  Charging now self-corrects continuously instead of trusting one edge,
  and survives a crash or OTA install mid-charge.
- An OTA install could interrupt an active DC fast charge — installs are
  now blocked for the whole charge session, not just a `is_charging` blip.
- **A charging reading could stay stuck "on" through an entire drive
  afterward** — confirmed on a real trip: is_charging held true for
  1h14min at highway speed after a real charge ended, which also fed
  ABRP a nonsense 2-hour "charging" entry that had swallowed the whole
  drive. `CarActor` is now the single source for this reading everywhere
  in the app, cross-checked against the plug each poll instead of
  latching on stale current.
- Update checks could keep offering a build you already installed.
- A short trip could show "estimated" from its first minute even though
  OBD2 was connected the whole time.
- A day of real, fully measured driving could still show "(estimate)"
  from idle telemetry with no actual driving in it. Already-affected
  recent days repaired in place.
- ABRP could misreport a normal AC charge as a DC fast charge.
- Several polish issues on the large Spotify card added in v0.3.1
  (toggle, art sizing, caption, spacing).

### Internal
- `TelemetryActivity` split from 2,863 lines into one file per Config
  section; unit test suite grew from 194 to 325 tests.

## [v0.3.2] — 2026-09-22

### Fixed
- A drive that started under Valet, briefly paused, then continued after
  Valet was turned off could merge into one trip and vanish from trip
  history entirely — not just get grouped under Valet.
- Recurrence of the "OBD2 connected, shown as estimated" bug, this time
  on data young enough for the battery_temp_c cross-check to apply.
  Repaired in place via a v22 migration (raw flag, then affected
  daily_stat rows).

### Changed
- Valet can now be turned off while driving, not only while parked.

## [v0.3.1] — 2026-09-22

### Added
- **New large Spotify Now Playing card, picked in Config.** Full-bleed
  album art with a Turbo-card-styled Play/Skip band across the bottom,
  as an alternative to the existing small card. Art is shown uncropped
  (letterboxed, not cut off) and capped in height so a tall promo-style
  cover can't blow the card up; a blurred, dimmed copy of the same art
  fills the letterbox margins instead of leaving them plain white.
- **Each window can now open to its own amount when "crack all windows"
  is used**, instead of every pane opening to the same fixed position.
  Each pane's regulator maps position differently, so the four panes
  didn't visibly match at one shared value. New per-window fields in
  Config > Doors, 0-100, defaulting to the old shared value.

### Fixed
- **A single missed OBD2 reading could mark real driving data as
  "estimated" for far longer than the miss itself.** Power was only
  computed when voltage and current both came back in the exact same
  poll round; if one dropped a packet while the other kept succeeding,
  power stayed stuck unset until they happened to land together again
  -- even though OBD2 was genuinely connected the whole time. Power is
  now computed from each reading's own latest known value instead of
  requiring both in lockstep, so a single dropped packet recovers on
  the very next successful reading of either one.

### Changed
- The state-of-charge battery indicator now fills as a shadow behind
  the outline instead of a flat block, matching the rest of the UI's
  visual treatment.

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
