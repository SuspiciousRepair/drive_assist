# Long-term driving stats from frozen daily totals

## Context

Raw `telemetry_sample` rows are pruned after 90 days (see `TelemetryRollup`
and `CarDb`'s own comments). Before a day's raw rows are pruned, its totals
are frozen once into one `daily_stat` row — permanent, never recomputed,
designed specifically to survive past that 90-day window (see `CarDb`'s
`createDailyStat` comment).

Today, `daily_stat` is only read one day at a time (`DailyStatsProvider`'s
day-overview lookup, `OdoStats.readLog` for the odometer trend). There is no
view that looks across many `daily_stat` rows at once — so driving history
older than 90 days is technically preserved but has no screen showing it.

This plan adds a **Trends** view: monthly/yearly rollups built entirely from
`daily_stat`, so it works forever, independent of the 90-day raw-data window.

## Important limitation, stated up front

`daily_stat` is day-level only: total distance, total energy, charge count,
ascent/descent, etc. for that whole day. **No per-trip detail survives past
90 days** — that needs raw `telemetry_sample`, which is gone. This view
answers "how did October compare to September," not "show me every trip
from October." That distinction should be visible in the UI itself (e.g. a
note under any month older than ~90 days), not just in this doc.

## What `daily_stat` already has to build this from

Columns (from `CarDb.createDailyStat`): `date`, `first_odo_km`/`last_odo_km`,
`first/last/min/max_battery_pct`, `avg_speed_kmh`/`max_speed_kmh`,
`min/avg/max_temp_c`, `ascent_m`/`descent_m`, `trip_count`,
`driving_minutes`, `charge_count`, `charge_kwh`, `charge_cost`,
`discharge_kwh`, `regen_kwh`, `net_kwh`.

Distance per day = `last_odo_km - first_odo_km`. Efficiency per day is not
stored directly — recompute it the same way `DailyStatsProvider` does for a
single day, via the shared `DrivingConsumption.efficiencyKwh100km(distanceKm,
dischargeKwh, regenKwh)` (see the 2026-09-13 consolidation commit) — a
month's efficiency is the same formula fed monthly sums, not a new formula.

## 1. New query layer: `sensors/LongTermStats.java`

Mirrors `OdoStats`'s shape (a thin, testable layer over `daily_stat`, no UI
code):

- `MonthSummary`: year, month, `distanceKm`, `dischargeKwh`, `regenKwh`,
  `netKwh`, `efficiencyKwh100km` (via the shared formula), `chargeKwh`,
  `chargeCost`, `tripCount`, `drivingMinutes`, `ascentM`, `descentM`.
- `static List<MonthSummary> monthlySummaries(Context ctx, int months)` —
  `SELECT date, ... FROM daily_stat ORDER BY date ASC`, grouped by
  calendar month in Java (same "query the DB, aggregate in a pure function"
  split `OdoStats` already uses, so the aggregation itself stays unit
  -testable without a database).
- `static MonthSummary aggregate(List<DailyStatRow> days)` — the pure,
  testable grouping/summing function, given a already-fetched list of rows
  (mirrors `OdoStats.kmSince(List<Reading>, ...)`'s testability split).
- `static LifetimeSummary lifetime(Context ctx)` — `SELECT SUM(...), MIN(date),
  MAX(date) FROM daily_stat` for an all-time total distance/energy/charge
  card, plus how many days of history exist.

## 2. UI: a new `SEC_TRENDS` (or a tab inside the existing Daily Stats screen)

Given `DailyStatsView` is already a dedicated screen (not a `TelemetryActivity`
section), the natural home is a **second tab/mode inside `DailyStatsView`**
rather than a new top-level nav entry — "Day" (today's existing view) /
"Trends" (new), a simple two-way toggle at the top, same idea as
`TelemetryActivity`'s charge-period 30d/365d toggle.

Trends view contents:
- A bar chart of the last N months' distance (or efficiency — toggle),
  reusing the existing `com.github.mikephil.charting` dependency already in
  this file, same library/styling as the current 14-day chart.
- A row of lifetime totals (distance, energy, charge cost) using
  `Style.valueWithUnit` for consistent number/unit styling (per the
  2026-09-13 consolidation — this view should use it from day one, not add
  a fifth ad hoc copy).
- Each month is a row like a session card (`Style.card`/`Style.tile`),
  showing distance, efficiency, trip count — tapping one could jump to that
  month's first in-range day in the existing daily view (nice-to-have, not
  required for v1).

## 3. Test: `LongTermStatsTest.java`

Mirrors `OdoStatsTest.java`: build a small in-memory `List<DailyStatRow>`
spanning a couple of months, assert `aggregate()` sums correctly, and that
`efficiencyKwh100km` on the aggregate matches calling the shared
`DrivingConsumption` formula directly on the summed totals (i.e. this
doesn't reintroduce a fifth calculation).

## Order of work

1. `LongTermStats.java` (query + pure aggregation) + its unit test.
2. Wire the "Day / Trends" toggle into `DailyStatsView`.
3. Bar chart + lifetime totals + month rows, styled with `Style.valueWithUnit`.
4. Manual check: confirm a month total roughly matches manually summing that
   month's daily figures from the existing single-day view.

Not covered by this plan (raise if actually wanted): exporting long-term
data (CSV/USB, `UsbExport` already exists for the raw DB and could extend to
this), per-month cost-per-km, comparing this vehicle's efficiency across
seasons/temperature bands.
