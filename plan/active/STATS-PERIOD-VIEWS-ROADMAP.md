# Weekly / monthly stats views roadmap

**Status: not deferred — designed, just not urgent.** The design below is
decided. It's low priority only because nobody has been running the app
long enough yet for weeks/months of accumulated data to be worth
aggregating — not because anything here is unresolved.

## Idea

The stats page (`DailyStatsView.java`, backed by `DailyStatsProvider.java`)
only shows one calendar day at a time: a bar chart of km and a chronological
log of trips and charges for that day. Add a **weekly** and **monthly** view
alongside the existing daily one, so the owner can see totals and trends over
a longer period without stepping through each day.

## Intended presentation

**UI decided:** right under the existing top chart, a row split into two
columns:

- **Left column** — a date navigator: "Anterior" (Previous) / the current
  date (or date range) / "Próximo" (Next), for paging backward and
  forward through periods one at a time.
- **Right column, aligned right** — Day/Week/Month view buttons (a
  segmented control, same visual style as existing ones like the
  Tarde/Médio/Cedo or AVAS tune picker).

Switching Day/Week/Month:
- changes the chart's x-axis granularity (days, weeks, or months), and
- recalculates every metric on the page for that period — not just the
  chart, the whole page's numbers rescale to match the selected
  granularity.

The existing per-day bar chart becomes one bar per day (week view) or one
bar per week/day (month view), matching the "one column per period" shape
`DayItem` already uses for daily km. Tapping a bar in Week/Month drops back
into that day's existing daily view.

## Discovery work — resolved 2026-09-13

1. **Is `DailyStatsProvider` a loop-friendly per-day function?** Yes for the
   full page (`getDayOverview(ctx, dateStr)` already computes one day
   end-to-end, correctly folding in `daily_stat` for frozen past days and
   live telemetry/active-trip state for today). No for the existing 14-day
   *chart* — that path already runs its own combined SQL over a date range
   rather than calling `getDayOverview` per day, for exactly the performance
   reason discovery item 3 asks about. Conclusion: two different techniques
   for two different needs (see Execution plan).
2. **Week/month boundary: calendar, not rolling.** Sunday–Saturday weeks,
   calendar months. Fixed boundaries are what make "Anterior/Próximo" a
   well-defined single step and what make "this week" mean the same thing
   every time the screen reopens — a rolling window doesn't page cleanly.
3. **Cost of a month view (~31 `getDayOverview` calls):** not measured on
   device yet, but every one of those calls is already fast enough for
   today's interactive single-day paging; 31 of them for one on-demand
   screen render is very unlikely to be the bottleneck. Left as a real
   measurement to do once built, not a blocker to starting.
