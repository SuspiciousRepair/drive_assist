# Open-source head-unit software review

**Project:** Drive Assist  
**Scope:** whole repository review, 2026-09-15  
**Audience:** maintainers and contributors of a sideloaded utility for rooted Geely IHU629G head units (Android 9 / API 28)

## Executive view

Drive Assist has a thoughtful, device-specific safety posture. In particular, the
normal-app/system-UID split is well explained, vehicle writes are deliberately
limited, operational instructions are unusually complete, and the project has
repeatable JVM checks plus real-device validation guidance. Those are the right
foundations for this kind of software.

The appropriate standard is **not** Play Store compatibility or a theoretical
zero-trust phone. It is a trustworthy, recoverable appliance for owners who have
already chosen to root and sideload their own head unit. That still requires a
strict boundary around commands that change vehicle-, installation-, or
debug-access state. Today that boundary is the largest risk, followed by release
provenance, the amount of orchestration in a few large classes, and insufficient
automated evidence for the privileged components.

This is a review, not a claim that the application is unsafe to use. Findings are
prioritized so that limited volunteer time goes first to reducing the most
consequential failure modes.

## What is already working well

- The system-UID `modehelper` is separate from the WebView-capable main app, with
  a clear rationale in its manifest and `CONTRIBUTING.md`. This is a valuable
  architectural boundary and should remain non-negotiable.
- The repository states explicit vehicle invariants: no reboot, Park-only
  disruptive operations, read-only VHAL exploration, suspend/resume recovery,
  and Android 9 SQLite compatibility. See `CONTRIBUTING.md` and
  `docs/ARCHITECTURE-SAFETY-AUDIT.md`.
- `CarActor` centralizes main-app car I/O on one `HandlerThread`, and the
  EntityBus/cached-read model avoids binder calls from arbitrary UI code.
- The project supplies both an offline installer and scripted installation,
  documentation in English and Brazilian Portuguese, a PII scanner, checksum
  publication, CI, and a hardware-validation procedure. This lowers the support
  burden of a niche open-source project.
- `allowBackup=false`, private application components where possible, HTTPS
  checking, size limits, TLS support, and an existing-signature comparison are
  sensible defense-in-depth measures for the target environment.

## Priority recommendations

### P0 — make the privileged command surface safe by construction

`modehelper` runs as `android.uid.system` and requests package install/delete,
secure-settings, Bluetooth privileged, wake-lock, and car permissions. Yet its
manifest exports multiple action receivers without a permission or a caller
authorization check: install, property probe, ADB control, trusted-Wi-Fi setup,
Bluetooth pairing, dashcam, parked monitoring, and the AEB/AVAS diagnostic
receivers. See `modehelper/AndroidManifest.xml` and the receiver classes.

This is not merely theoretical surface area: the release manifest exports
`AebWriteTestReceiver` without a permission. Its receiver opens a car connection
and attempts a diagnostic AEB write without validating action, caller, Park, or
speed; its delayed restore is not protected by `finally`. Whether a particular
firmware accepts that write is beside the point—the attempt must not be present
in a normal owner release.

An exported receiver must be treated as callable by any application installed on
the head unit, not only Drive Assist or ADB. Payload validation is useful but is
not an authorization mechanism. In particular, the installation path accepts an
HTTPS URL and then accepts an APK whose signer matches the installed app. The
repository deliberately publishes that signing key (`keystore/debug.ks` and
`keystore/README.md`), so signer equality cannot identify a trusted publisher.

Recommended design:

1. Remove production diagnostic write receivers (AEB and AVAS) from the release
   manifest. Put any unavoidable lab-only capability in a separately signed,
   non-release diagnostic APK.
2. Make every remaining inter-app entry point explicit and package-scoped; define
   a small, documented command contract. Where package targeting is insufficient,
   use a non-exported bound service or a permission that is actually secret on
   the target device. Do not rely on a custom permission protected by the public
   development certificate.
3. Treat commands independently: the ADB enable, Wi-Fi trust, pairing, install,
   and any vehicle write each need an allowlist, strict extra validation, an
   auditable event record, and an expiry/physical-presence or on-screen
   confirmation appropriate to their impact.
4. Make the main app use explicit intents with `setPackage()` / component names
   everywhere it calls the helper; then add tests that reject implicit or
   unauthorised requests.

### P0 — enforce safety at the last responsible moment

The documented Park invariant is strong, but it must be enforced in the helper
that performs the operation, not only when the UI decides to offer it. Review
every privileged side effect (especially installation and diagnostic writes) for
a fresh `CarState`/gear reading immediately before commit, with a fail-closed
result for unknown or stale state. The operation should be cancelled if the car
leaves Park while it is downloading or awaiting confirmation.

