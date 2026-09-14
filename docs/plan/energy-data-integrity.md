# Data integrity: verify, make sense of, and heal energy totals

**Status: planned, not started.** Hold on implementing per explicit request
— this doc records the agreed approach for later execution.

## Context

"Balanço de Energia" (and every other energy total downstream of it — daily
discharge/regen kWh, trip totals) is a straight SUM of
`telemetry_sample.energy_spent_kwh`/`energy_regen_kwh`, one row per ~15s
tick, each row itself a trapezoidal integral of OBD2 power readings at ~2s
cadence (`EnergyIntegrator.java`). If the OBD2 dongle drops out mid-drive —
Bluetooth hiccup, app restart, a gap `EnergyIntegrator.MAX_GAP_MS` (10s)
explicitly refuses to bridge — that stretch of real energy use is silently
undercounted: nothing today cross-checks the summed total against the one
thing that's immune to sampling gaps, the battery's own reported state of
charge. Found while explaining how the chart worked; the fix requested:
verify the numbers, diagnose *why* they're off when they are, and heal what
can be healed.

There is no existing convention in this codebase for marking
uncertain/derived data (no `confidence`/`estimated`/`gap`/`quality` column
or naming pattern anywhere) — this introduces one, deliberately kept small
and literal (`gap_ms`, `soc_delta_kwh`, `net_kwh_corrected`) rather than a
generic "confidence score."

**Battery capacity**: `ChargeSession.CAPACITY_WH = 39_600` (39.6 kWh) is the
only pack-capacity figure in the codebase, and it's undocumented as
nameplate vs. usable (no mention in `field-catalog.md` either — this is a
pre-existing documentation gap, not something this feature needs to
resolve). Decision: use it as-is, and keep the mismatch tolerance loose
enough that the ~5-10% nameplate/usable slop this constant might carry
doesn't itself trigger false positives.

## The unit of verification: completed trips, not calendar days

A trip (the `trip` table: `start_ms`, `end_ms`, `start_sample_id`,
`end_sample_id`) is the right granularity, not a whole day:
- It's already bounded by two `telemetry_sample` rows with known
  `battery_pct` (via `s1`/`s2` joins — see
  `DailyStatsProvider.queryDaySessions()`,
  `drivemem/src/main/java/com/geely/drivemem/sensors/DailyStatsProvider.java`
  around the trip query), giving a clean start/end SoC with nothing else
  (no charging) happening in between.
- A whole day mixes driving and charging, so its net SoC change reflects
  both — comparing it to a pure discharge estimate would be meaningless
  without also subtracting charge_session kWh, adding an extra layer of
  assumptions for no real benefit over just checking each trip.
- `queryDrivingConsumption()` (same file) already recomputes a trip's
  measured net/spent/regen kWh from raw `telemetry_sample` rows in
  `[start_ms, end_ms]` — reuse this directly, don't re-derive it.

## Verify: SoC-delta cross-check + real gap detection

For each completed trip with known `socStart`/`socEnd` (`socStart >
socEnd`, i.e. a net-discharge trip — regen-dominant short trips where SoC
went *up* aren't a good test case and should be skipped):

1. `measuredNetKwh` = existing `queryDrivingConsumption()` result over the
   trip's `[start_ms, end_ms]` (already computed elsewhere — reuse, don't
   duplicate).
