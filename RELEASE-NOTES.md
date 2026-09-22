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
