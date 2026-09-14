# Smarter dashcam sessions roadmap

**Quick win, ready now:** item 3 below (wall-clock timestamp in the
subtitle) is decoupled from everything else here — no gear-edge plumbing,
no kW source question, no session correlation. Pure text-formatting
addition to `sample()`'s existing per-second cue string. Can be built and
shipped on its own, independent of the harder items (1, 2, 4).

## Idea

The 360° dashcam (`DashRecorder` in `modehelper`, see `docs/DASHCAM.md`) records
continuously and rotates segments on a **fixed 5-minute timer**
(`SEGMENT_MS`), independent of what the car is actually doing. Make it aware
of driving sessions:

1. Tag each clip with the driving/parking session it belongs to, so tapping a
   drive in the app's trip list shows only that drive's footage.
2. Cut a new segment when gear changes **P → [D, R]** or **[D, R] → P**,
   instead of only on a timer.
3. Add a wall-clock timestamp to the subtitle track.
4. Add instantaneous power (kW) to the subtitle track, so acceleration vs.
   braking/regen is visible while watching.

## What exists today

- `DashRecorder.loop()` arms a rotation only when `seg.ageMs() >= SEGMENT_MS`
  (5 min), then rotates at the next keyframe. Gear has no effect on rotation.
- `DashRecorder.sample()` already reads gear every second via
  `car.readGear()` for the subtitle text (`gear(g)`: N/R/P/D) — modehelper
  can already see gear directly through `CarMode`, no new plumbing needed for
  that part.
- The `.vtt` cue text (`Vtt.cue`) is timed **relative to segment start**
  (`ts(us)` from `pts`), with no wall-clock field anywhere in the cue.
- Clip filenames already carry a wall-clock stamp (`dash_yyyyMMdd_HHmmss.mp4`),
  parsed by `Clips.parseStamp()` on the drivemem side. Segment length is
  known (`Clip.seconds`, from the vtt cue count). So **every clip's
  [start, end] wall-clock interval is already recoverable** without touching
  modehelper at all.
- `TripSession.java` (drivemem) already defines "one drive" precisely: Park →
  Park, with a 75-second park-grace debounce and a 100 m / 45 s
  qualification filter, so brief stops don't fragment a trip. It runs in
  drivemem's process, entirely separate from `DashRecorder` in modehelper.
- No verified, reliable instantaneous-power VHAL property exists yet.
  `PowerProbe.java`'s `POWER_CANDIDATES` (`612369664`,
  `612380672`) are unconfirmed in `docs/field-catalog.md`; the one power
  property that *is* documented there (`606098432`,
  `CHARGE_FUNC_BATTERY_CHARGING_CURRENT_POWER`) is marked dead (🔴, always
  returns `0.0`). The one power source called reliable in the docs
  (`docs/ABRP-GUIDE.md`) is the **OBD2 dongle** (Volts × Amps from the BMS at
  `0x7E2`), read by `Obd2Reader.java` — in drivemem, over Bluetooth, not
  visible to modehelper.

## Intended presentation

- In the app's trip/drive list (wherever a single drive is already shown,
  e.g. daily stats), tapping a drive opens the dashcam gallery pre-filtered
  to that drive's time window instead of the full clip list.
- Segments line up with actual drive boundaries: starting to drive begins a
  new segment near-immediately (bounded by keyframe cadence), and shifting
  back to Park closes the current one, instead of a clip straddling the
  P → D transition in its middle.
- Each subtitle cue shows a real clock time (so a clip can be related to
  "what happened at 6:47pm") in addition to elapsed time, and a signed kW
  figure — positive while drawing power (accelerating), negative while
  regenerating/braking — next to speed and gear.

## Discovery work

1. **Session correlation needs no new signal.** Confirm that matching clips
   to trips can be done purely by overlapping `Clip.whenMs`..`+seconds` with
   each `TripSession` row's `start_ms`..`end_ms` (already stored — see
   `TripSession.java`'s `v.put("start_ms", startMs)` /
   `v.put("end_ms", endMs)`). Check `CarDb`/wherever these rows land for the
   trip table's actual schema and how to query it from the dashcam gallery
   code (`ClipPlayerActivity`/wherever the gallery list lives inside
   `TelemetryActivity`).
2. **Gear-triggered rotation granularity.** Cutting on every raw P↔non-P
   edge (not the debounced TripSession edge) will slice more finely than a
   "trip" — e.g., a stoplight-length Park inside a drive that TripSession's
   75s grace period would NOT treat as a new trip could still get its own
   segment(s). Confirm that's acceptable: it just means one trip may map to
   several consecutive clips instead of exactly one, which the correlation
   in (1) already tolerates. Decide whether the existing 5-minute time cap
   should still apply as a ceiling during a single long, unbroken drive (it
   should — otherwise one segment during a 3-hour highway drive would grow
   without bound).
