# Open Hitomi Setup

Open Hitomi accepts either:

- A local or self-hosted Ollama-compatible endpoint
- An xAI API key

## Local Ollama

The simplest local endpoint is:

```text
http://127.0.0.1:11434
```

Open Hitomi will query Ollama for installed models and use the first available one. If no models are installed yet, pull one first. Example:

```sh
ollama pull qwen2.5:3b
```

Then paste `http://127.0.0.1:11434` into Open Hitomi and tap `>>`.

If your Ollama server is on another machine, paste that reachable `http://...` or `https://...` endpoint instead.

## xAI

If you prefer xAI, paste your API key into the same field and tap `>>`.

## Notes

- Open Hitomi stores the entered endpoint or API key locally on-device.
- Solana support is monitoring-only for public addresses.
- Open Hitomi is not a wallet, stores no local private keys, and does not sign or send transactions.
