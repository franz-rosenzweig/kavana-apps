#!/bin/zsh
# Prepares (and, only when told to, publishes) a release of Chataltul for Mac: the app built from a commit in a
# clean checkout, signed on this Mac with the shared identity the other apps use, checked, zipped, and the
# Homebrew cask written. A GitHub release and the cask in the tap come only with --publish.
#
#   mac/release.sh 0.3.0                      build, check and write the cask; nothing is published (the default)
#   mac/release.sh 0.3.0 --ref origin/mac-stage3   build from another ref, to look at before the merges
#   mac/release.sh 0.3.0 --publish "notes"    the release, then the cask in the tap (needs --ref origin/main)
#
# What it needs to know about the signing lives outside the repository, in ~/.config/chataltul-release.env:
#   SIGN_NAME=...            the identity's name
#   SIGN_KEYCHAIN=...        the keychain file that holds it
#   SIGN_PASSWORD_FILE=...   the file that unlocks that keychain
#   TAP=git@github.com:owner/homebrew-tap.git
# The keychain is locked again afterwards. The version is X.Y.Z; the build number is 10000*X + 100*Y + Z, so each
# release outranks the last. The release's tag is mac-vX.Y.Z, so it can't be taken for one of the Android app's
# (vX.Y.Z) in the same repository.
set -euo pipefail
cd "${0:A:h}/.."
ROOT=$PWD

