# Valet mode roadmap

## Idea

Add a **Valet mode** that recognizes a group of very short drives and parking
gaps as one higher-level valet event. This should keep daily statistics useful
when a valet repeatedly moves the car over a small distance instead of showing
each maneuver as an ordinary trip.

The motivating example is late at night on **2026-09-11**, when the telemetry
contains six drives shorter than 0.2 km. Confirmed by direct analysis of
`car.db` (2026-09-12) — **this was a real valet event.** See discovery item 1
for the full reconstructed sequence.

## Intended presentation

- Keep the original drives and telemetry intact.
- Show one expandable valet event in the day's session list.
- Summarize its start and end time, total distance, total energy, number of
  maneuvers, and combined parking time.
- Let the owner expand it to inspect every drive and parking gap.
- Exclude the short maneuvers from the normal trip count when they are grouped,
  while retaining their distance and energy in daily totals.
- Make automatic grouping reversible so the owner can split an incorrect group.

## Discovery work

0. **The P→[D,R]→P loop signal is already partly erased by the time a
   `trip` row exists — this has to be reconciled before anything else
   here matters.** `TripSession.onGear()` (`state/TripSession.java`)
   already does two things to raw gear edges, both upstream of daily
   stats ever seeing them:
   - **Merge**: a Park→Drive flip within `PARK_GRACE_PERIOD_MS` (75s) of
     the last park does **not** start a new trip — `tripActive` is still
     true, so it just stitches the new drive segment onto the trip
     already open (`onGear()`, the `if (tripActive)` branch). Several
     quick sandwiches with short park gaps between them already collapse
     into **one** trip today, not several.
   - **Discard**: `finalizeTrip()` only writes a `trip` row if
     `isQualified()` — at least `MIN_TRIP_DISTANCE_KM` (0.1km) **or**
     `MIN_TRIP_DRIVE_DURATION_MS` (45s). Anything under both thresholds is
     logged ("Trip discarded...") and dropped — its energy snapshot is
     computed and then never written anywhere.
   - **Consequence**: a sequence of valet sandwiches, as it actually
     happened at the gear level, is *not* recoverable from the `trip`
     table alone — some of it may already be one merged row, some of it
     may never have been written at all. Grouping "existing drive and
     parking events" (as this plan originally assumed) means grouping
     data some of which has already been silently merged or erased by
     exactly the mechanism this feature needs to detect. Confirms why the
     six 2026-09-11 rows exist as separate qualifying trips at all: each
     individual maneuver must have independently cleared the 45s-or-100m
     bar, and each park gap between them must have exceeded 75s —
     otherwise they'd already be one row, or missing.
1. ~~Inspect the six sub-0.2 km drives on 2026-09-11~~ Done, 2026-09-12,
   direct query of `car.db` (`telemetry_sample`/`trip`). Reconstructed the
   raw gear sequence, not just the trip rows:

   | Time | Raw gear sequence | Recorded as |
   |---|---|---|
   | 20:56–21:25 | (real drive) | trip 9 |
   | 21:25:45 | R (backs into a spot) | — |
   | 21:29:31→21:31:23 | R, P↔N↔P wobble | trip 10 (0m, 178s) |
   | 21:41:44→21:44:55 | D↔P↔D↔P | trip 11 (0m, 192s) |
   | 21:46:53→21:49:22 | D→R→P | trip 12 (0m, 149s) |
   | *(~58 min parked)* | | |
   | 22:49:26→22:51:29 | D→R→P | trip 13 (100m, 123s) |
   | *(~38 min parked)* | | |
   | 23:29:39→23:35:08 | R→D→R→D→P | trip 14 (100m, 329s) |
   | *(~8 min parked)* | | |
   | **23:43:42→23:44:14** | **R→P, 32s, 0 speed throughout** | **discarded — no trip row at all** |
   | 23:48:05→23:54:19 | D→P→D→R→D→R→D→R→P | trip 15 (100m, 374s) |
   | *(~31 min parked)* | | |
   | 00:25:22 | (real drive away) | trip 16 |

   Confirms discovery item 0 concretely, not just in theory: the
   23:43:42 blip is a real maneuver (gear engaged, held for 32s) that
   never became a `trip` row — grouping from `trip` rows alone would
   silently miss it. And trip 15 alone already contains four separate
   D/R flips merged into one row — "one trip row = one sandwich" does
   not hold even for the rows that *do* get recorded.

   Also: **no location is stored anywhere per session.** Neither `trip`
   nor `park_session` has lat/lon columns, and `telemetry_sample` only
   carries `altitude_m`. The "small geographic area" secondary filter
   (below) has no existing data source — it would need new capture
   (`GpsReader` is already read elsewhere, just never persisted per trip).

   Total across the six recorded rows: ~300m, ~0.188 kWh spent, ~0.0004
   kWh regen, ~22 minutes of actual gear-engaged time — spread across
   **2.5 hours of elapsed wall-clock time**, with idle gaps up to 58
   minutes between bursts. See "bounded time window" below — this alone
   rules out a short fixed timeout.
