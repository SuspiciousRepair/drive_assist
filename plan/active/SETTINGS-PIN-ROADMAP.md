# Settings PIN lock roadmap

## Idea

Gate access to Config (`TelemetryActivity` — MQTT credentials, ADAS/AVAS
toggles once those exist, OBD2, theme, everything reachable from the gear
icon) behind a PIN, so a valet, a curious passenger, or anyone else who
gets into the driver's seat can't open or change settings. If the PIN is
forgotten, it can be reset without knowing it — but only by proving the
car is physically parked at home: a GPS location captured (never shown)
when the PIN was first set.

## Intended presentation

- Setting a PIN for the first time also silently captures the current GPS
  fix as "home." No fix available yet → PIN can still be set, but
  reset-by-location isn't available until a fix is captured (retry next
  time the app has one, e.g. next time parked at home).
- Opening `TelemetryActivity` (the gear icon) asks for the PIN first if
  one is set. `ComfortActivity` (the main dashboard) stays ungated — this
  is about protecting settings, not blocking ordinary driving controls.
- Forgot-PIN flow: a "Reset" affordance on the PIN entry screen. If the
  car's current GPS is within a home radius of the stored coordinates,
  allow setting a new PIN. Otherwise refuse with a generic message that
  doesn't reveal *why* (not "you're not home," just "can't reset right
  now") — no reason to teach a stranger trying to bypass it what the
  mechanism is.
- Home coordinates are never displayed anywhere in the UI, never sent
  over MQTT, never logged — matches this project's existing stance on not
  publishing coordinates (see `docs/HOME-ASSISTANT.pt-BR.md`).

## Discovery work

1. **GPS fix reliability at home is the load-bearing risk.** `GpsReader.read()`/
   `GpsReader.live` already exist and are used elsewhere, but a fix from
   inside a garage or close to a building can be weak, delayed, or
   unavailable. If home is normally a garage, the reset mechanism could be
   unusable exactly when needed. Needs testing at the actual parking spot,
   not assumed to just work.
2. Decide the geofence radius: civilian GPS accuracy is commonly 5-15m in
   open sky and worse near structures — too tight a radius makes
   "reset" reject the very spot it's supposed to authorize; too loose
   weakens the whole point. Needs a real-world number, not a guess.
3. Confirm PIN storage approach: a salted hash, not plaintext, in
   SharedPreferences — check whether any existing pattern in this codebase
   already does salted hashing (MQTT password fields currently store
   plaintext for the broker connection itself, which is a different
   requirement — a login secret you must send verbatim vs. a local PIN you
   only ever compare).
4. Decide whether every screen behind the gear icon needs the PIN, or only
   specific sensitive sections (MQTT credentials, OBD2, any future ADAS/AVAS
   toggle) while lower-stakes ones (theme) stay open. Simpler to gate the
   whole `TelemetryActivity` entry point; more granular is more UI work for
   arguably not much benefit.

## Decisions needed later

- PIN length and entry UI — numeric keypad built for this, or the OS
  keyboard (already the established pattern for numeric entry per recent
  charging-price work)?
- Does a successful location-based reset wipe just the PIN, or also offer
  to review/change other settings while unlocked that way?
- **Changing home later** (moved house): should be possible, but only by
  someone who already knows the current PIN — otherwise a thief who
  doesn't know the PIN could "move home" to somewhere convenient for them
  and defeat the whole mechanism. Likely: changing home requires the old
  PIN; forgetting the PIN only ever resets the PIN itself, at the
  *existing* home.
- Rate limiting / lockout behavior for repeated wrong PIN attempts, so it
  isn't trivially brute-forceable (a 4-digit PIN is only 10,000
  combinations).
- Does this need to survive an app reinstall (stored data wiped) — if so,
  is that acceptable (reinstall = fresh PIN setup) or does it need to
  persist somewhere sturdier?

## Implementation direction

Gate only `TelemetryActivity`'s entry point, not `ComfortActivity` —
keeps driving-relevant controls always reachable and confines this to
"protect settings," which is the actual goal. Store the PIN as a salted
hash and the home coordinates in `SharedPreferences`, written only at
PIN-set time using the existing `GpsReader` — no new location-reading
mechanism needed, no continuous tracking, just one read at setup and one
read per reset attempt. Nothing here should touch MQTT, logs, or any
outbound channel — this is a purely local, on-device gate.
