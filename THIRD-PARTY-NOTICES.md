# Third-party notices and credits

Drive Assist is GPLv3. It includes, or is built on the work of, the projects
below. Their licenses are compatible with GPLv3 and are kept with their code.

## roadcast — by Timoteo Sousa

- https://github.com/Timoteohss/roadcast
- Copyright 2026 Timoteo Sousa. Apache License 2.0.
- **What we use:** the whole CAN reader daemon, `roadcastd`, in
  `third_party/roadcast/` (upstream commit 22153ec). Its C code is unchanged.
  It ships inside the ModeHelper apk and powers "Exact energy".
- Its signal catalog (`data/dbc.json`), protocol design and the research
  behind them (reading the VHAL's `MMI_Rx_*` buffers) are Timoteo's work.
- Full license and notice: `third_party/roadcast/LICENSE`,
  `third_party/roadcast/NOTICE`.

## Capy — by Timoteo Sousa

- https://github.com/Timoteohss/capy
- Apache License 2.0.
- **What we use:** `modehelper/.../LocalAdbShell.java` is a Java port of
  Capy's `LocalAdbShell.kt` (the local ADB shell-v2 client). The way the
  daemon is installed and started through the car's own adbd, the checks on
  it, and the signal choices and bands for battery power follow Capy's
  roadcast integration. Capy proved all of this on EX2 cars first.

Thank you, Timoteo. Without roadcast and Capy, Drive Assist would have no
exact energy without a dongle.

## libuv

- https://github.com/libuv/libuv, v1.51.0
- Copyright the libuv project contributors. MIT License (plus the notices in
  `LICENSE-extra`).
- **What we use:** the event-loop library roadcastd is built on. Not stored
  in this repo: `tools/build-roadcastd.sh` downloads the official v1.51.0
  source and checks its SHA-256. Its license ships in the helper apk with
  the binary (`assets/licenses/libuv-LICENSE`).
