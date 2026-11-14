# Roadcast

Roadcast is a local, read-only broker for vehicle signals.

Its purpose is to expose to multiple applications the data that reaches the
VHAL but is not published through the Android Automotive APIs. The daemon reads
VHAL memory once, keeps the complete known vehicle state in RAM, and distributes
that state to every connected client.

## Principles

- **Read-only:** Roadcast observes the vehicle. It never sends commands to the
  VHAL or to the CAN bus.
- **App-agnostic:** N applications can consume the same daemon.
- **Complete catalog:** Useful and apparently useless signals are exposed
  equally.
- **No implicit persistence:** Snapshots, queues, and transport stay in RAM.
- **Low latency:** Acquisition targets 60 Hz, configurable.
- **A slow consumer never blocks the producer:** Every client has a bounded
  queue.
- **Dynamic discovery:** The daemon publishes schema, IDs, types, units, and
  origin.
- **Explicit compatibility:** Protocol, schema, and implementation are versioned
  independently.

## Transport

The primary transport is an abstract Unix socket, so no socket file is created
on the filesystem:

```text
@roadcast
```

Every client receives:

1. The schema/catalog.
2. A complete initial snapshot.
3. Incremental batches carrying only the entries that changed.
4. Heartbeats and sequence numbers, so loss or stalling is detectable.

Shared memory may later exist as an optional fast path, but it will never be
required to implement a Roadcast client.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Protocol](docs/PROTOCOL.md)
- [Decisions](docs/DECISIONS.md)
- [Validation](docs/VALIDATION.md)
- [Catalog surface](docs/CATALOG_SURFACE.md)
- [Client SDK](docs/CLIENT_SDK.md)
- [Android deployment](docs/ANDROID_DEPLOYMENT.md)
- [Changelog](CHANGELOG.md)

## Status

Version 0.1.0. Protocol version 3, schema version 1.

The first executable tracer is implemented and validated. It provides:

- A libuv-based multi-client daemon.
- A deterministic in-memory fake source for host validation.
- Explicit binary framing for raw CAN snapshots and delta batches.
- A generated catalog containing 815 decoded CAN signals.
- Paged schema discovery with stable IDs and a canonical schema hash.
- Consistent decoded-signal snapshots with subscription catch-up.
- Paged raw-frame snapshots and explicit observation state/timestamps.
- Directional request/response limits and operational heartbeat metrics.
- A reference CLI client and a reusable client SDK.
- Protocol, malformed-input, resynchronization, and multi-client tests.
- Android ARM64/API 28 cross-compilation scripts.

**The wire protocol is experimental and is not frozen.** At 0.x, message
layouts, numeric values, and names may change without a compatibility path. See
the known limits in [CHANGELOG.md](CHANGELOG.md).

Inspect a decoded signal by name:

```bash
roadcastctl --socket @roadcast --signal VehicleSpeed --seconds 10
```

## Host build

Requirements:

- A C11 compiler.
- libuv discoverable through `pkg-config`.
- `libelf` headers on hosts that do not provide `elf.h`.

On macOS:

```bash
brew install libuv libelf
make test
make integration
```

The host tracer uses a filesystem Unix socket because macOS does not support the
Linux abstract Unix socket namespace. The socket is created inside a temporary
test directory and removed after the test.

## Android build

```bash
export ANDROID_NDK="$HOME/Library/Android/sdk/ndk/28.2.13676358"
scripts/build-libuv-android.sh
make android
```

An existing Android libuv installation can be reused:

```bash
LIBUV_PREFIX=/path/to/libuv-android make android
```

## Continuous integration and releases

GitHub Actions runs host unit/integration tests and cross-compiles the Android
ARM64/API 28 binaries on every pull request and push to `main`. Each successful
run publishes a 14-day workflow artifact containing:

- `roadcastd`
- `roadcastctl`
- `libroadcast_client.so`
- `SHA256SUMS`
- `RELEASE_MANIFEST.json`

After a successful push to `main`, the same files replace the assets in the
rolling `edge` GitHub prerelease, whose tag points to the validated commit.
Pushing a `v*` tag, for example `v0.1.1`, runs the same gates and publishes a
separate versioned GitHub Release. The JSON manifest exposes the commit,
Android target, protocol/schema compatibility, and artifact hashes for
application updaters. The release job uses the workflow's scoped `contents:
write` permission; pull-request and build jobs remain read-only.

See [Android deployment](docs/ANDROID_DEPLOYMENT.md) for installation and the
supervision model.

## License

Apache License 2.0. See [LICENSE](LICENSE).

This is an independent research and interoperability project. It is not
affiliated with or endorsed by any vehicle manufacturer, and it redistributes
no manufacturer code, binary, or proprietary database. Read [NOTICE](NOTICE)
before using it: running this software may affect your vehicle warranty, and you
are responsible for having the right to access the vehicle you run it on.
