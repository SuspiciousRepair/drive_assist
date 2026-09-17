# Dashboard and dashcam validation — 2026-09-17

Test device: Android Automotive API 33 ARM64 emulator, 1920 × 1080, density
160, foreground user 10. No production vehicle was connected.

## Dashboard checks

The dashboard observations below were recorded before the dashcam adaptation.

- Home opens with a larger driving card on the left (68%) and Energy on the
  right (32%). At 1920 × 1080 both cards, the Restore defaults button, all four
  Energy metrics and the fixed horizontal climate strip fit without scrolling.
  The vehicle preview is 248 dp tall, with space for a model heading and nickname
  beneath it; mode buttons are 88 dp tall.
- Tapping Sport and High regeneration saved each preference independently;
  both survived force-stop/relaunch. Restore defaults selected and saved
  Comfort + Medium immediately. The Apply and Save-default buttons are absent.
  Editing Turbo duration to 45 saved without a Save button and persisted when
  leaving the Driving section; the previous duration was restored after testing.
- English and Thai Home layouts render in the bundled fonts; light and dark
  appearances retain readable labels and the intended heading hierarchy.
- Dark uses matte charcoal surfaces and muted mode/chart colours. Light/Dark
  switching works in Appearance and the retired Neon option is absent.
  Measured primary/secondary text contrast on the dark card surfaces is at
  least 4.5:1; selected Eco/Comfort/Sport text is at least 6.3:1.
- Restore defaults shows an automatically dismissing popup in English and
  Thai, naming Comfort and Medium regeneration. Its 22 sp text, green check,
  green heading and accent strip follow the app font and light/dark palette.
  The popup is shared by Home and Settings and confirms saved choices;
  reported vehicle readings remain separate.
- Language changes immediately through the dropdown. Its selected check is
  green and the navigation label changes between Language and ภาษา.
- Car taps cycle through white, silver, grey, beige, green and back to white.
  The caption changes on the same screen. Silver persisted across force-stop
  and relaunch, and appeared in the Settings preview as well.
- Vehicle naming: tapping the heading/pencil opens the editor. Saving
  `Geely EX2 Max` with nickname `Nova` updated Home immediately, with the model
  on the first line and the nickname below it, and survived a cold start.
  Cancel preserved both values after editing the model and nickname; Settings
  showed the same saved profile as Home.
  The editor offers equal-width Geely EX2 / Geely EX2 Max buttons side by side.
  Clearing the nickname kept the Max model; selecting EX2 and saving updated
  Home immediately. The emulator was restored to EX2 with no nickname.
  The Save button uses green (`#009B46`) with white, bold 24 sp text.
  Tapping the nickname field opened the system keyboard (`mInputShown=true`);
  the model buttons, nickname field and Save action remained visible above it.
- At that stage, all 552 translatable default string keys had Thai counterparts,
  with no format-placeholder mismatches. Current totals are recorded below.
- The dashboard verification passed all 199 unit tests, including four Energy
  display-data cases covering missing/invalid values, zero battery, measured
  charging power, and unplugging
  while the current sensor remains latched.
- A separate temporary harness compiled the actual TurboMode source against
  deterministic Android/actor stubs: all 10 checks passed, covering pending-read
  cancellation, queued and concurrent expiry, normal expiry, refresh, new-boost
  isolation and Comfort fallback. These checks establish command ordering;
  they do not exercise Android scheduling or a real VHAL.
- Debug and release APKs build. Both fonts and all five car images are present;
  release resource paths are shortened by Android resource optimization.
  All 27 ModeHelper source files compile against the available Android 34 SDK;
  its fallback driving preference is now Comfort, matching the app.
- No application crash was recorded during the UI checks.

## Dashcam adaptation and current verification

- The Dashcam screen groups recording controls, clip duration, storage choice
  and usage with an asynchronous clip gallery. The loop-recording explanation
  states that the oldest unprotected clips are deleted when the limit is
  reached. Protected clips and recoverable unfinished recordings are retained.
  Storage and duration changes apply at the next clip boundary.
- The player offers saved field-of-view and pitch adjustments independently for
  Front, Rear, Left and Right. Rendering updates immediately; restoring defaults
  uses the original camera calibration without changing its static arrays.
  All and Orbit views retain their existing behavior.
- Live preview uses the recorder's single EVS input with separate encoder and
  preview GL outputs. Opening or leaving preview does not start or stop the
  recorder. The screen provides explicit Start/Stop controls and distinguishes
  waiting for frames from receiving live frames. Preview sessions have an
  authenticated owner, heartbeat lease and acknowledged surface cleanup.
  These describe the implementation; real vehicle validation remains pending.
- Readable mounted USB storage, including a volume mounted read-only, is
  included in the clip library. Only writable mounted storage is offered as a
  recording destination. A read-only scan leaves pending hold markers intact.
  FAT32/exFAT access depends on the head unit mounting the filesystem; the app
  does not install an exFAT driver.
