#!/bin/bash
# Makes Chataltul/Resources/AppIcon.icns from the web repo's icon drawing (design/icon/final/icon.svg), which
# is already drawn on the macOS icon grid, with its margin and shadow. Quick Look draws the SVG.
#   KAVANA_REPO=~/src/kavana mac/tools/make-icon.sh
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=${KAVANA_REPO:?set KAVANA_REPO to a clone of the kavana repo}
T=$(mktemp -d)
cp "$REPO/design/icon/final/icon.svg" "$T/icon.svg"
qlmanage -t -s 1024 -o "$T" "$T/icon.svg" >/dev/null 2>&1
SET=$T/AppIcon.iconset && mkdir -p "$SET"
for s in 16 32 128 256 512; do
  sips -z $s $s "$T/icon.svg.png" --out "$SET/icon_${s}x${s}.png" >/dev/null
  sips -z $((s * 2)) $((s * 2)) "$T/icon.svg.png" --out "$SET/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns "$SET" -o Chataltul/Resources/AppIcon.icns
rm -rf "$T"
echo "made Chataltul/Resources/AppIcon.icns"
