#!/usr/bin/env bash
# tools/push-release.sh
# Creates a clean squashed release commit on master from dev and pushes to origin/master.
#
# Usage:
#   ./tools/push-release.sh "Release title/description"
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [ $# -lt 1 ] || [ -z "$1" ]; then
    echo "Usage: $0 \"<commit message>\" [--force]"
    echo "Example: $0 \"feat: release v1.2.1 - HVAC improvements and bugfixes\""
    exit 1
fi

MSG="$1"
FORCE_RELEASE="${2:-}"

# Check that working tree is clean
if ! git diff-index --quiet HEAD --; then
    echo "Error: Working directory has uncommitted changes. Please commit or stash them first."
    exit 1
fi

# Ensure dev and master exist locally
if ! git rev-parse --verify dev >/dev/null 2>&1; then
    echo "Error: 'dev' branch does not exist."
    exit 1
fi

if ! git rev-parse --verify master >/dev/null 2>&1; then
    echo "Error: 'master' branch does not exist."
    exit 1
fi

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)

DEV_TREE=$(git rev-parse dev^{tree})
MASTER_TREE=$(git rev-parse master^{tree})

if [ "$DEV_TREE" = "$MASTER_TREE" ] && [ "$FORCE_RELEASE" != "--force" ]; then
    echo "Notice: Trees of 'dev' and 'master' are identical. Nothing new to release."
    echo "If you want to force an empty release commit anyway, pass --force as the second argument."
    exit 0
fi

MASTER_HEAD=$(git rev-parse master)

echo "Creating release commit on master with tree from dev (${DEV_TREE:0:7})..."
NEW_COMMIT=$(git commit-tree "$DEV_TREE" -p "$MASTER_HEAD" -m "$MSG")

echo "Created commit: $NEW_COMMIT"
git update-ref refs/heads/master "$NEW_COMMIT"

if [ "$CURRENT_BRANCH" = "master" ]; then
    git reset --hard "$NEW_COMMIT"
fi

echo "Pushing master to origin..."
git push origin master

echo ""
echo "Successfully published release to origin/master!"
echo "Master HEAD: $(git rev-parse --short master) - $MSG"
echo "Active branch remains: $CURRENT_BRANCH"
