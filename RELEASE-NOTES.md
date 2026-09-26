# Driver-facing release notes

Short, plain-language notes for the in-car "What's new" update dialog —
not the full technical history (that's `CHANGELOG.md`, for GitHub).

Add a new `## vX.Y.Z` section here at the same time you close out a
version in `CHANGELOG.md`. `build.sh` reads only the newest section here,
bundles it into the app, and refuses to build if the newest version here
doesn't match the newest version in `CHANGELOG.md` — so this can't go
stale the way the raw-copied changelog did.

Rules for entries: one line per bullet, plain words, no code names, no
issue numbers, no markdown emphasis (the update dialog is plain text).

## v0.4.1 — 2026-09-26

- New: recover an unclosed dashcam recording directly in the app. It
  keeps the speed and driving data and takes only seconds.
- Improved: the app and the dashcam now run much faster and use less
  processor time on this head unit.
- Fixed: a dashcam recording that could not be closed was deleted instead
  of being kept for recovery.
- Fixed: recordings saved by a parked event could be deleted automatically.
- Fixed: unclosed recordings no longer fill up the dashcam storage.
- Fixed: the EX2's battery temperature no longer shows impossible
  sub-zero readings caused by a generic sensor offset.
- Fixed: ABRP no longer drops a reading when the charging sensor is
  briefly unavailable.
- Fixed: ABRP could show two drives with a stop between them as one drive.

## v0.4.0 — 2026-09-24

- Fixed: the gate button could look connected and do nothing when
  pressed.
- Fixed: a charge session could stop tracking early on a brief signal
  hiccup. Charging sessions now also survive a crash or an update
  installing mid-charge.
- Fixed: an update could start installing during an active fast charge.
- Fixed: the app could sometimes still think you were charging for a
  while after you'd actually driven away.
- Fixed: the app could keep offering an update you already installed.
- Fixed: a short trip, or a day of real driving, could show as
  "estimated" even though the car's sensor was connected the whole time.
- Fixed: an ordinary home charge could show up as a fast charge in ABRP.
- Fixed: several small issues with the large Spotify card added last
  release.

## v0.3.2 — 2026-09-22

- Fixed: a drive right after turning off Valet mode could disappear from
  your trip history instead of showing up.
- Fixed: another case of a driving trip mislabeled as "estimated" when
  it was really measured by the car.
- Changed: you can now turn off Valet mode while driving, not just while
  parked.

## v0.3.1 — 2026-09-22

- New: a large Spotify card, with full album art and big Play/Skip
  buttons. Pick it in Settings > Spotify.
- New: each window can now open to its own amount when you crack all
  four at once, instead of all four opening the same amount.
- Fixed: a rare case where a driving trip could get mislabeled as
  "estimated" even though it was measured by the car.

## v0.3.0 — 2026-09-21

- Fixed: a fast charge could show up in your history as barely any
  charge at all. Charging tracking now double-checks the car's real
  charging state instead of trusting one sensor.
- Fixed: the app could slow the whole system down, sometimes stopping
  your music.
- Fixed: the update popup no longer asks you to update twice in a row.
- Fixed: weekly driving reports now start on Monday, so a weekend stays
  together on one report instead of split across two.
- Fixed: a week of driving data (Sept 10-17) was wrongly labeled as
  estimated. It's now correctly shown as measured.
- New: the Driving Mode, Doors, and Elements screens now show real car
  artwork instead of a plain background.
- New: the Eco/Comfort/Sport cards have a cleaner look with real icons.
