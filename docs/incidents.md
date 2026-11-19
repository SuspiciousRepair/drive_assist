# Drive Assist — Incident Log

A chronological record of production incidents, edge cases, and hardware quirks discovered during in-vehicle testing and deployment on the Geely IHU629G platform. Inline code comments link directly to entries in this log to explain safety guards and invariants without accumulating narrative bloat in source files.

---

### 2026-09-10: NULL Energy on Telemetry Service Restart
<a id="2026-09-10-null-energy-on-service-restart"></a>

- **What broke**: In `DailyStatsProvider`, efficiency calculations credited distance to speed buckets without attributing energy, artificially lowering consumption numbers (kWh/100km).
- **Root cause**: Following a background service restart, `energy_spent_kwh` and `energy_regen_kwh` remained NULL for ~53 minutes (sample IDs 2067–2269) while odometer and speed metrics continued reporting normally. The query lacked a fallback for uninitialized energy samples during service recovery.
- **Fix**: In `DailyStatsProvider.getSpeedBucketEfficiency()`, fall back to trapezoidal integration of `instant_power_kw_est` across the gap whenever the energy sample pair is NULL, rather than coercing NULL to zero.
- **Files**: `drivemem/src/main/java/com/geely/drivemem/sensors/DailyStatsProvider.java`

---

### 2026-09-12: In-Progress Trip Loss Across App Restart
<a id="2026-09-12-trip-restart-loss"></a>

- **What broke**: An in-progress drive of ~34 minutes and 12 km was completely lost from trip history.
- **Root cause**: Active trip accumulators lived exclusively in memory fields in `TripSession`, only committing to SQLite when `finalizeTrip()` executed upon entering Park. An OTA update installed during a brief stop in Park restarted the application process, wiping all in-memory trip state.
- **Fix**: Minimal trip identity attributes (`trip_open_start_ms`, `trip_open_start_sample_id`, `trip_open_start_odo_km`, `trip_open_start_soc`) are persisted to `SharedPreferences` on trip start. On startup, `recoverOpenTrip()` checks for an unfinished trip and reconstructs metrics by querying recorded rows in `telemetry_sample`.
- **Files / Commits**: `drivemem/src/main/java/com/geely/drivemem/state/TripSession.java` (shipped in commit `6b02704`, v0.1.4).

---

### 2026-09-12: SQLite Schema Downgrade Exception on Startup
<a id="2026-09-12-sqlite-downgrade-rejection"></a>

- **What broke**: `TelemetryService` crashed on startup, causing Android to place the app package into backoff restart throttling for an hour.
- **Root cause**: An ADB test build had pushed `car.db` to schema `user_version` 14. A subsequently deployed build requested version 10. `SQLiteOpenHelper` throws an unhandled `SQLiteException` when opened against a newer database file. `TripSession.recoverOpenTrip()` was executed bare during initialization without exception handling, propagating the crash to the service entry point.
- **Fix**: Database version was bumped past the highest test schema (`VERSION = 15`, currently 22). In `TripSession`, wrapped `recoverOpenTripUnsafe()` in a try/catch block so that database or recovery failures log a warning without killing service or app startup.
- **Files**: `drivemem/src/main/java/com/geely/drivemem/car/CarDb.java`, `drivemem/src/main/java/com/geely/drivemem/state/TripSession.java`.

---

### 2026-09-13: Dashboard Card Column Height Overflow
<a id="2026-09-13-card-column-overflow"></a>

- **What broke**: Cards on the Home screen occasionally clipped off the bottom of the display ("sometimes 3 cards pack in column 1 and the third card is clipped").
- **Root cause**: Column packing ran once during initial view layout based on initial measured heights. Cards whose contents expanded asynchronously after layout (e.g., incoming telemetry updates or session list loads) exceeded the screen's height budget without triggering a repack pass.
- **Fix**: Added a self-healing layout verification pass in `Style.packIntoColumns()`. After layout settles, columns are inspected for height budget overflow; if an overflow is detected, `packIntoColumnsOnce()` executes a second repack pass automatically.
- **Files**: `drivemem/src/main/java/com/geely/drivemem/util/Style.java`.

---

### 2026-09-13: DailyStatsView Live Tick Clobbers Period Views
<a id="2026-09-13-view-clobber-on-live-tick"></a>

