#!/bin/zsh
# Builds Chataltul.app with the Command Line Tools alone, no Xcode needed:
#   ./build.sh && open build/Chataltul.app
# The settings and Info.plist keys come from project.yml, the same spec the Xcode project is generated
# from, so the two builds cannot drift apart. The desktop widgets (Widgets/) are built into the app as its extension.
#   CHATALTUL_BUNDLE_ID=…   another bundle id (a test copy), which also gives it a folder of its own
#   CHATALTUL_OUT=dir       somewhere other than build/
#   CHATALTUL_SIGN=-        ad hoc signing (tests); the default is an Apple Development identity if there is one
#   CHATALTUL_OPT=-Onone    a quicker build for trying things
#   CHATALTUL_SELFTEST=1    builds with SelfTest/ in place of Main.swift (selftest.sh does this)
#   CHATALTUL_VERSION=X.Y.Z, CHATALTUL_BUILD=N   the version and build number (release.sh sets them; nothing is committed for them)
#   CHATALTUL_KEYCHAIN=file the keychain that holds the signing identity (release.sh)
set -euo pipefail
cd "${0:A:h}"

command -v swiftc >/dev/null || { echo "no swiftc: run xcode-select --install" >&2; exit 1; }
command -v xcodegen >/dev/null || { echo "no xcodegen: run brew install xcodegen" >&2; exit 1; }

setting() { sed -n "s/^ *$1: *\"*\([^\"]*\)\"*$/\1/p" project.yml | head -1; }
BUNDLE_ID=${CHATALTUL_BUNDLE_ID:-$(setting PRODUCT_BUNDLE_IDENTIFIER)}
VERSION=${CHATALTUL_VERSION:-$(setting MARKETING_VERSION)}
BUILD=${CHATALTUL_BUILD:-$(setting CURRENT_PROJECT_VERSION)}
MACOS=$(setting macOS)
OUT=${CHATALTUL_OUT:-build}
APP=$OUT/Chataltul.app

xcodegen generate --quiet               # writes the Info.plists, the entitlements and the Xcode project
rm -rf $APP && mkdir -p $APP/Contents/MacOS $APP/Contents/Resources

SWIFTC=(swiftc ${CHATALTUL_OPT:--O} -wmo -sdk "$(xcrun --sdk macosx --show-sdk-path)" -target "$(uname -m)-apple-macos$MACOS" -swift-version 5 -parse-as-library)

# The desktop widgets: an app extension inside the app, built alongside it. A self-test copy has none: the self-test draws
# the same faces itself. An extension starts in NSExtensionMain, as Xcode links it, or WidgetKit can't run it.
EXT=""
if [[ -z ${CHATALTUL_SELFTEST:-} ]]; then
    EXT=$APP/Contents/PlugIns/ChataltulWidgets.appex
    mkdir -p $EXT/Contents/MacOS
    $SWIFTC -application-extension -Xlinker -e -Xlinker _NSExtensionMain -module-name ChataltulWidgets \
        Widgets/*.swift Chataltul/Core/WidgetSnapshot.swift Chataltul/Core/Format.swift -o $EXT/Contents/MacOS/ChataltulWidgets &
    EXT_BUILD=$!
fi

SOURCES=(Chataltul/Core/*.swift)
if [[ -n ${CHATALTUL_SELFTEST:-} ]]; then
    SOURCES+=(${$(echo Chataltul/App/*.swift):#Chataltul/App/Main.swift} SelfTest/*.swift Widgets/WidgetViews.swift)
else
    SOURCES+=(Chataltul/App/*.swift)
fi
$SWIFTC -module-name Chataltul $SOURCES -o $APP/Contents/MacOS/Chataltul
if [[ -n $EXT ]]; then
    wait $EXT_BUILD || { echo "the widgets didn't build" >&2; exit 1; }
    sed -e "s/\$(DEVELOPMENT_LANGUAGE)/en/" -e "s/\$(EXECUTABLE_NAME)/ChataltulWidgets/" \
        -e "s/\$(PRODUCT_BUNDLE_IDENTIFIER)/$BUNDLE_ID.widgets/" -e "s/\$(MARKETING_VERSION)/$VERSION/" \
        -e "s/\$(CURRENT_PROJECT_VERSION)/$BUILD/" -e "s/\$(PRODUCT_NAME)/ChataltulWidgets/" Widgets/Info.plist > $EXT/Contents/Info.plist
    plutil -lint -s $EXT/Contents/Info.plist
    # the folder it may read is the one of this copy's bundle id
    sed "s#/xyz\.tiresia\.chataltul/widget/#/$BUNDLE_ID/widget/#" Widgets/Widgets.entitlements > $OUT/widgets.entitlements
fi

sed -e "s/\$(DEVELOPMENT_LANGUAGE)/en/" -e "s/\$(EXECUTABLE_NAME)/Chataltul/" \
    -e "s/\$(PRODUCT_BUNDLE_IDENTIFIER)/$BUNDLE_ID/" -e "s/\$(MARKETING_VERSION)/$VERSION/" \
    -e "s/\$(CURRENT_PROJECT_VERSION)/$BUILD/" -e "s/\$(PRODUCT_NAME)/Chataltul/" Chataltul/Info.plist > $APP/Contents/Info.plist
# a test copy talks to a sandbox server on this Mac, which is plain http
if [[ -n ${CHATALTUL_SELFTEST:-} ]]; then
    plutil -insert NSAppTransportSecurity -json '{"NSAllowsLocalNetworking":true}' $APP/Contents/Info.plist
fi
# only the app itself takes chataltul:// links: a copy under another bundle id must not catch the widgets' clicks
[[ $BUNDLE_ID == $(setting PRODUCT_BUNDLE_IDENTIFIER) ]] || plutil -remove CFBundleURLTypes $APP/Contents/Info.plist
plutil -lint -s $APP/Contents/Info.plist

R=Chataltul/Resources
cp -R $R/js $R/cats $R/cafe $R/audio $APP/Contents/Resources/
cp $R/rig.json $R/sounds.json $APP/Contents/Resources/
[[ -f $R/AppIcon.icns ]] && cp $R/AppIcon.icns $APP/Contents/Resources/ && plutil -replace CFBundleIconFile -string AppIcon $APP/Contents/Info.plist

# An Apple Development identity if there is one (Xcode makes it from your Apple ID), ad hoc otherwise,
# which is enough to run it on this Mac.
IDENTITY=${CHATALTUL_SIGN:-$(security find-identity -v -p codesigning | sed -n 's/^ *[0-9]*) \([0-9A-F]*\) "Apple Development.*/\1/p' | head -1)}
[[ $IDENTITY == "-" ]] && IDENTITY=""
# the widgets first, with their own entitlements (the sandbox), then the app around them
[[ -n $EXT ]] && codesign --force --options runtime --entitlements $OUT/widgets.entitlements ${CHATALTUL_KEYCHAIN:+--keychain "$CHATALTUL_KEYCHAIN"} --sign "${IDENTITY:--}" $EXT
codesign --force --options runtime --entitlements Chataltul/Chataltul.entitlements ${CHATALTUL_KEYCHAIN:+--keychain "$CHATALTUL_KEYCHAIN"} --sign "${IDENTITY:--}" $APP
[[ -n $IDENTITY ]] || echo "note: signed ad hoc; fine on this Mac"
echo "built $APP"
