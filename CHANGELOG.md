# Changelog

All notable changes to this project are documented here. This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
