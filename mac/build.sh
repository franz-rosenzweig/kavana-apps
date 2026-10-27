#!/bin/zsh
# Builds Chataltul.app with the Command Line Tools alone, no Xcode needed:
#   ./build.sh && open build/Chataltul.app
# The settings and Info.plist keys come from project.yml, the same spec the Xcode project is generated
# from, so the two builds cannot drift apart.
#   CHATALTUL_BUNDLE_ID=…   another bundle id (a test copy), which also gives it a folder of its own
#   CHATALTUL_OUT=dir       somewhere other than build/
#   CHATALTUL_SIGN=-        ad hoc signing (tests); the default is an Apple Development identity if there is one
#   CHATALTUL_OPT=-Onone    a quicker build for trying things
#   CHATALTUL_SELFTEST=1    builds with SelfTest/ in place of Main.swift (selftest.sh does this)
set -euo pipefail
cd "${0:A:h}"

command -v swiftc >/dev/null || { echo "no swiftc: run xcode-select --install" >&2; exit 1; }
command -v xcodegen >/dev/null || { echo "no xcodegen: run brew install xcodegen" >&2; exit 1; }

setting() { sed -n "s/^ *$1: *\"*\([^\"]*\)\"*$/\1/p" project.yml | head -1; }
BUNDLE_ID=${CHATALTUL_BUNDLE_ID:-$(setting PRODUCT_BUNDLE_IDENTIFIER)}
VERSION=$(setting MARKETING_VERSION)
BUILD=$(setting CURRENT_PROJECT_VERSION)
MACOS=$(setting macOS)
OUT=${CHATALTUL_OUT:-build}
APP=$OUT/Chataltul.app

xcodegen generate --quiet               # writes Chataltul/Info.plist, the entitlements and the Xcode project
rm -rf $APP && mkdir -p $APP/Contents/MacOS $APP/Contents/Resources

SOURCES=(Chataltul/Core/*.swift)
if [[ -n ${CHATALTUL_SELFTEST:-} ]]; then
    SOURCES+=(${$(echo Chataltul/App/*.swift):#Chataltul/App/Main.swift} SelfTest/*.swift)
else
    SOURCES+=(Chataltul/App/*.swift)
fi
swiftc ${CHATALTUL_OPT:--O} -wmo -sdk "$(xcrun --show-sdk-path)" -target "$(uname -m)-apple-macos$MACOS" -swift-version 5 \
    -parse-as-library -module-name Chataltul $SOURCES -o $APP/Contents/MacOS/Chataltul

sed -e "s/\$(DEVELOPMENT_LANGUAGE)/en/" -e "s/\$(EXECUTABLE_NAME)/Chataltul/" \
    -e "s/\$(PRODUCT_BUNDLE_IDENTIFIER)/$BUNDLE_ID/" -e "s/\$(MARKETING_VERSION)/$VERSION/" \
    -e "s/\$(CURRENT_PROJECT_VERSION)/$BUILD/" -e "s/\$(PRODUCT_NAME)/Chataltul/" Chataltul/Info.plist > $APP/Contents/Info.plist
# a test copy talks to a sandbox server on this Mac, which is plain http
if [[ -n ${CHATALTUL_SELFTEST:-} ]]; then
    plutil -insert NSAppTransportSecurity -json '{"NSAllowsLocalNetworking":true}' $APP/Contents/Info.plist
fi
plutil -lint -s $APP/Contents/Info.plist

R=Chataltul/Resources
cp -R $R/js $R/cats $R/cafe $R/audio $APP/Contents/Resources/
cp $R/rig.json $R/sounds.json $APP/Contents/Resources/
[[ -f $R/AppIcon.icns ]] && cp $R/AppIcon.icns $APP/Contents/Resources/ && plutil -replace CFBundleIconFile -string AppIcon $APP/Contents/Info.plist

# An Apple Development identity if there is one (Xcode makes it from your Apple ID), ad hoc otherwise,
# which is enough to run it on this Mac.
IDENTITY=${CHATALTUL_SIGN:-$(security find-identity -v -p codesigning | sed -n 's/^ *[0-9]*) \([0-9A-F]*\) "Apple Development.*/\1/p' | head -1)}
[[ $IDENTITY == "-" ]] && IDENTITY=""
codesign --force --options runtime --entitlements Chataltul/Chataltul.entitlements --sign "${IDENTITY:--}" $APP
[[ -n $IDENTITY ]] || echo "note: signed ad hoc; fine on this Mac"
echo "built $APP"
