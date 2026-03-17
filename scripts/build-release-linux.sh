#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

cd "$REPO_ROOT"

if [ ! -f keystore.properties ]; then
    echo "Missing keystore.properties in $REPO_ROOT" >&2
    exit 1
fi

if [ -z "${JAVA_HOME:-}" ] || [ -z "${ANDROID_HOME:-}" ] || [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    echo "Set JAVA_HOME, ANDROID_HOME, and ANDROID_SDK_ROOT before running this script." >&2
    exit 1
fi

VERSION_NAME=$(sed -n 's/.*versionName "\(.*\)".*/\1/p' app/build.gradle | head -n 1)
VERSION_CODE=$(sed -n 's/.*versionCode \([0-9][0-9]*\).*/\1/p' app/build.gradle | head -n 1)

if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
    echo "Failed to read versionName/versionCode from app/build.gradle" >&2
    exit 1
fi

./gradlew clean bundleRelease --no-daemon

OUTPUT_DIR="releases/$VERSION_NAME"
OUTPUT_FILE="$OUTPUT_DIR/hitomi-v$VERSION_NAME-release-vc$VERSION_CODE.aab"
mkdir -p "$OUTPUT_DIR"
cp app/build/outputs/bundle/release/app-release.aab "$OUTPUT_FILE"

printf 'Built %s\n' "$OUTPUT_FILE"