2. Compare them with legitimate short trips and ordinary driveway maneuvers.
3. ~~Determine which available signals can identify valet activity
   reliably~~ Decided: repeated P→[D,R]→P gear-transition loops, distance/
   area/time as secondary filters (see "Candidate grouping rule" below) —
   but per item 0, this has to run on the *raw* gear signal, not be
   inferred from `trip` rows after TripSession's merge/discard has already
   run on it.
4. Decide whether Valet mode is explicitly enabled by the owner, inferred after
   the fact, or supports both flows.
5. Prototype the grouped event in Daily Statistics before changing stored data.

## Candidate grouping rule to evaluate

**Primary signal, decided:** detect the gear-transition pattern itself, not
distance first. A single **P → [D, R] → P sandwich** — Park, into Drive or
Reverse, back to Park — is one maneuver. A **loop** of these, several
sandwiches happening back-to-back with only brief Park gaps between them,
is the valet signature: it's what shuffling/repositioning a car into a
spot looks like at the gear level, in a way an ordinary short trip does
not. This directly resolves "should a single short drive ever become a
valet event?" (below) — no: one sandwich alone is an ordinary maneuver,
only a *repeat* of them is the pattern.

Distance/area/duration (the original candidate below) become secondary
filters on top of this, not the primary detector:

- every drive in the loop is at most some small distance (confirmed by the
  September 11 example: 0-100m per maneuver, well under 0.2km);
- consecutive drives remain within one small geographic area (no data
  source for this exists today — see discovery item 1);
- the whole sandwich-loop fits within a bounded time window — but per the
  real example, gaps between bursts reached **58 minutes**, and the whole
  event spanned **2.5 hours**. A short fixed timeout (the original
  assumption here) would have split this one real event into 2-3
  disconnected clusters. Either the window needs to be generous (an hour
  or more), or the end condition needs to be something other than a fixed
  timeout — e.g. "ends when the next drive looks like a real departure"
  (matches the qualification filter's own OR logic: long distance or long
  duration) rather than "ends after N minutes of no activity."

Time of day alone should still not classify an event.

## Decisions needed later

- What starts and ends an explicitly enabled Valet mode?
- Should the mode add vehicle restrictions or only organize statistics?
- What maximum distance, parking gap, geographic radius, and total duration are
  safe defaults?
- ~~Should a single short drive ever become a valet event?~~ Decided: no —
  only a repeated P→[D,R]→P loop counts, one sandwich is ordinary.
- How many sandwiches, and how short a Park gap between them, counts as a
  "loop" rather than two unrelated ordinary maneuvers hours apart?
- How should a normal trip inside a candidate sequence break the group?
- Should grouped valet activity appear in exports and charts, and at what level?
- Which name is clearest in Portuguese and English: **Manobrista / Valet**?

## Implementation direction

Still a derived layer, not a rewrite of `TripSession` itself — but per
discovery item 0, it cannot be *purely* a read-only view over the `trip`
table, since that table has already lost the raw sandwich structure to
merging and discarding. Likely needs its own raw gear-edge listener
(subscribing to the same gear signal `TripSession.onGear()` does,
independently, the way `DASHCAM-SMART-SESSIONS-ROADMAP.md`'s gear-cut
idea also taps gear directly) that records candidate valet sandwiches
*before* TripSession's merge/discard decisions apply — then reconciles
that raw log against whatever `trip` rows (if any) actually resulted, so
the grouped event can still show real distance/energy even for maneuvers
that never became a `trip` row on their own. This is the piece to
prototype first — everything else here (thresholds, presentation,
naming) is downstream of confirming this reconciliation actually works.

Once the rules are stable, add fixture data modeled on the September 11
sequence and tests for grouping boundaries, totals, expansion, and manual
ungrouping. Use the `mdi:shield-car` icon to mark valet parking.

