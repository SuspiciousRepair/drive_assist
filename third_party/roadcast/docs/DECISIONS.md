# Architecture Decisions

Initial record of decisions made during design. This file will be divided into
individual ADRs if the volume or history requirement grows.

## D-001: Independent Project

**Status:** Accepted.

Roadcast is a new project. The VHAL reader was validated during previous
studies, but the production protocol will not be added implicitly to a monolithic
CLI tool.

## D-002: App-Agnostic Server

**Status:** Accepted.

No application owns the snapshot or controls the daemon lifecycle. All sessions
have the same read rights, subject to an access policy that will be defined
later.

## D-003: Complete Catalog

**Status:** Accepted.

Roadcast does not select only signals considered useful. Frames, decoded
signals, and observable properties enter the catalog, even when scale or meaning
are not yet confirmed.

Uncertainty is represented by metadata, not by silent exclusion.

## D-004: Runtime Without Persistence

**Status:** Accepted.

The daemon does not write snapshots, backlog, or telemetry. Operational state,
queues, and transport remain in RAM. Persistence is a choice for each consumer.

## D-005: Configurable 60 Hz

**Status:** Accepted as initial target.

The sampler will target 60 Hz, with configuration available. The effective rate
will be measured and exposed. Sampling rate must not be confused with the actual
publication rate of each ECU.

## D-006: Abstract Unix Socket as Base Transport

**Status:** Accepted for the first tracer.

The abstract socket avoids filesystem files and was validated on the device
during previous studies. The base protocol will not depend on shared memory.

Loopback TCP remains a compatibility alternative if tests with applications in
other domains encounter connection restrictions to the Unix socket.

## D-007: Complete Snapshot Followed by Deltas

**Status:** Accepted.

Every client receives complete state when connecting, then only changed entries.
This exposes the entire catalog without retransmitting thousands of idle entries
every cycle.

## D-008: No Client Blocks the Sampler

**Status:** Accepted.

Per-client queues are bounded. Under delay, the daemon prefers current state,
marks discontinuity, and requires resynchronization. Backpressure never reaches
the acquisition thread.

## D-009: Shared Memory Is Not a Requirement

**Status:** Accepted.

memfd or ashmem can be added as a fast path. The simplest client only needs to
implement the socket protocol.

A future fd adopted by a client must not replace a global buffer, because that
would freeze existing consumers.

## D-010: Initial Filtering Belongs to the Client

**Status:** Accepted for the first protocol.

The initial technical sources are raw CAN frames, decoded CAN signals, and
VehiclePropertyStore properties. Names like PEPS, BCM, BMSH, and VCU describe
the producer or semantic domain, not mutually exclusive transports. A PEPS
signal can exist on CAN and be republished as a property by the VHAL.

The schema identifies `kind`, source, and domain to allow local filtering. The
server sends complete snapshots and complete deltas by default. Server-side
subscriptions by source or domain will only be added if measurements show
significant cost, or if different access requirements arise by data class.

This choice avoids additional state and resynchronization per session.
Measurements on the vehicle in 2026-07-26 showed, at 120 Hz, approximately 2.8
frames, 0.4 signals, and 0.4 properties changed per tick, although the total
catalog has 111 frames, 815 signals, and 1804 properties.

## D-011: libuv Runtime with a Small Native Wire Protocol

**Status:** Accepted.

Roadcast uses libuv for the event loop, timers, signal handling, client
lifecycle, asynchronous I/O, and write queue observation. The project does not
implement a growing custom `poll()` loop, partial-write scheduler, or socket
lifecycle framework.

The Linux abstract socket still requires a narrow native adapter because
`uv_pipe_bind()` does not bind abstract namespace addresses. Roadcast creates
and binds `@roadcast` with the Linux socket API, then adopts the descriptor in
libuv. This exception does not justify a second event library.

The hot data path retains an explicit Roadcast message header and fixed-width
delta records. This wire format is intentionally simple enough for consumers to
implement without installing libuv or a Roadcast SDK.

FlatBuffers is the preferred future representation for catalog, schema, and
control payloads, where schema evolution and generated Dart/Kotlin/C++ bindings
provide concrete value. It will not wrap every small telemetry delta. Adopting
FlatBuffers in the daemon requires either an official supported C path or a
C++ protocol boundary; adding an unofficial C binding implicitly is rejected.

NNG, ZeroMQ, gRPC, protobuf-c, nanopb, and libevent are not baseline
dependencies. They may only be reconsidered when a measured requirement cannot
be met by libuv and the existing protocol.

## D-012: Generated CAN Catalog and Protocol Version 2

**Status:** Accepted.

`data/dbc.json` is the source of truth for the initial 111 raw CAN frames and
815 decoded signals. A deterministic generator emits the compiled frame list,
bit layout, metadata, stable IDs, and canonical schema hash. The daemon performs
no JSON or DBC parsing at runtime.

A CAN signal stable ID is FNV-1a 64 over:

```text
"roadcast.can.signal.v1" NUL can_id_be16 signal_name_utf8
```

Including the CAN ID distinguishes equal names such as `Diag_req` on `0x7C1`
and `0x7DF`. Bit layout, width, signedness, scale, offset, unit, calibration,
and invalid-signal relationship affect the schema hash but not semantic
identity.

