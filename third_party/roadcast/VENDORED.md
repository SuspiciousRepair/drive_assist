# Vendored: roadcast

- Upstream: https://github.com/Timoteohss/roadcast (Apache-2.0, see LICENSE and NOTICE)
- Commit: 22153ec9cb93e79adca3b5161cd3c609c222f543
- Built by: `tools/build-roadcastd.sh` (not by the upstream Makefile or scripts)

## Changes from upstream

Every change to this directory runs as root on the car. Review it as such.

None to the code: `src/` and `include/` are upstream's, byte for byte.
Any app on the car may connect and read (a deliberate choice: one shared,
open, read-only source instead of apps fighting over who owns the car; see
`docs/CAN-API.md`).

1. Removed scripts we do not use: the upstream Android build/install scripts
   (replaced by `tools/build-roadcastd.sh`), the release-manifest generator,
   and the log collectors (they carry the upstream author's device address).

To update: copy the new upstream `src/`, `include/`, `data/`, `docs/` here,
drop the scripts listed above again, and rebuild with tools/build-roadcastd.sh.
