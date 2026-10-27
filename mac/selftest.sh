#!/bin/zsh
# Tests Chataltul for Mac without a screen and without me:
#
#   ./selftest.sh            the core tests (everything below that needs no window)
#   ./selftest.sh core       the same
#   ./selftest.sh sync       the account and sync, against a sandbox copy of the web server on this Mac
#   ./selftest.sh ui         the app's own windows drawn to PNG files in build/selftest/shots
#   ./selftest.sh all
#
# core: the engine in JavaScriptCore (a block, its pings, the cat names in them, the away op), the speed
#   bump's rules, the hold, the away prompt, the blocklist, and that the sound list is the web's.
#   If KAVANA_REPO (a clone of the web repo) is set, or one sits beside this repo as kavana-web or kavana,
#   it also fails when the sound list here differs from KINDS in the web's sounds.js.
# sync, ui: see the sections below. Both run the app as xyz.tiresia.chataltul.selftest with its own folder,
#   so they never touch the real app's settings or doc, and the sync test refuses any server that isn't on
#   this Mac.
set -euo pipefail
cd "${0:A:h}"
MODE=${1:-core}
OUT=build/selftest
mkdir -p $OUT

core() {
  command -v swiftc >/dev/null || { echo "no swiftc: run xcode-select --install" >&2; exit 1; }
  MACOS=$(sed -n 's/^ *macOS: *"\(.*\)"/\1/p' project.yml | head -1)
  swiftc -O -sdk "$(xcrun --show-sdk-path)" -target "$(uname -m)-apple-macos$MACOS" -swift-version 5 \
      Chataltul/Core/*.swift Tests/main.swift -o $OUT/core-tests 2>&1 | grep -E "error|warning: unre" || true
  [[ -x $OUT/core-tests ]] || { echo "the core tests didn't build" >&2; exit 1; }
  $OUT/core-tests Chataltul/Resources | tee $OUT/core.log | grep -E "^(FAIL|---|[0-9]+ passed|SELFTEST)"
  grep -q "SELFTEST passed" $OUT/core.log

  # the web's own checks: the engine files are the web's, and so is the sound list
  node tools/check-web.js
}

# the app's own windows, drawn to PNG with nothing on the screen
ui() {
  local APP=$OUT/ui/Chataltul.app
  CHATALTUL_SELFTEST=1 CHATALTUL_BUNDLE_ID=xyz.tiresia.chataltul.selftest CHATALTUL_OUT=$OUT/ui CHATALTUL_SIGN=- CHATALTUL_OPT=${CHATALTUL_OPT:--Onone} \
      ./build.sh >$OUT/ui-build.log 2>&1 || { grep -E "error" $OUT/ui-build.log | head -30; echo "the self-test copy didn't build (build/selftest/ui-build.log)" >&2; exit 1; }
  rm -rf $OUT/shots; mkdir -p $OUT/shots
  local FOLDER; FOLDER=$(mktemp -d -t chataltul-ui)
  ( sleep 180; pkill -f "$APP/Contents/MacOS/Chataltul" ) 2>/dev/null &
  local watchdog=$!
  CHATALTUL_OFFSCREEN=1 CHATALTUL_NO_NOTIFY=1 CHATALTUL_NO_WATCH=1 CHATALTUL_FOLDER=$FOLDER CHATALTUL_FILE_SESSION=1 "$APP/Contents/MacOS/Chataltul" -SelfTestOut "$PWD/$OUT" -ApplePersistenceIgnoreState YES >$OUT/ui.log 2>&1 || true
  kill $watchdog 2>/dev/null || true
  rm -rf "$FOLDER"
  grep -E "^(FAIL|[0-9]+ passed|SELFTEST|UNCAUGHT|SIGNAL)" $OUT/ui.log || { echo "no result; see build/selftest/ui.log" >&2; tail -20 $OUT/ui.log; exit 1; }
  grep -q "SELFTEST passed" $OUT/ui.log
}

case $MODE in
  core) core ;;
  ui) ui ;;
  all) core; ui ;;
  *) echo "unknown mode $MODE" >&2; exit 2 ;;
esac
