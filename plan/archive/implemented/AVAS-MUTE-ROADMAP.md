# AVAS mute roadmap

## Idea

Add a Drive Assist control to mute AVAS (Acoustic Vehicle Alerting System —
the low-speed pedestrian warning sound), the way CentralEXAuto does. This
started as a question about "ADAS sound" but turned out to be a different
system entirely — worth keeping as its own plan so the two acronyms don't
get conflated again (see the note in `docs/field-catalog.md` §6a).

## Findings so far (verified 2026-09-12, live on the car)

- **The OEM Settings "Som" screen has no mute control for this, for any
  tune.** Scrolled the entire screen live: it only offers which AVAS tune
  plays (`Clássico` / `Tom Galático` / `Caminhada Espacial`) via a plain
  property. There is no off/mute button anywhere on that screen — this
  isn't a hidden option on an existing control, the control simply
  doesn't exist in the stock UI.
- **Muting is a separate API, not a property.** `android.car.media.CarAudioManager`
  (hidden framework class, called by reflection — the same pattern
  `CarAccess.audioCall()` already uses in `drivemem` for other audio
  calls) exposes `getAVASMode()` / `setAVASMode(int)`: `0` = muted, `>=1` =
  active mode. The reference app's own mute toggle
  (`KEY_AVAS_MUTED` in `backup-centralex/jadx-out`) uses exactly this call
  — not a patch to the OEM Settings app.
- **Confirmed working from `modehelper`.** A narrowly-scoped one-off test
  (`AvasMuteTestReceiver`, action `com.geely.modehelper.AVASMUTETEST`)
  read the baseline mode (`1`), called `setAVASMode(0)`, read back `0`
  (confirmed muted), called `setAVASMode(1)`, read back `1` (confirmed
  restored). No `SecurityException`.
- **Drive Assist itself cannot make this call directly.** The identical
  call attempted from Drive Assist earlier failed with `SecurityException:
  requires permission android.car.permission.CAR_CONTROL_AUDIO_VOLUME`.
  Same shape as the AEB finding: the platform-signed helper can do it, the
  normal app can't — this needs to route through `modehelper`, same as
  AEB.

## Status bar icon (new idea)

Add a status bar icon that appears **only when AVAS is muted** (hidden
whenever it's on/normal, i.e. the default state shows nothing) — evoking
`mdi:volume-off`. Same mechanism as the existing icon services
(`docs/STATUS-ICONS.md`): its own rank slot from the unassigned pool
(**27-30** free today) and its own small foreground service, mirroring
`SocIconService`'s shape (a listener that posts/updates a notification,
except this one hides itself entirely rather than showing a fallback
value when the state is "normal").

Same open question as the AEB icon (`ADAS-CONTROLS-ROADMAP.md`): every
existing icon here is hand-drawn on a `Canvas`, not loaded from an icon
font — approximate `mdi:volume-off`'s look by hand, or add vector/MDI
rendering as new capability. Decide once for both icons, not twice.

## Open questions

- Does muting AVAS reset on power-on, the way AEB does? Untested — the
  OEM has no UI warning about it either way, unlike AEB's explicit dialog.
  The enforcement strategy below makes this moot in practice (it gets
  corrected within one poll tick regardless), but it's still worth
  knowing for understanding OEM intent.
- Is muting AVAS legal/appropriate to leave persistently on in the
  driver's jurisdiction — pedestrian warning sounds are a regulatory
  requirement in some places at low speed. This is a real-world
  consideration, not just a technical one, and should inform whether the
  feature defaults to a quick per-drive toggle or something stickier.
- Should the UI also expose the tune-selection property
  (`AVAS_SOUND_TYPE_PROP_ID`, need to confirm the exact value — was
  `557871191` in the reference app's constants, not yet independently
  verified live the way the AEB id was), or is mute alone the ask?

## Implementation direction

**Depends on `plan/MODEHELPER-WATCHDOG-ROADMAP.md`** — same reasoning as
the AEB roadmap: no point adding AVAS to a poll loop that can silently
die without restarting.

**Decided strategy: same as AEB (`ADAS-CONTROLS-ROADMAP.md`) — extend
`ModeHelperService`'s existing drive/regen mode enforcement instead of a
one-shot boot restore.**

`ModeHelperService` already holds the owner's preferred drive/regen mode
(sent from Drive Assist via the `SET_MODE` broadcast, cached in
`SharedPreferences("modehelper")`) and corrects drift from it every 4
seconds while parked, in `enforceModeParked()`. AVAS mute should join that
same preference set and the same poll tick: add the owner's preferred AVAS
mode alongside `drive`/`regen`, and an `enforceAvasParked()`-style check
that calls `CarMode.audioCall("getAVASMode")`, compares against the saved
preference, and calls `audioCall("setAVASMode", ...)` to correct it —
using the same `audioCall()` helper already added for
`AvasMuteTestReceiver`, wrapped just as narrowly in the production path
(hardcoded method/args, never a generic "call any method" receiver).

This means AEB and AVAS end up as two more fields tracked by the same
mechanism that already tracks drive/regen mode — one poll loop, one
parked-gate, one place that knows "what the owner wants vs. what the car
currently has," rather than three separate enforcement paths.
