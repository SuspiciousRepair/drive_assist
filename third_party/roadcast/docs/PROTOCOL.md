# Protocol

## Document Status

This document defines the initial direction. Numeric values, C layouts, and
message names are not yet a stable contract.

## Objectives

The protocol must:

- Work over an abstract Unix socket.
- Accept multiple simultaneous clients.
- Be implementable without a specific SDK.
- Use unambiguous binary framing.
- Allow discovery of all signals.
- Transmit complete snapshots and incremental updates.
- Detect incompatibility and message loss.
- Allow extension without breaking old clients.

## Version Separation

Three numbers must not be confused:

- **Protocol version:** Framing, messages, and connection semantics.
- **Schema version/hash:** Catalog and entry order.
- **Daemon version:** Roadcast implementation version.

Updating the catalog does not necessarily require updating the protocol.

## Initial Session

Protocol version 3 session flow:

```text
client                              roadcastd
  |                                    |
  +-- HELLO protocol=[2..2] --------->|
  |<-- WELCOME protocol=2 capabilities -|
  |                                    |
  +-- GET_SCHEMA start=0 ------------>|
  |<-- SCHEMA_CHUNK start=0 count=N ---|
  +-- GET_SCHEMA start=N ------------>|
  |<-- SCHEMA_CHUNK ... ---------------|
  |                                    |
  +-- GET_SNAPSHOT start=0 ---------->|
  |<-- SNAPSHOT start=0 count=N -------|
  +-- GET_SNAPSHOT start=N ---------->|
  |<-- SNAPSHOT ... -------------------|
  +-- GET_SIGNAL_SNAPSHOT start=0 --->|
  |<-- SIGNAL_SNAPSHOT_CHUNK ----------|
  +-- GET_SIGNAL_SNAPSHOT ... -------->|
  |<-- SIGNAL_SNAPSHOT_CHUNK ... ------|
  |                                    |
  +-- SUBSCRIBE_ALL ----------------->|
  |<-- UPDATE_BATCH catch-up ----------|
  |<-- SIGNAL_UPDATE_BATCH catch-up ---|
  |<-- SUBSCRIBED ---------------------|
  |<-- UPDATE_BATCH -------------------|
  |<-- SIGNAL_UPDATE_BATCH ------------|
  |<-- UPDATE_BATCH -------------------|
  |<-- HEARTBEAT ----------------------|
```

Raw-frame, schema, and decoded-signal snapshots are pulled by index. Each
response remains within the negotiated response payload limit. The frame and
signal snapshots are frozen together per client; all pages carry the same
`sample_seq` and change sequence.

Changes that happen during snapshot transfer are not lost. `SUBSCRIBE_ALL`
first queues the difference between the frozen snapshot and current canonical
state, then queues `SUBSCRIBED`. Live updates follow that acknowledgement in
stream order.

## Framing

Conceptual header:

```c
struct roadcast_message_header {
    uint32_t magic;
    uint16_t protocol_version;
    uint16_t message_type;
    uint32_t flags;
    uint32_t payload_bytes;
    uint64_t sequence;
    uint64_t timestamp_ns;
};
```

Protocol version 3 encodes the 32-byte header in network byte order:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | magic |
| 4 | 2 | protocol version |
| 6 | 2 | message type |
| 8 | 4 | flags |
| 12 | 4 | payload bytes |
| 16 | 8 | change sequence |
| 24 | 8 | monotonic sample timestamp in nanoseconds |

Requirements:

- Integers have endianness defined by the protocol.
- `payload_bytes` does not include the header.
- Maximum message size is announced in the handshake.
- Unknown messages can be ignored only when marked as optional.
- Malformed payload terminates only the client session.

The framing header and high-frequency delta records are native Roadcast wire
types, not FlatBuffers or Protocol Buffers envelopes. Their serialized layout
must be defined field by field; an in-memory C struct must never be sent
directly when compiler padding or host endianness could affect the result.

This fixed representation is part of the public protocol and does not require
clients to link libuv. libuv is a daemon implementation detail.

`WELCOME` announces two directional limits:

- `max_request_payload`: The largest payload the daemon input buffer accepts.
- `max_response_payload`: The largest payload the daemon will emit.

The current daemon announces 4064 and 2016 bytes respectively. These are
transport limits, not permission to use arbitrary payload sizes: each command
still has an exact payload contract.

