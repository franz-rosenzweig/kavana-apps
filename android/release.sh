#!/bin/zsh
# Builds the signed release APKs on the Mac, android/Chataltul-<version>.apk and the watch's
# android/Chataltul-Watch-<version>.apk (the same key, or the two can't talk), and checks their signatures.
#
#   ./release.sh                     # signs with android/keystore.properties if there is one,
#                                    # else with Dvarim's (../../dvarim/android/keystore.properties)
#   adb install -r Chataltul-<version>.apk
#   adb -s <watch> install -r Chataltul-Watch-<version>.apk   # the watch, over Wi-Fi (Wireless debugging)
set -euo pipefail
cd "${0:A:h}"
export JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}
SIGNING=${1:-}
if [[ -z $SIGNING ]]; then
    if [[ -f keystore.properties ]]; then SIGNING=$PWD/keystore.properties
    else SIGNING=${PWD:h:h}/dvarim/android/keystore.properties; fi
fi
[[ -f $SIGNING ]] || { echo "no signing properties at $SIGNING" >&2; exit 2; }
./gradlew --quiet assembleRelease -PsigningProperties="$SIGNING"
APK=app/build/outputs/apk/release/app-release.apk
[[ -f $APK ]] || { echo "the release build is not signed: check $SIGNING" >&2; exit 1; }
VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)
WATCH_VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' wear/build.gradle.kts | head -1)
[[ $VERSION == $WATCH_VERSION ]] || { echo "the phone is $VERSION and the watch $WATCH_VERSION: give them the same version" >&2; exit 1; }
OUT=Chataltul-$VERSION.apk
WOUT=Chataltul-Watch-$VERSION.apk
cp $APK $OUT
cp wear/build/outputs/apk/release/wear-release.apk $WOUT
SIGNER=$(ls -d $ANDROID_HOME/build-tools/*/apksigner | tail -1)
for f in $OUT $WOUT; do
    echo "$f:"
    $SIGNER verify --print-certs $f | grep -E "Signer #1 certificate (DN|SHA-256)"
done
echo "signed: android/$OUT and android/$WOUT"
