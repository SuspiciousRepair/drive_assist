# SQL and data-layer improvement plan

Status: proposed  
Scope reviewed: executable SQL and SQLite access on `dev` (the Android app plus
`tools/export-car-trace.py`). No standalone `.sql` files exist. The review covers
the nine tables created by `CarDb`, all `rawQuery`/`execSQL` call sites, inserts,
updates, retention, legacy import, and the raw database export path.

Review inventory:

- Schema and migrations: [`CarDb.java`](../../drivemem/src/main/java/com/geely/drivemem/car/CarDb.java)
  and [`DbMigration.java`](../../drivemem/src/main/java/com/geely/drivemem/util/DbMigration.java).
- Producers and lifecycle writes:
  [`TelemetrySampler.java`](../../drivemem/src/main/java/com/geely/drivemem/sensors/TelemetrySampler.java),
  [`ComfortEvents.java`](../../drivemem/src/main/java/com/geely/drivemem/hvac/ComfortEvents.java),
  [`TripSession.java`](../../drivemem/src/main/java/com/geely/drivemem/state/TripSession.java),
  [`ParkSession.java`](../../drivemem/src/main/java/com/geely/drivemem/state/ParkSession.java),
  [`ChargeSession.java`](../../drivemem/src/main/java/com/geely/drivemem/state/ChargeSession.java),
  and [`ValetSession.java`](../../drivemem/src/main/java/com/geely/drivemem/state/ValetSession.java).
- Aggregation and readers:
  [`TelemetryRollup.java`](../../drivemem/src/main/java/com/geely/drivemem/sensors/TelemetryRollup.java),
  [`DailyStatsProvider.java`](../../drivemem/src/main/java/com/geely/drivemem/sensors/DailyStatsProvider.java),
  [`OdoStats.java`](../../drivemem/src/main/java/com/geely/drivemem/sensors/OdoStats.java),
  [`ChargeStatsView.java`](../../drivemem/src/main/java/com/geely/drivemem/ui/ChargeStatsView.java),
  [`ChargeCurrentCurveDialog.java`](../../drivemem/src/main/java/com/geely/drivemem/ui/ChargeCurrentCurveDialog.java),
  and [`MqttReporter.java`](../../drivemem/src/main/java/com/geely/drivemem/net/MqttReporter.java).
- Offline consumer:
  [`export-car-trace.py`](../../tools/export-car-trace.py). Its fixed column list,
  bound half-open time range, deterministic `ORDER BY ts_ms,id`, read-only URI,
  and exclusive output creation are appropriate; retain those properties.

## Outcome and priorities

The database is small enough that SQLite remains the right storage engine, and
bound parameters are used consistently; no direct SQL-injection path was found.
The main risks are instead durability, day-boundary correctness, unindexed scans,
and synchronous analytics on UI threads.

Implement in this order:

1. **P0 — prevent silent loss/duplication and unsafe snapshots.** Make legacy
   import idempotent, make related writes atomic, check insert/update results,
   serialize every write, and produce a consistent privacy-aware export.
2. **P0 — establish one definition of time intervals and daily attribution.** Fix
   the current-day odometer omission, use half-open timestamp ranges, and decide
   how trips/charges crossing midnight are apportioned.
3. **P1 — make time filters use indexes and remove query fan-out.** Replace
   per-day `date(column/1000,...)` filters and N+1 loops, then add only indexes
   proven useful by `EXPLAIN QUERY PLAN` on the head unit.
4. **P1 — move reads off the main thread and add migration/query tests.** This is
   a prerequisite for adopting Room without enabling its unsafe main-thread
   escape hatch.
5. **P2 — adopt Room 2.x in a controlled ownership cutover.** Keep complex SQL,
   but gain compile-time query checking, typed mappings, exported schemas, and
   migration validation. Do not combine two live database owners and do not use
   destructive fallback.

## Evidence and proposed changes

### P0. Make migration and durable writes crash-safe

#### Legacy import can duplicate rows after a process death

