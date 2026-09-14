# Home screen drive/park cards + manual Valet Mode roadmap

## Idea

Rename **Config > Barra de Menu** (`cfg_nav_bar`/`cfg_bar_header`, currently
just status-bar icon toggles: outside temp, Wi-Fi, battery %) to something
broader like **Config > Elements**, and add a toggle there for a new
**Current Drive Card** — a home-screen card, sibling to the existing
Charging Card (`chargeCard()` in `ComfortActivity.java`), visible while
driving. Also improve the (not-yet-existing) **Parked Card** and polish the
existing Charging Card.

## What exists today (grounding)

- **Charging Card** (`ComfortActivity.chargeCard()`) already establishes
  the pattern to follow: title row with a glyph, a big live number
  (52sp), a bar, an elapsed/remaining row, and — critically — an existing
  **dismiss** button (`chargeDismissBtn`) that calls `hideCharging()`
  without ending the underlying `ChargeSession` (`ChargeSession.dismissSession()`
  is a separate, explicit action). This is exactly the "dismiss hides the
  card, doesn't touch the session" behavior asked for below — it already
  exists, just needs the same shape applied to a new card.
- **No Parked Card exists yet** — despite being described as a sibling,
  there's currently no dedicated "just parked, not charging" card. This is
  net-new, not a rename/extension of something hidden.
- **Live drive data is already computed, just not surfaced on this
  screen.** `EnergyIntegrator.currentTrip()` returns a live
  `TripSnapshot` (spent/regen/net kWh) while a trip is active;
  `TripSession` already tracks `startMs` (for elapsed time) and
  `startOdoKm` (distance-so-far = current odometer − this). The Current
  Drive Card is mostly a new **view** over data that already exists, not
  new data plumbing.
- **`cards` list + `repackColumns()`** (`ComfortActivity.java`) is the
  existing mechanism for a card that appears/disappears — `chargeCard`'s
  visibility toggles (`View.GONE`/`View.VISIBLE`) trigger a repack. The
  new cards should follow the same add-to-`cards`-list-once,
  toggle-visibility pattern, not a parallel mechanism.
- **Dashcam recording** (`modehelper/DashRecorder`) has `start()`/`stop()`
  with no tagging parameter today — "start a new recording tagged Valet
  Mode" is new capability, not a rename of an existing one.
- **No confirmed instantaneous-power (watts) property exists.** Already an
  open item in `plan/DASHCAM-SMART-SESSIONS-ROADMAP.md` (discovery item
  5) — "track max watts" for joyride detection has the exact same
  dependency. Don't re-solve this twice; one finding should serve both
  features.
- **Speed is already reliable** (`PERF_VEHICLE_SPEED`, ✅ in
  `field-catalog.md`) — "track max speed" needs no new discovery, just an
  accumulator.

## This changes the existing Valet Mode plan

`plan/VALET-MODE-ROADMAP.md` was about **auto-detecting** a valet event
from repeated P→[D,R]→P gear loops — and that plan's hardest problem
(discovery item 0) is that the raw gear signal needed for detection is
partly erased by `TripSession`'s own merge/discard logic before it's
usable.

**A manual toggle sidesteps that problem entirely.** If the owner presses
a button to start Valet Mode, there's no detection heuristic to get
right — the session boundary is given directly by explicit action (start)
and an explicit or timeout-based end (see below), not inferred from noisy
gear transitions after the fact.

