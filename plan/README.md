# Planning index

This directory holds product and engineering plans that are useful to review
alongside the code. A plan moves out of `active/` when its direction is no
longer current; the archive preserves the reasoning without making completed
work look pending.

## Active

- [Dashcam reliability](active/DASHCAM-RELIABILITY-ROADMAP.md)
- [Runtime efficiency review](active/RUNTIME-EFFICIENCY-REVIEW.md)
- ["Hey Geely" voice assistant](active/VOICE-ASSISTANT-ROADMAP.md)
- [Smarter dashcam sessions](active/DASHCAM-SMART-SESSIONS-ROADMAP.md)
- [Right-hand-drive layout](active/RIGHT-HAND-DRIVE-ROADMAP.md)
- [Settings PIN](active/SETTINGS-PIN-ROADMAP.md)
- [Weekly and monthly statistics](active/STATS-PERIOD-VIEWS-ROADMAP.md)
- [Test coverage](active/TEST-COVERAGE-PLAN.md)

## Archive

### Implemented

- [AEB controls](archive/implemented/ADAS-CONTROLS-ROADMAP.md)
- [AVAS mute](archive/implemented/AVAS-MUTE-ROADMAP.md)
- [Fake-car fixtures](archive/implemented/CAR-TEST-FIXTURES.md)
- [Charge statistics overview](archive/implemented/CHARGE-STATS-OVERVIEW-ROADMAP.md)
- [Home drive and parked cards](archive/implemented/HOME-DRIVE-CARDS-ROADMAP.md)
- [ModeHelper watchdog](archive/implemented/MODEHELPER-WATCHDOG-ROADMAP.md)

### Deferred

- [Recharge cost accounting](archive/deferred/RECHARGE-COST-ACCOUNTING-ROADMAP.md)

### Superseded

- [Automatic valet grouping](archive/superseded/VALET-MODE-ROADMAP.md) —
  superseded by the deliberate, manually started Valet Mode implemented with
  the Home Drive Cards work.

## Housekeeping

Keep a plan in `active/` while it remains an intended direction. Move it to
`archive/implemented/` only after the scoped behavior is in the code and
verified. Use `archive/deferred/` for deliberately postponed ideas and
`archive/superseded/` when a later decision replaces a plan. Supporting captures
in `guide-screenshots/` remain local and are intentionally not versioned.
