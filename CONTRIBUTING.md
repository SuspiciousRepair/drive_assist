# Contributing to Drive Assist

Thank you for your interest in contributing to **Drive Assist** (`com.geely.drivemem`), the open-source automotive software suite for the Geely EX2 / Geometry E (IHU629G Head Unit, Android 9 / API 28).

Because Drive Assist interfaces directly with physical vehicle hardware, vehicle safety, stability, and code quality are paramount. Please review these guidelines before submitting code.

---

## 1. Branching Strategy

We follow a structured Git branching model:

* **`dev` (Default & Integration Branch)**:
  - All feature branches, bug fixes, refactoring, and documentation improvements must branch off and target `dev`.
  - Feature branches should be named descriptively: `feat/<feature-name>`, `fix/<issue-name>`, or `docs/<topic>`.
* **`master` (Protected Release Branch)**:
  - Reserved exclusively for validated, stable releases.
  - Merges into `master` are strictly gated on end-to-end vehicle hardware validation and release readiness audits.
  - **Do not open pull requests directly against `master`**.

---

## 2. Commit Message Standards

We strictly follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

```text
<type>(<scope>): <short summary in imperative mood>

[optional body explaining context and rationale]

[optional footer(s)]
```

### Allowed Types
* `feat`: A new user-facing feature or vehicle capability.
* `fix`: A bug fix or crash remediation.
* `docs`: Documentation updates, guides, or screenshot assets.
* `refactor`: Code change that neither fixes a bug nor adds a feature.
* `test`: Adding or improving unit tests and test suites.
* `chore`: Build system changes, dependency tuning, or hygiene updates.
* `perf`: Performance optimizations (e.g. reducing CPU wakeups or memory allocations).

### Common Scopes
* `car`: `CarActor`, `CarAccess`, `CarDataHub`, and VHAL binder interactions.
* `hvac`: Climate state machine, `EffortTable`, and `ComfortRuler`.
* `telemetry`: MQTT publication, Home Assistant auto-discovery, and ABRP uploader.
* `modehelper`: Privileged system companion (`android.uid.system`).
* `installer`: Standalone self-uninstalling installer package.
* `ui`: `ComfortActivity`, `DailyStatsView`, `TelemetryActivity`, and overlays.
* `deps`: Gradle plugins, libraries, and build scripts.

---

## 3. Local Validation & Quality Pipeline

Before opening a Pull Request, all contributors must execute the complete verification pipeline locally:

### 3.1 Unit Tests & JaCoCo Coverage
```bash
./gradlew test jacocoTestReport
```
* **Requirement**: All 131 unit tests across 15 suites must pass (0 failures, 0 errors).
* Generates HTML report at `drivemem/build/reports/jacoco/test/html/index.html`.
* Coverage baseline must not regress.

### 3.2 Release APK Assembly
```bash
./gradlew :drivemem:assembleRelease
```
* **Requirement**: The release APK (`drivemem/build/outputs/apk/release/drive_assist.apk`) must build cleanly without test code or instrumentation artifacts.

### 3.3 Static Code Analysis (Lint, Checkstyle, SpotBugs)
```bash
./gradlew lint checkstyle spotbugs
```
* **Android Lint**: Zero fatal errors. Ensure all `StringFormatMatches` and `StringFormatInvalid` checks pass across all localization files (`res/values-*/strings.xml`).
* **Checkstyle**: Conforms to ruleset defined in `config/checkstyle/checkstyle.xml`.
* **SpotBugs**: Conforms to ruleset and exclusion filter in `config/spotbugs/exclude.xml`.

---

## 4. Critical Vehicle Safety & Architectural Invariants

Modifications interacting with vehicle systems must strictly respect the following core invariants:

### 4.1 The No-Reboot Rule
* **Never invoke `reboot` or `adb reboot` on the head unit**.
* Power-cycling the digital signal processor (DSP) causes a loud, jarring acoustic pop through the vehicle cabin speakers that can startle the driver and damage audio equipment.
* To test boot-triggered workflows, simulate the broadcast safely:
  ```bash
  adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -n com.geely.drivemem/.BootReceiver
  ```
