#!/usr/bin/env bash
# tools/push-release.sh
# Creates a clean, curated release commit on public master from a locally
# validated integration tree, then tags and pushes it. The source history never
# becomes part of public master.
#
# Usage:
#   ./tools/push-release.sh vX.Y.Z "Release title/description" [source-ref]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [ $# -lt 2 ] || [ $# -gt 3 ] || [ -z "$1" ] || [ -z "$2" ]; then
    echo "Usage: $0 vX.Y.Z \"Release title/description\" [source-ref]"
    echo "Example: $0 v1.2.1 \"Energy metrics and UI refinements\" release/v1.2"
    exit 1
fi

TAG="$1"
MSG="$2"
SOURCE_REF="${3:-next}"
case "$TAG" in v[0-9]*.[0-9]*.[0-9]*) ;; *) echo "Tag must use vX.Y.Z (for example v0.3.0)." >&2; exit 1;; esac

# Check that working tree is clean. Ignored `.release/` validation evidence is
# deliberately allowed; every source change, including untracked files, must
# be committed before its tree can be published.
if [ -n "$(git status --porcelain -- . ':!.release')" ]; then
    echo "Error: Working directory has uncommitted changes. Please commit or stash them first."
    exit 1
fi

# Ensure source and public release target exist locally.
if ! git rev-parse --verify "${SOURCE_REF}^{commit}" >/dev/null 2>&1; then
    echo "Error: source ref '$SOURCE_REF' does not exist."
    exit 1
fi

if ! git rev-parse --verify master >/dev/null 2>&1; then
    echo "Error: 'master' branch does not exist."
    exit 1
fi

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)

SOURCE_SHA=$(git rev-parse "${SOURCE_REF}^{commit}")
SOURCE_TREE=$(git rev-parse "${SOURCE_REF}^{tree}")
MASTER_TREE=$(git rev-parse master^{tree})

if git rev-parse --verify "refs/tags/$TAG" >/dev/null 2>&1; then
    echo "Error: tag '$TAG' already exists. Releases are immutable." >&2
    exit 1
fi

VALIDATION_FILE="$REPO_ROOT/.release/validation-${SOURCE_SHA}.json"
if [ ! -f "$VALIDATION_FILE" ]; then
    echo "Release blocked: no validation evidence for $SOURCE_REF ($SOURCE_SHA)." >&2
    echo "Run ./tools/validate-release.sh $SOURCE_REF from that clean checkout first." >&2
    exit 2
fi
if ! grep -Fq "\"source_sha\": \"$SOURCE_SHA\"" "$VALIDATION_FILE"; then
    echo "Release blocked: validation evidence does not name the selected source SHA." >&2
    exit 2
fi

if [ "$SOURCE_TREE" = "$MASTER_TREE" ]; then
    echo "Notice: source and master trees are identical. Nothing new to release."
    exit 0
fi

MASTER_HEAD=$(git rev-parse master)

echo "Creating $TAG from validated $SOURCE_REF (${SOURCE_SHA:0:7})..."
NEW_COMMIT=$(git commit-tree "$SOURCE_TREE" -p "$MASTER_HEAD" -m "release: $TAG - $MSG")

echo "Created commit: $NEW_COMMIT"
git update-ref refs/heads/master "$NEW_COMMIT"
git tag -a "$TAG" "$NEW_COMMIT" -m "Drive Assist $TAG"

if [ "$CURRENT_BRANCH" = "master" ]; then
    git reset --hard "$NEW_COMMIT"
fi

echo "Pushing public master and immutable release tag to origin..."
git push origin master "refs/tags/$TAG"

echo ""
echo "Successfully published $TAG to origin/master!"
echo "Master HEAD: $(git rev-parse --short master) - $MSG"
echo "Active branch remains: $CURRENT_BRANCH"
