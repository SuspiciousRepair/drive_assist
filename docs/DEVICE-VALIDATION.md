---
title: Vehicle validation checklist
---

# Vehicle validation checklist

Automated verification proves the APK builds and its deterministic behavior
remains stable. It does not authorize installation or prove a change is safe on
the vehicle. Use this short record for changes that touch vehicle writes,
watchdogs, boot/resume behavior, telemetry persistence, or database migrations.

## Before touching the car

- [ ] `./tools/verify.sh` passed for the exact commit being tested.
- [ ] The change and its rollback path were reviewed while parked.
- [ ] A current database backup exists before a migration or retained-history
  change.
- [ ] No credentials, VIN, home address, broker address, or unsanitized logs
  will be copied into a commit or issue.

## On the parked car

- [ ] Record the commit SHA and APK checksum in a private test note.
- [ ] Install only while parked; do not reboot the IHU.
- [ ] Confirm the intended state and its failure/rollback behavior.
- [ ] For service/watchdog work, verify a suspend/resume or targeted service
  restart restores the expected service without changing vehicle state.
- [ ] For migrations, confirm the old records remain readable and a new record
  can be written.

## Close out

- [ ] Capture only sanitized screenshots or observations for public docs.
- [ ] Add a regression test or recorded scenario when the behavior can be
  represented without real vehicle data.
- [ ] State whether the commit was vehicle-validated in its PR, journal entry,
  or private release note.

This is a test checklist, not a deployment command. It never widens the safety
rules in [Contributing](../CONTRIBUTING.md).
