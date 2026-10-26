#!/bin/bash
# Copies the web app's engine and pictures into the Android app, from a commit of the kavana repo
# (default: what's live, /srv/dev/kavana-live). The app runs these files as they are, so after a
# web change to engine.js, lines.js, cats.js, news.js or the meows in web/audio, run this, then
# tools/test-bridge.js, then rebuild. A new or renumbered recording goes into Sound.kt KINDS by hand.
#
#   tools/sync-web.sh                 # from the live checkout's commit
#   tools/sync-web.sh <commit>        # from a commit in /srv/dev/kavana
#   KAVANA_REPO=~/src/kavana tools/sync-web.sh <commit>   # from another clone (on the Mac)
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=${KAVANA_REPO:-/srv/dev/kavana}
REV=${1:-$(git -C /srv/dev/kavana-live rev-parse HEAD)}
REV=$(git -C "$REPO" rev-parse "$REV")
A=android/app/src/main/assets
for f in engine.js lines.js cats.js news.js; do git -C "$REPO" show "$REV:web/$f" > "$A/js/$f"; done
for d in cats cafe; do
  rm -f "$A/$d"/*.webp
  git -C "$REPO" ls-tree --name-only "$REV" "web/$d/" | grep '\.webp$' | while read -r f; do git -C "$REPO" show "$REV:$f" > "$A/$d/$(basename "$f")"; done
done
# The meows for a kitten coming home (tiny). The background recordings aren't copied: like the web, the
# app downloads each one the first time it's picked; their file names are in Sound.kt KINDS, from sounds.js.
mkdir -p "$A/audio"
rm -f "$A/audio"/*.mp3
git -C "$REPO" ls-tree --name-only "$REV" "web/audio/" | grep '/meow-.*\.mp3$' | while read -r f; do git -C "$REPO" show "$REV:$f" > "$A/audio/$(basename "$f")"; done
# The living cats' rig (eyes, head, ears, tail of every picture), once the web app has it.
if git -C "$REPO" cat-file -e "$REV:web/rig.json" 2>/dev/null; then git -C "$REPO" show "$REV:web/rig.json" > "$A/rig.json"; fi
echo "$REV" > "$A/js/WEB_COMMIT"
echo "copied web/ at $(git -C "$REPO" log -1 --format='%h %s' "$REV")"