Raw signal values are 64-bit because the catalog contains 64-bit diagnostic
signals. Physical values use IEEE-754 binary64.

Schema and signal snapshots use client-requested pages bounded by the existing
message limit. A per-client frozen snapshot keeps all signal pages at one
`sample_seq`. Before acknowledging `SUBSCRIBE_ALL`, the daemon sends a bounded
catch-up delta from that frozen snapshot to current state. This prevents a
client from losing changes that occur while it retrieves pages.

Protocol version 2 introduces schema discovery, decoded signal snapshots, and
decoded signal delta batches. FlatBuffers remains deferred: the current
generated fixed format meets the C, Dart, and Kotlin compatibility boundary
without a runtime serialization dependency.

## D-013: Bounded Local Session Setup

**Status:** Accepted.

The operating system remains the authority for whether a process may connect to
the Roadcast socket. The daemon reads immutable peer credentials from the
accepted socket and uses the peer UID for resource accounting. A UID may hold at
most eight concurrent sessions.

An accepted client has two seconds to send a valid `HELLO` and ten seconds after
`WELCOME` to complete snapshot setup and subscribe. A client that needs
resynchronization has the same bounded setup window. Fully subscribed clients
have no inbound idle timeout because a read-only consumer is not required to
send periodic traffic.

Peer credentials and quotas are availability controls, not a complete
authorization mechanism. The production UID allowlist, if one is required,
must be decided after validating the actual app and daemon execution domains on
an enforcing vehicle.

## D-014: Latest-Snapshot Sampler Handoff

**Status:** Accepted.

The sampler publishes one latest frame snapshot through a short mutex-protected
copy, then notifies the libuv loop. It never waits for client queues or socket
I/O. The event loop may coalesce multiple notifications and consume only the
newest snapshot; sequence deltas preserve the number of elapsed samples and the
daemon reports intermediate coalesced samples.

Publishing does not use `pthread_mutex_trylock`: lock contention must not
silently discard a completed source read. A ring buffer is unnecessary while
the product contract promises current state rather than every intermediate
sample.

## D-015: Protocol Version 3 Exposes Uncertainty and Directional Limits

**Status:** Accepted.

Protocol version 3 is an intentional incompatible change. It adds:

- Separate maximum request and response payloads in `WELCOME`.
- Explicit `total_count`, `start_index`, and `count` batch metadata.
- Paged raw-frame snapshots.
- Unavailable, never-observed, valid, and invalid observation states.
- First-observed and last-change monotonic timestamps.
- Effective sampling rate, source state, and handoff coalescing in heartbeats.

The VHAL memory reader exposes storage, not a CAN receive event. Roadcast
therefore treats the startup bytes as an unobserved baseline. It marks a frame
observed only after detecting a byte transition. This may leave a genuinely
constant frame in `never observed`, but avoids inventing source presence.

Per-client output queues retain 16 fixed 2048-byte slots. Subscription catch-up
is resumable: it fills available slots, continues from write callbacks, and
repeats against the latest canonical state before acknowledging `SUBSCRIBED`.
The dedicated integration case constrains a session to one output slot and
delays subscription until a multipage catch-up is required. Successful
subscription proves catch-up no longer depends on the entire catalog fitting in
the queue.

## D-016: SELinux Enforcing Is a Release Gate

**Status:** Accepted.

Roadcast is required to operate on an SELinux Enforcing vehicle without
modifying vendor policy. Root UID and a successful `su 0` run on a Permissive
device are not acceptance evidence because SELinux authorization is based on
the process domain, not only the Linux UID.

The release proof must run the production binary through the intended
installation and launch mechanism on an Enforcing target. It must demonstrate
VHAL source discovery, remote memory reads, socket access from a real app
domain, and sustained streaming without relevant AVC denials.

The 2026-07-26 Enforcing spike satisfied the source-side portion in
`u:r:su:s0`: the daemon resolved and sampled all 111 frames and served a version
3 client without relevant AVC denials. Production launch-domain assignment and
socket access from a real Android app domain remain unsatisfied portions of this
gate.

Permissive runs remain useful for functional, performance, and would-deny AVC
diagnostics. Roadcast tests must not call `setenforce`, install policy, or alter
vendor files without explicit authorization for that exact experiment.

## Open Questions

- Exact binary format and endianness.
- Queue limits and discard policy.
- Production authorization policy beyond peer-credential resource accounting.
- Conditions that would justify replacing schema pages with FlatBuffers.
- C SDK API.
- Strategy to test without depending on the vehicle.
- Definitive mechanism for daemon installation and startup.
- Which parts of the current reader can be extracted without coupling Roadcast
  to the internal layout of the VHAL binary.
- Whether to expose the 44 transmit buffers and their 580 DWARF-defined signals.
  Reading them adds no write path, but the schema must distinguish an observed
  transmit buffer from an observed receive frame, and `kind`/`source` currently
  cannot express that difference. See `docs/CATALOG_SURFACE.md`.
- How to represent the 61 percent of already-read frame bits that carry no
  DWARF-defined signal. They are only discoverable empirically, so any catalog
  entry for them would have a name Roadcast invented rather than one extracted,
  which the data-semantics rules otherwise forbid.
- How to detect that a VHAL update changed or stripped the symbol surface, given
  that the daemon starts successfully when only a subset of buffers resolves.
