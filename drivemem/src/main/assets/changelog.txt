# Changelog

All notable changes to this project are documented here. This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
