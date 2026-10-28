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
#   bump's rules, the hold, the away prompt, the blocklist, the ready-made website lists (read, matched, and
#   downloaded from a small web server on this Mac), and that the sound list is the web's.
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
  # the engine with the change we ask the web repo for (docs/engine-takeoff.patch), in a copy
  rm -rf $OUT/js-takeoff && cp -R Chataltul/Resources/js $OUT/js-takeoff
  mkdir -p $OUT/js-takeoff/web && cp $OUT/js-takeoff/engine.js $OUT/js-takeoff/web/engine.js
  (cd $OUT/js-takeoff && patch -s -p1 -i ../../../docs/engine-takeoff.patch && cp web/engine.js engine.js && rm -r web)
  # the ready-made lists are downloaded from a small web server on this Mac, with lists of every kind (and some that aren't)
  local LISTS; LISTS=$(mktemp -d -t chataltul-lists)
  local LISTS_SERVER="" LSERVER=""
  if command -v python3 >/dev/null; then
    n() { local i; for i in {1..$1}; do echo "site$i.$2"; done; }
    { echo "# a hosts file"; echo "127.0.0.1 localhost"; for i in {1..8}; do echo "0.0.0.0 site$i.example"; done; } > $LISTS/hosts.txt
    n 8 example > $LISTS/plain.txt
    { echo "[Adblock Plus 2.0]"; echo "! rules"; for i in {1..8}; do echo "||site$i.example^"; done; echo "@@||ok.example^"; } > $LISTS/abp.txt
    n 2 example > $LISTS/tiny.txt
    echo "<!doctype html><html><body>Not a list</body></html>" > $LISTS/page.html
    n 20000 example > $LISTS/big.txt
    mkdir -p $LISTS/pack/dating && n 12 love > $LISTS/pack/dating/domains && echo "http://x.love/" > $LISTS/pack/dating/urls
    tar -czf $LISTS/dating.tar.gz -C $LISTS/pack dating
    local LPORT; LPORT=$(python3 -c "import socket; s=socket.socket(); s.bind(('127.0.0.1', 0)); print(s.getsockname()[1])")
    python3 -m http.server $LPORT --bind 127.0.0.1 --directory $LISTS >$OUT/lists-server.log 2>&1 &
    LSERVER=$!
    for i in {1..50}; do curl -sf http://127.0.0.1:$LPORT/hosts.txt >/dev/null && break; sleep 0.1; done
    lsof -nP -iTCP:$LPORT -sTCP:LISTEN -t | grep -qx $LSERVER || { echo "the lists server didn't start on :$LPORT" >&2; kill $LSERVER 2>/dev/null; exit 1; }
    LISTS_SERVER=http://127.0.0.1:$LPORT/
  else
    echo "SKIP the lists from a server on this Mac: no python3"
  fi
  CHATALTUL_LISTS_SERVER=$LISTS_SERVER $OUT/core-tests Chataltul/Resources $OUT/js-takeoff | tee $OUT/core.log | grep -E "^(FAIL|---|SKIP|[0-9]+ passed|SELFTEST)" || true
  [[ -n $LSERVER ]] && kill $LSERVER 2>/dev/null || true
  rm -rf "$LISTS"
  grep -q "SELFTEST passed" $OUT/core.log

  # the web's own checks: the engine files are the web's, and so is the sound list
  node tools/check-web.js
  node tools/test-takeoff.js | tail -1
}

# the self-test copy: the app with SelfTest/ in place of Main.swift, built once per run
UI_BUILT=
build_ui() {
  [[ -n $UI_BUILT ]] && return
  CHATALTUL_SELFTEST=1 CHATALTUL_BUNDLE_ID=xyz.tiresia.chataltul.selftest CHATALTUL_OUT=$OUT/ui CHATALTUL_SIGN=- CHATALTUL_OPT=${CHATALTUL_OPT:--Onone} \
      ./build.sh >$OUT/ui-build.log 2>&1 || { grep -E "error" $OUT/ui-build.log | head -30; echo "the self-test copy didn't build (build/selftest/ui-build.log)" >&2; exit 1; }
  UI_BUILT=1
}

# the app's own windows, drawn to PNG with nothing on the screen
ui() {
  local APP=$OUT/ui/Chataltul.app
  build_ui
  rm -rf $OUT/shots; mkdir -p $OUT/shots
  local FOLDER; FOLDER=$(mktemp -d -t chataltul-ui)
  ( sleep 180; pkill -f "$APP/Contents/MacOS/Chataltul" ) 2>/dev/null &
  local watchdog=$!
  CHATALTUL_OFFSCREEN=1 CHATALTUL_NO_NOTIFY=1 CHATALTUL_NO_WATCH=1 CHATALTUL_NO_LISTS=1 CHATALTUL_FOLDER=$FOLDER CHATALTUL_FILE_SESSION=1 "$APP/Contents/MacOS/Chataltul" -SelfTestOut "$PWD/$OUT" -ApplePersistenceIgnoreState YES >$OUT/ui.log 2>&1 || true
  kill $watchdog 2>/dev/null || true
  rm -rf "$FOLDER"
  grep -E "^(FAIL|[0-9]+ passed|SELFTEST|UNCAUGHT|SIGNAL)" $OUT/ui.log || { echo "no result; see build/selftest/ui.log" >&2; tail -20 $OUT/ui.log; exit 1; }
  grep -q "SELFTEST passed" $OUT/ui.log
}

