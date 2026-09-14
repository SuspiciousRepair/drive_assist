# Fake car and recorded scenarios

`src/test/java/com/geely/drivemem/support/FakeCarAccess` is a shared JVM test double:
existing `CarDataHubApplyTest` uses its write spies and configurable write failures.
`CarTrace` adds explicit virtual timestamps, gear, speed and charging observations.
`CarTraceConsumptionTest` replays a synthetic Park → traffic → charging → driving
scenario through the production consumption accumulator, including speed boundaries.
No Android car service, real-time delays, device, or Gradle dependency is required.

Each CSV row is a complete observation. Blank cells represent unavailable readings;
zero is a real value. Reads never silently reuse the preceding row. Tests decide
how to interpret missing charging state; the fixture preserves it as null.
The fake models only its explicitly overridden methods, not a complete ECU, Binder
lifecycle, callbacks, or an interactive fake mode in the installed application.
Write spies record commands independently from read snapshots: a successful write
does not pretend an immediate ECU acknowledgement has arrived.

## Record an observed behavior

The app already persists approximately 15-second observations in `telemetry_sample`.
Obtain a consistent **local snapshot** of `car.db` using the project's existing
backup workflow; include SQLite WAL contents or use SQLite's backup API. Copying
only the main file while the app writes can omit recent observations. The exporter
never connects to the car, changes vehicle settings, or modifies the source database.

Select the observed interval using epoch milliseconds (end exclusive):

```sh
python3 tools/export-car-trace.py /tmp/car.db /tmp/traffic.csv \
  --start-ms 1789130000000 --end-ms 1789130600000
```

Export retains only the eight fixture columns. It removes absolute timestamps and
normalizes positive odometer readings while retaining distance differences and
zero startup readings. It does not include location, VIN, prices, or account data.
Review the short trace, document what was physically observed, and then copy it into
`drivemem/src/test/resources/car/` with a test asserting independently known results.
The checked-in `park-traffic-charge.csv` is synthetic, not a capture from the car.
Do not use an output from a potentially corrupted recording as its own expected answer.

Replay with `CarTrace.read(reader).replay(fake, sample -> ...)`; feed explicit sample
timestamps and values into the production component under test. Run with:

```sh
./gradlew :drivemem:testDebugUnitTest
```

These sampled traces can reproduce missing readings, traffic stops, park exclusion,
and charging classification. They cannot establish transitions inside the 15-second
window or reproduce high-frequency OBD integration: that requires a future capture
at the OBD/event boundary with monotonic timestamps. Historical recalculation is
not a coverage target. Next useful extensions are charging session edge/grace replay,
P↔D event timing, and controllable asynchronous read acknowledgement.