This needs a three-state model (`PARKED`, `DRIVING`, `UNKNOWN`), not a default
of “parked” before a fresh successful read. The current state code does default
to parked, and the update path allows a URL containing `force` to bypass the
main-app Park check; the helper installer itself does not check Park at all.
Remove any bypass encoded in a URL or other remotely controlled input. A service
release should never be able to silently turn a safety policy into an optional
convention. Record the decision, gear freshness, artifact digest, actor, and
outcome locally so a maintainer can diagnose a field report.

### P0 — establish verifiable release provenance and rollback rules

Keep the publicly committed key if seamless community builds are a deliberate
project goal, but stop treating it as an update-trust key. It proves continuity,
not publisher identity.

- Publish an immutable, signed release manifest containing package name,
  monotonically increasing version, SHA-256, source commit, and artifact URLs.
  Pin the release-signing public key in the helper (or require an explicitly
  imported maintainer key).
- Verify both the manifest signature and APK digest before `PackageInstaller`.
  Signature continuity remains a useful additional check.
- Reject versions lower than or equal to the installed version unless a local,
  clearly labelled recovery workflow authorizes a rollback. Equality alone does
  not prevent downgrade.
- Make builds reproducible enough to compare a published digest with a checkout:
  pin dependency checksums, document the exact JDK/SDK/toolchain, and preserve a
  source-to-artifact attestation in GitHub releases.

This preserves the sideloading model while giving users a way to distinguish a
maintainer release from any APK built with a widely available development key.

### P1 — turn the helper boundary into a small product with tests

The main Gradle module has unit tests, lint, Checkstyle, SpotBugs, and release
assembly in `tools/verify.sh`. `modehelper`, `installer`, and `sysprobe` are
instead built through separate shell scripts and are not part of that same
verification contract. That is backwards for risk: the least testable and most
privileged code deserves the clearest automated evidence.

Adopt a single top-level verification entry point that builds all installable
APKs and checks, at minimum:

- manifests: exported components, permissions, package names, and no release
  diagnostic surface;
- install/update policy: package, signing material, manifest signature/digest,
  version ordering, timeout, cancellation, and cleanup;
- the inter-process command schema and rejection cases;
- APK inspection: expected certificate fingerprint, no accidental debug-only
  classes, and fixed asset/package inventory.

It is acceptable that actual VHAL, system UID, camera, Bluetooth, and suspend
behavior need a real car. The goal is to make everything *around* that hardware
deterministic and testable on a developer machine.

Tag publication must consume the same verified artifacts, rather than a separate
build workflow that happens not to run the whole verification contract. Make a
build-only command the obvious default; deployment, Home Assistant publication,
and vehicle installation should remain explicit operations.

### P1 — reduce orchestration hotspots without a rewrite

Several individual Java files own UI construction, state, persistence, network
work, and lifecycle coordination at once: `ComfortActivity` (~108 KB),
`TelemetryActivity` (~129 KB), `DailyStatsView` (~80 KB), and `MqttReporter`
(~74 KB). This is understandable in a hardware-first project, but it makes
regressions expensive: a contributor cannot change a screen or a telemetry
field without navigating unrelated policy.

Use incremental extraction, not a framework migration:

1. Define plain Java interfaces for external edges: car access, clock/scheduler,
   HTTP/MQTT transport, storage, and helper commands.
2. Move state transitions and policy into small, side-effect-free controllers;
   keep Activities and Services as lifecycle/view adapters.
3. Group feature code by capability (for example `update`, `telemetry`,
   `dashcam`, `comfort`) rather than allowing cross-package static access.
4. Give each persistent preference/database key an owner, schema version, and
   migration test. SharedPreferences is appropriate here, but it currently acts
   as a broad implicit integration bus.

Keep `CarActor` as the sole main-app vehicle-I/O owner. Make helper operations
similarly single-threaded and command-serialized, with explicit cancellation
and bounded queues.

There is one important follow-up to that principle: `CarActor` currently exposes
raw `CarAccess` and one-shot read/write helpers, while `EntityBus` invokes
listeners synchronously on the actor thread and permits general publication.
That is weaker than the README's “CarActor-only publication” description.
Retire raw production access in favor of narrow diagnostic APIs, add thread
assertions, attach timestamp/source/connection-generation metadata to immutable
readings, and give subscriptions explicit lifetime handles. Storage, network,
and render preparation should not run on the vehicle-I/O thread.

### P1 — make quality signals enforceable rather than informational

The existing quality report records 14.20% scoped JaCoCo instruction coverage,
large integration gaps, 381 lint warnings (including errors), 2,338 Checkstyle
warnings, and 117 SpotBugs findings. The build intentionally uses
`abortOnError false` and `ignoreFailures` for static tools. Baselines are a
pragmatic starting point for legacy code, but a permanently non-blocking gate
does not prevent deterioration.

- Preserve a checked-in baseline for each tool and fail CI on **new** high
  severity findings or any increase in baseline count.
- Fix the small set of correctness findings first: format errors, thread-unsafe
  shared date formatters, default charset use, unchecked storage operations, and
  lifecycle/context leaks.
