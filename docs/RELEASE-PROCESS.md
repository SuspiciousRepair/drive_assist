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

## Documentation

A release is not done until the documents say what the release does. Do this
in the release-notes pull request (`docs: vX.Y.Z release notes`), before the
validation run.

Run `./tools/check-docs.sh --report`. It lists the features and fixes merged
since the last release, the guides that changed, and the Portuguese pages that
are older than their English page. Then go through this list:

1. **`CHANGELOG.md`**: a `## [vX.Y.Z]` section, and `[Unreleased]` left empty.
2. **`RELEASE-NOTES.md`**: a `## vX.Y.Z` section in plain words for the car's
   update dialog. Then run `drivemem/build.sh` with `NO_DEPLOY=1` once, or copy
   the section by hand, so `drivemem/src/main/assets/changelog.txt` matches.
3. **Guides**: for each feature in the report, the guide a driver reads says
   it exists: `docs/DRIVER-GUIDE.md` and its `.pt-BR.md` pair, and the guide of
   the integration it touches (Home Assistant, ABRP, dashcam, statistics).
4. **Safety and privacy**: if the release adds something the app reads,
   changes, or sends, or a new network address, `docs/WELCOME.md` says so. A
   new package, permission, system file change or root process also goes in
   `docs/ADB-ROOT-SAFETY.md` (both languages).
5. **References**: a new or changed hub key is in `docs/HUB-DATA.md`; a new
   car property is in `docs/field-catalog.md`; a new library is in
   `THIRD-PARTY-NOTICES.md`.
6. **Plans**: a plan that this release finished moves from `plan/active/` to
   `plan/archive/implemented/`, and `plan/README.md` follows.
7. **Screenshots**: retake a picture in `docs/screenshots/` when its screen
   changed.

What a script checks, on every push and again at validation
(`tools/check-docs.sh`, called by `tools/verify.sh`):

- every relative link in a Markdown file points to a file that exists;
- every hub key constant in a `*Hub.java` file is in `docs/HUB-DATA.md`;
- every file in `plan/active/` is in `plan/README.md`, and nothing is under
  `docs/plan/` (`docs/` is the public website);
- `changelog.txt` is the newest section of `RELEASE-NOTES.md`, and that
  version is the newest one in `CHANGELOG.md`.

`tools/push-release.sh` also refuses a tag that has no section in
`CHANGELOG.md` and `RELEASE-NOTES.md`.

Items 3, 4 and 7 cannot be checked by a script. They are the reason this list
exists.

## Gates

| Stage | Required checks |
| --- | --- |
| Feature proposal | PII scan before push, automated verification, focused review; no claim of vehicle validation without evidence. |
| `next` integration | Tests, release assembly, static-analysis baseline review, and safety review of touched vehicle-facing code. |
| Candidate | SHA-bound validation evidence and applicable device-validation checklist entries. |
| Published release | Curated `master` tree, immutable tag, APK checksums, release notes, and the [documentation](#documentation) list done. |
| OTA | Separate owner-authorized deployment after the release/candidate artifact is selected. |

GitHub Actions verifies pushes to `next`, `release/**`, and `master`, plus PRs
into `next` or a release branch. The artifact workflow publishes downloadable
candidate artifacts from `next` and release branches; only `v*` tags create a
GitHub Release. Neither workflow announces or installs an OTA.
