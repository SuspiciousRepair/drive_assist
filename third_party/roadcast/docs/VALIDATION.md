# Validation

This document records meaningful host and vehicle validation runs. Runtime
telemetry is not persisted on the vehicle.

## 2026-07-26: First Raw CAN Tracer

### Scope

The tracer exercised:

```text
OEM VHAL memory -> 60 Hz sampler -> libuv -> @roadcast-test
                -> two concurrent roadcastctl clients
```

The test used unique temporary binary and socket names. It did not stop or
replace any existing process.

### Target State

```text
device: IHU629G
Android: 9
architecture: ARM64
VHAL PID: 295
SELinux observed state: Permissive
```

Roadcast did not change SELinux state, restart the device, stop the VHAL, or
modify vendor files.

### Build

The daemon and client were cross-compiled as PIE executables for Android API 28
with Android NDK `28.2.13676358` and libuv `1.51.0`.

Both outputs were identified as ARM AArch64 ELF64 executables using
`/system/bin/linker64`.

### Commands

```bash
adb -s <head-unit-address>:5555 push \
  build/android-arm64-v8a-api28/roadcastd \
  /data/local/tmp/roadcastd-test

adb -s <head-unit-address>:5555 shell \
  'su 0 /data/local/tmp/roadcastd-test --hz 60 --socket @roadcast-test'

adb -s <head-unit-address>:5555 shell \
  'su 0 /data/local/tmp/roadcastctl-test \
    --socket @roadcast-test --seconds 5'
```

Two instances of the final client command ran concurrently.

### Results

The source resolver found all `111/111` raw CAN frame symbols in the OEM VHAL.

Daemon statistics:

```text
effective sampling rate: 59.9-60.0 Hz
changed frames per sample: approximately 5.7
source read errors: 0
client queue drops: 0
```

Each client received:

```text
protocol version: 1
snapshot frames: 111
update batches: approximately 20 per second
changed frame records: approximately 341 per second
sequence gaps: 0
resynchronizations: 0
```

The batch rate is lower than the sampling rate because Roadcast sends an update
batch only when at least one frame changed. `sample_seq` advanced at
approximately 60 per second independently of the batch rate.

Both clients disconnected independently. The daemon was then stopped and both
temporary executables were removed from `/data/local/tmp`.

## 2026-07-26: Protocol Version 3 Permissive Diagnostic

### Scope and Limitation

This run exercised the current version 3 path:

```text
OEM VHAL memory -> 60 Hz sampler -> @roadcast-v3-202045
                -> two concurrent version 3 clients for 12 seconds
```

The device was still `Permissive`. The daemon ran as `u:r:su:s0`, launched with
`su 0`. This validates the functional and performance path but **does not prove
that Roadcast works under SELinux Enforcing**. Enforcing validation remains a
release gate and must use the intended production launch domain.

Roadcast did not call `setenforce`, change policy, reboot, stop the VHAL, or
modify vendor files.

### Target and Build

```text
device: IHU629G
Android: 9
architecture: ARM64
VHAL PID: 295
VHAL process: android.hardware.automotive.vehicle@2.0-service
SELinux state: Permissive
Roadcast context: u:r:su:s0
NDK: 28.2.13676358
protocol: 3
schema: version 1, hash 0x0ceade5f14ed7915
```

### Results

The source resolver found all `111/111` frame symbols. The daemon's ten-second
statistics window reported:

```text
effective sampling rate: 59.90 Hz
changed frames per sample: 6.10
source read errors: 0
/proc/PID/mem fallback batches: 0
coalesced samples: 0
client queue drops: 0
session timeouts/rejections: 0
```

Both clients received the same initial frozen snapshot and completed 12 seconds
with:

```text
frame batches: approximately 20-23 per second
changed frame records: approximately 360-375 per second
signal batches: approximately 19-22 per second
changed signal records: approximately 49-63 per second
sequence gaps: 0
resynchronizations: 0
```

A process sample while both clients were connected reported two threads,
4712 KiB RSS, 12332 KiB virtual size, and 3.3% CPU in that `top` interval.