- Add tests at policy seams rather than chasing a global percentage: update
  authorization, Park/freshness behavior, receiver command validation, retry
  bounds, telemetry redaction, database migration, and file-space failure.
- Update stale metrics in documentation (for example, `CONTRIBUTING.md` says
  164 tests while the quality report records 194) from generated CI output.

### P2 — measure resource use on the actual appliance

For an Android 9 head unit, smoothness, wakeups, storage pressure, and recovery
matter more than modern Android API fashion. The README describes 2–15 second
polling and an unconditional 15-second telemetry tick; dashcam, OBD2, rendering,
and MQTT can overlap. Optimize only after measurement, but make the budget
visible.

Create a repeatable parked/driving/charging test matrix that records:

- CPU and memory after cold boot, 30 minutes driving, and overnight parked;
- wake-lock/alarm count, binder poll rate, MQTT reconnect rate, and dropped or
  queued telemetry;
- storage growth, free-space thresholds, clip rotation/export behavior, and
  recovery after full storage;
- UI frame time/touch latency at native 1920×1080; and
- behavior across suspend/resume, network loss, Bluetooth loss, and process
  death.

Use simple in-app counters and timestamped diagnostics that owners can export.
This is more sustainable than requiring every contributor to set up a profiler
against a rooted car.

There is also a concrete UI-path candidate for correction before broader tuning:
`ChargeStatsView.refresh()` reads the charge log and always builds a 30-day
chart. Its current chart path asks `rawEnergyForDay()` once per day, using SQL
that wraps `ts_ms` in `date(...)`; this prevents normal timestamp-index range
use and can mean repeated telemetry scans on a refresh. Replace it with one
background range query plus one in-memory aggregation/render model. Reuse the
timestamp-boundary approach already documented in `TelemetryRollup` and
`DailyStatsProvider`; test it against a 90-day fixture and then measure it on a
real head unit before declaring a latency improvement.

### P2 — make persistence, update, and recovery states explicit

The head unit can suspend, lose power, lose network, and receive updates while a
trip or charge is open. Database history and update state therefore deserve the
same care as vehicle-command state. The code already contains repair migrations,
raw-telemetry retention, and recovery logic for an interrupted trip—use those
past lessons to add fixtures for every supported schema migration, including a
historical summary whose raw rows have already been pruned.

Represent OTA as an operation with an ID and terminal state (`checking`,
`downloaded`, `verified`, `deferred`, `installing`, `succeeded`, `failed`). Do
not mark a URL as applied before the helper returns a terminal installation
result. Bound every download, delete partial files on every exit path, and
deduplicate retries using the artifact identity/version rather than a mutable
URL. Test suspend/restart and storage-full paths for open trip/charge sessions,
updates, and clip export.

### P2 — design for a small open-source community

The documentation is already a strength. The next step is to make contribution
and field evidence structured:

- Add a `device-profile` / capability document: supported firmware fingerprints,
  known permissions, confirmed read/write interfaces, feature availability, and
  explicit unsupported variants. Never assume another Geely firmware behaves
  like IHU629G.
- Provide sanitized trace fixtures and a replay harness for car signals. A pull
  request could then demonstrate state-machine behavior without physical access
  to the author's vehicle.
- Use issue templates for bug reports, feature proposals, and safety reports;
  ask for app version, firmware fingerprint, redacted logs, reproduction state,
  and whether the car was parked/moving.
- Publish a short support policy: experimental features, recovery procedure,
  downgrade process, data collection boundaries, and what information must never
  be attached to an issue.
- Maintain an ownership/review map for vehicle writes and privileged code. A
  second reviewer should be required for changes to the helper, manifests,
  updater, vehicle controls, and install scripts.

## Suggested sequencing

| Window | Outcome | Concrete deliverable |
| --- | --- | --- |
| First | Close high-impact command and motion hazards | Release manifest excludes diagnostic writers; helper command allowlist; last-moment Park/freshness check; remove remote bypasses. |
| Next | Establish a trusted update story | Signed manifest + digest verification, ordered-version policy, recovery-only rollback, artifact attestation. |
| Next | Make regressions visible | Unified build/manifest/APK checks, baseline ratchets, policy-focused tests, generated quality metrics. |
| Ongoing | Lower change and field-support cost | Incremental controller extraction, trace replay, resource-budget runs, device profile and issue templates. |

## Definition of done for a mature release

A release is ready for this project’s standard when it is reproducibly built;
its APK identities and digests are published; privileged commands have explicit
authorization and audit trails; every safety-sensitive action is freshly gated
on known Park state; all installable APKs pass one verification contract; no new
high-severity static findings are introduced; and the changed feature has passed
the documented on-device validation matrix.

That standard respects the reality of rooted, owner-operated head units while
making the system easier to maintain, safer to evolve, and easier for outside
contributors to trust.
