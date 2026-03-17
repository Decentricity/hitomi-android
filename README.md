# Agent1c Android (Hitomi Overlay)

![Hitomi Android Screenshot](docs/hitomi-android-screenshot-latest.png)

Native Android floating Hitomi ("talking hedgehog") companion app for the Agent1c ecosystem.

This repo now ships two Android variants from one shared codebase:
- `Hitomi`
  - package ID: `ai.agent1c.hitomi`
  - hosted auth + hosted chat path
- `Open Hitomi`
  - package ID: `ai.agent1c.hitomi.open`
  - local Grok API key entry on-device
  - intended BYOK / F-Droid-friendly direction

Related projects:
- `agent1c.ai` (cloud-hosted Agent1c OS)
- `agent1c.me` (local-first / BYOK Agent1c OS)

## Building

Build instructions are environment-specific in this repo.

See `BUILDING.md` before running Gradle, especially if you are building either:
- on Ubuntu/x86_64
- inside Debian proot on Android/Termux

Convenience scripts:
- `scripts/build-release-linux.sh`
  - run on a normal Linux/x86_64 machine with Android SDK + JDK installed
  - builds a signed release AAB if `keystore.properties` is present
  - copies the result into `releases/<version>/`
- `scripts/build-release-termux-proot.sh`
  - run inside the Debian proot clone on the phone
  - applies the Termux-host `aapt2` override for this environment
  - builds a signed release AAB and copies it into `releases/<version>/`

For local debug APKs:
- `./gradlew assembleHostedDebug`
- `./gradlew assembleOpenDebug`

Typical output paths:
- hosted debug APK:
  - `app/build/outputs/apk/hosted/debug/app-hosted-debug.apk`
- open debug APK:
  - `app/build/outputs/apk/open/debug/app-open-debug.apk`

## What The App Includes

- BeOS/HedgeyOS-inspired Android main screen styling
- Floating Hitomi hedgehog overlay as the main companion surface
- Signed-in welcome line that advertises browser, Termux, and Solana capabilities
- Clippy-style chat bubble with tail
- Hosted variant uses Agent1c.ai/Supabase-backed auth + chat
- Open variant accepts a Grok API key locally and skips hosted web login

- Long-press quick actions for settings, mic, and hide-to-edge
- Native Android STT always-listening mode
- Mic permission flow from the overlay into the main settings window
- Microphone/STT startup hardening and failure recovery
- Drag Hitomi to the bottom-center `X` target to close the floating overlay
- Hide-to-edge arc tab restore interaction
- Hidden-edge restore hit area and swipe restore polish
- Hidden edge tab now re-anchors correctly on rotation

- Hitomi Browser mini overlay in the same BeOS-style window family
- Hitomi-triggered browser open flow
- Browser-read page excerpt flow
- Visible stepped scrolling while Hitomi reads a page
- Particle stream effect linking Hitomi to the browser while browsing/reading
- Browser summon animation with first-spawn random placement near Hitomi
- Re-browse behavior reuses the existing Hitomi Browser window in place

- Green-on-black Terminal overlay window for visible Termux command/result transcripts
- Automatic terminal summon when Hitomi uses Termux
- Termux detection and command bridge for superuser use
- Termux package visibility fix so installed Termux, open, enable, and test controls appear reliably
- Copyable Termux setup command UX and friendlier setup guidance
- Termux-not-connected flow now opens the main settings window and tells the user how to reconnect
- Android Termux tool token support (`android_termux_exec`) with client-side blacklist

- Purple Solana wallet window with local wallet save flow
- Read-only Solana wallet overview and refresh tools

## Notes

- This is an early prototype release.
- Signed release AAB builds are supported when a local `keystore.properties` is present.
- Keep signing material local to the build machine; do not commit `keystore.properties` or the keystore itself.

## Termux (T1/T1.5) setup notes

The Android app can detect Termux and test the Termux command bridge, but **two prerequisites** are required before commands work:

1. Grant the Android runtime permission:
   - `com.termux.permission.RUN_COMMAND`
   - (The app now guides this via the `Enable Termux Shell Tools` button.)
2. In Termux, enable external app commands by setting:
   - `allow-external-apps=true` in `~/.termux/termux.properties`
   - then fully close and reopen Termux

Helpful Termux command (the app also shows this in status/help text):

```sh
mkdir -p ~/.termux && grep -qx 'allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps=true' >> ~/.termux/termux.properties
```
