# modehelper liveness watchdog roadmap

## Idea

Give `modehelper` a heartbeat + self-restart watchdog, mirroring the one
`drivemem` already has (`util/Beat.java` + `util/BootReceiver.java`'s
`scheduleWatchdog`/`ensureAll`). Today nothing tracks whether
`ModeHelperService`'s poll loop is actually alive.

## Why this matters (root cause of the valet-parking incident)

Investigated 2026-09-12 after the owner found the car in Comfort mode
after valet parking, with the saved default untouched. Two separate
things were going on:

1. Quick-picking a mode in Drive Assist applies it live but does not
   touch the saved default — only pressing **Save** does that
   (`TelemetryActivity.saveDefault()` vs. the plain-apply path). That part
   is working as designed.
2. The real gap: `ModeHelperService.enforceModeParked()` should have
   caught Comfort drifting from the saved default within one 4-second
   poll tick while the car sat parked — but evidently didn't. `modehelper`
   only restarts on a genuine `BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED`
   (`modehelper/src/.../BootReceiver.java`) — it has **no** equivalent of
   `drivemem`'s `SCREEN_ON`/wake-listener + `onResume` re-arm, which exists
   there specifically because **this head unit suspends instead of
   rebooting**. If `ModeHelperService`'s process died during a long
   suspend (entirely plausible over a valet handoff), nothing would bring
   it back until an actual reboot. This is the same class of bug that
   `drivemem`'s own `Beat`/watchdog system was built to catch — it just
   was never extended to the other app.

## Proposed mechanism (mirrors `drivemem`, self-contained in `modehelper`)

`drivemem`'s pattern, for reference:
- Each service calls `Beat.mark(ctx, who)` once per loop iteration —
  a timestamp in `SharedPreferences`, so it survives process death.
- `BootReceiver.scheduleWatchdog()` schedules a repeating alarm via
  `AlarmManager.setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, ...)` —
  chosen specifically because an alarm that comes due *during* suspend
  fires the moment the car wakes, which is exactly the failure mode here.
- `ensureAll()` checks each service's beat against a generous staleness
  threshold (4x its loop interval) and restarts only that service if
  stuck, rate-limited to avoid restart loops (`Beat.mayRestart`).

`modehelper` needs its own copy of this, not a cross-app one — it's a
separate process/uid, so only it can reliably restart itself:

- `ModeHelperService.pollLoop()` marks a heartbeat every iteration (reuse
  the existing `SharedPreferences("modehelper")` file already used for
  the drive/regen/AEB/AVAS preferences).
- `BootReceiver` (modehelper's) schedules the same kind of
  `setExactAndAllowWhileIdle` alarm, checks the heartbeat, and restarts
  `ModeHelperService` if stale — same shape as `drivemem`, one service
  instead of four, so no dispatch table needed.
- Stale threshold: something like 4x the 4-second poll interval floored
  at a sane minimum, same reasoning as `drivemem`'s `STALE_*_MS` constants.

## Bonus: this also covers AEB and AVAS

`plan/ADAS-CONTROLS-ROADMAP.md` and `plan/AVAS-MUTE-ROADMAP.md` both
decided to extend `ModeHelperService`'s existing poll loop rather than
build separate enforcement paths. That means this one watchdog, once
built, backstops all three (drive/regen, AEB, AVAS) — not three separate
reliability problems to solve later.

## Open questions

- Should the heartbeat/staleness also be surfaced to Drive Assist (e.g.
  over MQTT, "modehelper last seen Xs ago"), so the owner has visibility
  instead of only silent self-healing? Ties back to the original
  question of "does the car announce a mode change" — a visible heartbeat
  at least confirms enforcement is active, even without attributing *why*
  a mode changed.
- Same staleness constant for all three enforced values, or does AEB (a
  genuine safety property) deserve a tighter check than drive/regen mode
  cosmetic drift?

## Implementation direction

Build this before or alongside the AEB/AVAS enforcement work, not after —
extending the poll loop to cover three properties without also fixing its
"can silently die and never come back" problem just triples the blast
radius of the same bug.
