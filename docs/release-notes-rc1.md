# Vowed 0.1.0-rc1.7 (build 8), pre-release

Android APK for the Solana Mobile hackathon. **Solana devnet only: test tokens, no real money.** Debug-style demo signing key (the same key as every earlier rc1 build, so it updates in place).

- File: `vowed-v0.1.0-rc1.apk`, 100,705,938 bytes (96 MB)
- SHA-256: `08fc15ab58aecaf1936306a8e654eeadf319d04e1e40f45273f1a4417abc910e`
- Signing certificate SHA-256: `2811c2292bdd7772282b39c1c37765da9d8da42b1018fcff4f374c987305e7b8`
- Backend: https://vowed-backend.onrender.com (free tier; the first request after a pause can take up to a minute; a server redeploy resets sessions, so the app asks you to sign in again)

## What changed since rc1.6 (Phantom fix)
- **Phantom 26 never showed the approval screen** because it does not support `sign_and_send_transactions`. Vowed now asks the wallet what it supports (`get_capabilities`), uses `sign_transactions` when needed, and its server sends the signed transaction exactly once. The method that answered is remembered per wallet app.
- **Phantom drops a request that arrives right after its Connect screen closes.** Vowed now waits 3 s after any wallet screen (sign-in and transactions).
- **A real Samsung phone with Phantom failed with "Blockhash not found"**: Phantom's start-up, Connect and Confirm took longer than a blockhash lives. Vowed now asks the server for the same transaction with a fresh blockhash right before it asks the wallet to sign (and checks the copy differs only in the blockhash).
- **Phantom adds a priority fee to the transaction.** The server now recognises a signed transaction by what it does (fee payer and every non-Compute-Budget instruction) and accepts a priority fee up to 0.001 SOL.
- A refused saved authorization is remembered per wallet and not sent again. "Try again" opens a fresh request with the method that wallet answers to.
- "Session closed or no answer" is treated as unknown: Vowed reads the chain for up to 20 s before saying it failed, and offers **Check my last attempt**. It never asks for a second signature without checking the chain first.
- Wallet diagnostics: wallet app, capabilities, method, timings, chain checks.
- App Link for the identity host (Android reports it verified).

## Verified with real Phantom (emulator, 2026-10-11)
Phantom 26.32.0 from Google Play, throwaway devnet wallet, Testnet Mode: sign in (Connect, Sign message) and create-and-stake. The create-and-stake is on devnet: `4h8b6oYKSpV8x8m2E18KbxFNmF9ZBkh5cFZy7npVwtNCqf36vpo9qamJwBya3Sr5xcfatjWE8zn4sHbUrvMTrXde` (CreatePool + JoinPool, no error). Screenshots: `docs/ui/phantom-real-*.png`, `docs/ui/vowed-real-phantom-stake-landed.png`. Details and Phantom's own log lines: `docs/verified-facts.md`.

## Known limits (read before the demo)
- **Phantom sometimes does not draw its approval sheet** on the emulator (it logs the request and shows nothing). It can take 20 to 50 seconds on a slow emulator: wait about a minute, then tap **Try again** (a fresh request has worked). Not seen on a real phone yet.
- **Phantom still shows "This app's identity could not be verified"** (red banner) although Android reports the domain verified. Phantom's own check fails and cannot be fixed from the app. Connecting still works.
- **Not tested with real Phantom:** a physical Samsung phone, a Seeker, rejecting a request, closing Phantom mid-request, a wallet with too little SOL, joining a challenge (create-and-stake was tested), and Solflare. These were tested with the Mock wallet and unit tests only.
- Test tokens (tUSDC, tSKR) and the yield figures are test or simulated and are labelled in the app.