- **What broke**: Switching the statistics screen to Week or Month view abruptly reverted back to Day view 1–2 seconds after switching.
- **Root cause**: `DailyStatsView.onLiveTick()` subscribed to `telemetry.tick` and unconditionally invoked `renderOverview()`. Because `selectedIdx` remained indexed to today in Day-mode state, each periodic tick forced the UI back to today's single-day view.
- **Fix**: Added an explicit guard `if (period != Period.DAY) return;` at the beginning of `DailyStatsView.onLiveTick()`.
- **Files**: `drivemem/src/main/java/com/geely/drivemem/ui/DailyStatsView.java`.

---

### 2026-09-14: Stuck Charge Current Sensor Latches Active
<a id="2026-09-14-charge-a-latch"></a>

- **What broke**: Real driving trips showed 0.0 kWh total energy consumption, and daily driving consumption charts were emptied.
- **Root cause**: The VHAL property `charge_a` (605291008) latches at its last non-zero reading when the charging cable is physically unplugged (observed holding 245V / 11.4A for hours). Logic that evaluated `charge_a > 0.5A` concluded the vehicle was still parked and charging even while driving on the highway, suppressing driving energy integration and causing driving speed charts to exclude the trip.
- **Fix**: Prioritized gear state over charging state: if `CarState.isParked()` is false, charging suppression is bypassed because driving and parked-charging are mutually exclusive. Downstream, `CarActor` added connector-engagement cross-checking (`plug_connected`).
- **Files / Commits**: `drivemem/src/main/java/com/geely/drivemem/sensors/EnergyIntegrator.java`, `drivemem/src/main/java/com/geely/drivemem/sensors/DrivingConsumption.java`, `drivemem/src/main/java/com/geely/drivemem/sensors/DailyStatsProvider.java` (commit `573ee5e`, v0.1.5).

---

### 2026-09-14: Core Vehicle State Lifecycle Decoupled from TelemetryService
<a id="2026-09-14-core-state-lifecycle"></a>

- **What broke**: `ParkingState`'s "parked since" timer remained stuck on a timestamp from the previous day, surviving a real drive in between without updating.
- **Root cause**: Core state tracking singletons (`CarActor`, `TripSession`, `ParkSession`, `ParkingState`, `ChargeSession`, `EnergyIntegrator`) were initialized inside `TelemetryService.onStartCommand()`. On 2026-09-14 (~22:17–22:46), `TelemetryService` crashed and was restarted four times. Furthermore, `BootReceiver` skips starting `TelemetryService` whenever MQTT telemetry is disabled. In both scenarios, the listeners responsible for updating vehicle state were never registered.
- **Fix**: Moved initialization of all core vehicle tracking state singletons and bus subscriptions into `DriveMemApplication.onCreate()`, ensuring they are registered unconditionally at process start.
- **Files**: `drivemem/src/main/java/com/geely/drivemem/DriveMemApplication.java`, `drivemem/src/main/java/com/geely/drivemem/services/TelemetryService.java`.

---

### 2026-09-14: Independent Gear Subscription Desynchronization
<a id="2026-09-14-carstate-gear-desync"></a>

- **What broke**: A charging session outlived the start of a driving trip, resulting in conflicting simultaneous states.
- **Root cause**: Both `TripSession` and `CarState` subscribed independently to `car.gear` events, each maintaining its own debouncing and latching state. `CarState.observe()` compared transitions only against its internal copy and missed the driving edge that `TripSession` had already processed.
- **Fix**: Designated `TripSession.onGear()` as the single authoritative detector of Park transitions. `TripSession` notifies `CarState.reportParked()`, turning `CarState` into a pure broadcast relay for other consumers (Turbo, Charging, Valet, Gate).
- **Files**: `drivemem/src/main/java/com/geely/drivemem/state/CarState.java`, `drivemem/src/main/java/com/geely/drivemem/state/TripSession.java`.

---

### 2026-09-15: Reverse Camera Freeze from Parked-Monitoring Probe
<a id="2026-09-15-camera-probe-freeze"></a>