No AVC entry matching the Roadcast process, PID, VHAL, `process_vm_readv`, or
`/proc/PID/mem` appeared in the captured denial tail. Absence of a matching
permissive AVC is useful evidence, but it is not a substitute for an Enforcing
run.

The daemon was terminated normally and both uniquely named test binaries were
removed from `/data/local/tmp`.

## 2026-07-26: Protocol Version 3 Under SELinux Enforcing

### Safety Controls

This was an explicitly authorized transient Enforcing test. Before changing
state, a root watchdog shell was started to restore `Permissive` after 25
seconds. The active test shell also installed an exit trap that restored the
original state.

The observed sequence was:

```text
before:   Permissive
active:   Enforcing
restored: Permissive
```

The host independently confirmed `Permissive` after the test. No policy,
partition, vendor file, VHAL process, or boot configuration was changed.

### Scope

```text
OEM VHAL memory -> roadcastd in u:r:su:s0 at 60 Hz
                -> abstract Unix socket
                -> roadcastctl in u:r:su:s0 for 4 seconds
```

The daemon resolved all `111/111` frame symbols and remained alive in
`Enforcing`. The version 3 client received:

```text
schema: 815 signals, version 1, hash 0x0ceade5f14ed7915
snapshot: sample_seq=110, change_seq=37
frame batches: approximately 20 per second
changed frame records: approximately 367-372 per second
signal batches: approximately 19-20 per second
changed signal records: approximately 49-55 per second
sequence gaps: 0
resynchronizations: 0
```

The client exited successfully. The relevant kernel denial query returned no
AVC entry for Roadcast, the VHAL, remote memory access, or the test PID while
Enforcing was active.

### What This Proves

This proves that the current source discovery, `process_vm_readv` acquisition,
protocol version 3 server, and abstract-socket client path work under Enforcing
when both Roadcast processes run in `u:r:su:s0`.

It does not yet prove that an Android application domain such as
`u:r:system_app:s0` can connect to `@roadcast`, nor that a future production
launcher will assign the daemon the same `su` domain. Those two execution-domain
boundaries remain part of the production installation test.

The daemon was terminated, both uniquely named binaries were removed from
`/data/local/tmp`, and no Roadcast test process remained.

## Host Validation

`make test` validates explicit wire encoding, handshake payloads, frame batches,
reserved fields, directional payload limits, page metadata, observation states
and timestamps, capacity limits, truncation, and version 3 heartbeat payloads.

`make integration` starts a high-load 240 Hz fake source over a temporary
filesystem Unix socket. It verifies:

- Sixteen idle pre-HELLO connections cannot deny service after the two-second
  handshake deadline.
- Malformed headers, oversized payload declarations, reserved flags, commands
  out of order, duplicate HELLO messages, random bytes, and a byte-fragmented
  HELLO remain isolated to their sessions.
- Two clients receive complete snapshots and continuous delta streams without
  sequence gaps while another client stops reading.
- The 111-frame snapshot is retrieved as multiple bounded pages.
- Decoded fake-source updates are observed/valid and carry non-zero
  first-observed and last-change timestamps.
- A dedicated one-slot session pauses and resumes a multipage subscription
  catch-up before receiving `SUBSCRIBED`.
- A paused client overflows its bounded queue, receives `RESYNC_REQUIRED`,
  retrieves a new consistent snapshot, subscribes again, and resumes with zero
  sequence gaps after recovery.

Accepted connections are identified with `SO_PEERCRED` on Android/Linux or
`getpeereid()` on macOS. A single UID may hold at most eight sessions. Sessions
that do not complete HELLO in two seconds or do not reach a subscribed state in
ten seconds are closed. These controls bound unauthenticated setup work; they
are not an authorization policy.

The temporary directory and socket are removed after the test.

The sampler-to-event-loop handoff no longer drops an observation when its
snapshot mutex is contended. libuv notifications may still coalesce, so the
daemon reports the number of intermediate samples represented by a newer
published snapshot as `coalesced`.

The same suite passes with UndefinedBehaviorSanitizer enabled. AddressSanitizer
could not be evaluated on the current macOS host because the Clang ASan
runtime deadlocked inside `AsanInitInternal` before entering the test program's
`main()`.

## Dense Updates and No-Subscriber Distribution

