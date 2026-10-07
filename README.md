# Vowed

Stake on any goal, prove it each day with your phone, play with friends. A native Android app (Kotlin and Jetpack Compose) with a Solana program, built for the Solana Mobile "CLOCK IN" hackathon.

> **Status: work in progress, devnet only.** Everything below that says "works" was run on the Android emulator against Solana devnet with test tokens (see `docs/submission-notes.md` for exactly what is proven and what is not). No mainnet, no real funds. The program is unaudited.

## What works today

- Connect a wallet with Mobile Wallet Adapter, sign in, register the phone's hardware-backed proof key.
- Create a challenge from a goal template, stake into a program-owned vault, check in each day, settle, and claim, including a success payout that includes a friend's forfeited stake.
- Daily proofs: self-attest, focus timer, steps, place, app usage (collectors on the phone; verified end to end on devnet with the backend).
- Demo pools with minutes-long days, always labelled **DEMO POOL**, for trying the whole loop quickly.
- **Test tokens**: an in-app button that sends test USDC and test SKR (see below).

Not built yet: camera rep counting, plain-language goal parsing (AI), squads UI and push, letters, widget, yield, SKR rewards. See `SPEC.md` and `docs/progress.md`.

## Repository layout

| Path | What |
|---|---|
| `programs/vowed` | Anchor program (Rust) and tests |
| `backend` | TypeScript API, oracle, indexer, settlement crank, test-token faucet |
| `android` | Kotlin and Compose app |
| `shared` | JSON schemas and test vectors used by all three |
| `docs` | progress, verified facts, device tests, threat model, runbook |

Commands are listed in `CLAUDE.md` and `docs/runbook.md`.

## Solana Mobile Stack components used

- **Mobile Wallet Adapter** (`clientlib-ktx` 2.2.0): connect, Sign-In-With-Solana, sign messages, sign and send transactions.
- **Android Keystore** proof key with key attestation (the backend verifies the certificate chain against Google's roots).
- Developed against the **Mock MWA Wallet**; a real MWA wallet and a Seeker are on the device checklist (`docs/device-tests.md`).

## Test tokens (no outside faucet needed for the app's tokens)

The app needs two kinds of devnet funds:

1. **Test USDC (tUSDC) and test SKR (tSKR)**: our own tokens, created for this project. They have no value and exist only on devnet. In the app, **Today -> Test tokens -> Get test tokens**. The backend sends a small fixed amount (20 of each by default) to the signed-in wallet.
   - Limits (enforced on the server): **one claim per wallet per 24 hours**, and a **global cap per UTC day** (200 claims by default). The caller cannot choose amounts or recipients. A failed send does not use up a claim. If the faucet's fee wallet runs low it stops and says so.
   - The tokens are labelled "TEST TOKENS" wherever they appear.
   - It is switched off unless the backend has `FAUCET_AUTHORITY_SECRET_KEY` and both mint addresses in its environment. The key is the mint authority of the two test mints; it lives only in the backend's environment (for local runs it is read from the git-ignored `backend/.devnet/` folder by `scripts/dev-backend.ps1`). It is never in the repository or the APK. See `backend/.env.example`.
   - Tests for the limits: `backend/test/faucet.test.ts` (13 tests; deliberately broken versions of each limit are caught).
2. **Devnet SOL** for network fees and account rent. The test tokens do **not** include SOL. A tester needs a little devnet SOL (a pool costs about 0.005 SOL of rent; joining about 0.002). Use Solana's public faucet at <https://faucet.solana.com/> or ask the project team. The "Copy my wallet address" button in the Test tokens card helps with this.

### Circle's devnet USDC

Circle's official devnet USDC (mint `4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU`) is on the allowed list for **normal (non-demo) pools**. Demo pools accept only our own test tokens, by design. Status of Circle USDC in the app, checked on 2026-10-07:

- Creating a normal pool in the app with Circle's mint **works** (pool created on devnet, Open, 24-hour days).
- Joining, check-ins and claiming with it are **not verified**, because no test wallet holds any and we did not call Circle's faucet (<https://faucet.circle.com/>, 20 USDC per address per chain every 2 hours, no account needed). The program treats every standard SPL mint the same way, so this is expected to work, but it is untested. A tester who gets Circle devnet USDC can use it for normal pools.

## Using the app on devnet: which wallets can be set to devnet

The app asks the wallet for the `solana:devnet` chain. A wallet that cannot serve that chain answers with the MWA error `ERROR_CHAIN_NOT_SUPPORTED`. What we verified against official documentation (sources in `docs/verified-facts.md`):

| Wallet | Devnet | How |
|---|---|---|
| Mock MWA Wallet (Solana Mobile's test wallet) | **Yes, verified by running it** | Submits to devnet RPC. Development only. |
| Phantom | **Yes, per Phantom's docs** (not run by us) | Settings -> Developer Settings -> Testnet Mode, then enable Solana Devnet |
| Solflare | **Yes, per Solflare's help center** (not run by us) | Settings -> Network -> Devnet (the article gives no more detail) |
| Seed Vault Wallet (Seeker) | **Not documented**; unverified | Solana Mobile's docs do not say. To be tested on a Seeker. |
| Backpack | **Unverified**; no official statement found | |

## Trust model (short)

Funds are held by the program; users sign their own deposits and claims. Daily completion is recorded by an **oracle** key after the backend verifies a device-signed proof. The oracle can be wrong or malicious; mitigations (hardware key attestation, nonces, trust tiers that cap stakes, plausibility limits, void before payout) are in `docs/threat-model.md`. Not built: multiple attestors, dispute voting.

## License

To be added before submission.
