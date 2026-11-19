# Car Diary — maintenance event log with due warnings

## Context

Drive Assist already tracks charging sessions (`ChargeSession`), trips, and
daily stats in `CarDb` (`car.db`, currently schema version 9). There is no
place to record real-world maintenance — tire changes, brake fluid flushes,
12V battery replacement, etc. — or to see when one of those is coming due.

This plan adds a new **Car Diary** section, modeled directly on the existing
`ChargeSession`/charge-history code path (same DB-table-plus-state-class
pattern, same `TelemetryActivity` sidebar-section pattern, same
`ChargeCostDialog`-style input popup).

Decisions already made with the user:
- Scope is **log + due warnings** (not a bare log).
- Due intervals are **fixed in code**, not user-editable (can be revisited
  later if it turns out to matter).

## Tracked maintenance types

The car is an EV (see `ChargeSession`, SOC-based telemetry), so "oil change"
doesn't apply. Types, each with a default interval expressed as km and/or
months (whichever limit is hit first triggers the warning; a null limit on
either axis means that axis is not checked):

| Type | km interval | month interval |
|---|---|---|
| Tires | 40,000 km | — |
| Brake pads | 30,000 km | — |
| Cabin air filter | 20,000 km | 12 |
| Brake fluid | — | 24 |
| 12V battery | — | 48 |
| Coolant | — | 60 |
| Wipers | — | 12 |
| Other (free text) | — | — (never warns) |

These live as constants in the new `MaintenanceLog` class, not in a
database table — same "fixed in code" reasoning `Modes.java` already uses
for drive-mode constants elsewhere in this codebase.

## 1. Database: `CarDb.java`

Bump `VERSION` 9 → 10.

In `onCreate`, add:
```sql
CREATE TABLE maintenance_event (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts_ms INTEGER NOT NULL,
  type TEXT NOT NULL,
  odo_km REAL,
  note TEXT,
  cost REAL
)
CREATE INDEX idx_maint_ts ON maintenance_event(ts_ms)
```

In `onUpgrade`, add an `if (oldVersion < 10)` block that runs the same
`CREATE TABLE IF NOT EXISTS` — safe on an existing database since it's a
brand new table, no ALTER/backfill needed (same shape as the v4→v5 `daily_odo`
addition).

## 2. New state class: `state/MaintenanceLog.java`

Mirrors `ChargeSession.java`'s shape:

- `enum Type { TIRES, BRAKE_PADS, CABIN_FILTER, BRAKE_FLUID, BATTERY_12V, COOLANT, WIPERS, OTHER }`
  with a `displayNameRes()` helper mapping to the string resources below.
- `static final class Entry { long id; long tsMs; Type type; Double odoKm; String note; Double cost; }`
- `static void insert(Context ctx, Type type, Double odoKm, String note, Double cost)`
  — writes via `CarDb.get(ctx).write(...)`, same single-writer-thread pattern
  as everything else in `CarDb`.
- `static List<Entry> readLog(Context ctx)` — oldest-first, same convention
  as `ChargeSession.readLog` / `OdoStats.readLog`.
- `static void delete(Context ctx, long id)`.
- `static final class DueStatus { Type type; Entry lastEntry /* nullable */;
  Double kmSinceLast; Integer monthsSinceLast; boolean overdue; boolean dueSoon; }`
- `static List<DueStatus> dueStatuses(Context ctx)` — one entry per tracked
  type (excludes `OTHER`), computed from the latest `maintenance_event` row
  of that type (if any) plus the current odometer (`OdoStats` latest reading)
  and current wall time. "Due soon" = within 10% of the limit; "overdue" =
  past it. A type with no prior entry is never flagged (nothing to compare
  against) — it just shows as untracked.

## 3. UI: `ui/TelemetryActivity.java`

- Add `SEC_DIARY` to the section-index block, appended after the last
  existing constant (`SEC_SYSTEM`) per the file's own "appended, never
  renumbered" rule for the `section` intent-extra.
- Add `nav.addView(navItem(getString(R.string.cfg_nav_diary), SEC_DIARY));`
  to the **CAR** nav group (alongside Drive/Doors/Clips) — a maintenance log
  is a car-facing concern, not a display/integration one.
- Add the `case SEC_DIARY: buildDiary(); break;` arm in `selectSection`.
- New `buildDiary()` method:
  - Header ("Car Diary").
  - A row of warning cards from `MaintenanceLog.dueStatuses()` — only shows
    cards that are `dueSoon` or `overdue`, styled like the existing
    `statTile`/`ltmCard` treatment (accent-outlined for overdue).
  - An "Add event" button opening the new `MaintenanceEventDialog`.
  - The full log, newest-first, each row showing type, date, odometer, note,
    and cost if present — same `chargeRow`-style card, with a delete button
    (confirm dialog, same pattern as `clipRow`'s delete confirmation).

## 4. New dialog: `ui/MaintenanceEventDialog.java`

Mirrors `ChargeCostDialog.java`:
- Title + a row of type-chip buttons (`Style.cardButton`, one per `Type`,
  selected one highlighted).
- Odometer field, prefilled from the latest known reading
  (`OdoStats.readLog` last entry, or live odometer if `CarDataHub` has one),
  editable.
- Note field (free text, optional).
- Cost field (optional, same numeric-input handling as `ChargeCostDialog`).
- Cancel / Save buttons; Save calls `MaintenanceLog.insert(...)`, dismisses,
  and refreshes the Diary section via the same `onUpdated` callback pattern
  `ChargeCostDialog` already uses.

## 5. Strings

Add to all three files (`values/strings.xml`, `values-pt/strings.xml`,
`values-pt-rPT/strings.xml`):

`cfg_nav_diary`, `diary_title`, `diary_add_btn`, `diary_none`,
`diary_type_tires`, `diary_type_brake_pads`, `diary_type_cabin_filter`,
`diary_type_brake_fluid`, `diary_type_battery_12v`, `diary_type_coolant`,
`diary_type_wipers`, `diary_type_other`, `diary_due_overdue`,
`diary_due_soon`, `diary_field_odo`, `diary_field_note`, `diary_field_cost`,
`diary_event_dialog_title`, `diary_btn_save`, `diary_delete`,
`diary_delete_q`, `diary_last_at` (e.g. "Last: %s • %.0f km").

## 6. Test: `test/java/com/geely/drivemem/MaintenanceLogTest.java`

Mirrors `ChargeSessionTest.java`'s structure:
- Insert-then-read round trip for a few entries across different types.
- `dueStatuses()` correctness: given a fake "now" and a fake current
  odometer, assert overdue/dueSoon flags come out right for both a
  km-limited type and a month-limited type, and that a type with no prior
  entry is never flagged.

## Order of work

1. `CarDb` schema bump + migration (v10).
2. `MaintenanceLog.java` (model + due-status logic) + its unit test.
3. Strings (all three locales).
4. `MaintenanceEventDialog.java`.
5. `TelemetryActivity` wiring (`SEC_DIARY`, nav entry, `buildDiary()`).
6. Manual check on device/emulator: add an event, confirm it lists, confirm
   an overdue warning shows when the fake interval is exceeded.

Not covered by this plan (raise if actually wanted): editing an existing
entry (only add + delete), CSV/USB export of the diary (charge history has
one via `UsbExport`; diary could reuse it later), user-configurable
intervals.