- **What broke**: The OEM reverse camera display froze on a stale frame upon shifting from Park into Reverse, creating a safety hazard while maneuvering.
- **Root cause**: An experimental background camera probe attempted to open a second hardware connection to the video feed while the dashcam service was active.
- **Fix**: Completely disabled the parked-monitoring camera probe. Enforced that only the dashcam recorder may open the vehicle camera interface.
- **Files / Commits**: Commit `88b63a9` (shipped in v0.2.0).

---

### 2026-09-15: Parked Timer Stalling from Listener List Replacement
<a id="2026-09-15-park-timer-listener-clobber"></a>

- **What broke**: The parked timer remained frozen at a 10+ hour-old timestamp across five separate drives in a single day.
- **Root cause**: `CarState.setListener()` replaced the entire listener collection with a single callback rather than appending to it. Opening the main screen installed an activity listener that silently dropped the existing listeners responsible for tracking park durations and charging.
- **Fix**: Converted listener management to `CopyOnWriteArrayList` with `addListener()` and `removeListener()` to support concurrent listeners safely.
- **Files / Commits**: `drivemem/src/main/java/com/geely/drivemem/state/CarState.java` (shipped in commit `4301ac4`, v0.2.0).

---

### 2026-09-16: Month Stats Speed Chart Date Parsing Failure
<a id="2026-09-16-stats-month-label-parse-failure"></a>

- **What broke**: The Month overview card displayed hourly speed data for only a single day instead of aggregating the entire month.
- **Root cause**: The view fetched the hourly speed dataset using `overview.date`, which for multi-day periods contains a human-readable display string ("Setembro de 2026") rather than an ISO date string (`yyyy-MM-dd`). Parsing failed and silently defaulted to today's date.
- **Fix**: Centralized period date calculations in `DailyStatsProvider.datesForGranularity()`, decoupling display labels from query date ranges inside `StatsDetail`.
- **Files**: `drivemem/src/main/java/com/geely/drivemem/sensors/DailyStatsProvider.java`.

---

### 2026-09-18: Latched Current Sensor Hides DC Fast Charge
<a id="2026-09-18-charge-latch"></a>

- **What broke**: A genuine 30-minute, 16.5 kWh DC fast charging session was recorded as only 2 minutes and 0.6 kWh.
- **Root cause**: `charge_a` remained latched at 1 from an earlier charging session. Because `car.is_charging` never transitioned back to 0 when the cable was unplugged, it could not produce the fresh 0->1 transition edge required by `ChargeSession` when the new charging session began.
- **Fix**: `CarActor`'s 2-second `car.is_charging` poll now cross-checks current (`charge_a > 0.5A`) with the hardware connector-engagement property (`plug_connected`, 557887621) in the same poll tick. When unplugged, `car.is_charging` drops to 0 immediately, guaranteeing clean edge detection on next plug-in.
- **Files / Commits**: `drivemem/src/main/java/com/geely/drivemem/car/CarActor.java` (commit `5da6925`, v0.3.0).

---

### 2026-09-21: Main-Thread CPU Spin on GONE Views
<a id="2026-09-21-layoutwait-anr"></a>

