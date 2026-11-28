# Planning index

This directory holds product and engineering plans that are useful to review
alongside the code. A plan moves out of `active/` when its direction is no
longer current; the archive preserves the reasoning without making completed
work look pending.

## Active

- [Dashcam reliability](active/DASHCAM-RELIABILITY-ROADMAP.md)
- [Smarter dashcam sessions](active/DASHCAM-SMART-SESSIONS-ROADMAP.md)
- [Parked monitoring](active/PARKED-MONITORING-PLAN.md) and its
  [on-car test protocol](active/PARKED-MONITORING-CAR-TEST-PROTOCOL.md).
  Record-on-motion shipped in v0.4.4; the rest is open.
- [Runtime efficiency review](active/RUNTIME-EFFICIENCY-REVIEW.md)
- [Test coverage](active/TEST-COVERAGE-PLAN.md)
- [SQL and data layer](active/SQL-DATA-LAYER-IMPROVEMENT-PLAN.md)
- [OBD2 ECU discovery](active/OBD2-ECU-DISCOVERY-PLAN.md)
- [Home screen as a dashboard](active/HOME-DASHBOARD-ROADMAP.md)
- [Integrations as data](active/INTEGRATIONS-DSL-ROADMAP.md)
- [LLM integration (BYOK assistant and AI tasks)](active/LLM-INTEGRATION-ROADMAP.md)
- [Road Score and Driver Score (head-unit IMU)](active/ROAD-AND-DRIVER-SCORE-ROADMAP.md).
  The scores are built; the roughness map and the trends are open.
- [Private web statistics sharing](active/PRIVATE-WEB-STATS-SHARE-ROADMAP.md)
- [Car diary and maintenance log](active/car-diary-maintenance-log.md)
- [Parking prices and automatic toll logging](active/PARKING-AND-TOLLS-ROADMAP.md)
- ["Hey Geely" voice assistant](active/VOICE-ASSISTANT-ROADMAP.md)
- [Comfort ruler v2: one tap, one change](active/COMFORT-RULER-V2.md)

## Archive

### Implemented

- [AEB controls](archive/implemented/ADAS-CONTROLS-ROADMAP.md)
- [AVAS mute](archive/implemented/AVAS-MUTE-ROADMAP.md)
- [CAN energy](archive/implemented/CAN-ENERGY-ROADMAP.md)
- [Fake-car fixtures](archive/implemented/CAR-TEST-FIXTURES.md)
- [Charge statistics overview](archive/implemented/CHARGE-STATS-OVERVIEW-ROADMAP.md)
- [Home drive and parked cards](archive/implemented/HOME-DRIVE-CARDS-ROADMAP.md)
- [ModeHelper watchdog](archive/implemented/MODEHELPER-WATCHDOG-ROADMAP.md)
- [Right-hand-drive layout](archive/implemented/RIGHT-HAND-DRIVE-ROADMAP.md)
- [Settings PIN](archive/implemented/SETTINGS-PIN-ROADMAP.md)
- [Weekly and monthly statistics](archive/implemented/STATS-PERIOD-VIEWS-ROADMAP.md)
- [Trip routes](archive/implemented/TRIP-ROUTES-ROADMAP.md)

### Deferred

- [EntityBus execution domains](archive/deferred/ENTITYBUS-EXECUTION-DOMAINS-PLAN.md)
- [Recharge cost accounting](archive/deferred/RECHARGE-COST-ACCOUNTING-ROADMAP.md)

### Superseded

- [Automatic valet grouping](archive/superseded/VALET-MODE-ROADMAP.md) —
  superseded by the deliberate, manually started Valet Mode implemented with
  the Home Drive Cards work.
- [Energy data integrity](archive/superseded/energy-data-integrity.md) —
  a different design shipped in v0.4.0 (energy source and estimated rows).
- [Long-term driving statistics](archive/superseded/long-term-driving-stats.md) —
  the week, month and year views cover it.

## Housekeeping

Keep a plan in `active/` while it remains an intended direction. Move it to
`archive/implemented/` only after the scoped behavior is in the code and
verified. Use `archive/deferred/` for deliberately postponed ideas and
`archive/superseded/` when a later decision replaces a plan. Supporting captures
in `guide-screenshots/` remain local and are intentionally not versioned.

Every plan lives in this directory, not under `docs/`: `docs/` is published
as the website. List each file in `active/` above.