- All **597 translatable default string keys** have Thai counterparts with
  matching format placeholders. The latest full verification passed **221 main
  unit tests**, with no failures or errors, and assembled the release APK.
  A targeted run of the settings and clip-storage tests also passed all 32
  checks; these are a subset of the main tests, not additional cases.
- Main regression coverage includes player calibration/range handling, writable
  versus read-only mount policy, read-only library scans without mutation, and
  exact `long` byte accounting/listing for a sparse file larger than 5 GiB.
  The sparse-file check establishes accounting behavior, not exFAT compatibility
  on physical media.
- All **11 ModeHelper test suites** pass. They cover recorder start/stop/restart
  ordering, storage disappearance, protected/recovery-file retention, 100 loop
  rotations, 10,000 bounded preview frame handoffs, session replacement, stale
  detach and heartbeat expiry, alongside the existing parked-monitor policies.
- All **35 ModeHelper Java sources** compile with Java 8 compatibility against
  Android API 34. A helper APK was packaged using the installed build-tools 36
  D8 as a local toolchain workaround. This is compilation/packaging evidence,
  not validation of a production helper release or vehicle installation.
- An emulator EGL smoke harness encoded 323 synthetic frames. Encoding continued
  for 45 frames while the preview consumer was left unconsumed for two seconds.
  Encoder and preview orientation checks and GL cleanup passed. Synthetic input
  exercises the rendering path but does not exercise Geely EVS.
- The live-preview screen was inspected in English and Thai with ModeHelper absent:
  it showed the unavailable-camera explanation and a disabled green Start
  button, without claiming live video or recording.
- A separate normal-UID synthetic helper exercised the actual PreviewActivity
  across APK boundaries: missing helper → idle → Start → visible Live frames →
  Back/detach → reopen Live → Stop. The temporary helper wrote no dashcam files,
  so the recording status correctly remained separate from preview liveness.
  This exposed and fixed anonymous ResultReceiver parceling across APKs. The
  helper was removed and the unavailable state verified again. This tests IPC
  and UI lifecycle, not real camera capture or the production helper's identity
  authorization. The synthetic image is explicitly labelled as QA footage.

- The final dashcam settings layout was inspected in English/light and Thai/dark.
  Clip durations, the storage dropdown and its FAT32/exFAT explanation, and the
  loop-retention hint fit on screen. Duration autosave, clip hold/release, player
  adjustment/reset feedback and preview navigation were checked in the emulator.
  The original language, theme, vehicle profile and recording preferences were
  restored after QA.

## Limits of the checks

The emulator's vehicle stubs return values such as 0% battery, 0 km range and
−40°C outside temperature. These screenshots establish layout, not sensor or
hardware accuracy. Unknown OBD2 temperature and trip energy display as —.
Battery/range follow the existing 15-second telemetry cadence; charging/plug
events and OBD2 events can refresh the panel sooner. Colour selection is local
and applies immediately.

No physical Geely head unit, EVS camera stream, reverse-camera coexistence or
USB/exFAT device was tested. Retention across 100 simulated segment completions and continued encoding
during preview stalls have automated/synthetic coverage. Actual recorder segment
rotation still requires integration/hardware verification, as do vehicle camera
availability, storage permissions and head-unit sleep behavior.
Background recording is intended while the head unit remains awake. Gallery
recording indicators use recent file activity and can lag by the polling and
freshness intervals; a waiting preview is not evidence of recorded frames.

`tools/verify.sh` completed under the repository's existing non-blocking
analysis configuration. Lint still reports 316 ExtraTranslation issues and
five pre-existing StringFormatInvalid errors in other locales; these counts
match the baseline. The latest full verification reports **513 lint warnings**
and **2,734 Checkstyle findings**. SpotBugs is incomplete
under this JDK because `java.rmi.Remote` is missing (exit 3 is ignored by the
project). These are not a claim of clean static analysis.

The existing dashboard submission screenshots show the controls and profile
editor in both languages; they predate the dashcam adaptation:

- English: [Home dashboard](screenshots/home-english-vehicle-dashboard.png),
  [vehicle profile editor](screenshots/vehicle-profile-editor-english.png).
- Thai: [Home dashboard](screenshots/home-thai-vehicle-dashboard.png),
  [vehicle profile editor](screenshots/vehicle-profile-editor-thai.png).

Dashcam submission screenshots:

- [English settings](screenshots/dashcam-english-light.png).
- [Thai settings](screenshots/dashcam-thai-dark.png).
- [Live-preview IPC test](screenshots/dashcam-preview-synthetic-qa.png): synthetic
  frames from a temporary helper, not a vehicle camera or proof of file recording.

Additional local screenshots are available in `drivemem/build/ui-preview/`. Asset provenance and
font/redistribution considerations are recorded in [UI-REFERENCES.md](UI-REFERENCES.md).