V=${1:?usage: mac/release.sh X.Y.Z [--ref REF] [--publish "notes"]}
shift
[[ $V =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || { echo "a version is X.Y.Z" >&2; exit 2; }
BUILD=$(( match[1] * 10000 + match[2] * 100 + match[3] ))
REF=origin/main; PUBLISH=0; NOTES=""
while (( $# )); do
  case $1 in
    --ref) REF=$2; shift ;;
    --publish) PUBLISH=1; NOTES=${2:-}; [[ -n $NOTES ]] && shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
CONF=${CHATALTUL_RELEASE_ENV:-$HOME/.config/chataltul-release.env}
[[ -f $CONF ]] || { echo "no $CONF (see the top of this script)" >&2; exit 2; }
source "$CONF"
: ${SIGN_NAME:?} ${SIGN_KEYCHAIN:?} ${SIGN_PASSWORD_FILE:?} ${TAP:?}
SIGN_KEYCHAIN=${SIGN_KEYCHAIN/#\~/$HOME}; SIGN_PASSWORD_FILE=${SIGN_PASSWORD_FILE/#\~/$HOME}
REPO=franz-rosenzweig/kavana-apps
TAG=mac-v$V
say() { echo "release: $*"; }
fail() { echo "CHECK FAILED: $*" >&2; exit 1; }

git fetch -q origin
SHA=$(git rev-parse "$REF")
if (( PUBLISH )); then
  [[ $REF == origin/main ]] || { echo "only what is merged into origin/main is published" >&2; exit 1; }
  gh release view "$TAG" --repo $REPO >/dev/null 2>&1 && { echo "$TAG is already released" >&2; exit 1; }
fi
say "$TAG (build $BUILD) from $(git log --oneline -1 "$SHA")"

# a clean checkout of exactly that commit: what ships is what is committed
WORK=$(mktemp -d)
trap 'git -C "$ROOT" worktree remove --force "$WORK/src" >/dev/null 2>&1 || true; rm -rf "$WORK"; security lock-keychain "$SIGN_KEYCHAIN" >/dev/null 2>&1 || true' EXIT
git worktree add -q --detach "$WORK/src" "$SHA"

# --- build, signed with the shared identity (its hash stays in a variable, never printed)
HASH=$(security find-identity -p codesigning "$SIGN_KEYCHAIN" | sed -n "s/^ *[0-9]*) \([0-9A-F]\{40\}\) \"$SIGN_NAME\".*/\1/p" | head -1)
[[ -n $HASH ]] || { echo "no identity named $SIGN_NAME in the keychain" >&2; exit 1; }
security unlock-keychain -p "$(cat "$SIGN_PASSWORD_FILE")" "$SIGN_KEYCHAIN"
OUT=$ROOT/mac/build/release
rm -rf "$OUT"; mkdir -p "$OUT"
( cd "$WORK/src/mac" && CHATALTUL_OUT=$OUT CHATALTUL_VERSION=$V CHATALTUL_BUILD=$BUILD CHATALTUL_SIGN=$HASH CHATALTUL_KEYCHAIN=$SIGN_KEYCHAIN ./build.sh >$OUT/build.log 2>&1 ) || { grep -E "error" "$OUT/build.log" | head; fail "the build (see mac/build/release/build.log)"; }
APP="$OUT/Chataltul.app"
security lock-keychain "$SIGN_KEYCHAIN"

# --- what the app says about itself, before anything leaves the Mac
say "checking"
codesign --verify --deep --strict "$APP" || fail "the signature"
sig=$(codesign -dvv "$APP" 2>&1)
auth=$(sed -n 's/^Authority=//p' <<<"$sig" | sort -u)
[[ $auth == "$SIGN_NAME" ]] || fail "signed by '$auth', not only by $SIGN_NAME"
[[ $sig == *$'\n'"TeamIdentifier=not set"* ]] || fail "the app carries a team"
[[ $sig == *"flags=0x10000(runtime)"* ]] || fail "the hardened runtime isn't on"
ent=$(codesign -d --entitlements - "$APP" 2>/dev/null)
[[ $ent == *"com.apple.security.automation.apple-events"* ]] || fail "the Apple events entitlement is missing"
plist="$APP/Contents/Info.plist"
pb() { /usr/libexec/PlistBuddy -c "Print :$1" "$plist"; }
[[ $(pb CFBundleShortVersionString) == $V ]] || fail "the version"
[[ $(pb CFBundleVersion) == $BUILD ]] || fail "the build number"
[[ $(pb CFBundleIdentifier) == xyz.tiresia.chataltul ]] || fail "the bundle id"
[[ $(pb LSMinimumSystemVersion) == 14.0 ]] || fail "the minimum macOS"
[[ $(pb LSUIElement) == true ]] || fail "the menu bar app setting"
[[ -f $APP/Contents/Resources/AppIcon.icns && -f $APP/Contents/Resources/sounds.json && -f $APP/Contents/Resources/js/engine.js && -f $APP/Contents/Resources/js/WEB_COMMIT ]] || fail "something the app needs is missing"
[[ -z $(grep -r -l -a '/Users/' "$APP" || true) ]] || fail "a home folder's path is in the app: $(grep -r -l -a '/Users/' "$APP")"
[[ -z $(grep -r -l -a -E '[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}:[0-9]{2,5}|@pm\.me|@passmail|proton' "$APP" || true) ]] || fail "a private address or an email is in the app"
[[ -z $(find "$APP" -name 'doc.json*' -o -name 'mac.json' -o -name 'timing.json' -o -name 'usage.json' -o -name 'sync.json' -o -name 'session*' || true) ]] || fail "someone's data is in the app"

# --- it starts: the signed app, run once with a folder of its own and nothing on the screen
say "starting it once"
F=$(mktemp -d)
launched=$(CHATALTUL_OFFSCREEN=1 CHATALTUL_QUIET_EXIT=1 CHATALTUL_QUIT_AFTER=4 CHATALTUL_MUTE=1 CHATALTUL_NO_WATCH=1 CHATALTUL_NO_NOTIFY=1 CHATALTUL_FOLDER=$F CHATALTUL_FILE_SESSION=1 "$APP/Contents/MacOS/Chataltul" -ApplePersistenceIgnoreState YES 2>&1 | grep -E "LAUNCH|UNCAUGHT|SIGNAL" || true)
rm -rf "$F"
[[ $launched == *"LAUNCH ok"* ]] || fail "the signed app didn't start: $launched"

# --- the zip and the cask
ZIP="$OUT/Chataltul-$V-mac.zip"
ditto -c -k --keepParent "$APP" "$ZIP"
SUM=$(shasum -a 256 "$ZIP" | cut -d' ' -f1)
say "$(du -h "$ZIP" | cut -f1) zip, sha256 $SUM"
CASK="$OUT/chataltul.rb"
cat > "$CASK" <<CASKEOF
cask "chataltul" do
  version "$V"
  sha256 "$SUM"

  url "https://github.com/$REPO/releases/download/mac-v#{version}/Chataltul-#{version}-mac.zip"
  name "Chataltul"
  desc "Focus timer with a kitten: check-ins, a cat over distracting apps, breaks and a week in review"
  homepage "https://github.com/$REPO"

  depends_on macos: ">= :sonoma"

  app "Chataltul.app"

  postflight do
    system_command "/usr/bin/xattr", args: ["-dr", "com.apple.quarantine", "#{appdir}/Chataltul.app"]
  end
end
CASKEOF
ruby -c "$CASK" >/dev/null || fail "the cask isn't valid Ruby"
cat > "$OUT/release-notes.md" <<NOTESEOF
Chataltul for Mac $V. The web engine in this build is at $(cut -c1-7 < "$APP/Contents/Resources/js/WEB_COMMIT"). Built from $(git log --format=%h -1 "$SHA").

Install: \`brew install --cask franz-rosenzweig/tap/chataltul\`, or unzip it and move Chataltul.app to Applications. It needs macOS 14 or newer.
It is signed with my own identity, not Apple's, so the first time you may have to open it with a right click, then Open.

What's in it is in the README's Mac section: https://github.com/$REPO#mac-mac
NOTESEOF
say "built from $SHA; files in mac/build/release: $(ls "$OUT" | tr '\n' ' ')"
if (( ! PUBLISH )); then say "nothing is published: no release, no tag, no cask pushed"; exit 0; fi

# --- the release, then the cask
gh release create "$TAG" "$ZIP" --repo $REPO --target "$SHA" --title "Chataltul for Mac $V" --notes "${NOTES:-$(cat "$OUT/release-notes.md")}"
git clone -q "$TAP" "$WORK/tap"
mkdir -p "$WORK/tap/Casks"
cp "$CASK" "$WORK/tap/Casks/chataltul.rb"
if ! grep -q 'chataltul' "$WORK/tap/README.md"; then
  echo "| [chataltul](Casks/chataltul.rb) | [Chataltul](https://github.com/$REPO): focus timer with a kitten, for the Mac |" >> "$WORK/tap/README.md"
fi
git -C "$WORK/tap" add Casks/chataltul.rb README.md
git -C "$WORK/tap" commit -q -m "chataltul $V"
git -C "$WORK/tap" pull -q --rebase
git -C "$WORK/tap" push -q
say "released: $(gh release view "$TAG" --repo $REPO --json url -q .url), and the cask is at $V"
