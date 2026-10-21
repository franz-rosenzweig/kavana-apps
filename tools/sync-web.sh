#!/bin/bash
# Copies the web app's engine and pictures into the Android app, from a commit of the kavana repo
# (default: what's live, /srv/dev/kavana-live). The app runs these files as they are, so after a
# web change to engine.js, lines.js or cats.js, run this, then tools/test-bridge.js, then rebuild.
#
#   tools/sync-web.sh                 # from the live checkout's commit
#   tools/sync-web.sh <commit>        # from a commit in /srv/dev/kavana
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=${KAVANA_REPO:-/srv/dev/kavana}
REV=${1:-$(git -C /srv/dev/kavana-live rev-parse HEAD)}
A=android/app/src/main/assets
for f in engine.js lines.js cats.js; do git -C "$REPO" show "$REV:web/$f" > "$A/js/$f"; done
rm -f "$A"/cats/*.webp
git -C "$REPO" ls-tree --name-only "$REV" web/cats/ | while read -r f; do git -C "$REPO" show "$REV:$f" > "$A/cats/$(basename "$f")"; done
git -C "$REPO" show "$REV:web/cafe/gone-1.webp" > "$A/cafe/gone-1.webp"
echo "$REV" > "$A/js/WEB_COMMIT"
echo "copied web/ at $(git -C "$REPO" log -1 --format='%h %s' "$REV")"
