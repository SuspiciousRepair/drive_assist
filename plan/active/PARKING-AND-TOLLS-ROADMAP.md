# Parking Prices & Automatic Toll Passage Logging

Status: Phase 1 (parking prices, `CarDb` v38) and Phase 2 items 1, 2, 3, 5
and 6 implemented and merged into `next` (#207, 2026-11-26), plus a Parking
filter on the stats sessions and a park-location map in the price dialog.
Phase 2 item 4 (hosted tariff catalog) is DROPPED by the owner (2026-11-26):
OpenStreetMap prices plus the driver's own corrections are enough. Still
open: the real-drive checks listed at the end.

## Context

Drive Assist already records driving trips (`trip`, `trip_point`), charging
sessions with monetary cost (`charge_session.cost`, `ChargeCostDialog`),
charging location clustering (`ChargeSpots`), parked stays (`park_session`,
`DailyStatsView.buildGapRow`), Valet sessions (`valet_session`), Car Diary
TCO (`CarDiary.computeTco`), and—on `feat/imu-preview`—automatic OSM toll
passage detection (`TollMatcher`, `TollTracker`).

This roadmap tracks:
1. **What is already built on `feat/imu-preview`** (and must be preserved
   intact—do not regress any of these field-calibrated gains).
2. **Parking prices** — mall garages, lot/street parking, and Valet fees,
   integrated across Daily Stats, Valet, Trip Detail, and Car Diary TCO.
3. **Remaining optional toll & Phone Companion polish** — elevation ticks,
   manual `+ Add toll` pinning, Phone Companion `/tolls` + `/parking` CSV
   export, and optional regional tariff catalog enrichment.

---

## 1. Already Implemented on `feat/imu-preview` (Preserve)

The following toll and OpenStreetMap features are already live and tested on
`feat/imu-preview` (commits `a9d710fc` .. `c4d21e76`):

- **Database (`CarDb` v37 + `DbMerge`):**
  - `toll_booth` (`uuid`, `source`, `external_id`, `name`, `operator`, `kind`,
    `lat`, `lon`, `radius_m DEFAULT 80.0`, `bearing_deg`, `bearing_tol_deg`,
    `charge_raw`, `catalog_price`, `user_price`, `check_date`, `enabled`,
    `updated_ms`).
  - `toll_passage` (`uuid`, `trip_id`, `booth_id`, `ts_ms`, `lat`, `lon`,
    `speed_kmh`, `booth_name`, `booth_kind`, `cost`, `dismissed`, `source`) +
    `CLAIM_TOLL_PASSAGES_SQL`.
  - `toll_region` (`tile_key`, `fetched_ms`, `booth_count`) 30-day tile cache.
  - `DbMerge` (`mergeUniqueUuid`) preserving live `user_price` and `dismissed`
    flags across backup restores.
- **Pure-Java Matcher (`sensors/TollMatcher.java` + `TollMatcherTest`):**
  - Privacy-first **0.5° (~55 km) snapped Overpass tiles** (exact routes and
    home location are never sent to OSM).
  - Calibrated against real São Paulo highways (Tamoios, Ayrton Senna,
    Rodoanel, Dutra Free Flow): **80 m plaza radius** (covers express/tag
    lanes up to ~70 m from the node), **45 m Free Flow radius**, `±45°`
    direction filter, **400 m / 45 s** plaza duplicate-node dedupe, and
    **1500 m / 180 km/h** tunnel/canopy gap matching.
  - **Free Flow corridor collapse** (`10 min` / `12 km` gap): collapses
    consecutive corridor gantries into one drop-off passage both in backfill
    and at live trip end (`c4d21e76`).
  - **OSM `charge` tag parser** for `motorcar`, including **weekday vs.
    weekend schedules** (`Mo-Fr ...;Sa-Su ...`), with `user_price` always
    taking precedence.
  - Plaza name fallback chain: non-generic `name` → `note` → `ref` →
    `operator`.
- **Tracker & Offline Sync (`state/TollTracker.java`):**
  - Strictly opt-in (`Prefs.getTollsEnabled`, default `false`).
  - Resilient multi-mirror Overpass list led by Android-9-compatible
    `overpass.openstreetmap.fr`, offline catch-up backfill when parked with
    network, live scan progress attach, and `EntityBus` refresh on toll edits.
- **Car UI & Car Diary TCO:**
  - `TelemetryTollsSection` under `Config -> Integrations -> OpenStreetMap`
    unifying opt-in Map Backgrounds and Automatic Tolls.
  - `DailyStatsView`: high-contrast toll pill on `titleRow` (hidden when 0
    tolls; keeps the 4-column stat table intact).
  - `TripRouteActivity` & `RouteView`: high-contrast toll pins, **show/hide
    toll pins toggle** in header, and scrollable Tolls block in the stats
    panel.
  - `TollPassageDialog`: 960 dp 2-column dialog with a **zoomed-in local
    gantry map** (`RouteView` mini-map) on the left and cent-formatted price
    editor on the right, **no Park gate** (deliberately removed in `cef08b8e`),
    *"Remember for this booth"*, false-positive dismissal, and automatic
    Stats/Diary refresh on edit (`944fda68`).
  - `CarDiary`: `TCO_TOLL_COST_SQL` folds toll passage costs into `road` TCO
    without cluttering manual diary entries.

---

## 2. Remaining Work — Phase 1: Parking Prices (`CarDb` v38)

### Design Principles
- **Zero clutter on ordinary stays:** Parked gaps `< 15 min` stay visually
  silent unless priced; priced gaps show a compact right-aligned cost pill.
- **Consistent with `feat/imu-preview` conventions:**
  - Follow `docs/STYLE-GUIDE.md`: all monetary figures use
    `Style.moneyWithUnit` (`Style.UNIT_SCALE_ROW` / `Style.UNIT_SCALE_HERO`)
    and `Prefs.getCurrency(ctx)`.
  - Match `TollPassageDialog` / `ChargeCostDialog`'s cent-formatted numeric
    keyboard and live Stats/Diary refresh via `EntityBus`.
  - Keep `parking_cost` permanent and queried live (never frozen into
    `daily_stat`) so late edits stay 100% consistent across all period views.

### Database Schema (`CarDb` v37 → v38 + `DbMerge`)

```sql
-- Unified parking expenses (parked gaps and Valet stays).
-- Decoupled from raw park_session rows, which split on every brief shift to P.
CREATE TABLE IF NOT EXISTS parking_cost (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uuid TEXT NOT NULL UNIQUE,          -- idempotent merge key for DbMerge
  start_ms INTEGER NOT NULL,          -- start of parked window or valet_session
  end_ms INTEGER,                     -- end of parked window (nullable while live)
  valet_id INTEGER,                   -- optional FK -> valet_session.id
  cost REAL NOT NULL,
  place_name TEXT,                    -- optional label (e.g. "Shopping Iguatemi")
  kind TEXT NOT NULL DEFAULT 'lot',   -- 'lot' (car park) or 'street' (paid street parking)
  note TEXT,
  lat REAL, lon REAL,                 -- from LocationHub or CHARGE_PLACE_SQL
  updated_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_parking_cost_time ON parking_cost(start_ms, end_ms);
CREATE INDEX IF NOT EXISTS idx_parking_cost_valet ON parking_cost(valet_id);
```

- **Backup & Restore (`DbMerge`):**
  - Add `"parking_cost"` to `ID_TABLES`, register `valet_id -> valet_session`
    in `REFS`, and merge via the existing `mergeUniqueUuid` helper so live
    parking edits survive restores.

### State & Place Memory (`state/ParkingCost.java`)
- **CRUD + Event Bus:** `save(...)`, `delete(...)`, `forGap(startMs, endMs)`,
  `forValet(valetId)`, `afterTrip(tripEndMs, nextTripStartMs)`,
  `sumBetween(startMs, endMs)` — emitting `parking_cost.changed` on
  `EntityBus` so `DailyStatsView` and `CarDiaryView` refresh immediately
  (same pattern as `944fda68`).
- **Gap matching:** A `DailyStatsView` parked gap spans
  `[prevSession.endMs, nextSession.startMs]`. Match any non-Valet row whose
  `start_ms` falls within `[gapStart - 60 s, gapEnd]`.
- **Destination parking for a trip:** Match any parking cost starting within
  the immediate arrival window after `trip.end_ms` (before the next trip or
  charge, capped so an unrelated later stop never leaks onto an earlier trip).
- **Learned place memory (starts at 30 m, grows with use):** On save, attach
  `(lat, lon)` from `LocationHub` (when live) or `CarDb.CHARGE_PLACE_SQL`
  (last `trip_point` in the 24 h before `start_ms`).
  - A **spot** is all past priced stops that share one `place_name`. Each
    stop is one measured point of that spot. Spots are computed on the fly
    from `parking_cost` rows. No extra table, no stored centre or radius.
    A stop with no name gives only price chips (from stops within 30 m of
    it). It never grows a named spot.
  - A new stop matches a spot when it is within `30 m` of **any** earlier
    stop of that spot (one constant, `ParkingCost.SPOT_RADIUS_M = 30`,
    reusing `ChargeSpots.distanceM`). If more than one spot matches, the
    spot with the nearest stop wins.
  - So the area grows by itself: the first visit gives a 30 m circle. Each
    later stop at another corner of the same car park adds its own 30 m
    circle. After some visits, the spot covers the whole car park, and GPS
    drift in a garage is covered too.
  - The owner is the judge. The dialog prefills the matched `place_name`
    and offers 1-tap chips for the last 2 prices paid there. If the owner
    keeps the name, the point joins that spot. If the owner changes the
    name, the point starts or joins the other spot. Two spots with
    different names never merge, even when their stops are close.
  - Why 30 m per point and not 100 m: two car parks across one street are
    often only 40-60 m apart. A wrong prefill is worse than no prefill. A
    miss only means the field starts empty. Tune the constant after real
    stops if needed.
- **Street parking (`kind = 'street'`):** Paid street parking (for example
  Zona Azul) is not a car park. The car stops at a different place on the
  street each time, and the price is set per zone, not per place.
  - The dialog has 2 chips: `Car park` and `Street`. The default is the kind
    of the nearest earlier stop within 30 m, else `Car park`.
  - Street stops and car park stops never match each other. A street stop
    never prefills a car park name, and it never grows a car park spot.
  - Price chips for a street stop come from the last 2 street prices within
    `ParkingCost.STREET_PRICE_RADIUS_M = 300` m. One zone usually has one
    price, so a wide circle is safe here.
  - A name is optional. If the owner names a street stop (for example
    "Zona Azul Centro"), it groups with other street stops of that name,
    with the same 30 m chain rule. So a named street can grow along its
    length, like a car park does.

### UI Integration
1. **Parked gap rows (`DailyStatsView.buildGapRow`):**
   - Pass the gap's `[gapStartMs, gapEndMs]` and matched `ParkingCost` (if any).
   - Give the row a ≥ 44 dp touch target so tapping opens `ParkingCostDialog`.
   - When priced: `[P] Parked 2h 15m · <place>` on the left, `R$ 25,00` pill
     right-aligned in `Style.ACCENT` (`Style.moneyWithUnit`).
2. **Valet cards (`DailyStatsView.buildValetSessionCard`) & `ValetDetailActivity`:**
   - Fill the currently empty 4th stat column on Valet cards with `Cost`
     (`R$ 40,00` in `Style.ACCENT`, or dim `Tap to set`), aligning directly
     under the Charge card's 4th column.
   - Show and allow editing `Cost` in `ValetDetailActivity`, plus an optional
     quick price chip when stopping Valet.
3. **Trip Detail (`TripRouteActivity`):**
   - Under the existing `Tolls` block in the scrollable stats column, show a
     **Destination parking** row when parking was paid right after arrival (or
     a quiet `+ Add parking` action) plus a **Trip road costs** subtotal
     (`Tolls + Parking`, labeled as excluding energy) when either is > 0.
4. **Car Diary TCO (`CarDiary.java`):**
   - Add `TCO_PARKING_COST_SQL` (`SELECT COALESCE(SUM(cost), 0) FROM parking_cost WHERE cost > 0 AND start_ms >= ? AND start_ms <= ?`) and fold it into `road` alongside `TCO_TOLL_COST_SQL` so Car Diary's TCO reflects Energy + Road (Tolls & Parking) + Maintenance + Bureaucracy automatically.
5. **Dialog (`ui/ParkingCostDialog.java`):**
   - Reuse `ChargeCostDialog.formatCents` / `centsFromText` cent-formatted
     numeric input, optional `place_name` field (prefilled from learned spot
     memory), `Car park` / `Street` chips, recent price chips for that spot
     (or for street stops within 300 m), and `Save` / `Clear` / `Cancel`.
6. **Locales:** Add strings across all 4 locales (`values/`, `values-pt/`,
   `values-es/`, `values-th/`).

---

## 3. Remaining Work — Phase 2: Toll & Phone Companion Polish

1. **`ElevationView` toll ticks:** Draw subtle vertical markers at each toll
   passage's cumulative km along the height profile so drivers can correlate
   plazas / Free Flow gantries with speed/elevation changes.
2. **Manual `+ Add toll` pinning in `TripRouteActivity`:** Let the driver pin
   a missed toll or brand-new Free Flow gantry directly on the current route
   marker, creating both a `toll_passage` and a `source = 'user'` `toll_booth`.
3. **Phone Companion (`/tolls` and `/costs` in `PhoneServer` / `PhonePages`):**
   - Browse/edit `toll_booth` `user_price`, direction, and `enabled` flags
     with a phone keyboard.
   - Review/edit parking prices and place names.
   - Export `/tolls.csv` and `/parking.csv` for reimbursement / expense logs.
4. **~~Optional Hosted Regional Tariff Catalog~~ (dropped by the owner, 2026-11-26: OpenStreetMap plus the driver's corrections are enough):**
   - Offline generator script (`tools/build-toll-catalog.py`) joining OSM
     booths with official open data (Brazil ANTT `dados.antt.gov.br` + ARTESP
     `dadosabertos.artesp.sp.gov.br` / Siga Fácil) into a compact static JSON
     feed (~40 KB gzipped).
   - Sync updates `catalog_price` while **never overwriting** `user_price` or
     `enabled = 0`.
5. **Home Parked card `+ Parking` shortcut (`ComfortActivity`):**
   - Optional quick action while parked to enter a mall/lot fee right after
     paying at the kiosk before shifting to `D`.
6. **Documentation:** Update `docs/DRIVER-GUIDE.md` (and `docs/HUB-DATA.md`
   if any new hub key is introduced) in the implementing PRs.

---

## Verification Checklist
- `./gradlew test` — unit tests for `ParkingCost` (gap matching, Valet lookup,
  destination-parking window, learned spots: a stop 25 m from
  one earlier stop matches, a stop 45 m from all stops does not, a spot
  with stops at both ends of a 100 m car park matches anywhere between
  them, a renamed stop never joins the old name's spot, a street stop never
  matches a car park spot 10 m away, and a street stop gets the price of
  another street stop 250 m away), `CarDiary` TCO with tolls +
  parking, `DbMerge` round-trip (preserving `toll_booth`, `toll_passage`, and
  `parking_cost` live overrides), and existing `TollMatcherTest`.
- `./gradlew :drivemem:assembleRelease` — clean release build.
- Visual check at 1920×1080 (LHD + RHD, light + dark themes) confirming
  `Style.moneyWithUnit` formatting, ≥ 44 dp touch targets, and zero regressions
  to `feat/imu-preview`'s toll badges, gantry mini-map dialog, and Free Flow
  corridor collapsing.

## Still to check on a real drive (2026-11-26)
- Install on the owner car migrated `car.db` 37 → 38 with no error; a test
  parking price was saved, shown on the stats page and the trip screen, then
  cleared. Not yet seen: a Valet stay with a cost, the "+ R$" on the Parked
  card after a real paid stop, the place memory across two real visits, a
  manual toll matched on a later trip, and the phone pages.
