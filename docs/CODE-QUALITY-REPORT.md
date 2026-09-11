# Code Quality & Test Coverage Audit Report

**Project**: Drive Assist (`com.geely.drivemem`)  
**Target Platform**: Geely EX2 / Geometry E (IHU629G Head Unit, Android 9 / API 28)  
**Date**: 2026-09-10  
**Status**: Integrated & Verified Baseline  

---

## 1. Executive Summary

This report documents the implementation and results of the automated code quality and test coverage pipeline established for the Drive Assist automotive application. 

Key milestones achieved:
- **JaCoCo Unit Test Coverage**: Fully integrated into the Gradle build system (`jacocoTestReport`), producing unified XML and HTML reports for the debug variant while ensuring the production release build (`:drivemem:assembleRelease`) remains completely unmodified.
- **Android Lint Tuning**: Custom-tuned for Android 9 / API 28 automotive environment, suppressing irrelevant Play Store policy errors (`ExpiredTargetSdkVersion`, `MissingTranslation`, `MissingPermission`, `ObsoleteSdkInt`) while elevating critical string format errors (`StringFormatMatches`, `StringFormatInvalid`).
- **Basque Localization Fix**: Resolved a critical runtime format crash in Basque translation (`values-eu/strings.xml:222`) where unescaped `%100` resulted in argument count mismatch.
- **Checkstyle Integration**: Configured with a dedicated rule set (`config/checkstyle/checkstyle.xml`) tuned for Java 8/11 Android development, establishing an automated style baseline.
- **SpotBugs Integration**: Configured with custom exclude filters (`config/spotbugs/exclude.xml`) ignoring generated Android artifacts (`R.class`, `BuildConfig.class`) and intentional singletons while surfacing real multithreading and I/O hazards.
- **Code Quality Catalog**: Detailed analysis of 12 deprecated APIs, 10 cyclomatic complexity hotspots, and critical lifecycle management gaps across activities and background services.

---

## 2. Test Execution & Coverage Metrics

### 2.1 Test Execution Summary

The unit test suite consists of pure JUnit 4 tests executed against mock environments and data models:

| Metric | Value |
|---|---|
| **Total Test Suites** | 15 suites |
| **Total Tests Executed** | 131 tests |
| **Failures** | 0 |
| **Errors** | 0 |
| **Skipped** | 0 |
| **Pass Rate** | 100.0% |
| **Execution Duration** | ~0.57s (total test task ~1.69s) |

#### Test Suite Breakdown
| Test Suite | Tests | Failures | Time | Primary Focus |
|---|---|---|---|---|
| `com.geely.drivemem.EffortTableTest` | 35 | 0 | 0.017s | HVAC comfort ruler interpolation and effort lookup tables |
| `com.geely.drivemem.ClipsTest` | 17 | 0 | 0.029s | Media clip index parsing and timestamp math |
| `com.geely.drivemem.CarDataHubApplyTest` | 11 | 0 | 0.019s | Car property updates and VHAL property mapping |
| `com.geely.drivemem.GateStateTest` | 8 | 0 | 0.007s | Tailgate and door open/closed state transitions |
| `com.geely.drivemem.ModesTest` | 8 | 0 | 0.006s | Vehicle drive mode (Eco/Sport) and regen state machines |
| `com.geely.drivemem.CarDataHubTest` | 7 | 0 | 0.004s | Signal bus dispatch and listener registration |
| `com.geely.drivemem.CertImporterTest` | 7 | 0 | 0.424s | TLS PKCS12 / PEM certificate import and validation |
| `com.geely.drivemem.ChargeSessionLogTest` | 6 | 0 | 0.013s | Charging session CSV export and log rotation |
| `com.geely.drivemem.OdoStatsTest` | 6 | 0 | 0.007s | Odometer math, delta calculations, and daily counters |
| `com.geely.drivemem.MusicStateTest` | 5 | 0 | 0.004s | Media session metadata and playback state |
| `com.geely.drivemem.TelemetryTest` | 5 | 0 | 0.002s | Telemetry number formatting and rounding rules |
| `com.geely.drivemem.AbrpUploaderTest` | 4 | 0 | 0.029s | ABRP telemetry JSON payload serialization |
| `com.geely.drivemem.ChargeSessionTest` | 4 | 0 | 0.002s | Battery charging session start/stop and energy tracking |
| `com.geely.drivemem.MqttDiscoveryTest` | 4 | 0 | 0.006s | Home Assistant MQTT discovery JSON payloads |
| `com.geely.drivemem.PanelStateTest` | 4 | 0 | 0.002s | Comfort panel UI display state |