* For daemon restarts, restart the specific process or service (`kill -9 <PID>`).

### 4.2 Park-Only Gating (`CarState.isParked()`)
* Any disruptive UI operation—including OTA update confirmation dialogs, changelog viewers, and manual drive mode calibration—**must be gated strictly on Park (`CarState.isParked()`)**.
* If the vehicle shifts out of Park (`gear != 4`) while a dialog is visible, the dialog must **automatically dismiss immediately** (`ComfortActivity.java:1217`).
* OTA downloads and installations must be aborted or deferred if the vehicle is in motion.

### 4.3 Respect Read-Only VHAL Bounds
* Geely adaptation properties (`VehicleModules.getAdaptValue`) and VHAL property IDs must be treated as read-only unless an explicit, reverse-engineered setter is verified.
* Never execute random property sweeps or write unverified values to the CAN/VHAL bus on live hardware.

### 4.4 UID Privilege Separation (System UID vs. App UID)
* **Never combine `drivemem` and `modehelper` into a single APK**.
* Android 9 prohibits processes running with `android.uid.system` (UID 1000) from instantiating Chromium `WebView` instances (`"WebView cannot be used by the system user"`).
* The main application (`com.geely.drivemem`) must remain a standard user application UID to render Home Assistant web dashboards safely.
* Privileged operations (installing packages, modifying system secure settings) must remain isolated in `com.geely.modehelper` (`android.uid.system`), communicated via strict package-targeted broadcasts (`setPackage("com.geely.modehelper")`).

### 4.5 Automotive Suspend/Resume Resiliency
* The IHU629G suspends to RAM during vehicle stops rather than performing clean OS shutdowns.
* `BOOT_COMPLETED` does **not** fire upon waking from suspend, and `SCREEN_ON` is not broadcast to standard manifest receivers.
* All background recovery logic must rely on the 10-minute `AlarmManager.ELAPSED_REALTIME_WAKEUP` watchdog in `BootReceiver` and heartbeat tracking in `Beat.java`.

### 4.6 SQLite 3.22 Compatibility
* The head unit runs Android 9 with SQLite 3.22.0.
* **Prohibited**: Window functions (`LAG()`, `LEAD()`, `ROW_NUMBER()`) introduced in SQLite 3.25.
* **Prohibited**: Native UPSERT syntax (`ON CONFLICT DO UPDATE`) introduced in SQLite 3.24.
* Use Java-side sequential date-range traversal for rollups and `insertWithOnConflict(..., CONFLICT_IGNORE)` with dedicated write threads (`CarDb.java`).

### 4.7 Zero Committed Credentials & Identifier Sanitization
* Never commit vehicle identification numbers (VIN), private MQTT broker IPs, authentication tokens, ABRP keys, or TLS certificates to the repository.
* Sample configurations and documentation must use generic placeholders (`123456`, `tcp://homeassistant.local:1883`, `<token>`).

---

## 5. UI Ergonomics & Display Specifications

* **Native Resolution**: `1920x1080` at `160 dpi` (`density = 1.00`).
* In this environment, `1 dp = 1 px = 1 sp`. Touch targets and text sizes must account for the 75 cm viewing distance:
  - Outside temperature readout: `112sp`
  - Category headers: `26sp`
  - Standard card padding: `26dp`
* Avoid hardcoded pixel offsets; use fractional layout weights and responsive repacking (`repackColumns()`).

---

## 6. Pull Request Submission Checklist

When opening a Pull Request, confirm that:
- [ ] Target branch is `dev`.
- [ ] Commit messages follow Conventional Commits format.
- [ ] `./gradlew test jacocoTestReport` passes (131 tests, 0 failures).
- [ ] `./gradlew :drivemem:assembleRelease` builds successfully.
- [ ] `./gradlew lint checkstyle spotbugs` passes cleanly.
- [ ] No hardcoded secrets, private IPs, or personal VINs are introduced.
- [ ] Vehicle safety invariants (No-Reboot, Park-only gating, VHAL bounds) are fully respected.
- [ ] Documentation is updated in `docs/` where applicable.