`DbMigration.runOnce()` uses a `SharedPreferences` flag
([`DbMigration.java:28`](../../drivemem/src/main/java/com/geely/drivemem/util/DbMigration.java#L28)),
but charge and odometer imports commit in two separate transactions
([`DbMigration.java:55-74`](../../drivemem/src/main/java/com/geely/drivemem/util/DbMigration.java#L55),
[`95-116`](../../drivemem/src/main/java/com/geely/drivemem/util/DbMigration.java#L95)).
A death after either commit but before the preference is durably written causes
the retained flat files to be imported again. The in-memory `seenDates` only
deduplicates rows within one attempt, not against the database. In addition,
`SQLiteDatabase.insert()` returns `-1` on failure; the code increments
`inserted` anyway and can still set the migration flag.

Plan:

- Add a database-resident migration/import ledger, keyed by import name and a
  stable source fingerprint, and commit imported rows plus the ledger entry in
  one transaction. The database marker, not `SharedPreferences`, is the source
  of truth.
- Make each imported row idempotent. Prefer a ledger of source file + line hash
  because `(start_ms,end_ms)` may not be a guaranteed natural key. For odometer
  history, record the legacy source/day explicitly or perform an existence check
  within the same transaction.
- Use `insertOrThrow()` for the import and durable session/event tables, or check
  every returned row ID. Only mark the import complete after all required writes
  succeed.
- Add kill/retry tests after each write boundary and verify identical row counts
  and values after the second attempt.

#### Related state changes are not atomic, and not all writes use the writer queue

The charge session row and its outbox event are separate autocommit writes
([`ChargeSession.java:329-343`](../../drivemem/src/main/java/com/geely/drivemem/state/ChargeSession.java#L329));
a crash between them loses the event. Updating a charge cost and its frozen
`daily_stat` copy is also two autocommit statements
([`ChargeSession.java:568-580`](../../drivemem/src/main/java/com/geely/drivemem/state/ChargeSession.java#L568)).
Both should be transactions, with notifications published only after commit.

`CarDb` says all writes are serialized on `HandlerThread("cardb")`
([`CarDb.java:18-23`](../../drivemem/src/main/java/com/geely/drivemem/car/CarDb.java#L18)),
but valet start/stop/checkpoints write directly
([`ValetSession.java:53-76`](../../drivemem/src/main/java/com/geely/drivemem/state/ValetSession.java#L53),
[`81-104`](../../drivemem/src/main/java/com/geely/drivemem/state/ValetSession.java#L81),
[`108-132`](../../drivemem/src/main/java/com/geely/drivemem/state/ValetSession.java#L108)),
and MQTT acknowledgement writes directly while iterating a cursor and waiting on
network I/O ([`MqttReporter.java:285-309`](../../drivemem/src/main/java/com/geely/drivemem/net/MqttReporter.java#L285)).

Plan:

- Expose explicit asynchronous and synchronous transaction APIs from the data
  layer; route **all** mutations through its single transaction executor. Avoid a
  blocking synchronous call when already on that executor.
- Materialize pending outbox rows, close the cursor, publish outside a database
  transaction, then enqueue a conditional acknowledgement (`published = 0 AND
  id = ?`). Preserve at-least-once delivery and keep `occurrence_id` as the
  consumer deduplication key.
- Check affected-row counts for updates. Today `updateCost()` and
  `dismissSession()` publish success even if no row matched. Treat a missing
  session as an error and do not update a summary or publish a success event.
- Make rollup insertion and pruning a transaction. This gives every reader a
  single summary snapshot and makes retry behavior explicit.

#### Sample pointers can lag the logical event

Trip, park, and charge code calls `latestSampleId()` synchronously before its own
write is enqueued ([`ParkSession.java:40-55`](../../drivemem/src/main/java/com/geely/drivemem/state/ParkSession.java#L40),
[`TripSession.java:209-225`](../../drivemem/src/main/java/com/geely/drivemem/state/TripSession.java#L209),
[`ChargeSession.java:311-334`](../../drivemem/src/main/java/com/geely/drivemem/state/ChargeSession.java#L311)).
Telemetry samples themselves are queued asynchronously
([`TelemetrySampler.java:71-77`](../../drivemem/src/main/java/com/geely/drivemem/sensors/TelemetrySampler.java#L71)).
The selected ID can therefore precede a telemetry tick that logically happened
before the boundary.

Plan:

- Establish boundaries on the same transaction executor as sample insertion.
  Prefer passing/publishing the inserted sample ID from `TelemetrySampler`; as a
  fallback, resolve the nearest sample with deterministic `(ts_ms,id)` ordering
  inside the queued session transaction.
- Define whether an end pointer includes or excludes its sample. Apply the same
  half-open convention to chart and energy queries, and test adjacent sessions
  so a boundary sample cannot be counted twice.

### P0. Correct daily and interval semantics

#### Rolling odometer distance omits the newest day's travel

`OdoStats.readLog()` returns `first_odo_km` for every frozen day and the
`MIN(id)` sample for every raw day
([`OdoStats.java:96-108`](../../drivemem/src/main/java/com/geely/drivemem/sensors/OdoStats.java#L96)).
`kmSince()` subtracts the base from the final item
([`OdoStats.java:38-50`](../../drivemem/src/main/java/com/geely/drivemem/sensors/OdoStats.java#L38)).
Consequently the charge screen's 30/365-day distance
([`ChargeStatsView.java:194-214`](../../drivemem/src/main/java/com/geely/drivemem/ui/ChargeStatsView.java#L194))
ends at the first reading of today rather than the latest reading today.

Plan:

- Return a range (`first_odo_km`, `last_odo_km`) or independently query the
  latest valid `(ts_ms,id)` reading. Compute rolling distance from the reading at
  or immediately before the lower bound to the latest reading at/before `now`.
- Do not use `MIN(id)`/`MAX(id)` as a synonym for time. Legacy rows are inserted
  later with historical timestamps. Standardize chronological ordering as
  `ORDER BY ts_ms, id`, including the active-trip fallback at
  [`DailyStatsProvider.java:659-667`](../../drivemem/src/main/java/com/geely/drivemem/sensors/DailyStatsProvider.java#L659).
- Add database-backed tests for an active current day, sparse legacy rows,
  equal timestamps, out-of-order imports, odometer reset/glitch, and no reading
  before the cutoff.

#### “Day” and cross-midnight allocation are inconsistent

Daily rollup assigns all trip and charge values to `date(start_ms...)`
([`TelemetryRollup.java:115-121`](../../drivemem/src/main/java/com/geely/drivemem/sensors/TelemetryRollup.java#L115));
the daily overview repeats that rule
([`DailyStatsProvider.java:504-537`](../../drivemem/src/main/java/com/geely/drivemem/sensors/DailyStatsProvider.java#L504)).
The charge balance chart instead apportions a session by overlap
([`ChargeStatsView.java:297-320`](../../drivemem/src/main/java/com/geely/drivemem/ui/ChargeStatsView.java#L297)).
A charge or trip crossing midnight can therefore show different energy/count/
duration totals in different views. Trip energy uses inclusive `BETWEEN`
([`DailyStatsProvider.java:621-625`](../../drivemem/src/main/java/com/geely/drivemem/sensors/DailyStatsProvider.java#L621)),
and the charge-current curve does the same
([`ChargeCurrentCurveDialog.java:71-76`](../../drivemem/src/main/java/com/geely/drivemem/ui/ChargeCurrentCurveDialog.java#L71)).
That allows an exact boundary row to appear in adjacent intervals.

Plan:

- Adopt one interval contract: epoch milliseconds, `[start_ms,end_ms)`, with a
  shared utility that converts an ISO local date to real timezone-aware start
  and next-day instants. Never derive the end as `start + 24h`; that is wrong on
  daylight-saving transitions (currently done at
  [`ChargeStatsView.java:300-307`](../../drivemem/src/main/java/com/geely/drivemem/ui/ChargeStatsView.java#L300)).
- Product decision: use overlap attribution for duration and energy; define
  counts as either “session starts” or “session touches day” and label it. For
  ascent/descent, either persist per-day/session segments or accept and document
  start-day attribution; duration-based proportional splitting is not accurate
  for elevation.
- Build the 30-day chart from `today - 29 calendar days` through the current day.
  The current cutoff/floor/30-iteration loop ends on yesterday.
- Test midnight, a DST gap and fold in a timezone that observes DST, timezone
  changes, open sessions, and adjacent intervals.

### P1. Make hot predicates sargable and remove repeated scans

Most daily queries wrap indexed timestamps in
`date(ts_ms/1000,'unixepoch','localtime')`, for example
[`DailyStatsProvider.java:237-241`](../../drivemem/src/main/java/com/geely/drivemem/sensors/DailyStatsProvider.java#L237),
[`480-483`](../../drivemem/src/main/java/com/geely/drivemem/sensors/DailyStatsProvider.java#L480),
[`ChargeStatsView.java:325-330`](../../drivemem/src/main/java/com/geely/drivemem/ui/ChargeStatsView.java#L325),
and [`TelemetryRollup.java:74-121`](../../drivemem/src/main/java/com/geely/drivemem/sensors/TelemetryRollup.java#L74).
That prevents `idx_sample_ts` from seeking. A synthetic schema check with local
SQLite 3.53 produced `SCAN telemetry_sample` for the wrapped predicate and
`SEARCH telemetry_sample USING INDEX idx_sample_ts (ts_ms>? AND ts_ms<?)` for
`ts_ms >= ? AND ts_ms < ?`. The Android 9 head unit may ship a different SQLite
planner, so capture its plans before and after as the acceptance evidence.

Plan:

- Convert single-day filters to bound timestamp ranges. For multi-day rollup,
  first constrain the overall timestamp range so `idx_sample_ts` limits the rows,
  then group those rows by local date if grouping remains in SQL.
- Restrict `TelemetryRollup.groupedByDay()` queries to the actual missing days.
  They currently aggregate the full retained telemetry and all permanent trip/
  charge history every run, even when only yesterday is missing.
- Replace the charge chart's 30 calls to `rawEnergyForDay()`
  ([`ChargeStatsView.java:297-330`](../../drivemem/src/main/java/com/geely/drivemem/ui/ChargeStatsView.java#L297))
  with one range scan grouped in Java or SQL.
- Eliminate the per-trip energy query in `queryDaySessions()`
  ([`DailyStatsProvider.java:589-635`](../../drivemem/src/main/java/com/geely/drivemem/sensors/DailyStatsProvider.java#L589)).
  Fetch the day's ordered telemetry once and allocate it to ordered trip
  intervals, or aggregate all trip ranges in one query after benchmarking.
- Remove the unused `LEFT JOIN telemetry_sample s1` in the charge timeline query
  ([`DailyStatsProvider.java:736-741`](../../drivemem/src/main/java/com/geely/drivemem/sensors/DailyStatsProvider.java#L736)).
- Guard the dynamically constructed `IN` list in
  [`TelemetryRollup.java:198-221`](../../drivemem/src/main/java/com/geely/drivemem/sensors/TelemetryRollup.java#L198)
  against the device's bind-parameter limit by batching. Values are already
  bound; only placeholder tokens are constructed, so this is a capacity issue,
  not an injection issue.

Candidate indexes, added only after plan evidence on representative data:

| Candidate | Supports | Caveat |
|---|---|---|
| `trip(start_ms)` | week summary, daily rollup/overview, timeline | Convert date wrappers to ranges first. |
| `charge_session(start_ms)` | ordered history, daily totals/timeline | `readLog()` currently loads all history; also add a lower-bound query for period screens. |
| Partial `charge_session(end_ms DESC) WHERE dismissed = 0` | latest-undismissed lookup at [`ChargeSession.java:545-559`](../../drivemem/src/main/java/com/geely/drivemem/state/ChargeSession.java#L545) | Partial indexes are supported by the API-28 SQLite generation, but verify on the actual image. |
| Partial `charge_stop_event(id) WHERE published = 0` | ordered outbox drain | The existing unique index begins with `session_id`, so it cannot satisfy this filter/order. |
| `valet_session(start_ms, end_ms)` | overlap and anti-join checks | Low row volume may make a scan cheaper; measure. |

`idx_sample_odo` ([`CarDb.java:68-69`](../../drivemem/src/main/java/com/geely/drivemem/car/CarDb.java#L68))
does not match a reviewed filter/order; queries merely require a non-null value
and normally order by time/ID. Confirm with `EXPLAIN` and `sqlite_stat1`, then drop
it if unused to reduce storage and the cost of every 15-second telemetry insert.
Do not add wide covering indexes to the high-write telemetry table without an
on-device size/write benchmark.

### P1. Concurrency, UI latency, retention, and export

- `ChargeStatsView.refresh()` performs full charge-history loading, odometer
  queries, and 30 telemetry scans synchronously from view construction
  ([`ChargeStatsView.java:55-68`](../../drivemem/src/main/java/com/geely/drivemem/ui/ChargeStatsView.java#L55),
  [`194-214`](../../drivemem/src/main/java/com/geely/drivemem/ui/ChargeStatsView.java#L194)).
  Daily views also issue several synchronous aggregate scans. Move DAO/repository
  reads to a bounded query executor and post immutable results to the UI. Never
  adopt Room's `allowMainThreadQueries()` workaround.
- Evaluate WAL with the actual Android 9 image after all writes are serialized.
  Enable it through the database API rather than raw `PRAGMA journal_mode`; test
  suspend/resume, checkpointing, disk growth, and export. WAL can improve read /
  writer coexistence but does not fix slow queries.
- Rollup only creates `daily_stat` rows for days containing non-null odometer
  samples ([`TelemetryRollup.java:74-101`](../../drivemem/src/main/java/com/geely/drivemem/sensors/TelemetryRollup.java#L74)).
  A telemetry-only day with no odometer is never frozen and therefore never
  pruned by [`TelemetryRollup.java:247-252`](../../drivemem/src/main/java/com/geely/drivemem/sensors/TelemetryRollup.java#L247).
  Track rollup coverage independently of odometer availability; permit nullable
  daily odometer fields or add a per-day rollup ledger, then prune only after all
  required aggregates are durable.
- The UI copies the live `car.db` file directly to removable storage
  ([`UsbExport.java:48-85`](../../drivemem/src/main/java/com/geely/drivemem/util/UsbExport.java#L48),
  invoked at [`ChargeStatsView.java:107-118`](../../drivemem/src/main/java/com/geely/drivemem/ui/ChargeStatsView.java#L107)).
  A concurrent commit can yield a torn snapshot; after WAL adoption, copying only
  `car.db` also omits committed WAL pages. Create the export through a coordinated
  SQLite snapshot/logical copy, run `PRAGMA quick_check` on the result, close it,
  and only then copy it to USB.
- The exported database contains detailed timestamps, charging behavior,
  odometer and trip-derived history. Treat the action as an explicit sensitive
  export: show a confirmation describing its contents, use a uniquely named file
  rather than silently overwriting `car.db`, and offer a minimized CSV/JSON export.
  At-rest encryption is not a substitute on this test-key/root-ADB head unit;
  SQLCipher would add key-management and binary cost without protecting against
  the stated root threat model. Do not add it unless the threat model changes.

### P2. Harden the schema and migrations

The schema has no foreign keys or domain checks
([`CarDb.java:46-179`](../../drivemem/src/main/java/com/geely/drivemem/car/CarDb.java#L46)).
Add constraints during a tested table-rebuild migration, after first auditing and
normalizing existing rows:

- `end_ms IS NULL OR end_ms >= start_ms` on interval tables.
- Boolean columns restricted to `0/1` (nullable where “unknown” is meaningful).
- SOC/battery values restricted to `0..100`, converting the current `-1`
  sentinels to `NULL` first.
- Non-negative gross energy, cost, sample count, and odometer values where the
  domain guarantees it; keep signed net-energy/power columns unconstrained.
- Foreign keys from trip/park/charge sample pointers to `telemetry_sample(id)`
  with `ON DELETE SET NULL`, because raw samples are intentionally pruned; and
  from `charge_stop_event.session_id` to `charge_session(id)` with a deliberate
  delete policy. Enable FK enforcement in configuration and require a clean
  `PRAGMA foreign_key_check` before/after migration.

`onUpgrade()` currently contains conditional paths from versions 1 through 15
and intentionally drops all history below v4
([`CarDb.java:181-200`](../../drivemem/src/main/java/com/geely/drivemem/car/CarDb.java#L181)).
Before the next schema release:

- Decide and document the oldest supported on-device version. If v1-v3 devices
  still exist, replace destructive migration; otherwise fail with a clear backup
  requirement instead of silently deleting telemetry.
- Check in schema fixtures for every supported starting version. Upgrade each to
  latest and assert `user_version`, columns, indexes, row preservation,
  `quick_check`, and `foreign_key_check`. Include v12/v14 valet fixtures because
  those versions required table rebuild repairs
  ([`CarDb.java:281-303`](../../drivemem/src/main/java/com/geely/drivemem/car/CarDb.java#L281)).
- Test fresh creation and every supported upgrade against the same canonical
  schema. Back up a real database before device validation and rehearse rollback.

## Framework/abstraction decision

### Recommendation: staged adoption of Room 2.x, not a big-bang rewrite

Adopt the current stable Room **2.x** line after the P0 fixes and background-read
refactor. As of this review, AndroidX lists 2.8.5 as stable and explicitly
supports Java-only projects with `annotationProcessor`; re-check and pin the
version when implementation begins. Room fits this project because it has a
growing nine-table schema, many cursor-column mappings, handwritten migrations,
and static SQL that would benefit from compile-time validation. It also supplies
exported schema history and migration-test support. See the official
[Room overview](https://developer.android.com/training/data-storage/room),
[Room releases](https://developer.android.com/jetpack/androidx/releases/room),
and [SQLite-to-Room migration guide](https://developer.android.com/training/data-storage/room/sqlite-room-migration).

Do **not** start with Room 3 for this Java-only application. Room 3 is
Kotlin/KSP/coroutine-first; Android's own migration guidance recommends first
modernizing existing applications on Room 2.x. Do not adopt SQLDelight here:
moving SQL into `.sq` files would give useful generation/checking, but introduces
a Kotlin-oriented toolchain and Android driver while offering less direct help
for the existing `SQLiteOpenHelper` migration history. A home-grown ORM would
add risk without compile-time SQL verification.

Room will not by itself fix date semantics, query complexity, event ordering, or
unsafe export, so it follows rather than replaces P0/P1 work.

Migration approach:

1. Introduce typed result objects and narrow repository interfaces
   (`TelemetryStore`, `SessionStore`, `DailyStatsStore`, `OutboxStore`) around the
   current helper. Centralize table/column names and the local-day bounds utility.
   This gives tests a seam and shrinks the eventual Room cutover.
2. Add Room runtime/compiler/testing and checked-in exported schemas. Define all
   nine entities with names/types/nullability exactly matching the validated v15
   database. Prefer typed `@Query` DAOs; keep a narrowly reviewed raw-query escape
   hatch only where query shape is truly dynamic.
3. Make Room the **sole** owner of `car.db` at a new schema version. Provide and
   test migrations from every supported installed version, including the
   v15-to-Room ownership transition. Never open `CarDb` and Room concurrently,
   and never configure `fallbackToDestructiveMigration()` for this irreplaceable
   vehicle history.
4. Preserve the existing single transaction executor initially through Room's
   query/transaction executor configuration. Migrate one repository at a time,
   but make the ownership switch atomically in one release; remove direct
   `db()` exposure as call sites move to DAOs.
5. Use Room's migration test helper plus on-device API-28 tests. The official
   [migration testing guide](https://developer.android.com/training/data-storage/room/migrating-db-versions)
   recommends exported schemas and testing all defined migrations.

If dependency size or device validation blocks Room, retain `SQLiteOpenHelper`
but still complete steps 1, P0/P1, and the schema-fixture test suite. That is the
minimum acceptable abstraction; continuing to expose a writable
`SQLiteDatabase` globally is not.

## Validation and acceptance gates

1. Build a synthetic fixture with at least 100k telemetry rows, same-timestamp
   ties, sparse/out-of-order legacy rows, null sensor stretches, adjacent trips,
   a trip and charge spanning midnight, active sessions, and 90+ days of data.
2. For each corrected query, assert values against a small hand-calculated
   fixture. Require consistent totals across daily overview, charge balance,
   rollup, and rolling odometer screens.
3. Capture `EXPLAIN QUERY PLAN` and wall-clock timings on a representative copied
   database and on the Android 9 head unit. Daily point/range reads must seek
   `idx_sample_ts`; the 30-day chart must issue one telemetry range query, and
   the timeline must not issue one telemetry query per trip.
4. Run all supported version upgrades and interrupted legacy-import retries;
   require stable row counts plus `PRAGMA quick_check = ok` and an empty
   `PRAGMA foreign_key_check` result.
5. Stress concurrent telemetry sampling, valet updates, rollup, cost changes,
   MQTT reconnect/outbox drain, stats navigation, suspend/resume, and USB export.
   Require no database writes outside the transaction executor, no main-thread
   database I/O, no missing outbox event, and a standalone export that passes
   `quick_check` after the source continues receiving samples.
6. Before rollout, back up the real vehicle database and validate the migration
   and export on-device with publishing/deployment disabled.
