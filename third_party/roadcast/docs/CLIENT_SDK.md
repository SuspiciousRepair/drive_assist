# Roadcast Client SDK

## Purpose

`libroadcast_client` is the reusable native client for protocol v3. It owns the
socket session, schema discovery, initial snapshots, subscription catch-up, and
resynchronization. Consumers read a bounded in-memory cache instead of parsing
the wire protocol in a UI or application thread.

The SDK does not create persistent data files. Its catalog, snapshots, decode
scratch space, and live values remain in process memory.

## Runtime Model

`roadcast_client_connect()` performs these steps synchronously:

1. Connect to the Unix domain socket.
2. Negotiate protocol v3 capabilities and directional payload limits.
3. Download the complete signal schema.
4. Retrieve consistent paged frame and signal snapshots.
5. Complete `SUBSCRIBE_ALL` catch-up.
6. Start one background reader thread.

After the function returns, the cache is immediately readable. The background
thread applies live updates and handles `RESYNC_REQUIRED` by rebuilding a
consistent snapshot before atomically replacing the visible cache.

Live batch decoding uses preallocated scratch buffers. Cache readers hold a
mutex only while copying requested values; socket I/O, protocol decoding, and
allocation do not occur while that mutex is held.

## Public Interface

The stable C entry point is
[`include/roadcast_client.h`](../include/roadcast_client.h). Important
operations are:

- `roadcast_client_connect()` and `roadcast_client_close()` for ownership.
- `roadcast_client_find_signal()` for convenient one-time name resolution.
- `roadcast_client_find_signal_by_stable_id()` for unambiguous resolution when
  names are duplicated or a cached schema identity is available.
- `roadcast_client_read_signal()` and `roadcast_client_read_signals()` for
  synchronous cache reads.
- `roadcast_client_status()` and `roadcast_client_sample_age_ns()` for health
  and freshness.

Callers should prefer stable IDs as the durable identity, resolve them once,
retain their numeric indexes, and use batched reads for a gauge frame. Name-only
lookup returns the first match and is unsuitable for duplicated names. Schema
pointers are immutable and remain valid until the client is closed.

## Deadlines and Timeouts

`libroadcast_client` enforces monotonic clock deadlines on all blocking operations:

- **Connection:** 3000 ms limit to connect to the Unix domain socket.
- **Message I/O:** 3000 ms per outbound and inbound message (header and payload
  share a single monotonic deadline, preventing slow-trickle socket starvation).
- **Setup & Resync Budget:** 10000 ms global budget across handshake, schema
  discovery, and initial snapshot catch-up.
- **Non-blocking sockets:** Underlying file descriptors use `O_NONBLOCK` with
  poll-based readiness waits. Calling `roadcast_client_close()` triggers
  `shutdown()` and unblocks pending waits immediately.

## Android Integration

The Android build produces `libroadcast_client.so`. Application-specific Dart
FFI and JNI wrappers should be thin adapters over this library; they must not
implement protocol parsing independently.

For the first application migration, the integration keeps two independent
sessions:

- A Dart FFI-owned session for synchronous gauge reads.
- A Kotlin/JNI-owned session for the foreground service and data ingestion when
  the Flutter engine is absent.

This duplicates a small RAM cache and one local socket per runtime, but preserves
the current lifecycle separation. Neither side becomes a proxy for the other,
and Kotlin telemetry remains available without a Flutter engine. A single
process-wide Android owner can be reconsidered only after measurements show that
the extra session is material.

Roadcast installation and daemon lifecycle are outside the app SDK. The app
connects to an existing service and reports availability; it must not install,
start, or kill `roadcastd`.

## Current Scope

- Raw CAN frames and 815 decoded CAN signals are supported.
- VHAL properties are not yet part of the Roadcast catalog and must remain on
  the existing path during the first app migration.
- The SDK resynchronizes an established session but does not reconnect after
  socket loss. The owner must close the disconnected handle and connect again.
- Access from the Android app SELinux domain is not yet validated. Root-domain
  Enforcing validation proves the daemon source path, not app socket policy.

## Validation Status

- Host unit tests and the complete fake-source integration suite pass.
- The integration suite includes the public SDK connecting, resolving
  `ESC_VehicleSpeed`, reading its live cache, and reporting freshness.
- Android NDK API 28 produces a valid ARM64 `libroadcast_client.so`.
- AddressSanitizer validation is deferred. On the current macOS host, the
  instrumented daemon remained alive but did not create its socket within the
  integration deadline, while an isolated instrumented client run later
  blocked. No sanitizer diagnostic was emitted. Resolve the harness/runtime
  behavior before using ASan as a release gate; the regular and Android builds
  are not affected.
