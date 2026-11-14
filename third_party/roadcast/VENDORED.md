# Vendored: roadcast

- Upstream: https://github.com/Timoteohss/roadcast (Apache-2.0, see LICENSE and NOTICE)
- Commit: 22153ec9cb93e79adca3b5161cd3c609c222f543
- Built by: `tools/build-roadcastd.sh` (not by the upstream Makefile or scripts)

## Changes from upstream

Every change to this directory runs as root on the car. Review it as such.

1. `src/roadcastd.c`: `--allow-uid <uid>` (repeatable, up to 8). When given,
   the daemon closes any client whose `SO_PEERCRED` uid is not on the list.
   uid 0 is always allowed. Without the flag, behaviour is upstream's.
   Test: `tools/test-roadcastd-allowlist.sh`.
2. Removed scripts we do not use: the upstream Android build/install scripts
   (replaced by `tools/build-roadcastd.sh`), the release-manifest generator,
   and the log collectors (they carry the upstream author's device address).

Nothing else is changed. To update: copy the new upstream tree here, re-apply
the two changes above, and re-run the allow-list test.
