#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

cd "$REPO_ROOT"

if [ ! -f keystore.properties ]; then
    echo "Missing keystore.properties in $REPO_ROOT" >&2
    exit 1
fi

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/default-java}"
export ANDROID_HOME="${ANDROID_HOME:-/root/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/root/android-sdk}"

AAPT2_OVERRIDE="${AAPT2_OVERRIDE:-/data/data/com.termux/files/usr/bin/aapt2}"

if [ ! -x "$AAPT2_OVERRIDE" ]; then
    echo "Missing executable aapt2 override at $AAPT2_OVERRIDE" >&2
    exit 1
fi

VERSION_NAME=$(sed -n 's/.*versionName "\(.*\)".*/\1/p' app/build.gradle | head -n 1)
VERSION_CODE=$(sed -n 's/.*versionCode \([0-9][0-9]*\).*/\1/p' app/build.gradle | head -n 1)

if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
    echo "Failed to read versionName/versionCode from app/build.gradle" >&2
    exit 1
fi

./gradlew clean bundleRelease --no-daemon -Pandroid.aapt2FromMavenOverride="$AAPT2_OVERRIDE"

OUTPUT_DIR="releases/$VERSION_NAME"
OUTPUT_FILE="$OUTPUT_DIR/hitomi-v$VERSION_NAME-release-vc$VERSION_CODE.aab"
mkdir -p "$OUTPUT_DIR"
cp app/build/outputs/bundle/release/app-release.aab "$OUTPUT_FILE"

printf 'Built %s\n' "$OUTPUT_FILE"
