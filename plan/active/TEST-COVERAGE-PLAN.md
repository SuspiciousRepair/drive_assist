# Test coverage plan

Reviewed 2026-09-11 by a dedicated code/test review agent, with a fresh JaCoCo run by the primary agent. This is a proposed implementation plan; no testing framework or CI changes have been made yet.

## Current measured baseline

`./gradlew :drivemem:jacocoTestReport --offline --console=plain` passed with 164 tests, zero failures/errors. The generated report is at `drivemem/build/reports/jacoco/test/html/index.html`.

| Scope | Line coverage | Branch coverage |
| --- | --- | --- |
| Entire report | 947 / 11,968 (7.91%) | 538 / 5,872 (9.16%) |
| DrivingConsumption | 33 / 33 (100%) | 48 / 66 (72.73%) |
| EnergyIntegrator | 107 / 129 (82.95%) | 31 / 52 (59.62%) |
| DailyStatsProvider | 0 / 335 | 0 / 330 |
| TelemetryRollup | 0 / 125 | 0 / 76 |
| CarDb | 0 / 65 | 0 / 20 |
| ChargeStatsView | 0 / 225 | 0 / 66 |
| ChargeCostDialog | 0 / 83 | 0 / 24 |

These are local JVM coverage measurements, not hardware or UI validation. Full line coverage of the accumulator does not prove correct database queries or allocation across gear transitions.

JUnit and JaCoCo are configured in [drivemem/build.gradle](../drivemem/build.gradle). `unitTests.returnDefaultValues = true` permits Android methods to return defaults, so passing tests do not establish real SQLite, Handler, or lifecycle behavior. No Android-capable UI/database test framework is currently configured.

[CI](../.github/workflows/build.yml) assembles the release APK but does not run a Gradle unit-test task. [CONTRIBUTING](../CONTRIBUTING.md) still refers to 131 tests.

## User priorities (updated 2026-09-11)

Historical recalculation repairs are specific to development on the owner’s car and are not a requested test priority. Begin with reusable fake-car behavior and recording/replay support. Keep active recharge UI work with the primary agent. The historical test proposals below are deferred, not part of the initial implementation.

## Keep statistics first

The consumption fix is installed on the car. It excludes Park samples, retains zero-speed samples in known non-Park gears, and recalculates historical consumption from retained telemetry. Recharge history supports explicit editing, including a recorded price of zero distinct from a missing price.

The next tests should protect this work before expanding to unrelated features:

1. **Consumption and sampling boundaries.** Extend [DrivingConsumptionTest](../drivemem/src/test/java/com/geely/drivemem/sensors/DrivingConsumptionTest.java) and [EnergyIntegratorTest](../drivemem/src/test/java/com/geely/drivemem/EnergyIntegratorTest.java). Cover Park with AC, D at zero speed, explicit R/N behavior, unknown gear, charging, regeneration, exact 40/80/120 boundaries, absent values, odometer resets, legacy/direct transitions, duplicate and out-of-order timestamps. Replay power and gear events together across P→D and D→P inside a telemetry window. Use independently calculated expected energy.
2. **Live and historical SQLite calculations.** Execute the actual queries in [DailyStatsProvider](../drivemem/src/main/java/com/geely/drivemem/sensors/DailyStatsProvider.java) against synthetic fixtures. Verify live and frozen-day readings, stale rollups with retained raw data, repeat reads, midnight in America/Sao_Paulo, and isolation from today's live readings when selecting yesterday.
3. **Retention.** Test [TelemetryRollup](../drivemem/src/main/java/com/geely/drivemem/sensors/TelemetryRollup.java) and historical selection after pruning. Latest 14 recorded dates can span more than 90 calendar days. Define a fallback for missing raw data; absent data must not become an apparent real zero. Preserve corrected driving summaries before pruning if they must remain available indefinitely.
4. **Historical recharge price persistence.** Edit an earlier session to a positive price and to zero, await database completion, then reopen/reload. Assert the correct session changed, other sessions stayed intact, null remains missing, and both Home and statistics refresh from committed values. Current ChargeSession tests use null Context in relevant cases and therefore bypass database writes.
5. **CI enforcement.** Run unit tests, generate JaCoCo reports, and upload results before publishing build artifacts. Establish a coverage ratchet for calculation/storage code after the baseline is accepted; avoid an arbitrary whole-app target dominated by UI code.

Acceptance for this first slice: deterministic fixtures reproduce Park/traffic distinctions, live and historical queries pass against actual SQLite, a free historical recharge survives reload, and CI fails when these regressions are introduced.

## Hardware behaviors we can simulate

Existing [CarDataHubApplyTest](../drivemem/src/test/java/com/geely/drivemem/CarDataHubApplyTest.java) already uses `FakeCarAccess`. Extend this pattern with scripted sequences for missing properties, read/write errors, delayed readback, disconnection/reconnection, and unsupported properties.

Use narrow injectable dependencies for wall and monotonic clocks, scheduling/executors, telemetry input, and database access where needed. Reuse existing timestamps and reset hooks. Prefer deterministic event replay over sleeps or a large architecture rewrite. Test suspend/resume, service restart, duplicate subscriptions, and exactly-once session/row creation.

These tests exercise app behavior given vehicle signals. Actual CAN/VHAL semantics, vehicle-specific permissions, and audio/display behavior still need focused checks on the car.

## Persistence and UI test approach

Evaluate a small Android-capable test suite: Robolectric with real SQLite execution is a candidate, or API 28 emulator instrumentation. Verify SQLite compatibility with the head unit's SQLite 3.22; a newer host engine can accept SQL unavailable on the car. Framework selection and dependencies need a small compatibility spike before adoption.

Do not mock cursor/query results for persistence tests: that would hide joins, NULL handling, migrations, and transaction failures. Make asynchronous writes observable via a controllable executor or completion callback. Verify `charge.cost_updated` is consumed after the changed data is readable.

Next add charge aggregation/input tests: mixed paid/free/unpriced sessions; weighted cost per kWh; zero kWh; incomplete pricing; comma decimals; blank, negative, and nonfinite inputs; cancel without writes.

A small rendered UI suite can then cover compact controls at 1920×1080, period selection, visible historical-price editing, zero versus missing prices, Park-only dialogs, dismissal on gear changes, and listener cleanup when screens detach.

## Open statistics risks from the review

- A stored energy increment covers approximately 15 seconds but has one row's gear/speed. The accumulator cannot fully allocate an interval spanning a gear change. Replaying the integrator and sampler together should quantify this and guide a boundary-aware fix.
- Historical recalculation currently relies on retained raw samples. Frozen rollups still contain the original all-noncharging energy totals, and raw samples are pruned after 90 days. The retention tests must drive an explicit durable-history policy.
- Raw queries order by insertion ID. Delayed insertion and wall-clock changes need tests before assuming insertion and event order always match.

Use synthetic or anonymized fixtures; do not commit private vehicle traces. Track behavioral coverage and regressions prevented alongside coverage percentages.