`make integration` also runs a dense fake source at 1 and 60 Hz. Unlike the
incrementing fake source, `--fake-dense` alternates every source bit each tick,
so all 111 frames and all 815 signals change together. Concurrent CLI and SDK
readers must remain connected with zero resynchronizations, gaps or drops.
The existing slow-reader integration still checks that actual backlog causes
bounded resynchronization without interfering with other clients.

A complete dense update needs two raw-frame messages and seventeen signal
messages. The default queue is calculated from those ceilings plus one
heartbeat (currently 20 slots). It does not grow with runtime backlog.
`--client-queue-slots` can deliberately lower that limit to exercise overflow
and catch-up behavior. The broadcast scratch arrays use the same calculated
ceilings, so catalog changes cannot silently truncate a batch at a magic cap.

`make test` checks that canonical raw/decoded values, validity, frame timestamps
and sample/change sequences continue advancing without subscribers, with
setup-only clients, and with clients awaiting resynchronization. None of
these cases may serialize a broadcast chunk. The ten-second daemon statistics
expose `serialized_chunks` for observing this behavior without per-tick logs.

These are host functional checks. They do not measure vehicle power savings
or alter the acquisition rate, protocol, firmware or suspension policy.

## 2026-10-18: Dense-Broadcast Fix on Android ARM64/API 28

Commit `74fd390` was validated on IHU629G using temporary binaries and
separate abstract sockets. The existing production daemon (PID 4861) was
preserved throughout. No firmware, boot, Wi-Fi or suspension setting changed.

The real-source run resolved 111/111 frames from VHAL PID 294 at 59.9–60 Hz.
The no-client statistics window recorded zero serialized chunks despite
approximately 5.92 changed frames per sample. With two concurrent CLI/SDK
clients, serialization resumed (332 chunks in the next ten-second window).
Both clients finished without gaps, resynchronizations or drops; the daemon
reported zero read errors, fallback batches, coalesced samples or queue drops.
The SOC SDK read was observed/valid and calibrated (55.0%).

A separate synthetic dense run on the same head unit alternated all source
bits at 60 Hz. Concurrent CLI/SDK clients ran for five seconds without gaps,
resynchronizations or drops. Synthetic signal values are test stimuli and
must not be interpreted as vehicle measurements.

All temporary processes exited and the test directory/binaries were removed.
The original daemon retained PID 4861. No experimental suspension blocker
remained active. This short functional run does not establish electrical
power savings or long-term reliability.

## Source Recovery

`make test` covers exponential source retry deadlines using an injected backend
and clock. Waiting ticks perform no source I/O, delay is capped at 30 s, only
a successful read resets it, generation changes on reopen, and failed partial
reads are discarded. Successful degraded reads do not trigger reconnection.

On Linux/Android, `test_vhal_source` spawns disposable executable fixtures
exporting one known CAN-buffer symbol. It checks real symbol/address discovery,
vectored and fallback reads, permission cooldown, unsupported-syscall caching,
interruption retry, retained error codes, start-time mismatch, and rejection of
a readable replacement PID with the old process's identity descriptor. A
decoy mentioning the target only in its arguments must not match discovery.

`make integration` additionally runs `recovery_integration.sh` on Linux. A
daemon starts without its fixture source; the source appears, exits, and
restarts with a new PID. One SDK connection must observe unavailable →
available → unavailable → available, with valid frame data after each recovery
and zero disconnects, resynchronizations or drops. The original VHAL is never
restarted by these tests. Process-specific checks skip on macOS.

On kernels with Yama ptrace restrictions, each disposable changing fixture
grants read access specifically to the test daemon with `PR_SET_PTRACER`.
This permits the sibling processes used by the integration test without
changing system ptrace settings or permissions on a real VHAL process.

These source tests and the complete recovery integration also passed on the
IHU629G under Android 9/API 28. A separate temporary real-source run resolved
111/111 frames at 59.9 Hz, with zero source errors, fallback batches, coalesced
samples or queue drops. Its SDK session stayed connected without resyncs or
drops. The production Roadcast and VHAL were preserved, and temporary test
files/processes were removed. No electrical energy savings are inferred.
