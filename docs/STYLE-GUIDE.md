# UI Style Guide

Decisions made while working through the recharge stats screen on
2026-09-13, generalized so the next screen doesn't have to re-derive them
or (worse) invent a fourth inconsistent version. Each rule names the
actual class/method to use — if you're about to write a one-off that does
the same thing differently, that's the signal to stop and reuse instead.

## Numbers and units

**A unit is always smaller and dimmer than the value it's attached to.**
Never build it as one plain concatenated string (`"20.3 kWh"` as a single
`TextView.setText`) — that renders the unit at the same size and weight as
the number, which reads as "these two things matter equally" when they
don't.

Use `Style.valueWithUnit(number, numberColor, unit, unitScale)`:
- `numberColor`: the color the value itself should render in (`null` to
  inherit whatever the TextView's own color is set to).
- `unitScale`: `Style.UNIT_SCALE_HERO` (0.40) for a large standalone
  figure (a card's headline number), `Style.UNIT_SCALE_ROW` (0.68) for a
  smaller inline one (a session-list row, a live status line).

A currency symbol as a *prefix* ("R$ 12,15") is not yet covered by this
helper (it's suffix-only today) and hasn't been touched — worth the same
treatment if this comes up again, but out of scope for this pass.

## Charging: AC vs DC

**Color and icon alone carry the meaning. No "AC"/"DC" text on a session
card** — the one place that still needs it is a legend where two colors
sit side by side with nothing else to tell them apart
(`ChargeStatsView.legend()`, next to the battery-range chart).

| | Color | Icon |
|---|---|---|
| AC | `Style.ACCENT` (blue) | `R.drawable.ic_power_plug` |
| DC (fast charge) | `Style.GOOD` (green) | `R.drawable.ic_ev_plug_ccs2` |

`Style.GOOD` (`0xFF43A047`) was added for this — before it existed, three
different files had independently invented three different, mutually
inconsistent schemes (blue/orange, green/orange, blue/purple). Test with
`ChargeSession.Summary.isDcfc()`; when charge voltage isn't known at all,
fall back to `Style.TEXT_DIM` rather than guessing AC or DC.

## Card spacing

**The gap between two cards in a list should be at least as large as the
padding inside one card.** A gap smaller than the internal padding reads
as "these cards are crowded together" even though each one individually
looks fine — the eye compares whitespace-between to whitespace-within,
not either in isolation.

**Use `Style.CARD_GAP_DP` (16) for the gap between stacked cards and
`Style.COLUMN_GAP_DP` (24) between columns of cards — not a fresh literal.**
Before these existed, every screen picked its own number (12/14/16/20dp all
appeared for the same conceptual gap), which is what read as *erratic*
rather than merely "two deliberately different values" (found live on the
Home screen, 2026-09-13). `ChargeStatsView.chargeRow`'s 14dp internal
padding is still its own number, not yet folded into a shared constant —
internal padding and inter-card gap are different concerns, only the
latter is standardized so far. Not yet backfilled everywhere —
DailyStatsView's session cards predate this and are a known follow-up, not
a contradiction of it.

## Column-flow card layouts

A dashboard of differently-sized, differently-important cards (Home
screen's Clima/Portão/Turbo/etc.) should flow into columns like HTML
multi-column text — fill a column top to bottom in priority order, start a
new one when the next card wouldn't fit, rather than clipping or leaving a
gap. **Use `Style.packIntoColumns(context, columns, cards, columnWidthPx,
availableHeightPx)`**, not a one-off packing loop — it's re-entrant (call
it again whenever a card's visibility or size might have changed) and
self-healing (a card that grows *after* being packed, e.g. new data
arriving, no longer requires whoever changed it to remember to repack —
one verify pass catches it automatically). See `ComfortActivity
.repackColumns()` for the calling pattern: it owns only the
screen-specific part (reading the real measured column width/height and
retrying until that's available), and hands the actual packing to the
shared function.

## Icon-only touch targets

A small glyph still needs a real touch target. Size the tappable
`LayoutParams` for a hand (≥40dp, 44dp where there's room), and let padding
inside that box control how large the glyph itself looks — the box and the
icon are two different numbers. See `ChargeStatsView.chargeRow`'s curve
icon (44dp box, 24dp glyph) or `ComfortActivity`'s journey-card dismiss ×
(48dp box) for the pattern. This isn't new — CLAUDE.md's own section on
this panel's display already makes the general case (a 44dp distance is
~6mm of glass, ordinary at phone density but easy to under-size at 1:1);
this is that same rule applied specifically to icon buttons.

## In-bar labels

When a bar chart's bar represents a *span* (a time range, a SoC range),
consider drawing the span's own duration inside the bar rather than only
as a separate label. Two existing implementations of this exact idea:
`Style.chargeRangeBar()` (draws into a Bitmap, used by the older
TelemetryActivity charge history) and `ChargeSocChart.Plot.onDraw()`
(draws directly to a Canvas). If a third one is ever needed, extract a
shared helper instead of writing a fourth version of the same math.
