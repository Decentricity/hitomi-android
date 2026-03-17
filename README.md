# Agent1c Android (Hitomi Overlay)

![Hitomi Android Screenshot](docs/hitomi-android-screenshot-latest.png)

Native Android floating Hitomi ("talking hedgehog") companion app for the Agent1c ecosystem.

This repo is the Android client prototype / app codebase.

Related projects:
- `agent1c.ai` (cloud-hosted Agent1c OS)
- `agent1c.me` (local-first / BYOK Agent1c OS)

## Building

Build instructions are environment-specific in this repo.

See `BUILDING.md` before running Gradle, especially if you are building either:
- on Ubuntu/x86_64
- inside Debian proot on Android/Termux

## Download APK (Current)

Release `0.1.1`:
- Direct APK download: https://raw.githubusercontent.com/Decentricity/hitomi-android/master/releases/0.1.1/hitomi-v0.1.1-debug.apk
- `releases/0.1.1/hitomi-v0.1.1-debug.apk`
- checksum: `releases/0.1.1/SHA256SUMS_APK.txt`

## What 0.1.1 includes

- Green-on-black Terminal overlay window for visible Termux command/result transcripts
- Automatic terminal summon when Hitomi uses Termux
- Termux package visibility fix so installed Termux, open, enable, and test controls appear reliably
- Termux-not-connected flow now opens the main settings window and tells the user how to reconnect
- Signed-in welcome line now advertises browser, Termux, and Solana capabilities
- Drag Hitomi to the bottom-center `X` target to close the floating overlay
- Hidden-edge restore hit area and swipe restore polish
- Hidden edge tab now re-anchors correctly on rotation
- Mic permission flow fix from overlay to main settings window
- Microphone/STT startup hardening and better failure recovery

- Purple Solana wallet window with local wallet save flow
- Read-only Solana wallet overview / refresh tools
- Floating Hitomi hedgehog overlay (draggable)
- Clippy-style chat bubble with tail
- Supabase login (web-first handoff to app)
- Cloud chat via Agent1c.ai Supabase/xAI backend
- Android BeOS/HedgeyOS-inspired main screen styling
- Long-press radial quick actions (settings, mic, hide-to-edge)
- Native Android STT always-listening mode
- Hide-to-edge arc tab restore interaction
- Hitomi Browser mini overlay (BeOS/HedgeyOS-style)
- Hitomi-triggered browser open + browser-read page excerpt flow
- Hitomi Browser visible stepped scrolling during browse (cute page-by-page reading)
- Particle stream effect linking Hitomi to the browser while browsing/reading
- Browser summon animation + first-spawn random placement near Hitomi
- Re-browse behavior reuses the existing Hitomi Browser window in place
- Termux detection + command bridge (superuser optional)
- Copyable Termux setup command UX and friendlier setup guidance
- Android Termux tool token support (`android_termux_exec`) with client-side blacklist

## Notes

- This is an early prototype release.
- I plan to publish through F-Droid later; for now, direct APK testing is the intended path.
- App signing / release builds can be added after the auth + overlay UX stabilizes further.

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