**Recommendation: build this manual-trigger version first.** Treat the
auto-detection idea from the older plan as a possible *future* enhancement
layered on top later (e.g., a "Looks like valet parking — tag this as
Valet Mode?" prompt offered after the fact), not a prerequisite for
anything here. The two plans should stay separate documents, but this one
is now the actual implementation path; the older one is deferred research
that may or may not ever be needed once the manual version exists.

## The five states

Not two cards with a state each — **five distinct states** across three
card identities, and the transitions between them matter as much as each
state's content:

1. **Drive Card, while driving.** Live distance/energy/regen/time.
2. **Drive Card, the moment it parks.** Same card, doesn't disappear
   immediately — this is where the Valet Mode button appears. A
   transitional state, not an instant handoff to the Parked Card.
3. **Parked Card, settled parked.** Takes over from state 2 (see open
   question below on exactly when) — park duration, temp swing, battery
   drop.
4. **Charging Card, during charging.** Exists today, polish only.
5. **Charging Card, once charging finishes.** Exists today
   (`chargeCompletedLayout`/`showCompletedCharging()`) for cost input —
   but three things are new asks, not polish:
   - **Send a Home Assistant event on completion.** Nothing publishes
     `charge.completed` (already an internal `EntityBus` event, see
     `ChargeSession.java`) out to MQTT/HA today — `MqttReporter` has no
     subscriber for it and no discovery entity for a "last charge"
     sensor/event. This is genuinely new wiring, not exposing something
     hidden.
   - **Track idle time** — time spent plugged in *after* charging
     finished but before unplugging. The signal already exists to build
     this from: `CHARGE_SWITCH` distinguishes "plugged in, not charging"
     (`OFF`=605028609) from "no cable at all" (`0`) — idle time is the
     span between the session's `end_ms` and whenever the switch reaches
     `0`. No new car-property discovery needed, just a new duration to
     track and display.
   - **Show a charging current curve chart.** No new storage needed
     either — `telemetry_sample` already stores `charge_a`/`charge_v`
     every ~15s for the whole session window (`start_sample_id`/
     `end_sample_id` on the `trip`-adjacent charge record already bound
     it). A curve chart is a new query + a new small chart view over data
     that's already sitting in the database, the same shape as the
     Charge Stats overview's bar chart work.

## Intended presentation

### Config > Elements (renamed from Barra de Menu)

- Same existing toggles (outside temp, Wi-Fi, battery % in the status
  bar) stay — this section just grows a new item, not a new screen.
- New toggle: **show the Current Drive Card**.

### Current Drive Card (new, sibling to Charging Card)

- Visible on the home screen while driving (mirrors how Charging Card is
  only visible while charging).
- Shows: distance so far, energy spent, energy regen, elapsed time.
- **Dismissible**, same semantics as the Charging Card's existing dismiss:
  hides the card, does **not** touch the underlying drive/trip session —
  `TripSession`/`EnergyIntegrator` keep accumulating regardless of whether
  the card is shown.
- **When parked**, the card shows a button to turn on **Valet Mode**.

### Valet Mode (manual trigger)

Turning it on:
- Stops the current dashcam recording segment and starts a new one,
  tagged as Valet Mode (new capability needed in `DashRecorder` — some
  marker distinguishing these clips in the gallery, e.g. a filename
  convention or a sidecar flag, to be decided in discovery).
- Starts tracking, for the duration of Valet Mode: max speed, max watts
  (pending the same open power-source question as
  `DASHCAM-SMART-SESSIONS-ROADMAP.md`), and total distance — a "joyride
  detection" figure the owner can check afterward.
- Aggregates every drive and parking gap that happens next into the same
  Valet Mode session — no more "six separate trips," one running session
  — until either:
  - the owner explicitly turns Valet Mode off from the screen, or
  - **10 minutes of continuous driving** happens (a real drive, not
    shuffling) — auto-exits on the theory that a real 10-minute drive
    means the car is back with its owner, not being shuffled by a valet.

### Parked Card (new)

- Shows how long the car has been parked (delta from first-parked
  timestamp to now).
- Temperature swing since parking (outside temp at park-start vs. now —
  `AC_AMBIENT_TEMP`/`readOutsideTempC()` already read elsewhere).
- Battery drop since parking (SoC at park-start vs. now — already read
  every tick).

### Charging Card (polish only)

Already functionally complete per the owner ("we have most of what we
need now") — this is font-size/number-balance tuning on the existing
`chargeCard()`, not new functionality. Lowest-effort item here.

## Discovery work

1. **Dashcam tagging mechanism.** Decide how a Valet Mode segment is
   marked so the gallery can filter/label it — filename convention
   (matches the existing `dash_yyyyMMdd_HHmmss.mp4` pattern with a
   suffix/prefix?), or a sidecar metadata file, or a DB-side tag if clips
   ever get a database row of their own. Affects both this feature and
   any future work on `DASHCAM-SMART-SESSIONS-ROADMAP.md`'s session
   tagging.
2. **Where does "10 minutes of continuous driving" get measured from?**
   Likely mirrors `TripSession`'s own `drivingDurationMs` accumulation
   (driving segments only, park time doesn't count toward it) rather than
   wall-clock time since Valet Mode started — needs to tolerate normal
   red-light/traffic stops without resetting, the same way `TripSession`
   already does via its park-grace period.
3. **Watts/max-power source** — same open question as
   `DASHCAM-SMART-SESSIONS-ROADMAP.md` discovery item 5. Resolve once,
   reuse for both.
4. **What happens to Valet Mode's own aggregated session in Daily
   Stats/trip history** — does it show as one combined entry (like the
   older Valet Mode plan's "one expandable event"), or is that grouped
   presentation itself out of scope for the manual-trigger version (i.e.,
   Valet Mode only changes dashcam tagging + a joyride summary, and daily
   stats keeps showing the individual short trips as today)? This decides
   how much of the *old* plan's "Intended presentation" (expandable
   grouped event, excluded from trip count, reversible grouping) still
   applies here vs. is deferred.
5. Confirm `TripSession`/`EnergyIntegrator`'s live accessors
   (`currentTrip()`, `startOdoKm`, `startMs`) are safely readable from
   `ComfortActivity` on a UI-refresh cadence, the same way the Charging
   Card already polls/receives updates for its own live numbers.

## Decisions needed later

- **When does state 2 (Drive Card just parked) hand off to state 3
  (Parked Card)?** Immediately on park (Drive Card only flashes the Valet
  button for an instant), after some grace delay, or only once the owner
  dismisses the Valet offer / it times out? This is the one transition in
  the whole roadmap without an obvious existing precedent to copy.
- What exactly counts as "charging finished" for the new HA event —
  the same `charge.completed` `EntityBus` moment `ChargeSession` already
  fires, or a later point (e.g. once the owner enters a cost, or once
  unplugged)? Affects whether the event fires once per session or could
  need to fire again later.
- Exact wording/icon for "Valet Mode" button and card (Portuguese +
  English, consistent with the older plan's still-open
  Manobrista/Valet naming question).
- Does dismissing the Current Drive Card mid-drive re-show automatically
  next time the car parks (matching "dismiss ≠ end session"), or does the
  owner have to re-enable it from Config each time?
- Should Parked Card's temperature/battery deltas persist and show even
  after a very long park (multi-day), or reset/hide past some threshold?
- Is Valet Mode's joyride summary (max speed/watts/distance) shown
  automatically when Valet Mode ends, or only on request?

## Implementation direction

Build in this order, since each piece is independently useful and later
ones depend on earlier ones existing:

1. **Charging Card polish** — lowest effort, no new plumbing, do it
   whenever, independent of everything else here.
2. **Current Drive Card** — new view only, reusing
   `EnergyIntegrator.currentTrip()`/`TripSession` fields that already
   exist. Copy the Charging Card's card-in-`cards`-list +
   dismiss-without-ending-session pattern directly.
3. **Parked Card** — same shape as Current Drive Card, new accumulators
   for park-start temp/SoC/timestamp (small, local to `ComfortActivity`
   or a new small state class mirroring `TripSession`'s style).
4. **Valet Mode** — depends on (2) for its entry point (the button shown
   on Current Drive Card once parked). Dashcam tagging and the
   10-minute-driving exit condition are the two pieces of real new logic;
   everything else is aggregation/display on top of data already tracked.

Config > Elements rename + the new toggle is a small, independent change —
can land first, before any of the cards exist, since it's just a
renamed section with one more (initially inert) toggle.
