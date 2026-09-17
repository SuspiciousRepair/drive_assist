# Dashboard validation — 2026-09-17

Test device: Android Automotive API 33 ARM64 emulator, 1920 × 1080, density
160, foreground user 10. No production vehicle was connected.

## Verified

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
- All 551 translatable default string keys have Thai counterparts, with no
  format-placeholder mismatches.
- All 199 unit tests pass, including four Energy display-data cases covering
  missing/invalid values, zero battery, measured charging power, and unplugging
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

## Limits of the checks

The emulator's vehicle stubs return values such as 0% battery, 0 km range and
−40°C outside temperature. These screenshots establish layout, not sensor or
hardware accuracy. Unknown OBD2 temperature and trip energy display as —.
Battery/range follow the existing 15-second telemetry cadence; charging/plug
events and OBD2 events can refresh the panel sooner. Colour selection is local
and applies immediately.

`tools/verify.sh` completed under the repository's existing non-blocking
analysis configuration. Lint still reports 316 ExtraTranslation issues and
five pre-existing StringFormatInvalid errors in other locales; these counts
match the baseline. The submission run reports 509 lint warnings and 2,743
Checkstyle findings. SpotBugs is incomplete
under this JDK because `java.rmi.Remote` is missing (exit 3 is ignored by the
project). These are not a claim of clean static analysis.

Submission screenshots show the current controls and profile editor in both languages:

- English: [Home dashboard](screenshots/home-english-vehicle-dashboard.png),
  [vehicle profile editor](screenshots/vehicle-profile-editor-english.png).
- Thai: [Home dashboard](screenshots/home-thai-vehicle-dashboard.png),
  [vehicle profile editor](screenshots/vehicle-profile-editor-thai.png).

Additional local screenshots are available in `drivemem/build/ui-preview/`. Asset provenance and
font/redistribution considerations are recorded in [UI-REFERENCES.md](UI-REFERENCES.md).