---

### 2.2 JaCoCo Code Coverage Metrics

JaCoCo code coverage was captured during `testDebugUnitTest` across all 68 Java source files (135 compiled class files):

| Counter Type | Total | Covered | Missed | Coverage % |
|---|---|---|---|---|
| **Instructions** | 60,750 | 3,374 | 57,376 | **5.55%** |
| **Branches** | 4,823 | 348 | 4,475 | **7.22%** |
| **Lines** | 9,998 | 553 | 9,445 | **5.53%** |
| **Cyclomatic Complexity** | 3,833 | 250 | 3,583 | **6.52%** |
| **Methods** | 1,391 | 115 | 1,276 | **8.27%** |
| **Classes** | 135 | 29 | 106 | **21.48%** |

#### Package Coverage Breakdown
| Package | Instructions Covered | Instruction % | Lines Covered | Line % | Branches Covered | Branch % |
|---|---|---|---|---|---|---|
| `com.geely.drivemem.hvac` | 718 / 2,573 | **27.9%** | 101 / 417 | **24.2%** | 134 / 393 | **34.1%** |
| `com.geely.drivemem.state` | 288 / 1,549 | **18.6%** | 68 / 336 | **20.2%** | 20 / 166 | **12.0%** |
| `com.geely.drivemem.car` | 845 / 4,716 | **17.9%** | 90 / 654 | **13.8%** | 41 / 333 | **12.3%** |
| `com.geely.drivemem.net` | 938 / 9,247 | **10.1%** | 203 / 1,538 | **13.2%** | 108 / 1,002 | **10.8%** |
| `com.geely.drivemem.util` | 407 / 7,857 | **5.2%** | 61 / 1,212 | **5.0%** | 37 / 639 | **5.8%** |
| `com.geely.drivemem.sensors` | 178 / 5,052 | **3.5%** | 30 / 840 | **3.6%** | 8 / 494 | **1.6%** |
| `com.geely.drivemem.controls` | 0 / 1,709 | **0.0%** | 0 / 273 | **0.0%** | 0 / 148 | **0.0%** |
| `com.geely.drivemem.services` | 0 / 1,952 | **0.0%** | 0 / 410 | **0.0%** | 0 / 118 | **0.0%** |
| `com.geely.drivemem.ui` | 0 / 20,755 | **0.0%** | 0 / 3,664 | **0.0%** | 0 / 1,216 | **0.0%** |
| `com.geely.drivemem.art` | 0 / 5,340 | **0.0%** | 0 / 654 | **0.0%** | 0 / 314 | **0.0%** |

#### Key Classes Coverage Analysis
- **High Coverage Models & Logic**:
  - `com.geely.drivemem.hvac.EffortTable`: **97.4%** instruction coverage (98.9% lines)
  - `com.geely.drivemem.util.Modes`: **96.9%** instruction coverage (91.7% lines)
  - `com.geely.drivemem.car.CarDataHub`: **91.1%** instruction coverage (84.5% lines)
  - `com.geely.drivemem.state.GateState`: **100.0%** instruction coverage (100.0% lines)
  - `com.geely.drivemem.sensors.OdoStats`: **65.8%** instruction coverage (67.6% lines)
  - `com.geely.drivemem.net.CertImporter`: **28.2%** instruction coverage (31.8% lines)
- **Coverage Gap Root Cause**:
  - The UI package (`com.geely.drivemem.ui`, 20,755 instructions) and Custom Canvas Art (`com.geely.drivemem.art`, 5,340 instructions) comprise 43% of the total application codebase. Because these classes directly inherit from Android `Activity` or `View` and interact with the Android window manager without Robolectric or an instrumentation runner, they are unexercised by JVM unit tests.
  - Background services (`OutTempService`, `SocIconService`, `WifiIconService`) directly manipulate `NotificationManager` and system status bar overlays, which require Android framework stubs.

---

