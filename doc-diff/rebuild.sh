#!/usr/bin/env bash
#
# Rebuild docs/public with the right baseURL for the local preview server,
# then re-run diff.mjs so the new HTML carries data-origin annotations.
# Run this whenever the source under docs/ changes.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
PORT="${PORT:-1314}"

( cd "$REPO_ROOT/docs" && hugo --quiet --minify=false --baseURL "http://localhost:${PORT}/" )
( cd "$SCRIPT_DIR" && node diff.mjs )

echo "Ready. Open http://localhost:${PORT}/ and click the Diff toggle (bottom right)."
