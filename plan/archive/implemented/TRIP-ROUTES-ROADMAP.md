# Trip details, routes and per-mode efficiency, no map API (issue #19)

> **Implemented** (v0.8.0 and v0.9.0). Kept for the reasoning.

Status (2026-11-17): phases 0, 1 and 2 merged into `next` (#51, #62, #64,
#65); #19 closed. Charge places on the route maps: #101, #103. Phase 3
(GPX and CSV export, USB and phone): `feat/gpx-export`, for #82.

## Goal

Tap a trip and see where it went, how the road climbed, how fast each part
was, and the trip's key numbers. Tap a charge and see where it was. Export
routes for any map app. No map API, no user API key, no internet needed in
the car.

## What exists today

- The car has a GPS fix. `GpsReader` reads it for the Home Assistant location,
  ABRP, `telemetry_sample.altitude_m` and the dashcam subtitles' heading.
- **Latitude and longitude are never saved.** `telemetry_sample` is one row
  every 30 s (`CarActor.TICK_INTERVAL_MS`), too coarse for a route anyway.
- `trip` already keeps `ascent_m` / `descent_m` (GPS altitude, noise band).
- Old trips will never have a route. Routes start with the build that records
  them.

## Phase 0 — record drive mode and regen level (efficiency statistics)

Owner's request (2026-11-15): keep drive mode and regen level in the database
so efficiency can be compared per mode. Today both are hub keys
(`car.drive_mode`, `car.regen_mode`, on change) but **nothing stores them**.
Independent of GPS, small, and every day without it is data lost, so it can
ship first.

Status: built (DB v27, PR #51), then changed in phase 1.

- `telemetry_sample` gets `drive_mode` and `regen_mode` (the 30 s rows), read
  from the hub cache.
- **Owner decision (2026-11-15): keep raw data, analyse later.** v27 also
  wrote a `trip_mode_segment` summary table (the trip cut into parts per
  mode). DB v28 drops it. The modes ride on each route point instead
  (`trip_point.drive_mode` / `regen_mode`, every 5 s, kept for good), next to
  the trip's energy so far, so per-mode numbers are computed when needed.
- A Week/Month "by mode" view is a later step; the data comes first.

## Phase 1 — record positions

- **Hub first** (CLAUDE.md rule 7): a new hub key `location.fix`
  (lat, lon, altitude, speed, bearing, accuracy) fed from `GpsReader`, every
  2 s, quantized to about 5 m so a parked car does not republish. Add it to
  `docs/HUB-DATA.md`. The existing direct readers (MQTT, ABRP, sampler, trip
  ascent) move to the hub key.
- New table `trip_point (trip_id, ts_ms, lat, lon, alt_m, speed_kmh, bearing,
  accuracy_m, drive_mode, regen_mode, power_kw, spent_kwh, regen_kwh)`,
  filled by `state.RoutePoints` from the hub while a trip is open:
  - one point every 5 s, skipped when the car moved less than ~25 m;
  - `speed_kmh` is GPS speed: the car's own speed is only in the hub every
    30 s (faster only while the home screen polls it);
  - `spent_kwh` / `regen_kwh` are the trip's totals so far
    (`EnergyIntegrator`), so the energy of any stretch is a subtraction;
  - written with `trip_id` NULL, claimed when the trip row is saved, so an
    app restart mid-drive loses nothing; a trip too short to keep drops them;
  - about 60 KB per hour of driving.
- Start and end of a trip are its first and last point (no extra columns).
  `charge_session` gets `lat/lon`.
- Points are deleted with their trip. They go into the password-locked
  backup and the DB merge on restore. Nothing new is sent anywhere.
- A switch, Config → System → Trip routes: "Record trip routes". **On by
  default** (owner, 2026-11-15): routes work for everyone, data stays in the car.
- Valet trips record too: the owner can see where the valet drove.

## Phase 2 — trip details screen

Tap a trip card (Day sessions, Week/Month lists) to open one screen:

1. **Route in a plane.** The points projected to x/y (equirectangular around
   the trip's centre, fine at city scale), fitted to the panel, north up.
   No map tiles. Start and end marks, a scale bar (e.g. "1 km").
2. **Coloured by average speed per segment.** Each segment between two points
   gets the mean of their speeds, on a fixed scale (same idea as the
   kWh/100 km tile): ≤ 20 km/h red (stop-and-go), 20–50 orange, 50–80 green,
   80–110 blue, > 110 purple. A small legend. Fixed, not per-trip, so two
   trips compare at a glance.
3. **Elevation profile.** Altitude against distance, under the route. GPS
   altitude is noisy: smooth with a moving median (~5 points) before drawing.
   The same speed colours under the line, so a slow climb is easy to spot.
   Tapping the profile marks that place on the route.
4. **Key numbers.** Distance; driving time and stopped time; average and max
   speed; net energy, spent and regen kWh; kWh/100 km (same colour scale as
   the home card); battery start → end; ascent / descent; outside temperature
   range; energy source (CAN / OBD2 / estimate); Valet if it was one.

Charge details: the same numbers the charge card has, plus a small plane
plot of where it was, with a mark when it is the same place as earlier
charges (~100 m), e.g. "Home".

Trips recorded before Phase 1 open the same screen with numbers only.

## Phase 3 — export

- **Export GPX**: the Charges screen's **Export to USB** writes one file per
  day (`drive_assist_routes_yyyy-MM-dd.gpx`, one track per trip) to
  `Drive Assist/Routes/yyyy-MM/` on the USB stick, via `UsbExport`. A month
  file was dropped: a busy month is several MB, too big for some map apps.
  The phone page (**Routes (GPX)**) lists months, then days: download a whole
  day or one trip. Speed and time per
  point go in GPX `<extensions>`. Google Maps (My Maps), Google Earth and most
  map apps open GPX.
- Trips CSV gets `start_lat, start_lon, end_lat, end_lon`; charges CSV gets
  `lat, lon`.

## Not planned

- Map tiles in the app (needs internet; OpenStreetMap's tile policy forbids
  heavy app use) and bring-your-own map key (too advanced for most drivers).
- Place names (street, city): reverse geocoding needs an online service here.
- A month "all routes" map in the app: GPX export of the month covers it in a
  real map app.

## Later ideas

- Colour by consumption instead of speed (a toggle on the screen).
- Home Assistant users already have a route history in HA's map; nothing to
  add there.