## 3. Static Code Analysis: Android Lint

### 3.1 Configuration Rationale (Android 9 / API 28 Automotive Head Unit)

The application is deployed directly to the vehicle head unit (Geely IHU629G / ECARX 250060) as a sideloaded system utility. It is not distributed via Google Play. Standard Android Lint settings produce extensive false positives that obstruct build pipelines:

1. `ExpiredTargetSdkVersion`: Google Play requires API 33+, whereas the vehicle OS is Android 9 (API 28). Disabled to permit targeting API 28.
2. `MissingTranslation`: Non-blocking partial localizations (e.g. Catalan, Basque, Hungarian). Suppressed to prevent translation gaps from breaking compilation.
3. `MissingPermission`: Flagged `BluetoothAdapter.enable()` for missing `android.permission.BLUETOOTH_CONNECT`. `BLUETOOTH_CONNECT` is an API 31+ runtime permission that does not exist in API 28. Suppressed as a false positive.
4. `ObsoleteSdkInt`: Redundant backward compatibility guards (`Build.VERSION.SDK_INT >= 26`) retained intentionally for potential deployment across legacy ECARX variants. Suppressed.
5. `StringFormatMatches` & `StringFormatInvalid`: Elevated to fatal errors to catch format string mismatch crashes at compile time.

### 3.2 String Formatting Defect Remediated

- **Defect**: In `ComfortActivity.java:1259`:
  ```java
  chargeRemainingView.setText(getString(R.string.charge_card_remaining, remainingMs / 60000));
  ```
  The Basque translation in `drivemem/src/main/res/values-eu/strings.xml:222` contained:
  ```xml
  <!-- BEFORE: Format crash -->
  <string name="charge_card_remaining">~%1$d min %100era arte</string>
  ```
  Because `%100` lacks escaping (`%%`), `String.format()` treats `%10` as a reference to a 10th argument or malformed conversion. At runtime, when the vehicle language was set to Basque during charging, `getString()` threw `java.util.UnknownFormatConversionException` / `MissingFormatArgumentException`, crashing the launcher activity.
- **Fix Applied**:
  ```xml
  <!-- AFTER: Escaped percentage -->
  <string name="charge_card_remaining">~%1$d min %%100era arte</string>
  ```
  Verification confirmed `StringFormatMatches` is now **0** violations across all resource bundles.

### 3.3 Lint Summary Findings
- **Total Warnings**: 381
- **Total Errors**: 7 (Unescaped `%` character in `cfg_bar_soc` across 7 language files: `values-ca`, `values-hu`, `values-eu`, `values-b+es+419`, `values-es-rUS`, `values-es`, `values-tl`)
- **Key Warning Categories**:
  - `TypographyEllipsis` (282 occurrences): Plain three-dot ellipsis `...` instead of Unicode `…`.
  - `UnusedResources` (39 occurrences): Unreferenced drawables and layout identifiers.
  - `SetTextI18n` (13 occurrences): Hardcoded strings concatenated into TextViews.
  - `DefaultLocale` (8 occurrences): `String.format()` or `toLowerCase()` called without explicit `Locale.ROOT` or `Locale.US`.
  - `StaticFieldLeak` (6 occurrences): Static `Context` fields in `AbrpUploader`, `CarActor`, `ComfortHub`, `Obd2Reader`, `SpotifyClient`, and `TurboMode`.

---

## 4. Static Code Analysis: Checkstyle

### 4.1 Configuration
Checkstyle 10.12.7 was configured via `config/checkstyle/checkstyle.xml` with rules tailored for Android Java 8/11 development:
- Enforces curly braces on all control structures (`NeedBraces`).
- One statement per line (`OneStatementPerLine`) and one variable declaration per line (`MultipleVariableDeclarations`).
- Prohibits unused (`UnusedImports`), redundant (`RedundantImport`), and wildcard imports (`AvoidStarImport`).
- Requires whitespace after commas/typecasts and checks naming conventions.

### 4.2 Findings Summary
A total of **2,338 warnings across 68 files** were recorded:

