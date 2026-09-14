# AEB toggle & ADAS sound roadmap

## Idea

Add a Drive Assist control to turn off **AEB** (Assistente Pré-colisão /
Autonomous Emergency Braking), the way the reference app CentralEXAuto does.
A second, related idea surfaced during research: can the ADAS warning sound
be silenced without going through the OEM UI at all, as a lighter-weight
alternative to disabling AEB itself?

## Findings so far (verified 2026-09-12, live on the car)

- **AEB has its own property, separate from FCW.** Property `557858874`
  (boolean, area 0) is the AEB master switch. It is distinct from
  `557858878` (Forward Collision **Warning**, already in the catalog) —
  AEB is the active-braking system, FCW is only the alert. Confirmed by a
  directed diff: snapshot before/after toggling "AEB" on the OEM
  Assistente Pré-colisão screen changed exactly this property, `true` ->
  `false`.
- **Independently confirmed by decompile.** The reference app's own source
  (`backup-centralex/jadx-out/sources/com/ex/auto/AppConstants.java`)
  defines `AEB_PROP_ID = 557858874`, `AEB_PROP_ZONE = 0` — an exact match
  to the live-diff result from a completely different method. High
  confidence this is the right property.
- **Turning off AEB also turns off FCW.** The same diff shows
  `557858878` and `557858879` (FCW sensitivity) changing alongside AEB,
  and the OEM screen grays out the "Aviso de Colisão Frontal" row when AEB
  is off. The two are not independent in the OEM's own logic.
- **The OEM re-arms AEB on every power-on, by design.** The confirmation
  dialog states outright: "Esse recurso é reativado automaticamente na
  próxima vez que o veículo for ligado." The reference app's own
  preference keys — `KEY_AEB_RESTORE_ON_BOOT` / `aeb_restore_on_boot` and
  `KEY_AEB_STATE` / `aeb_state_saved` — show it works around this by
  saving the desired state and rewriting the property after boot, rather
  than fighting the re-arm directly.
- **`modehelper` CAN write it — confirmed live, 2026-09-12.** A narrowly
  scoped, hardcoded one-off test receiver
  (`modehelper/src/.../AebWriteTestReceiver.java`, action
  `com.geely.modehelper.AEBWRITETEST`) read the baseline (`true`), wrote
  `false`, read back `false` (confirmed changed), then restored `true`
  and read back `true` again — no `SecurityException`, unlike the audio
  calls below. The OEM screen reflected the change live and did not show
  its own confirmation dialog at all — that dialog is enforced in the
  settings app's UI layer, not by the property itself, so any writer that
  isn't the OEM app skips it entirely. **This settles the core
  feasibility question: the write path exists and works from the
  existing platform-signed helper.** Write access from Drive Assist
  itself (not platform-signed) is still untested and expected to fail the
  same way the audio calls did, based on the pattern below.
- **Some car audio APIs are permission-walled to platform-signed apps.**
  Live probing (`AUDIOPROBE` diagnostic) showed `getBeepLevel`,
  `getVehicleAlarmLevel`, and `isAVASModeSupported` all fail from Drive
  Assist with `SecurityException: requires permission
  android.car.permission.CAR_CONTROL_AUDIO_VOLUME`. The reference app
  (`CentralEXAuto-geely-platform-signed.apk`) is platform-signed and does
  not hit this wall. Drive Assist is signed with `debug.ks`, not the
  platform key, so the same permission gate likely applies to writing
  `557858874` too — untested, but the audio result is a strong precedent.
  `modehelper` (already platform-signed, `android.uid.system`) is the
  existing precedent in this codebase for exactly this kind of
  permission gap.
- **No separate "ADAS beep volume" property found — and the original ask
  turned out to be a different system entirely.** The per-type warn
  volume family (`getWarnVolume`/`setWarnVolume`, warnType 0..19) reads
  back successfully from Drive Assist (no permission error) but returned
  identical, suspicious-looking values (`max=39 min=0 cur=0`) for every
  single type while parked — inconclusive and likely a dead end. Follow-up
  with the user clarified "ADAS sound" actually meant **AVAS** (the
  low-speed pedestrian warning sound, a completely different system) — see
  `plan/AVAS-MUTE-ROADMAP.md` and `docs/field-catalog.md` §6a for that
  investigation, which did find a working mute path. Nothing here
  suggests a real mute lever for the FCW/AEB collision beep specifically
  still exists to be found.

## Status bar icon (new idea)