2. `socDeltaKwh` = `(socStart - socEnd) / 100.0 * (CAPACITY_WH / 1000.0)`.
3. **Real gap detection** (the "make sense" step — don't flag on magnitude
   mismatch alone, confirm there's actual missing coverage): query
   `telemetry_sample` rows in `[start_ms, end_ms]` ordered by `ts_ms`, walk
   consecutive deltas, sum every delta `> 45_000` ms (three missed ticks at
   the sampler's ~15s cadence — `TelemetrySampler.java`'s own doc comment —
   comfortably past normal jitter) into `gapMs`.
4. Flag the trip only when **both** hold: `gapMs > 30_000` (at least one
   real gap) **and** `|socDeltaKwh - measuredNetKwh| / socDeltaKwh > 0.20`
   (a mismatch too large to be integration noise). Requiring both avoids
   false positives from the capacity constant's own slop on a
   gap-free trip.

This whole step is pure computation over data that already exists —
nothing written yet. Good candidate for a unit test (pure function: feed
it a list of `(ts_ms)` values + soc/measured numbers, assert the flag and
`gapMs`), same "pure logic, no DB" split this codebase already uses for
`DailyStatsProvider.aggregate()`.

## Make sense + Heal: what gets stored, and where it runs

Runs as part of `TelemetryRollup.freezeCompletedDays()`
(`drivemem/src/main/java/com/geely/drivemem/sensors/TelemetryRollup.java`)
— the existing once-daily background job that already turns raw
`telemetry_sample` rows into permanent `daily_stat` facts for completed
days. Verification only makes sense for a trip that's over, so this is the
natural home; no new job/scheduler needed.

**Schema (`CarDb.java`, `VERSION` 16 → 17, following the exact v16 idiom —
`ALTER TABLE ... ADD COLUMN`, comment above explaining why, no backfill
since nothing existed before)**:
```java
// v17: data-integrity fields for a completed trip -- gap_ms is real
// detected telemetry coverage loss (see TelemetryRollup's own
// verification pass), net_kwh_corrected is only populated when a gap was
// found AND the SoC-delta cross-check disagreed with the measured sum by
// more than the noise floor. NULL net_kwh_corrected means "trust
// net_kwh as-is" -- the common case.
if (oldVersion < 17) {
    db.execSQL("ALTER TABLE trip ADD COLUMN gap_ms INTEGER NOT NULL DEFAULT 0");
    db.execSQL("ALTER TABLE trip ADD COLUMN net_kwh_corrected REAL");
}
```
`net_kwh_corrected` nullable, `gap_ms` defaulting to 0 — every existing
trip row reads as "no gap, trust the measured number," exactly today's
behavior, until the rollup pass has actually evaluated it.

**Healing formula** — fill the hole, don't discard the whole reading:
```
missingKwh = max(0, socDeltaKwh - measuredNetKwh)
net_kwh_corrected = measuredNetKwh + missingKwh   // == socDeltaKwh, but
                                                   // written this way so
                                                   // the intent (measured
                                                   // + the gap's estimated
                                                   // share) is legible
```
This trusts the covered portion of the trip (measured) and only
substitutes for the portion telemetry never saw — not a blanket
"replace with SoC estimate," which would throw away real, good data the
dongle *did* capture outside the gap.

**Where the corrected number surfaces**:
- `DailyStatsProvider.aggregate()` / `getDayOverview()`'s trip-energy path:
  prefer `net_kwh_corrected` over the recomputed `measuredNetKwh` when
  non-null, for that trip's contribution to the day's `dischargeKwh`.
- `ChargeStatsView.rawEnergyForDay()` (the direct source of "Balanço de
  Energia"'s bars): same preference — sum `COALESCE(net_kwh_corrected,
  <measured>)` per trip instead of a flat `SUM(energy_spent_kwh)` over the
  day. This is the one place that needs a real query shape change (it
  currently sums `telemetry_sample` directly, not per-trip); the simplest
  correct version joins against `trip` for the day's trips and adds any
  `missingKwh` on top of the existing raw sum, rather than rewriting the
  whole query around `trip`.
- Raw `telemetry_sample.energy_spent_kwh` values are never modified —
  healing only ever adds a derived, clearly-separate number next to the
  measured one.

## Surface: a small badge, reusing the existing pattern

`DailyStatsView.java` already draws a small tinted `ImageView` badge next
to a session row's time label (trip badge: `ic_car`, tinted
`Style.TEXT_DIM`; charge badge: `ic_ev_plug_ccs2`/`ic_power_plug` — see the
"Line 1: Left [Badge pill + time]" sessions). A gap-affected trip gets a
second small badge in the same slot family (e.g. a tinted "!"/alert glyph,
`Style.HEAT` colored) with a short tap-to-explain string (new
`strings.xml` entry, e.g. "Dados incompletos: dongle desconectado por Xmin
— consumo estimado a partir da bateria"). No new screen, no new card type
— same visual idiom the session rows already use for trip-vs-charge.

## Files touched

- `CarDb.java` — v17 migration (above).
- `TelemetryRollup.java` — new verification/healing step inside
  `freezeCompletedDays()`, after trips for the frozen days are known;
  reuses `DailyStatsProvider.queryDrivingConsumption()` (may need to
  become non-private/package-visible if it isn't already reachable from
  this package — both classes are already in `com.geely.drivemem.sensors`,
  so likely no visibility change needed) and `ChargeSession.CAPACITY_WH`
  (needs a public accessor — it's currently `private` — e.g. `static
  double capacityWh()`).
- New small pure-logic helper (mirrors `DailyStatsProvider.aggregate()`'s
  "pure, testable" split) for the flag/gap-detection math — e.g. a
  `TripIntegrityCheck` class or a couple of package-private static methods
  on `TelemetryRollup` — whichever keeps it unit-testable without a DB.
- `DailyStatsProvider.java` — prefer `net_kwh_corrected` where trip energy
  feeds day totals.
- `ChargeStatsView.java` — `rawEnergyForDay()`'s query gains the
  `net_kwh_corrected` preference.
- `DailyStatsView.java` — new badge + `strings.xml` entry (en + pt).

## Verification plan

1. Unit test the pure gap-detection/flag/healing-formula function against
   synthetic `(ts_ms)` lists and known soc/measured pairs — no DB, no
   device, fast — following `DailyStatsProviderTest.java`'s existing
   pattern.
2. `./gradlew :drivemem:test` for the whole suite (regression check).
3. On-device: force a real gap (disable Bluetooth mid-drive, or unplug the
   OBD2 dongle for >45s while driving), let the day roll over (or trigger
   `TelemetryRollup.runIfDue()` manually via the existing `BOOT_COMPLETED`
   broadcast trick in CLAUDE.md — check whether the rollup guard needs a
   manual pref reset to re-run same-day for testing), then confirm: the
   trip's badge appears, `Balanço de Energia`'s bar for that day changed
   by the expected `missingKwh`, and `adb shell` querying `trip` directly
   shows `gap_ms`/`net_kwh_corrected` populated as expected.
4. Confirm a normal, gap-free day is completely unaffected (no badge,
   `net_kwh_corrected` stays NULL, totals unchanged) — the common case
   must be a no-op.
