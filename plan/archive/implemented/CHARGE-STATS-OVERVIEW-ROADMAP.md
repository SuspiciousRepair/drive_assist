# Recharge stats page upgrade roadmap

## Idea

`ChargeStatsView.java` lists every recharge session as one full-size card
(`chargeRow()`): title row, a bar, and an info row with a cost button.
Over many sessions this list gets long and each entry takes real screen
space to say relatively little. Replace the pure list with a two-column
layout: a compact, readable list of sessions on the left, and a SoC bar
chart on the right that shows the shape of every recharge at a glance.

## Intended presentation

Two columns, not a chart-above-list stack:

- **Left column**: the list of recharge events, one per row, sized with a
  proper (not cramped) font — showing the information that actually
  matters per session (date/time, SoC range, kWh, AC/DCFC, cost), not
  necessarily everything `chargeRow()` shows today.
- **Right side**: a bar chart, one bar per recharge session, **sorted by
  start datetime** (chronological, not grouped by day the way
  `DailyStatsProvider`'s `DayItem` bars are).
- **Each bar is stacked in two segments, not a plain height:**
  - Bottom segment, spanning `0` to `socStart`: transparent / matches the
    chart background. This is the "empty space below the charge," not
    part of the visual — it exists so every bar's *top* still lines up
    with the 0-100% axis.
  - Top segment, spanning `socStart` to `socEnd`: actually colored. This
    is the real charge — its height is the SoC gained, and its vertical
    position on the bar shows where in the battery's range it happened
    (e.g. a 20%→80% charge sits differently than a 70%→90% one, even
    though both gained visibly different amounts).
- **Color encodes AC vs. DCFC**, not amount — two colors, one per charging
  type, so the owner can see at a glance which sessions were fast-charges
  vs. home/slow charges without reading numbers. **By voltage, not power.**
  `ChargeStatsView.chargeRow()` already derives a type today
  (`isDcfc = s.avgPowerW >= 22000`, `DCFC_W_THRESHOLD`) and colors by it
  (`Style.HEAT` for DCFC, `Style.COOL`/`Style.ACCENT` for AC) — reuse that
  same color palette, but **not that threshold**: `docs/field-catalog.md`'s
  own "Fast Charging (DCFC) Detection Logic" documents pack voltage as the
  correct discriminator (AC: ≤32A, ~220–245V; DCFC: ≥250V, typically
  380–415V) specifically *because* current tapers at high SoC while
  voltage stays reliable — a power-based average doesn't have that
  property and can misclassify a tapered DCFC session. This chart should
  use voltage, which likely means fixing the existing card's
  classification too, not just avoiding repeating its shortcut in new
  code.
- **Selection is bidirectional**: clicking a bar highlights its row in the
  left-column list, and clicking a row in the list highlights its bar in
  the chart — not a one-way "tap the chart to jump to the list."

## Discovery work

1. **Voltage is read live but never persisted per session — expected to be
   an easy, natural addition, not a blocker.** `ChargeSession.onTelemetryTick()`
   already reads `charge_v` every tick (used today only to integrate
   `whAccum += a * v * hours`), so no new car-property access is needed —
   just track a running peak alongside the existing `whAccum` accumulator
   and carry it into `Summary` and the DB row, the same shape as every
   other per-session field already there. Peak (not average) matches the
   documented "380-415V" DCFC range even as current tapers near full.
2. Review `chargeRow()` (`ChargeStatsView.java` ~line 300) to decide which
   fields move into the new left-column row vs. get dropped now that the
   chart carries the SoC-range/type information visually.
3. Check whether `EnergyBalanceChart` can be reused/extended for this bar
   shape (stacked, transparent-below-socStart), or whether this needs its
   own chart class — the "invisible bottom segment" shape doesn't match
   `DailyStatsProvider`'s plain-height `DayItem` bars, so reuse is
   unlikely to be direct.
4. Confirm how many sessions typically exist per period so the chronological
   bar chart stays legible (may need horizontal scroll for `periodDays =
   30`+, same concern as before, now sharper since bars are ordered by
   exact timestamp rather than bucketed by day).
5. Design the bidirectional selection state (bar ↔ row) — likely a single
   "selected session id" field the view holds, with both the chart and the
   list re-rendering their highlight from it, rather than two separate
   selection paths that have to be kept in sync by hand.

## Decisions needed later

- What does the left-column row show — date/time, SoC range, kWh, cost,
  AC/DCFC, cost/kWh — and in what priority when space is tight?
- Should the overview respect the same FIFO/LIFO/average costing once
  `RECHARGE-COST-ACCOUNTING-ROADMAP.md` lands, or stay on raw per-session
  cost until then?
- Does this page gain the same Day/Week/Month period switch being
  considered in `STATS-PERIOD-VIEWS-ROADMAP.md`, or keep its own
  `periodDays` selector?

## Implementation direction

Two small, sequential steps, not one big one: (1) start persisting peak
charge voltage in `ChargeSession` (easy — same shape as the existing
`whAccum` tracking) and switch classification everywhere — including
today's `chargeRow()` badge — from `avgPowerW >= 22000` to the voltage
threshold; (2) then build the two-column layout (list left, chart right)
on top of the now-correct classification, together rather than as a
bolt-on above the old list, since the design assumes both are visible at
once and share one selection state from the start.