| Checkstyle Rule | Violations | Description |
|---|---|---|
| `NeedBracesCheck` | 788 | Single-line `if`, `for`, `while` statements without braces |
| `LeftCurlyCheck` | 624 | Inconsistent brace placement on method/class headers |
| `OneStatementPerLineCheck` | 551 | Compact ternary or assignment chains on a single line |
| `MultipleVariableDeclarationsCheck` | 145 | Multiple fields declared on a single line (e.g., `int a = 1, b = 2;`) |
| `UnusedImportsCheck` | 118 | Leftover unused `import` statements across activities |
| `RightCurlyCheck` | 72 | Closing brace formatting on `else` / `catch` blocks |
| `WhitespaceAfterCheck` | 36 | Missing space after commas or cast operators |
| `MissingSwitchDefaultCheck` | 2 | Switch statements lacking a `default` case branch |
| `ModifierOrderCheck` | 1 | Non-standard modifier order (e.g. `final static` instead of `static final`) |
| `RedundantModifierCheck` | 1 | Redundant `public` on interface method |

---

## 5. Static Code Analysis: SpotBugs

### 5.1 Configuration
SpotBugs 6.0.26 was integrated with `Effort.MAX` and `Confidence.MEDIUM`. Filter `config/spotbugs/exclude.xml` eliminates generated classes (`R$*`, `BuildConfig`) and intentional singleton context caches.

### 5.2 Findings Summary
A total of **117 SpotBugs issues** were identified across 6 categories:

| Category | Count | Primary Implications |
|---|---|---|
| **BAD_PRACTICE** | 39 | Ignored return values, non-standard comparator contracts |
| **MALICIOUS_CODE** | 29 | Public static mutable fields and arrays |
| **MT_CORRECTNESS** | 20 | Static `DateFormat` instances invoked across threads |
| **I18N** | 18 | `String.getBytes()` or `FileReader` using default platform encoding |
| **STYLE** | 8 | Unread fields, non-short-circuit boolean evaluation |
| **PERFORMANCE** | 3 | Boxing in loops, suboptimal random usage |

#### Critical SpotBugs Patterns
1. `STCAL_INVOKE_ON_STATIC_DATE_FORMAT_INSTANCE` (18 occurrences):
   - `java.text.SimpleDateFormat` is **not thread-safe**. Static formatters shared across threads in `TelemetryActivity`, `MqttReporter`, and `ChargeSessionLog` can corrupt date strings or throw exceptions during concurrent vehicle state updates.
   - *Remediation*: Replace with `java.time.format.DateTimeFormatter` (thread-safe) or wrap in `ThreadLocal<SimpleDateFormat>`.
2. `DM_DEFAULT_ENCODING` (18 occurrences):
   - Relying on the default platform charset when writing CSV logs or reading config files creates silent data corruption when moving between head unit firmware builds.
   - *Remediation*: Explicitly pass `StandardCharsets.UTF_8`.
3. `RV_RETURN_VALUE_IGNORED_BAD_PRACTICE` (12 occurrences):
   - Return values of `File.mkdirs()` and `File.delete()` are ignored in `CertImporter`, `ChargeSessionLog`, and `AdbGate`. If storage is full or read-only, failures occur silently.
   - *Remediation*: Check return boolean and log error conditions.

---

## 6. Catalog of Deprecated APIs

Direct compilation inspection with `javac -Xlint:deprecation` identified **12 distinct deprecated API patterns**:

