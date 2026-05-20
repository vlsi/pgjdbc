#!/usr/bin/env bash
#
# Prepare a separate worktree at the chosen baseline ref (default: the
# canonical upstream pgjdbc/pgjdbc master, not the local fork's origin) and
# build its Hugo site so doc-diff/diff.mjs can index it.
#
# Configuration via env vars:
#   UPSTREAM_REMOTE  name of the upstream remote to use/create (default: pg)
#   UPSTREAM_URL     URL for the upstream remote, used only if we have to
#                    add it (default: https://github.com/pgjdbc/pgjdbc.git)
#   BASELINE_REF     git ref to check out (default: ${UPSTREAM_REMOTE}/master)
#   BASELINE_DIR     where to put the worktree (default: sibling of repo root)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"

UPSTREAM_REMOTE="${UPSTREAM_REMOTE:-pg}"
UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/pgjdbc/pgjdbc.git}"
REF="${BASELINE_REF:-${UPSTREAM_REMOTE}/master}"
BASELINE_DIR="${BASELINE_DIR:-$REPO_ROOT/../pgjdbc-doc-diff-baseline}"

echo "Repo root:       $REPO_ROOT"
echo "Upstream remote: $UPSTREAM_REMOTE → $UPSTREAM_URL"
echo "Baseline ref:    $REF"
echo "Baseline dir:    $BASELINE_DIR"

# Ensure the upstream remote exists. We never touch an existing one — if it
# already points elsewhere, that's the user's choice; surface it loudly.
if existing_url=$(git -C "$REPO_ROOT" remote get-url "$UPSTREAM_REMOTE" 2>/dev/null); then
  if [ "$existing_url" != "$UPSTREAM_URL" ]; then
    echo "Note: remote '$UPSTREAM_REMOTE' already points at $existing_url (not changing)."
  fi
else
  echo "Adding remote $UPSTREAM_REMOTE → $UPSTREAM_URL"
  git -C "$REPO_ROOT" remote add "$UPSTREAM_REMOTE" "$UPSTREAM_URL"
fi
git -C "$REPO_ROOT" fetch "$UPSTREAM_REMOTE" master --quiet

if [ -e "$BASELINE_DIR/.git" ]; then
  echo "Updating existing baseline worktree."
  git -C "$BASELINE_DIR" fetch "$UPSTREAM_REMOTE" --quiet
  git -C "$BASELINE_DIR" checkout --quiet --detach "$REF"
else
  echo "Creating baseline worktree."
  git -C "$REPO_ROOT" worktree add --detach "$BASELINE_DIR" "$REF"
fi

PUBLIC_DIR="$BASELINE_DIR/docs/public"
echo "Building docs at baseline."
# Prefer the Gradle wrapper when the baseline has the :docs:buildDocs task —
# it handles all the data-file generation, fingerprinting, etc. Otherwise fall
# back to a direct `hugo` invocation, which is enough for older baselines
# (e.g. upstream origin/master before docs/ was wired into Gradle).
if ( cd "$BASELINE_DIR" && ./gradlew --quiet :docs:buildDocs --dry-run ) >/dev/null 2>&1; then
  echo "  using ./gradlew :docs:buildDocs"
  ( cd "$BASELINE_DIR" && ./gradlew --quiet :docs:buildDocs )
else
  echo "  no :docs:buildDocs task in baseline, falling back to plain hugo"
  if ! command -v hugo >/dev/null; then
    echo "ERROR: hugo not found in PATH; install it or use a baseline that has :docs:buildDocs" >&2
    exit 1
  fi
  rm -rf "$PUBLIC_DIR"
  ( cd "$BASELINE_DIR/docs" && hugo --quiet --minify=false --baseURL "${LIVE_BASE:-https://jdbc.postgresql.org/}" )
fi

if [ ! -d "$PUBLIC_DIR" ]; then
  echo "ERROR: expected build output at $PUBLIC_DIR" >&2
  exit 1
fi

printf 'BASELINE_DIR=%s\n' "$BASELINE_DIR" > "$SCRIPT_DIR/.baseline-path"
echo "Done. Baseline public site: $PUBLIC_DIR"
