# Curated release process

Drive Assist maintains a public integration history and a curated release
history. This gives contributors visible credit while preserving a controlled,
reproducible stable channel. PII is prevented before public pushes, not removed
from history later.

## Branch roles

| Location | Ref | Purpose |
| --- | --- | --- |
| Public | `next` | Integration and nightly-candidate builds of reviewed fixes and features. |
| Public | `feat/*`, `fix/*`, `docs/*` | Short-lived focused work; PRs target `next`. |
| Public | `release/vX.Y` | Short-lived stabilization of one planned version. |
| Public | `master` | Curated, validated release trees only. |
| Public | `vX.Y.Z` tags | Immutable identifiers for published releases. |

Public PRs target `next`. They are never a vehicle release by themselves;
accepted changes are reviewed and squash-merged when appropriate. Contributors
remain visible through the PR, commit trailers, and release notes.

Before the first public push in a clone, run `./tools/install-git-hooks.sh`.
The tracked pre-push hook scans precisely the additions being sent; GitHub
Actions performs the same check independently after the push. Hooks are a
backstop, not permission to commit sensitive material.

## Validation evidence

Run `./tools/validate-release.sh release/vX.Y` from a clean checkout of the
candidate.
It runs the PII guard and the normal non-hardware verification contract, then
writes a local `.release/validation-<source-sha>.json` record containing the
exact source SHA and release-APK checksum. `.release/` is intentionally
ignored: it is machine-local evidence for the maintainer's release decision.

`tools/push-release.sh` refuses to publish unless that evidence matches the
chosen source SHA. It creates a new `master` commit from only the validated
tree, attaches an annotated `vX.Y.Z` tag, and pushes both. The original
source branch's commit history is not copied into the curated `master` commit.

## Gates

| Stage | Required checks |
| --- | --- |
| Feature proposal | PII scan before push, automated verification, focused review; no claim of vehicle validation without evidence. |
| `next` integration | Tests, release assembly, static-analysis baseline review, and safety review of touched vehicle-facing code. |
| Candidate | SHA-bound validation evidence and applicable device-validation checklist entries. |
| Published release | Curated `master` tree, immutable tag, APK checksums, release notes. |
| OTA | Separate owner-authorized deployment after the release/candidate artifact is selected. |

GitHub Actions verifies pushes to `next`, `release/**`, and `master`, plus PRs
into `next` or a release branch. The artifact workflow publishes downloadable
candidate artifacts from `next` and release branches; only `v*` tags create a
GitHub Release. Neither workflow announces or installs an OTA.