| # | Deprecated API | Deprecated In | Recommended Replacement | Project Locations |
|---|---|---|---|---|
| 1 | `BluetoothAdapter.getDefaultAdapter()` | API 31 | `BluetoothManager.getAdapter()` | `Obd2Reader.java:291, 321, 439`<br>`CarplayState.java:64` |
| 2 | `BluetoothAdapter.enable()` | API 33 | User prompt via `ACTION_REQUEST_ENABLE` | `CarplayState.java:67` |
| 3 | `BluetoothGattDescriptor.setValue(byte[])`<br>`BluetoothGatt.writeDescriptor(desc)` | API 33 | `BluetoothGatt.writeDescriptor(desc, value)` | `Obd2Reader.java:580, 581` |
| 4 | `BluetoothGattCallback.onCharacteristicChanged(gatt, char)`<br>`BluetoothGattCharacteristic.getValue()` | API 33 | `onCharacteristicChanged(gatt, char, byte[])` | `Obd2Reader.java:590, 591` |
| 5 | `BluetoothGattCharacteristic.setValue(byte[])`<br>`BluetoothGatt.writeCharacteristic(char)` | API 33 | `BluetoothGatt.writeCharacteristic(char, byte[], writeType)` | `Obd2Reader.java:676, 677` |
| 6 | `WifiManager.setWifiEnabled(boolean)` | API 29 | `Settings.Panel.ACTION_WIFI` / Privileged API | `BootReceiver.java:123, 211`<br>`OutTempService.java:55` |
| 7 | `LayoutParams.FLAG_TRANSLUCENT_STATUS`<br>`View.SYSTEM_UI_FLAG_*`<br>`View.setSystemUiVisibility(int)` | API 30 | `WindowInsetsController` | `Style.java:242, 245, 249, 250` |
| 8 | `WifiManager.getConnectionInfo()`<br>`WifiManager.getDhcpInfo()` | API 31 | `ConnectivityManager.getNetworkCapabilities()` | `AdbGate.java:64, 120`<br>`WifiIconService.java:76, 85` |
| 9 | `WifiManager.calculateSignalLevel(int, int)` | API 30 | `WifiManager.calculateSignalLevel(int)` | `WifiIconService.java:78` |
| 10 | `PackageInfo.versionCode` | API 28 | `PackageInfo.getLongVersionCode()` | `Updater.java:117` |
| 11 | `X509Certificate.getSubjectDN()` | Java 16 | `X509Certificate.getSubjectX500Principal()` | `MqttTls.java:105` |
| 12 | `LocationListener.onStatusChanged(String, int, Bundle)` | API 29 | Removed / default no-op in API 29+ | `TelemetryService.java:169` |

---

## 7. Cyclomatic Complexity Hotspots

### 7.1 Top 10 Most Complex Classes

Branch token analysis (`if`, `for`, `while`, `case`, `catch`, `&&`, `||`, `?`):

| Rank | Class / File | Total Lines | Total Complexity | Key Responsibilities & Architectural Burden |
|---|---|---|---|---|
| 1 | `com.geely.drivemem.ui.TelemetryActivity` | 2,456 | **274** | Monolithic activity: MQTT UI, live logs, diagnostic series, certificate importation |
| 2 | `com.geely.drivemem.ui.ComfortActivity` | 1,685 | **265** | Main launcher UI: column layout, climate ruler, gate card, modal dialogs |
| 3 | `com.geely.drivemem.net.MqttReporter` | 1,404 | **260** | MQTT discovery JSON serialization, payload dispatch, connection state machine |
| 4 | `com.geely.drivemem.sensors.Obd2Reader` | 746 | **171** | BLE scanner, GATT characteristics parser, ELM327 protocol engine |
| 5 | `com.geely.drivemem.net.CertImporter` | 753 | **166** | PKCS12 / PEM extraction, KeyStore installation, TLS socket validation |
| 6 | `com.geely.drivemem.util.Diagnostics` | 559 | **148** | ADB broadcast debug action dispatcher (`FANTEST`, `AUDIOPROBE`, `MEMDUMP`) |
| 7 | `com.geely.drivemem.car.CarAccess` | 633 | **138** | CarPropertyManager abstraction, property ID reflection, vendor adaptors |
| 8 | `com.geely.drivemem.net.AbrpUploader` | 539 | **128** | ABRP HTTP payload generator, upload state machine, file audit logger |
| 9 | `com.geely.drivemem.hvac.ComfortRuler` | 455 | **121** | Temperature target blending, AC/fan calculations, debounce rules |
| 10 | `com.geely.drivemem.art.VaporArtView` | 857 | **106** | Canvas rendering, starfield animation, Konami code particle animations |

### 7.2 Top 10 Most Complex Methods

