# AGENTS.md - `hitomi-android`

This file is the handoff brief for future Codex/GPT instances working on the Hitomi Android app.

## 1) What this repo is

`hitomi-android` is the Android client app for Hitomi Companion:
- floating hedgehog overlay UI
- chat bubble + local interaction loop
- Supabase-authenticated cloud chat calls
- optional Termux shell bridge tooling

This app now uses a **separate branded login web frontend** (Hitomi Companion GitHub Pages), while still sharing backend infrastructure with Agent1c.

## 2) Cross-repo map (critical)

From this repo (`/home/decentricity/hitomi-android`):
- Web auth frontend repo is at: `../hitomicompanion.github.io`
- Agent1c.ai web OS repo is at: `../agent1c-ai.github.io`
- Agent1c.me web OS repo is at: `../agent1c-me.github.io`

From `../hitomicompanion.github.io` back to this app:
- Android app repo is: `../hitomi-android`

## 3) Current implemented auth architecture

### 3.1 Android app side (this repo)

Primary auth manager:
- `app/src/main/java/com/example/test/SupabaseAuthManager.java`

Important constants already implemented:
- `AUTH_ENTRY_URL_PRIMARY = "https://hitomicompanion.github.io/"`
- `AUTH_ENTRY_URL_FALLBACK = "https://agent1c.ai/"`
- `USE_FALLBACK_AUTH_ENTRY = false` (operator override for fast revert)

Deep-link support in app:
- primary scheme: `hitomicompanion://auth/callback` (+ `/oauth`)
- legacy compatibility scheme: `agent1cai://auth/callback` (+ `/oauth`)

Manifest intent filters:
- `app/src/main/AndroidManifest.xml` includes both schemes.

### 3.2 Web auth side (other repo)

Hitomi web auth site:
- `../hitomicompanion.github.io`
- receives OAuth and magic-link callbacks
- performs Android handoff via Supabase Edge Function
- redirects back to app deep link

### 3.3 Supabase shared backend

Project:
- `gkfhxhrleuauhnuewfmw`

Functions used by Android and/or auth handoff:
- `xai-chat`
- `android-auth-handoff`

## 4) What has already been built (important for continuity)

- Version bump to `0.1.0` completed.
  - `app/build.gradle`: `versionCode 4`, `versionName "0.1.0"`
- APK produced:
  - `releases/0.1.0/hitomi-v0.1.0-debug.apk`
  - checksum file: `releases/0.1.0/SHA256SUMS_APK.txt`
- Web-first auth entry switched from `agent1c.ai` to `hitomicompanion.github.io`.
- Backward-compatible callback handling retained (legacy `agent1cai://...` still accepted).
- iOS-oriented web-login click hardening exists on web side (`click + pointer/touch` handling).

## 5) Non-obvious watchouts from real failures

### 5.1 Supabase provider naming

Do not use `"twitter"` as OAuth provider when calling Supabase OAuth start.
- Use provider `"x"` in modern flows.
- Old naming caused `Unsupported provider: provider is not enabled`.

### 5.2 Redirect/allowlist drift

If login appears to work but returns to wrong site or fails callback:
- verify Supabase Auth redirect allowlist includes `https://hitomicompanion.github.io/`
- verify deep-link callback scheme in Android and web repo match.

### 5.3 xAI chat 401 trap (critical)

For `xai-chat` edge function in this Supabase project:
- **Verify JWT with legacy secret MUST stay OFF**
- Supabase UI can silently flip this ON during edits/deploys
- When ON, app frequently breaks with 401 in chat path

Always check it after touching function settings/deploys.

### 5.4 GitHub Pages publish trap

`hitomicompanion.github.io` can 404 if GitHub Pages source branch is not set.
- Must be configured to deploy from `master` (root) for this repo layout.

### 5.5 Android build environment trap

Observed local issue:
- system JDK lacked `jlink`, causing Gradle compile failure.

Working fallback used:
- `JAVA_HOME=/home/decentricity/jdk-21-temurin`
- then run Gradle with that JAVA_HOME.

## 6) Release/build procedure (known-good)

From this repo root:

```bash
cd /home/decentricity/hitomi-android
JAVA_HOME=/home/decentricity/jdk-21-temurin PATH=/home/decentricity/jdk-21-temurin/bin:$PATH ./gradlew --no-daemon assembleDebug
```

Then copy artifact:

```bash
mkdir -p releases/0.1.0
cp app/build/outputs/apk/debug/app-debug.apk releases/0.1.0/hitomi-v0.1.0-debug.apk
sha256sum releases/0.1.0/hitomi-v0.1.0-debug.apk > releases/0.1.0/SHA256SUMS_APK.txt
```

## 7) Fast operator rollback strategy (already designed)

If web auth handoff site has outage, app can be pointed back quickly:
- edit `SupabaseAuthManager.java`
- set `USE_FALLBACK_AUTH_ENTRY = true`
- rebuild APK

This preserves non-user-settable operator control as requested.

## 8) Code areas to treat carefully

- `SupabaseAuthManager.java`
  - session storage, token refresh, callback parser, handoff logic
- `MainActivity.java`
  - auth launch path and callback handling entrypoints
- `HedgehogOverlayService.java`
  - overlay lifecycle + browser snapshot + voice behavior
- `HitomiCloudChatClient.java`
  - cloud call boundary to Supabase function

Do not refactor these casually without end-to-end login + chat testing.

## 8.1) Existing code debt/watchouts in source

- Java source folder is still `app/src/main/java/com/example/test/...` while package name is `ai.agent1c.hitomi`.
  - This works, but can confuse tooling and future refactors.
- Some UI copy in `activity_main.xml` still references `Agent1c.ai` wording.
  - Functional but inconsistent with Hitomi Companion branding.
- Repo contains legacy release artifacts from earlier naming (`agent1c-hitomi-android-...`).
  - Keep for provenance unless explicitly cleaning release history.

## 9) Recommended next steps

1. Complete Android post-login continuity validation:
   - fresh install -> login -> callback -> chat
   - resume from cold start with existing session
2. Add release signing path (AAB/APK) for distribution readiness.
3. Add explicit auth diagnostics screen in-app:
   - current provider
   - callback URI seen
   - handoff status
4. Keep old Agent1c fallback path tested but hidden from user UI.
5. Add crash-safe analytics breadcrumbs around callback/handoff only (no sensitive payload logging).

## 10) Naming guardrail

Canonical domains:
- `agent1c.ai`
- `agent1c.me`
- `hitomicompanion.github.io` (for current standalone auth frontend)

Do not introduce `agentic.*` into this repo.