# signing in and syncing, and the recordings, against a sandbox copy of the web server on this Mac
sync() {
  local WEB=${KAVANA_REPO:-}
  for d in "$WEB" "${PWD:h:h}/kavana-web" "${PWD:h:h}/kavana"; do [[ -n $d && -f $d/server.js ]] && { WEB=$d; break; }; done
  [[ -f $WEB/server.js ]] || { echo "SKIP sync: no clone of the web repo here (set KAVANA_REPO)"; return 0; }
  command -v node >/dev/null || { echo "node is needed to run the sandbox server" >&2; exit 2; }
  local APP=$OUT/ui/Chataltul.app
  build_ui
  local PORT; PORT=$(python3 -c "import socket; s=socket.socket(); s.bind(('127.0.0.1', 0)); print(s.getsockname()[1])")
  STATE=$(mktemp -d -t chataltul-sandbox)
  FOLDER=$(mktemp -d -t chataltul-sync)
  (cd "$WEB" && exec env KAVANA_STATE=$STATE KAVANA_PORT=$PORT KAVANA_MAIL=outbox node server.js) >$OUT/sandbox.log 2>&1 &
  SERVER=$!
  trap 'kill $SERVER 2>/dev/null || true; rm -rf "$STATE" "$FOLDER"' EXIT
  for i in {1..50}; do curl -sf http://127.0.0.1:$PORT/api/config >/dev/null && break; sleep 0.2; done
  # the server answering there must be this one, on this Mac
  lsof -nP -iTCP:$PORT -sTCP:LISTEN -t | grep -qx $SERVER || { echo "the sandbox server didn't start on :$PORT" >&2; cat $OUT/sandbox.log >&2; exit 1; }
  ( sleep 240; pkill -f "$APP/Contents/MacOS/Chataltul" ) 2>/dev/null &
  local watchdog=$!
  CHATALTUL_OFFSCREEN=1 CHATALTUL_NO_NOTIFY=1 CHATALTUL_NO_WATCH=1 CHATALTUL_NO_LISTS=1 CHATALTUL_MUTE=1 CHATALTUL_FOLDER=$FOLDER CHATALTUL_FILE_SESSION=1 \
      "$APP/Contents/MacOS/Chataltul" -SelfTestSync http://127.0.0.1:$PORT/ -SelfTestOutbox "$STATE/outbox.jsonl" -ApplePersistenceIgnoreState YES >$OUT/sync.log 2>&1 || true
  kill $watchdog 2>/dev/null || true
  grep -E "^(PASS|FAIL|  said|  \(gave|[0-9]+ passed|SELFTEST|UNCAUGHT|SIGNAL)" $OUT/sync.log || { echo "no result; see build/selftest/sync.log" >&2; tail -20 $OUT/sync.log; exit 1; }
  grep -q "SELFTEST passed" $OUT/sync.log
}

# the app as it ships (no test code), started and left again: it must not crash on its way up
launch() {
  CHATALTUL_BUNDLE_ID=xyz.tiresia.chataltul.selftest CHATALTUL_OUT=$OUT/launch CHATALTUL_SIGN=- CHATALTUL_OPT=${CHATALTUL_OPT:--Onone} ./build.sh >$OUT/launch-build.log 2>&1 || { grep -E "error" $OUT/launch-build.log | head; exit 1; }
  local FOLDER; FOLDER=$(mktemp -d -t chataltul-launch)
  CHATALTUL_OFFSCREEN=1 CHATALTUL_QUIET_EXIT=1 CHATALTUL_QUIT_AFTER=4 CHATALTUL_MUTE=1 CHATALTUL_NO_WATCH=1 CHATALTUL_NO_LISTS=1 CHATALTUL_FOLDER=$FOLDER CHATALTUL_FILE_SESSION=1 \
      $OUT/launch/Chataltul.app/Contents/MacOS/Chataltul -ApplePersistenceIgnoreState YES >$OUT/launch.log 2>&1 || true
  rm -rf "$FOLDER"
  grep -E "LAUNCH|UNCAUGHT|SIGNAL" $OUT/launch.log || { echo "the shipped app didn't start; see build/selftest/launch.log" >&2; tail -20 $OUT/launch.log; exit 1; }
  grep -q "LAUNCH ok" $OUT/launch.log
}

case $MODE in
  core) core ;;
  sync) sync ;;
  launch) launch ;;
  ui) ui ;;
  all) core; ui; sync; launch ;;
  *) echo "unknown mode $MODE" >&2; exit 2 ;;
esac
