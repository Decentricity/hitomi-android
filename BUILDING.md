# Building

This repo is built in two different environments:

1. `Ubuntu x86_64` on a normal server/workstation
2. `Termux -> Debian proot` on an Android phone

Do not assume one environment's build fixes apply to the other.

## Build Matrix

- `Ubuntu x86_64`
  - preferred environment for normal development and release-oriented builds
  - use standard Android SDK + Gradle flow
  - use `scripts/build-release-linux.sh` for signed AAB output
- `Termux -> Debian proot`
  - supported for local Android-on-Android debug and signed release builds
  - use `scripts/build-release-termux-proot.sh` for signed AAB output
  - requires using the Termux host `aapt2` binary
  - slower and more fragile than Ubuntu x86

## Before Any Build

1. Check `app/build.gradle` for the current `compileSdk`, `targetSdk`, `versionCode`, and `versionName`.
2. Install the matching Android SDK platform in the build environment.
3. For release builds, ensure `keystore.properties` exists at the repo root and points at a local keystore on that machine.
4. Use `./gradlew assembleDebug --no-daemon` unless you specifically need a release artifact.

## Ubuntu x86_64

Prereqs:

- JDK 17 or newer
- Android SDK command-line tools
- matching SDK platform installed for the current `compileSdk`
- matching build-tools installed

Typical flow:

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=/path/to/android-sdk
./scripts/build-release-linux.sh
```

Notes:

- Keep `gradle.properties` free of Android/Termux-specific absolute paths.
- The Linux release script expects `keystore.properties` to be present locally and will fail fast if it is missing.

## Termux -> Debian Proot on Android

This environment uses the Debian clone of the repo but must rely on the Termux host `aapt2` binary:

- Termux host `aapt2` path:
  - `/data/data/com.termux/files/usr/bin/aapt2`

Typical flow inside Debian:

```sh
./scripts/build-release-termux-proot.sh
```

Important caveats:

- If AGP downloads an incompatible Linux tool, prefer the Termux-host override above rather than debugging Maven-downloaded binaries.
- If local Android builds start failing only after a `compileSdk` bump, verify the matching SDK platform is installed first.
- `compileSdk 35` may still be fragile in this environment when paired with the Termux-host `aapt2`. If Ubuntu x86 is available, prefer building there for higher-SDK changes.

## Output Paths

- Debug APK:
  - `app/build/outputs/apk/debug/app-debug.apk`
- Release AAB:
  - `app/build/outputs/bundle/release/app-release.aab`
- Versioned copied release AAB:
  - `releases/<version>/hitomi-v<version>-release-vc<versionCode>.aab`

## Agent Rule

If you are an agent working in this repo:

- inspect `app/build.gradle` before building
- inspect `gradle.properties` before building
- do not assume Termux/Android-specific paths exist on Ubuntu
- do not assume Ubuntu/x86 SDK behavior matches Debian-on-Android behavior
- when a build fix is environment-specific, document it here
- never commit `keystore.properties` or the keystore itself
