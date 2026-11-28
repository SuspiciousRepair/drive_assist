#!/usr/bin/env bash
# Documentation checks that a machine can make. Run by tools/verify.sh, so
# by CI and by tools/validate-release.sh.
#
#   ./tools/check-docs.sh            the checks; exit 1 if any fails
#   ./tools/check-docs.sh --report   also print what a person has to read
#                                    before a release (never fails by itself)
#
# What it cannot know: whether a guide describes a new screen. That stays a
# line in docs/RELEASE-PROCESS.md ("Documentation"); --report prints the
# lists that make that check quick.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
REPORT=0
[[ "${1:-}" == "--report" ]] && REPORT=1

failed=0
fail() { echo "docs: $*" >&2; failed=1; }

# --- 1. Relative links in tracked Markdown point to files that exist --------
# Code fences are skipped (a sample link is not a link). third_party/ is
# somebody else's documentation.
while IFS= read -r md; do
  dir=$(dirname "$md")
  while IFS= read -r target; do
    target=${target%%#*}; target=${target%%\?*}
    [[ -n "$target" ]] || continue
    case "$target" in
      [a-zA-Z]*:*|\<*) continue ;;      # http:, mailto:, <placeholder>
      /*) path=".${target}" ;;
      *)  path="$dir/$target" ;;
    esac
    [[ -e "$path" ]] || fail "broken link in $md: $target"
  done < <(awk '/^[[:space:]]*```/ { fence = !fence; next } !fence' "$md" \
           | grep -oE '\]\([^) ]+' | sed 's/^](//' || true)
done < <(git ls-files '*.md' | grep -v '^third_party/')

# --- 2. Every hub key constant is listed in docs/HUB-DATA.md ----------------
# CLAUDE.md: "Update that page in the same change as any new or changed hub
# key." Only the *Hub.java constants are seen; a key registered as a bare
# string elsewhere is not.
while IFS= read -r key; do
  grep -qF "\`$key\`" docs/HUB-DATA.md || fail "hub key $key is not in docs/HUB-DATA.md"
done < <(grep -hoE 'static final String [A-Z_0-9]+ *= *"[a-z0-9_]+\.[a-z0-9_.]+"' \
           drivemem/src/main/java/com/geely/drivemem/*/*Hub.java \
         | sed -E 's/.*"([^"]+)"/\1/' | sort -u)

# --- 3. Plans live in plan/, and every active one is in the index -----------
[[ -z "$(git ls-files docs/plan)" ]] || fail "docs/plan/ has files: docs/ is the public website, plans go in plan/"
for f in plan/active/*.md; do
  grep -qF "active/$(basename "$f")" plan/README.md || fail "$f is not listed in plan/README.md"
done

# --- 4. The in-app changelog is the newest RELEASE-NOTES.md section ---------
# drivemem/build.sh and installer/build-installer.sh regenerate it, while the
# GitHub release APK is built by Gradle from whatever is committed. Same rule
# in all three places, kept in step by hand: change all three together.
CL_VER=$(grep -oP '^## \[v\K[0-9.]+(?=\])' CHANGELOG.md | head -1)
RN_VER=$(grep -oP '^## v\K[0-9.]+' RELEASE-NOTES.md | head -1)
if [[ -z "$CL_VER" || "$CL_VER" != "$RN_VER" ]]; then
  fail "newest version in CHANGELOG.md (v${CL_VER:-none}) and RELEASE-NOTES.md (v${RN_VER:-none}) differ"
else
  want=$(awk -v ver="$CL_VER" '
    /^## / {
      if (insec) exit
      if ($0 ~ "^## v" ver "([[:space:]]|$)") { insec=1; next }
      next
    }
    insec && NF { print }
  ' RELEASE-NOTES.md)
  have=$(cat drivemem/src/main/assets/changelog.txt 2>/dev/null || true)
  [[ "$want" == "$have" ]] \
    || fail "drivemem/src/main/assets/changelog.txt is not the v$CL_VER section of RELEASE-NOTES.md"
fi

# --- Report: for the person doing the release -------------------------------
if [[ "$REPORT" -eq 1 ]]; then
  # Release tags sit on master's curated commits, which are not ancestors of
  # next. The last release on THIS branch is its release-notes commit.
  prev=$(git log -1 -E --grep='^docs: v[0-9.]+ release notes' --format=%H || true)
  echo
  if [[ -n "$prev" ]]; then since=$(git log -1 --format=%s "$prev"); else since="the first commit"; fi
  echo "Documentation report (since \"$since\"):"
  echo
  echo "  Features and fixes merged. Does a guide need each one?"
  git log --format='    %s' ${prev:+$prev..HEAD} | grep -E '^ +(feat|fix)' || echo "    (none)"
  echo
  echo "  Guides changed:"
  if [[ -n "$prev" ]]; then
    git diff --name-only "$prev" HEAD -- README.md README.pt-BR.md docs \
      | grep -v '^docs/archive/' | sed 's/^/    /' || echo "    (none)"
  fi
  echo
  echo "  Portuguese pages older than their English page (a hint, not proof):"
  while IFS= read -r pt; do
    en=${pt%.pt-BR.md}.md
    [[ -f "$en" ]] || continue
    t_pt=$(git log -1 --format=%ct -- "$pt"); t_en=$(git log -1 --format=%ct -- "$en")
    [[ "${t_pt:-0}" -ge "${t_en:-0}" ]] || echo "    $pt"
  done < <(git ls-files '*.pt-BR.md')
  echo
fi

[[ "$failed" -eq 0 ]] || { echo "docs: checks failed" >&2; exit 1; }
echo "Documentation checks: OK"
