# Recharge cost accounting roadmap

**Status: skipped.** Too much design complexity (FIFO/LIFO/weighted-average,
regen valuation, ledger reset policy — see "Decisions needed later" below)
for the gain: a running cost-of-energy figure, when per-session cost is
already tracked. Left in place as a record of the idea and the open
questions, in case it's revisited later, but not planned for now.

## Idea

Each charge session already stores its own cost and kWh
(`ChargeSession.Summary.cost`, `costPerKwhLabel()` in `ChargeCostDialog.java`).
Price per kWh is not constant across sessions — home, work, and public
chargers differ, and home price itself can change over time.

What is missing is turning per-session cost into a **cost of energy used**:
when the car later spends kWh driving, which recharge (or blend of recharges)
paid for that energy, and at what price. This is the same problem as
inventory costing for a warehouse, applied to kWh sitting in the battery
instead of units on a shelf.

## Intended presentation

- A running "cost of energy in the battery" figure, next to the existing
  per-session cost.
- Daily/trip statistics gain an estimated energy cost, not just kWh spent.
- The owner can see which accounting method is active and what it implies
  (e.g., "oldest cheap charge is being drawn down first").

## Candidate accounting methods to evaluate

- **FIFO** — energy is spent in the order it was charged in; the oldest
  session's price is drawn down first.
- **LIFO** — the most recent charge's price is spent first.
- **Weighted average** — one blended R$/kWh across all energy currently
  "in" the battery, recomputed on every charge.

Weighted average is simplest to keep consistent with a battery that has no
real compartments; FIFO/LIFO better match how a driver might mentally track
"tonight's cheap charge vs. that expensive public top-up" but need clear
rules for idle self-discharge and rounding.

## Discovery work

1. Confirm `ChargeSession` already has everything needed as input: session
   start/end SoC, kWh, and cost (`state/ChargeSession.java`).
2. Decide what consumes battery kWh between sessions — driving only, or also
   parked/vampire drain — since that determines what "spends" the ledger.
3. Check whether SoC-only tracking is precise enough, or whether the kWh
   accounting needs to reconcile against `energy_spent_kwh` /
   `energy_regen_kwh` already read in `Telemetry.java`.
4. Prototype all three methods against a few real days of sessions and
   compare the resulting R$/kWh trend for plausibility.

## Decisions needed later

- Which method is the default, and is it a user-visible setting or a fixed
  choice?
- How does regenerative braking (energy added without a "recharge" event)
  get costed — free, or valued at the last-known price?
- How does a charge session with no entered cost (dismissed/unset) affect
  the ledger — skipped, or estimated from a running average price?
- Does the ledger reset at some point (e.g., yearly) or run for the life of
  the car?
- Where does this show in the UI — Daily Statistics, a dedicated screen, or
  both?

## Implementation direction

Build this as a derived ledger computed from existing `ChargeSession` rows
and telemetry energy fields, not as a change to how sessions are recorded.
That keeps today's per-session cost entry untouched and lets the costing
method be swapped or recomputed later without migrating stored data.