Frame and signal batches begin with this 20-byte prefix:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 8 | sample sequence |
| 8 | 4 | total catalog count for this kind |
| 12 | 4 | page start index, or `UINT32_MAX` for a delta batch |
| 16 | 2 | record count |
| 18 | 2 | reserved, zero |

Snapshot pages must be contiguous and retain one sample and change sequence.
Delta records may be split across multiple messages carrying the same change
sequence.

## Schema

Each catalog entry must describe its identity and provenance.

Conceptual fields:

```text
stable_id
index
kind                 frame | signal | property
namespace
name
source
raw_type
physical_type
unit
scale
offset
calibrated
update_mode
source_address       CAN id, property id/area, or equivalent
```

The schema must represent unknown information without inventing values. For
example, absent unit is different from confirmed empty unit.

Protocol version 3 uses an explicitly versioned generated binary schema. Each
entry carries stable ID, index, invalid-signal index, CAN ID, kind, source,
width, signed/calibrated flags, scale, offset, name, and unit.

FlatBuffers remains a possible future encoding for schema and control payloads.
It is not used by protocol version 3 and will not wrap telemetry deltas.

## IDs and Indices

`stable_id` identifies an entry semantically across compatible schemas. `index`
is only the compact position in the current snapshot.

Clients use indices on the hot path, but must invalidate them when the schema
hash changes.

CAN signal IDs use FNV-1a 64 over the versioned namespace, big-endian 16-bit
CAN ID, and UTF-8 signal name. The schema hash additionally covers numeric
interpretation and bit layout.

The schema also distinguishes:

- `kind`: Raw CAN frame, decoded CAN signal, or VHAL property.
- `source`: Technical mechanism by which Roadcast obtained the data.
- `domain`: Inferred producer or semantic grouping, such as PEPS, BCM, or BMSH.

`domain` does not replace `source`: a PEPS value can appear as a CAN signal
and as a property republished by the VHAL.

## Snapshot

The snapshot represents the complete known state at a specific sequence.

Each value must distinguish:

- Never observed.
- Observed and invalid.
- Observed and valid.
- Raw available, physical unknown.
- Physical calculated with estimated scale.
- Physical calculated with calibrated scale.

Decoded CAN values contain a 64-bit raw value, an IEEE-754 binary64 physical
value, first-observed and last-change monotonic timestamps, an observation
state, and a calibration flag.

Protocol version 3 observation states are:

| Value | Meaning |
|---:|---|
| 0 | source unavailable or the frame could not be read |
| 1 | source resolved, but no transition has been observed since daemon start |
| 2 | observed and valid |
| 3 | observed but invalid according to an explicit catalog invalid flag |

Raw frames use states 0 through 2. Signals may also use state 3. A non-zero
memory value at daemon startup is a baseline, not proof that Roadcast observed
the frame arrive. A frame becomes observed when its eight source bytes first
change after that baseline. Therefore a frame that remains constant for the
entire daemon lifetime honestly remains `never observed`.

`calibrated` describes whether scale, offset, and unit are confirmed. It is
independent from observation validity.

## Updates

An `UPDATE_BATCH` contains all entries changed in a cycle or in a small
aggregation window.

The server does not promise to deliver every intermediate state to a slow
client. It promises:

- Preserve the most recent state.
- Signal loss of continuity.
- Allow resynchronization.
- Never block the sampler because of the client.

Clients that need to record every transition must negotiate a specific
capability with clearly announced memory limits.

The initial protocol sends all deltas and leaves filtering by `kind`, `source`,
or `domain` to the client. Server-side filters are a future extension, not a
condition to expose the complete catalog.

## Heartbeat

Heartbeats exist even when no signal changes. Protocol version 3 carries:

- Latest sample sequence.
- Latest change sequence, equal to the header sequence.
- Batches dropped for this client.
- Sampler observations coalesced by the event-loop handoff.
- Effective recent sampling frequency in milliHertz.
- Source state: unavailable, available, or degraded fallback.

The header timestamp is the monotonic timestamp of the latest source sample.

This way, a stopped value is not confused with a dead daemon.

## Optional Shared Memory

The protocol may announce a fast path through shared memory. It must be
optional: all data and all control operations must remain accessible through
the base transport.

If implemented without a dedicated SELinux policy, each fd provided by a client
represents an exclusive view for that client. It never replaces the canonical
snapshot or the buffer of another consumer.
