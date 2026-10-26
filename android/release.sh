#!/bin/zsh
# Builds the signed release APK, android/Chataltul-<version>.apk, on the Mac, and checks its signature.
#
#   ./release.sh                     # signs with android/keystore.properties if there is one,
#                                    # else with Dvarim's (../../dvarim/android/keystore.properties)
#   adb install -r Chataltul-0.4.4.apk
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
OUT=Chataltul-$VERSION.apk
cp $APK $OUT
SIGNER=$(ls -d $ANDROID_HOME/build-tools/*/apksigner | tail -1)
$SIGNER verify --print-certs $OUT | grep -E "Signer #1 certificate (DN|SHA-256)"
echo "signed: android/$OUT"