4. **Chronological log in Week/Month:** collapsed into daily rows (a day
   header, that day's trips/charges nested under it), not one flat list —
   a month of ungrouped trips would be an unreadable wall, and not hidden
   either, since seeing which days had activity is the point of the view.

## Decisions needed later — resolved 2026-09-13

- **Real aggregate, not just a wider chart.** Every `DayOverview` field gets
  summed (or correctly recomputed — see Execution plan for `avgSpeedKmh`)
  across the period, matching this doc's own "Intended presentation"
  section above ("recalculates every metric on the page"). The chart
  additionally becomes one bar per day (Week) or day/week (Month).
- **Default landing view stays Day.** Unchanged current behavior; this
  feature is additive.
- **Recharge cost:** `daily_stat.charge_cost` already exists in the schema
  and is already populated by `TelemetryRollup` on freeze — confirmed live
  in code, not hypothetical. It's just never been read into `DayOverview`
  yet. Wiring it in is a small, independent addition, and is NOT blocked on
  `RECHARGE-COST-ACCOUNTING-ROADMAP.md` (deferred) — that plan is about
  live cost *entry* during an active charge, a different concern from
  summing an already-frozen historical figure.
- **How far back Month can scroll: unlimited.** `daily_stat` rows are never
  pruned, so a period's *totals* stay exact forever. The caveat: a period
  whose underlying `telemetry_sample` rows have aged past 90 days keeps
  correct totals but loses its trip/charge-level detail (that needs raw
  samples). The Month view's session log should show a "detail unavailable
  — older than 90 days" state for those days rather than an empty list.
  This is the same territory a separate ad hoc note
  (`docs/plan/long-term-driving-stats.md`, written before this roadmap
  resurfaced) was covering — that note is superseded by this section and
  should be retired once this ships.

## Implementation direction

Prefer building Week/Month as an aggregation layer on top of the existing
per-day `DailyStatsProvider` queries rather than new SQL paths, so daily
data stays the single source of truth and no stored data changes shape.

## Execution plan

**1. `sensors/DailyStatsProvider.java`**
- New `PeriodOverview` type: the same fields as `DayOverview` (so rendering
  code barely has to branch) plus `List<DayOverview> days` — the daily
  breakdown backing the collapsed-by-day session log.
- New pure, testable `static PeriodOverview aggregate(List<DayOverview> days, String label)`:
  sums distance/energy/charge fields directly; recomputes `avgSpeedKmh` as
  `sum(distanceKm) / sum(drivingMinutes/60)` (an average of daily averages
  would be wrong — a short slow day and a long fast day don't weigh the
  same); takes min/max across days for battery and altitude; computes
  `efficiencyKwh100km` via the already-shared
  `DrivingConsumption.efficiencyKwh100km(sumDistance, sumDischarge, sumRegen)`
  — no new formula. `avgTempC` as a plain mean of the daily averages is an
  accepted, documented approximation (weighting by sample count isn't
  worth the complexity here).
- `static PeriodOverview getWeekOverview(Context ctx, String anyDateInWeek)`
  / `getMonthOverview(...)`: resolve the calendar range, call
  `getDayOverview` once per date, pass the list to `aggregate()`.
- Wire `daily_stat.charge_cost` into `DayOverview` (new field, read same as
  the other daily_stat columns; live-refine for today the same way
  `chargeKwh` already is, if a live equivalent exists).
- Extend the existing 14-day chart query with a `granularity` (day/week/month)
  parameter, following its current combined-SQL pattern rather than calling
  `getDayOverview` in a loop for the chart specifically (that path already
  proved out at daily granularity; reuse it, don't replace it).

**2. `ui/DailyStatsView.java`**
- Add the decided UI under the top chart: Anterior / period label / Próximo
  on the left, a Day/Week/Month segmented control on the right (same visual
  language as the existing Tarde/Médio/Cedo-style pickers).
- Switching granularity re-renders the existing hero/stat-row/session-list
  views against a `PeriodOverview` instead of a `DayOverview` — since the
  fields line up, this should mostly be widening a parameter type, not
  duplicating render methods.
- Week/Month session list: one header per day (date + that day's distance),
  its trips/charges nested underneath using the existing session-card
  rendering unchanged.
- A day inside the period whose `telemetry_sample` has been pruned (no raw
  rows, but a `daily_stat` row exists) renders as a header with its totals
  and a "detalhes indisponíveis — mais de 90 dias" line instead of an empty
  session list.

**3. Test: `DailyStatsProviderTest.java` (new)**
- `aggregate()` over a handful of hand-built `DayOverview` instances:
  correct sums, correct `avgSpeedKmh` recomputation (not an average of
  averages), and `efficiencyKwh100km` matching a direct
  `DrivingConsumption.efficiencyKwh100km` call on the same summed totals —
  proving this doesn't become a sixth copy of that formula.

## Order of work

1. `PeriodOverview` + `aggregate()` + week/month date-range resolution +
   unit tests. No UI, no schema change — safe to land alone.
2. Wire `daily_stat.charge_cost` into `DayOverview` (independent, small).
3. Extend the top chart's data query for week/month bucketing.
4. `DailyStatsView`: navigator + segmented control, re-render against
   `PeriodOverview`.
5. Grouped-by-day session list, including the "older than 90 days" state.
6. Manual check: a Week's totals should match manually summing that week's
   7 individual daily-view numbers today.
