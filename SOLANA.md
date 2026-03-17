# SOLANA.md - `hitomi-android`

This note captures the exact, minimal Solana integration plan for the Android Hitomi app.

The goal is to match the simple read-only Solana behavior already working in `agent1c.ai` without repeating the earlier token-bloat mistake, while using a manual local wallet entry flow that is actually testable on Android phones.

## Goal

Allow Hitomi Android to:
- see the stored wallet's SOL balance
- see a short list of recent transactions
- answer wallet questions honestly from fresh or cached read-only data

Do not add or imply:
- signing
- sending
- swapping
- custody
- private-key access
- background chain polling

## Shared architecture reality

- `agent1c.ai` is still the shared backend/runtime for both Agent1c and Hitomi.
- The Android app already shares Supabase auth and cloud chat backend with `agent1c.ai`.
- `hitomicompanion.github.io` is still only a partial auth/frontend split and should not be treated as the source of truth for Solana behavior.
- Mobile Solana wallet login through Supabase is not a good Android primary path here because phone-browser wallet flows are awkward and insecure to force as defaults.

## What already exists in this Android repo

Current useful hooks:
- Main cloud chat boundary:
  - `app/src/main/java/com/example/test/HitomiCloudChatClient.java`
- Assistant output tool parser/executor:
  - `app/src/main/java/com/example/test/HedgehogOverlayService.java`
- New local wallet read/storage owner:
  - `app/src/main/java/com/example/test/SolanaWalletClient.java`
- Android assistant prompt files:
  - `app/src/main/res/raw/soul_md.txt`
  - `app/src/main/res/raw/tools_md.txt`

Current approach:
- The Android app stores a Solana wallet name + wallet address locally.
- If the user asks wallet-related questions without a stored wallet, Hitomi opens a purple `Solana` window and asks the user to fill it in.
- Solana chain reads are then done on demand from that stored wallet address.

## The agent1c.ai pattern to copy exactly

From the working web implementation in `../agent1c-ai.github.io`:

1. Keep only the wallet address in runtime context/prompt.
2. Keep only the wallet address in runtime context/prompt.
3. Do not inject live balance or recent transaction data into the system prompt.
4. Teach the assistant via `TOOLS.md` that Solana balance/transactions can be checked on demand.
5. Fetch wallet data only when the assistant actually needs it.
6. Use simple read-only RPC calls with fallback RPC nodes.

This token guard matters:
- The earlier mistake on web was effectively sending too much wallet state into prompt context.
- Android must avoid that.
- Wallet data belongs in tool results or direct on-demand fetch results, not in every chat turn's base prompt.

## Exact Android hook plan

### 1) Add one small native read-only Solana helper

New file recommended:
- `app/src/main/java/com/example/test/SolanaWalletClient.java`

Mirror the simplicity of `agent1c-ai.github.io/js/solana-wallet.js`.

Responsibilities:
- normalize wallet address
- call `getBalance`
- call `getSignaturesForAddress`
- call `getTransaction`
- fall back across multiple RPC URLs if one fails
- return one normalized snapshot object

Recommended fallback RPC list:
- `https://api.mainnet-beta.solana.com`
- `https://solana-rpc.publicnode.com`
- `https://rpc.ankr.com/solana`

Suggested snapshot shape:
- `address`
- `chain`
- `lamports`
- `balanceSol`
- `fetchedAt`
- `rpcSource`
- `recentTransactions`
- `lastError`

Suggested transaction shape:
- `signature`
- `slot`
- `blockTime`
- `confirmationStatus`
- `ok`
- `err`
- `memo`
- `netLamports`
- `netSol`

### 2) Add a tiny Android wallet state holder

Do not build a large subsystem.

The Android app only needs enough state to answer wallet questions without refetching every turn:
- stored wallet name
- wallet address
- last fetched balance
- last fetched recent transactions
- rpc source
- fetched time
- last error