Add a status bar icon that appears **only when AEB is off** (hidden
whenever it's on/normal) — a persistent visual reminder, not a toggle.
Same mechanism as the existing `OutTempService`/`SocIconService`/
`WifiIconService` (notification-based injection, see
`docs/STATUS-ICONS.md`): needs its own rank slot from the unassigned pool
(**27-30** are currently free) and its own small foreground service.

One open question shared with the AVAS icon below (see
`AVAS-MUTE-ROADMAP.md`): every existing icon in this app is a **hand-drawn
bitmap** (`Canvas`-drawn digits/shapes), not a loaded icon font/vector —
so rendering something evoking `mdi:car-brake-alert` means either
hand-drawing an approximation in the same style, or introducing MDI/vector
icon rendering as a new capability this app doesn't have yet. Resolve once,
applies to both icons.

## Discovery work remaining

1. ~~Test a real write from `modehelper`.~~ Done — confirmed working,
   2026-09-12 (see finding above).
2. **Optionally confirm whether Drive Assist itself can write it too**
   (would need a boolean path added to the `WRITEPROP` diagnostic, which
   currently only handles int/float). Not required to ship the feature —
   `modehelper` already proves the path exists — but would simplify the
   architecture if it turns out Drive Assist doesn't actually need to
   route through `modehelper` for this one property. Low priority given
   the audio-call precedent suggests it will fail.
3. **Resolve the per-type warn volume question with the car actually
   driving/alerting** — the `cur=0` readings while parked may simply mean
   "no alert playing right now." Needs a real (or safely simulated) FCW
   trigger while watching which warnType's `cur` value moves, or a
   `Discovery` diff of the same properties across an alert event.
4. Decide whether "mute the beep" should even be pursued as a separate
   feature from "turn off AEB/FCW" if no distinct volume control is ever
   found — it may simply not exist as a separate lever.

## Decisions needed later

- Does Drive Assist mirror the OEM's own confirmation dialog text/flow
  before writing `557858874`, consistent with how `557884437` (LDW) is
  already noted as "requires modal confirmation on disable"?
- ~~Does Drive Assist fight the OEM's power-on re-arm...~~ Decided — see
  "Implementation direction" below: yes, by reusing the drive/regen mode
  enforcement pattern, not a one-shot boot restore.
- ~~Should this feature require Park?~~ Decided, 2026-09-12: **no** — the
  owner wants AEB toggleable and applied in any condition, driving or
  parked, same as AVAS. Overrides this project's usual "don't change
  safety-relevant state while moving" convention (used for OTA installs
  and update prompts) as a deliberate, explicit exception for this one
  control. Implemented: both the Drive Assist toggle's own Park check and
  `modehelper`'s `enforceAeb()` (renamed from `enforceAebParked()`) no
  longer gate on parked state. The confirmation dialog before turning AEB
  off stays either way — that's a separate concern from the Park gate.

## Implementation direction

**Depends on `plan/MODEHELPER-WATCHDOG-ROADMAP.md`.** That doc found the
likely reason `ModeHelperService`'s existing drive/regen enforcement can
silently stop working (no restart after the head unit's suspend/resume,
unlike `drivemem`'s services). Extending the poll loop to also enforce
AEB without first fixing that gap means AEB enforcement can silently die
right along with drive/regen enforcement — build the watchdog first or
alongside this.

**Decided strategy: don't build a bespoke boot-restore receiver — extend
the existing drive/regen mode enforcement in `ModeHelperService` to also
cover AEB, the same way `AVAS-MUTE-ROADMAP.md` extends it for AVAS.**

Today `ModeHelperService` already:
- receives the owner's preferred drive/regen mode from Drive Assist via
  the `SET_MODE` broadcast and stores it in `SharedPreferences("modehelper")`;
- polls every 4 seconds (`pollLoop()`); whenever the car is parked, calls
  `enforceModeParked()`, which reads the live drive/regen mode and, if it
  has drifted from the saved preference, corrects it with one write
  (`CarMode.readDrive()`/`writeDrive()`, same for regen).

This is a better fit for AEB's power-on re-arm than a one-shot
`BOOT_COMPLETED` restore (the reference app's approach via
`KEY_AEB_RESTORE_ON_BOOT`): the poll loop already runs continuously and
will correct the state within one 4-second tick regardless of *why* it
drifted — a fresh boot, the OEM UI re-enabling it, anything — with no
separate boot-path code to keep in sync.

Concretely: extend `SET_MODE` (or add a sibling broadcast) to also carry
the owner's preferred AEB state, store it alongside `drive`/`regen` in the
same `SharedPreferences`, and add an `enforceAebParked()`-style check next
to `enforceModeParked()` that reads `557858874` via `CarMode.readBoolProp`
and corrects it via `CarMode.writeBoolProp` (the helper already added for
`AebWriteTestReceiver`) when it doesn't match. Gate it the same way the
existing checks are already gated: only while parked.

Still open:
- Whether Drive Assist shows its own confirmation dialog before sending
  the preference (mirroring the OEM's warning text), since the OEM's own
  dialog is bypassed entirely by this path.
- Whether the throwaway `AebWriteTestReceiver` gets retired once the real
  enforcement path exists, or stays as a manual diagnostic (same spirit as
  `ProbeReceiver`'s read-only ids) — leaning toward keeping it, it's cheap
  and useful for re-verifying the write path after any car software
  update.
