# Android Deployment

Roadcast does not modify Android system or vendor partitions. The current
vehicle integration delegates distribution and supervision to a privileged
application:

- The APK bundles the ARM64/API 28 `roadcastd` executable.
- The application compares its SHA-256 hash with `/data/local/tmp/roadcastd`.
- The application copies the executable only when it is missing or its version
  changes.
- The installed executable is `root:root` with mode `0755`.
- The application starts it through the vehicle's root `adbd`.
- The application rejects an existing daemon outside `u:r:su:s0`.
- A foreground-service watchdog restores it after an unexpected exit.

The installed executable persists across normal app process restarts and
reboots. It is rewritten only after an APK carrying a different binary is
installed. Runtime telemetry remains entirely in memory.

## Lifecycle

On the validated device:

- Root `adbd` listens on TCP port 5555.
- `adbd` runs in `u:r:su:s0`.
- A new ADB host identity is accepted without user authorization.
- A shell opened through that connection runs in `u:r:su:s0`.

The client application uses a minimal ADB 1.0.1 host over `127.0.0.1:5555`.
After `BOOT_COMPLETED`, and whenever its watchdog cannot connect to `@roadcast`,
it asks `adbd` to execute:

```bash
nohup /data/local/tmp/roadcastd \
  --hz 60 \
  --socket @roadcast \
  </dev/null >/dev/null 2>&1 &
```

This launcher is vehicle-specific. The application must report an explicit
degraded state if local ADB is disabled, moved to another port, or starts
requiring authentication. The launcher requires ADB `shell_v2`: the vehicle's
legacy `shell:` service kills a detached child when the session closes. Other
clients remain independent protocol consumers and do not gain permission to
manage the daemon.

## Manual Validation

The optional installer reproduces the same target path without modifying
`system`:

```bash
export ANDROID_NDK=/path/to/android-ndk
make android
scripts/install-android.sh --device <head-unit-address>:5555
```

This script is for development and recovery. Production distribution is through
the APK asset.

## GitHub Build Artifacts

The `Build` GitHub Actions workflow cross-compiles the production Android
ARM64/API 28 artifacts after host tests pass. Normal workflow runs retain the
files for 14 days. Each successful push to `main` also replaces the assets in
the rolling `edge` prerelease and moves that tag to the validated commit. A
`v*` tag publishes a separate versioned GitHub Release:

```text
roadcastd
roadcastctl
libroadcast_client.so
SHA256SUMS
RELEASE_MANIFEST.json
```

Verify `SHA256SUMS` before copying release binaries into a consumer. The
application repository uses `scripts/update_roadcast_client.sh` to install the
selected daemon and client library into its APK source tree.

`RELEASE_MANIFEST.json` is the machine-readable update contract for consumers.
It records the release tag and commit, Android ABI/API requirements, protocol
and schema compatibility, and the SHA-256 and byte size of every Android
artifact. Values for the protocol and schema versions are read directly from
the public C headers during the build. Versioned tags expose their version
without the leading `v`; builds from `main` use the rolling `edge` version and
pull-request artifacts use `ci`.

The manifest is integrity metadata published through the same GitHub Release as
the binaries. It is not a cryptographic signature and does not provide an
independent authenticity boundary if the repository or release credentials are
compromised.

## Security Boundary

Calling `/system/xbin/su` from an Android `system_app` changes the Linux UID but
does not transition out of `u:r:system_app:s0` on this vehicle. The kernel then
denies access from Roadcast to `hal_vehicle_default` while SELinux is Enforcing.

Launching through root `adbd` creates Roadcast in `u:r:su:s0`, the same domain
that passed the Enforcing source-read validation. App-domain access to the
Roadcast abstract socket and the complete local-ADB bootstrap path remain
release gates until validated together under Enforcing.