| Rank | Class & Method | Line | Complexity | Method Description & Refactoring Priority |
|---|---|---|---|---|
| 1 | `Obd2Reader.scanRecordName` | 512 | **64** | Low-level BLE PDU record parser traversing raw advertising byte arrays. Refactor to separate binary parser. |
| 2 | `ClipPlayerActivity.tick` | 324 | **45** | Media player timer loop managing UI timeline, video sync, and state transitions. |
| 3 | `AbrpUploader.buildTlm` | 367 | **39** | Giant JSON telemetry builder mapping vehicle signals and GPS coordinates. |
| 4 | `ComfortRuler.apply` | 279 | **38** | Main climate control decision tree mapping target temp to physical HVAC actions. |
| 5 | `ComfortActivity.stopCarActorPolls` | 1,481 | **35** | Teardown and sensor poll unregistration sequence. |
| 6 | `CertImporter.testTls` | 620 | **34** | Multi-fallback TLS handshake testing custom SSLSocket factories. |
| 7 | `Updater.check` | 64 | **34** | Remote update checker parsing APK archive headers and version queries. |
| 8 | `Telemetry.roundFor` | 61 | **30** | Formatting and scale truncation rules across dozens of data channels. |
| 9 | `DailyStatsProvider.getDayOverview` | 204 | **29** | SQLite aggregation query over daily trip sessions. |
| 10 | `Style.darken` | 466 | **29** | Color arithmetic and theme palette shading. |

---

## 8. Unhandled Lifecycle States & Architectural Risks

### 8.1 Activity Lifecycle Deficiencies
1. `TelemetryActivity.java`:
   - **Missing `onPause` and `onStop`**: Event listeners (`EntityBus`, `Obd2Reader`, `AbrpUploader`) registered in `onCreate` remain active when the user switches to another app or the head unit screen sleeps. This leaks CPU cycles and battery.
   - *Remediation*: Unregister bus listeners in `onPause()` / `onStop()` and re-register in `onResume()`.
2. `ComfortActivity.java`:
   - **Missing `onSaveInstanceState` / `onRestoreInstanceState`**: If the system terminates the process while parked or during deep sleep, UI state (active card selections, ruler positions) resets to default values.
   - *Remediation*: Store active tab index and temporary settings in the saved instance state bundle.
3. `ClipPlayerActivity.java`:
   - Missing `onStop` and `onSaveInstanceState`. Media playback threads can remain active in background.

### 8.2 Service Lifecycle Gaps
- `OutTempService`, `SocIconService`, `WifiIconService`:
  - Pack all setup into `onStartCommand()` without implementing `onCreate()`.
  - Lack `onTrimMemory()` and `onLowMemory()` handlers. Under system memory pressure, Android may kill the services without cleanup, leaving orphan status bar notification icons.

### 8.3 Ineffective Lifecycle Broadcasts
- `BootReceiver.java:101-112`:
  - Registers manifest intent-filters for `Intent.ACTION_SCREEN_ON` and `Intent.ACTION_USER_PRESENT`. In Android 8.0+ (API 26+), broadcast receivers registered in the manifest cannot receive `SCREEN_ON` broadcasts.
  - The application relies on `AlarmManager.ELAPSED_REALTIME_WAKEUP` to wake periodically; the manifest receiver entries for `SCREEN_ON` are dead code and should be removed.

### 8.4 Static Context References
- Static references holding `Context` exist in:
  - `AbrpUploader.appCtx`
  - `CarActor.appContext`
  - `ComfortHub.instance`
  - `Obd2Reader.appCtx`
  - `SpotifyClient.appCtx`
  - `TurboMode.appContext`
- While initialized via `getApplicationContext()`, static references hinder unit testing and risk memory leaks during hot reload or instrumentation test cycles.

---

## 9. Verification & Pipeline Commands

All code quality and test coverage tasks are fully verified via Gradle:

```bash
# 1. Run unit tests and generate JaCoCo coverage reports
./gradlew test jacocoTestReport
# Artifacts:
#   drivemem/build/reports/jacoco/test/jacocoTestReport.xml
#   drivemem/build/reports/jacoco/test/html/index.html
#   drivemem/build/reports/tests/testDebugUnitTest/index.html

# 2. Verify clean production release build (uninstrumented)
./gradlew :drivemem:assembleRelease
# Artifact:
#   drivemem/build/outputs/apk/release/drive_assist.apk

# 3. Run all static analysis checks
./gradlew lint checkstyle spotbugs
# Artifacts:
#   drivemem/build/reports/lint-results.html (.xml, .txt)
#   drivemem/build/reports/checkstyle/checkstyle.html (.xml)
#   drivemem/build/reports/spotbugs/spotbugs.html (.xml)
```