3. **Where to put the gear-edge check.** `sample()` runs on its own thread
   and already tracks gear per-second; `loop()` (the encoder thread) is
   where rotation is armed. Figure out the least invasive way to hand a
   "gear changed" edge from `sample()` to `loop()` — likely a `volatile`
   flag set on transition, checked once per iteration next to the existing
   `seg.ageMs() >= SEGMENT_MS` check, mirroring how `tele` already crosses
   the same thread boundary.
4. **Wall-clock timestamp source.** Decide whether the per-cue timestamp is
   derived from `System.currentTimeMillis()` sampled once per cue (simple,
   but can drift slightly from the PTS-driven cue boundaries) or computed
   from the segment's recorded start wall-clock time plus the cue's
   segment-relative offset (stays exactly in step with the existing PTS
   sync guarantee documented in `docs/DASHCAM.md` section 3).
5. **kW source.** No confirmed VHAL power property exists yet per
   `field-catalog.md`. Before committing to a mechanism:
   - Test `POWER_CANDIDATES`' two untried VHAL properties
     (`612369664`/`moment_consumption`, `612380672`/`current_consumption_info`)
     directly from modehelper the same way `DashRecorder` already probes
     `P_CAM_LIMIT`/`P_NAVI_LIMIT` — log values across accel/brake/regen and
     see if either tracks direction correctly (sign flips at accel vs.
     regen) and isn't a dead `0.0`, the way `606098432` turned out to be.
   - If none work, decide whether it's worth relaying drivemem's own
     OBD2-derived power (already computed for ABRP) into modehelper instead
     of modehelper reading VHAL directly.

## Candidate mechanisms

**Session tagging** — no modehelper change. Add a query/helper on the
drivemem side that, given a trip's `start_ms`/`end_ms`, lists `Clips.list()`
entries whose `[whenMs, whenMs + seconds*1000]` interval overlaps it, and
wire the trip list's tap handler to open the dashcam gallery pre-filtered to
that set.

**Gear-triggered segment cuts** — in `DashRecorder`, track `lastGear` in
`sample()`; on a P↔non-P edge, set a `volatile boolean gearEdge`. In `loop()`,
treat `gearEdge` the same way `seg.ageMs() >= SEGMENT_MS` is treated today:
arm the rotation (still only executes at the next keyframe, preserving
"segments must start seekable"), then clear the flag. Keep the existing
5-minute timer as an upper bound so a single unbroken drive still rotates
periodically.

**Wall-clock + kW in subtitles** — extend the string built in `sample()`
(the `tele` field) with a formatted clock time and, once a working power
source is confirmed, a signed `%+.1f kW` term; both are pure text-formatting
additions to the existing per-second cue builder, no VTT format change
needed beyond longer cue text.

## Decisions needed later

- Does a trip with no qualifying clips at all (dashcam was off, or the ring
  buffer already evicted them) show an empty state, or hide the "view
  recording" affordance entirely on that trip's row?
- Should Park-only recording (car sitting still, dashcam still running per
  `docs/DASHCAM.md` section 1.2) also get its own browsable "parking
  session" grouping, or stay as ordinary ungrouped clips for now?
- Exact subtitle wording/format for the new fields (e.g. `18:42:07` vs. a
  relative "+3:12" label; `+42 kW` / `−18 kW` vs. "accelerating"/"braking"
  words) — should probably match the existing `·`-separated style already in
  `tele`.
- If VHAL power turns out unusable and OBD2 relay is the only option: kW
  would then only appear in clips recorded while the OBD2 dongle is paired
  and drivemem is running — is a subtitle field that's sometimes present and
  sometimes silently absent acceptable, or should the gallery indicate why
  it's missing?

## Implementation direction

Keep session tagging entirely a drivemem-side, read-only correlation over
existing data (clip filenames + trip table) — no recording-time coupling
between modehelper and TripSession's debounce logic, which stays exactly as
complex as it needs to be for its own purpose. Extend `DashRecorder`
narrowly for the two things that do need to happen at record time: an extra
rotation trigger (gear edge, additive to the existing timer) and two more
fields folded into the subtitle string already being built once a second.
Validate the power source with a logging-only pass (mirroring the
`limitLog()` pattern already used to evaluate speed-limit candidates) before
wiring any kW value into the visible subtitle track.
