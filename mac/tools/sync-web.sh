#!/bin/bash
# Copies the web app's engine, pictures and sound list into the Mac app, from a commit of the kavana
# repo. The app runs these files as they are and nothing here is edited by hand: after a web change
# to engine.js, lines.js, cats.js, news.js, the paintings or sounds.js, run this, then
# mac/selftest.sh core, then rebuild.
#
#   KAVANA_REPO=~/src/kavana mac/tools/sync-web.sh            # the clone's HEAD
#   KAVANA_REPO=~/src/kavana mac/tools/sync-web.sh <commit>   # a commit of it
#
# sounds.json is made from KINDS in web/sounds.js, so the list of sounds lives in one place (the web
# repo) and the Mac never hardcodes it. selftest.sh core fails if the two ever differ.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=${KAVANA_REPO:?set KAVANA_REPO to a clone of the kavana repo}
REV=$(git -C "$REPO" rev-parse "${1:-HEAD}")
R=Chataltul/Resources
mkdir -p "$R/js" "$R/cats" "$R/cafe" "$R/audio"
for f in engine.js lines.js cats.js news.js sounds.js; do git -C "$REPO" show "$REV:web/$f" > "$R/js/$f"; done
for d in cats cafe; do
  rm -f "$R/$d"/*.webp
  git -C "$REPO" ls-tree --name-only "$REV" "web/$d/" | grep '\.webp$' | while read -r f; do git -C "$REPO" show "$REV:$f" > "$R/$d/$(basename "$f")"; done
done
# The meows for a kitten coming home (tiny). The background recordings aren't copied: like the web, the
# app downloads each one the first time it's picked.
rm -f "$R/audio"/*.mp3
git -C "$REPO" ls-tree --name-only "$REV" "web/audio/" | grep '/meow-.*\.mp3$' | while read -r f; do git -C "$REPO" show "$REV:$f" > "$R/audio/$(basename "$f")"; done
git -C "$REPO" show "$REV:web/rig.json" > "$R/rig.json"
# The sound list, read out of sounds.js the way the web reads it.
node -e '
const vm = require("node:vm"), fs = require("node:fs");
const src = fs.readFileSync(process.argv[1], "utf8");
const ctx = vm.createContext({ console });
vm.runInContext(src, ctx);
const S = ctx.KavanaSound;
// MEOWS is private to sounds.js, so read the list out of its source
const meows = JSON.parse(src.match(/const MEOWS = (\[[^\]]*\])/)[1].replace(/\x27/g, "\""));
fs.writeFileSync(process.argv[2], JSON.stringify({ groups: S.GROUPS, kinds: S.KINDS, meows }, null, 1) + "\n");
' "$R/js/sounds.js" "$R/sounds.json"
rm "$R/js/sounds.js"
echo "$REV" > "$R/js/WEB_COMMIT"
echo "copied web/ at $(git -C "$REPO" log -1 --format='%h %s' "$REV")"