- **What broke**: Main thread ran at ~100% CPU (measured at 93.5%), causing UI stutter and triggering Android ANR termination of Spotify (`Input dispatching timed out`).
- **Root cause**: Six UI call sites polled for view layout dimensions by repeatedly re-posting runnables via `Handler.post()` until `getWidth()` or `getHeight()` returned non-zero. Views inside `View.GONE` containers never receive a layout pass, causing an infinite tight loop on the main thread message queue.
- **Fix**: Created `LayoutWait.onNextLayout()`, which attaches a `View.OnLayoutChangeListener`. The listener fires only when an actual layout pass occurs and idles without CPU consumption while the view remains `GONE`.
- **Files / Commits**: `drivemem/src/main/java/com/geely/drivemem/util/LayoutWait.java` (GitHub issue #5, commit `0f999a6`, v0.3.0).

---

### 2026-09-21: Consecutive Update Prompts for Companion Packages
<a id="2026-09-21-double-update-prompt"></a>

- **What broke**: After accepting a software update, a second update dialog immediately popped up asking to update again.
- **Root cause**: `drive_assist` and `modehelper` are versioned and built together. The update check discovered new versions for both packages simultaneously. Accepting the prompt updated only `drive_assist`, leaving `modehelper` outdated and triggering an immediate subsequent prompt.
- **Fix**: Updated `ComfortActivity` to always delegate installation to `Updater.updateHelper()`, which installs the bundled installer APK that updates both applications in a single operation.
- **Files**: `drivemem/src/main/java/com/geely/drivemem/ui/ComfortActivity.java` (v0.3.0).

---

### 2026-09-21: Turbo Card Missing Margin on Resume
<a id="2026-09-21-turbo-card-margin-on-resume"></a>

- **What broke**: The Portão (Gate) and Turbo cards rendered with no margin between them on the Home screen.
- **Root cause**: Shifting out of Park while the Config screen was open changed Turbo card visibility. Because `ComfortActivity` was paused, the live `CarState.Listener` did not repack columns. When navigating back to Home, the card became visible without being repacked into the layout.
- **Fix**: Added visibility verification and `repackColumns()` inside `ComfortActivity.onResume()`.
- **Files**: `drivemem/src/main/java/com/geely/drivemem/ui/ComfortActivity.java` (v0.3.0).

---

### 2026-09-22: Trip History Loss After Disabling Valet Mode
<a id="2026-09-22-valet-trip-disappearance"></a>

- **What broke**: A trip that started under Valet mode, paused briefly, and continued after Valet was toggled off disappeared completely from trip history.
- **Root cause**: Toggling off Valet mode failed to finalize and seal the open trip segment, resulting in invalid segment stitching that failed distance and duration thresholds during finalization.
- **Fix**: Explicitly finalize and close active trip segments when Valet mode is deactivated, allowing subsequent driving to register as a fresh trip.
- **Files / Commits**: Commit `1320624` (v0.3.2).

---

### 2026-09-23: Single Isolated OBD2 Reading Marked as Estimated
<a id="2026-09-23-single-obd-reading-miss"></a>

- **What broke**: Short drives were tagged as "estimated" energy in daily statistics even though the OBD2 scanner remained connected and functional throughout the trip.
- **Root cause**: In `EnergyIntegrator.drainWindow()`, a window with only one OBD2 reading had no predecessor to form a trapezoid (`count == 0`). The logic previously fell back to the VHAL SoC-delta estimate, marking the window as estimated despite valid OBD2 data.
- **Fix**: When `count == 0` but a single recent OBD2 reading exists (`<= MAX_GAP_MS`), hold that reading flat across the window and record the sample as measured.
- **Files / Commits**: `drivemem/src/main/java/com/geely/drivemem/sensors/EnergyIntegrator.java` (commit `76bee4b`, `5a5a73c`).

---

### 2026-09-23: Telemetry Poller Reporting Charging While Driving
<a id="2026-09-23-telemetry-charging-desync"></a>

- **What broke**: Telemetry broadcasted `is_charging=1` and `is_dcfc=1` over MQTT and ABRP while the vehicle was driving at 64.6 km/h.
- **Root cause**: `Telemetry.read()` re-derived `is_charging` independently from raw `charge_a > 0.5A` without checking physical connector engagement. When `charge_a` latched, `Telemetry` reported charging indefinitely.
- **Fix**: Removed independent charging calculations from `Telemetry.read()`. Telemetry now receives authoritative charging state directly from `CarActor`'s cross-checked poll, and omits the key if unknown.
- **Files / Commits**: `drivemem/src/main/java/com/geely/drivemem/car/Telemetry.java` (commits `a48d006`, `edaeaf0`).

---

### 2026-09-23: OTA Update Interrupting Active DC Fast Charge
<a id="2026-09-23-ota-install-during-charging"></a>

- **What broke**: A public DC fast charging session abruptly halted at ~12 minutes when an automatic OTA update installed.
- **Root cause**: `Updater.installBlockedReason()` only blocked installations when the vehicle was moving (`!CarState.isParked()`). Because the vehicle was parked during charging, the OTA installer proceeded, terminating the process and interrupting vehicle communications.
- **Fix**: Hardened `installBlockedReason()` to block OTA installs if the vehicle is actively charging, has an active charging session, has a grace period pending, or has an active charging row ID in the database.
- **Files / Commits**: `drivemem/src/main/java/com/geely/drivemem/net/Updater.java` (commits `f178a60`, `bc4a636`).