Implementation:
- keep this in `SolanaWalletClient` backed by `SharedPreferences`

Do not:
- poll in background
- refresh on every message
- append wallet snapshot to transcript by default

### 3) Add a visible purple `Solana` window in the overlay

Files:
- `app/src/main/java/com/example/test/HedgehogOverlayService.java`
- `app/src/main/res/layout/overlay_solana.xml`
- `app/src/main/res/drawable/beos_titlepill_bg_purple.xml`

Expected behavior:
- similar to the existing browser window pattern
- title bar should be purple, not yellow
- fields:
  - `Wallet Name`
  - `Wallet Address`
- button:
  - `OK`
- pressing `OK` stores the wallet locally for later read-only Solana checks
- if no wallet is stored and user asks anything wallet-related, Hitomi should open this window instead of pretending she can inspect a wallet already

### 4) Keep prompt injection minimal in `HitomiCloudChatClient`

File:
- `app/src/main/java/com/example/test/HitomiCloudChatClient.java`

Current hook:
- `buildSystemPrompt()` currently returns `soul_md + tools_md`

Required change:
- append only a short runtime note when a stored Solana wallet exists

Example shape:
- `Runtime note: the user is connected with Solana wallet "<address>". You may inspect balance and recent transactions when needed.`

Do not append:
- live balance
- lamports
- recent transaction lines
- summaries of wallet activity

This is the main token guardrail.

### 5) Update Android `soul_md.txt` and `tools_md.txt`

Files:
- `app/src/main/res/raw/soul_md.txt`
- `app/src/main/res/raw/tools_md.txt`

Add the same kind of read-only wallet-awareness language already working in `agent1c.ai`.

`soul_md.txt` should teach:
- stored Solana wallet is part of workspace context
- Hitomi may help inspect balances and recent transactions
- wallet access is read-only unless a tool proves otherwise
- never imply signing, sending, or custody
- never claim a refresh happened unless it actually did

`tools_md.txt` should teach:
- how to request wallet inspection
- that balance and transaction checks are on-demand only
- that these checks are read-only

Important:
- do not turn `tools_md.txt` into a dump of live wallet state
- keep it capability-oriented, not data-oriented

### 6) Android execution path

Use Android-local tool tokens for wallet reads:
- `android_solana_wallet_overview`
- `android_solana_wallet_refresh`

Extend `HedgehogOverlayService.parseAssistantReply()` and `maybeExtractAndroidTool()` to execute them locally and feed the tool result back into the next chat turn, similar to browser-read follow-up flow.

## Exact files likely to change

Almost certainly:
- `app/src/main/java/com/example/test/HitomiCloudChatClient.java`
- `app/src/main/java/com/example/test/HedgehogOverlayService.java`
- `app/src/main/res/raw/soul_md.txt`
- `app/src/main/res/raw/tools_md.txt`

Likely new:
- `app/src/main/java/com/example/test/SolanaWalletClient.java`
- `app/src/main/res/layout/overlay_solana.xml`
- `app/src/main/res/drawable/beos_titlepill_bg_purple.xml`

## Suggested minimal implementation order

1. Extend `SupabaseAuthManager` to capture wallet address and chain.
2. Add `SolanaWalletClient.java` with local wallet storage plus read-only RPC + fallback nodes.
3. Add the purple `Solana` window and local save flow in the overlay.
4. Add minimal wallet runtime note in `HitomiCloudChatClient.buildSystemPrompt()`.
5. Update `soul_md.txt` and `tools_md.txt` for read-only Solana capability.

## What not to do

- Do not inject full wallet snapshots into every system prompt.
- Do not put recent transactions into `SOUL.md` or `TOOLS.md`.
- Do not scrape wallet info from UI labels when structured auth identity fields exist.
- Do not depend on mobile browser wallet login as the main Android Solana path.
- Do not imply chain write capability.
- Do not add background refresh loops.
