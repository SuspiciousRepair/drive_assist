# EntityBus execution domains plan

## Why this is deferred

`EntityBus` currently delivers synchronously on `CarActor`'s single thread.
That makes a slow listener capable of delaying VHAL polling, but it also gives
trip and charge state machines one serialized execution domain. A prototype
that moved every listener to a separate FIFO dispatcher was reviewed and
reverted on 2026-09-22: `TripSession` and `ChargeSession` both schedule delayed
work back onto `CarActor`, while their state is mutated by their listeners.
Moving only the listeners creates cross-thread races around Park and charge
transitions.

The safe quick win already landed separately: `EntityBus.subscribe()` is
idempotent, so repeated lifecycle starts cannot multiply listener work.

## Design to discuss before implementation

Keep execution ownership explicit at subscription time rather than making a
global dispatch change:

| Subscriber class | Execution domain | Examples |
| --- | --- | --- |
| Vehicle state machines | `CarActor` | `TripSession`, `ChargeSession`, `ParkingState`, `ValetSession` |
| UI effects | main-thread/coalesced | screen refreshes and status icons |
| Storage/network side effects | dedicated serial worker | telemetry persistence, MQTT and ABRP uploads |

The API should make that choice visible (for example, actor-affine and
asynchronous subscription methods), preserve FIFO order within a subscriber,
and provide a bounded/coalescing policy for replaceable snapshots. Discrete
events such as completed charging sessions must never be silently dropped.

## Evidence required before changing it

1. Instrument per-listener elapsed time on the car actor, rate-limited to
   avoid creating a new logging cost.
2. Capture real-device timing through a drive/charge and suspend/resume cycle.
3. Add deterministic tests for Park/charge ordering, delayed grace callbacks,
   unsubscribe semantics, and no duplicate session finalization.
4. Migrate one clearly independent side-effect listener at a time and compare
   event ordering and car-actor latency before wider adoption.
