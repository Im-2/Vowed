# Vowed

Stake on any goal, prove it each day with your phone, play with friends. A native Android app (Kotlin and Jetpack Compose) with a Solana program, built for the Solana Mobile "CLOCK IN" hackathon.

> **Status: work in progress, devnet only.** Everything below that says "works" was run on the Android emulator against Solana devnet with test tokens (see `docs/submission-notes.md` for exactly what is proven and what is not). No mainnet, no real funds. The program is unaudited.

## What works today

- Connect a wallet with Mobile Wallet Adapter, sign in, register the phone's hardware-backed proof key.
- Create a challenge from a goal template, stake into a program-owned vault, check in each day, settle, and claim, including a success payout that includes a friend's forfeited stake.
- Daily proofs: self-attest, focus timer, steps, place, app usage (collectors on the phone; verified end to end on devnet with the backend).
- Demo pools with minutes-long days, always labelled **DEMO POOL**, for trying the whole loop quickly.
- **Test tokens**: an in-app button that sends test USDC and test SKR (see below).

Built: staking and settlement, plain-language goals with camera, steps, timer, place, usage and self-report proofs, squads and Explore, coach, letters to future me, a home-screen widget, weekly SKR rewards and an SKR streak freeze (test SKR on devnet), and an open proof-provider format with a sample provider. Not built: real yield (shown as SIMULATED), push when the app is closed, voice letters, the signed release APK. See `SPEC.md` and `docs/progress.md`.

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

## Goals in plain words (AI usage and costs)

Type a goal in your own words ("do 20 squats every day for a week"); the app shows a plan with how it will be proved, its **trust tier**, its limits, and lets you edit it before you stake.

- **13 built-in templates work with no AI**: squats, push-ups, steps, walk outside, gym visit, focus/study time, reading, meditation, app-usage limit, no-use window for an app, early wake, phone-free sleep window, hydration. A deterministic matcher reads the amount, app, time window and length from the text. Common goals are answered this way, instantly and for free.
- **Free-form goals use Google's Gemini free tier, through our server.** The app never holds a key. Only the **typed goal text** is sent to the model: no wallet address, no device information, no history. The free tier lets Google use submitted content to improve its products and allows human review, so the app says this before sending, asks people not to type personal details, and has a switch to turn AI off (then only the templates are used).
- **The model's answer is never trusted.** It must pass a strict schema and a second set of rules: the proof type must fit the target, amounts must be realistic (too small or too large is refused), only whitelisted parameters survive (no coordinates), hidden or direction-changing characters are removed, and the **trust tier is always decided by the proof type**, never by the plan. Anything that fails falls back to the templates or asks a question. The same checks run again when a pool is created.
- **Cost: $0.** Default model `gemini-3.5-flash-lite` on the free tier. The server caps its own use (per wallet per hour, per day for everyone), caches answers, and backs off when Google says it is busy. Exact free-tier limits are shown only in Google AI Studio, so none is assumed.
- Goals no phone can verify (weight, food, smoking, feelings, money) are labelled as such, with a closest checkable version and an optional low-trust "I did it" version with a small stake cap.

## Camera rep counting

Squats and push-ups are counted on the phone with CameraX and ML Kit pose detection (the model is inside the app; no download). **The camera image is analysed in memory and dropped: it is never stored or sent. Only the rep count and "hand-raise check passed" leave the phone.** Before the set counts, the screen asks for a random hand to be raised at a random moment, which a pre-recorded video cannot answer. The counting logic is unit-tested on a synthetic skeleton; counting a real person is on the real-device checklist (`docs/device-tests.md`) and has not been run yet.

## Trust model (short)

Funds are held by the program; users sign their own deposits and claims. Daily completion is recorded by an **oracle** key after the backend verifies a device-signed proof. The oracle can be wrong or malicious; mitigations (hardware key attestation, nonces, trust tiers that cap stakes, plausibility limits, void before payout) are in `docs/threat-model.md`. Not built: multiple attestors, dispute voting.

## License

To be added before submission.


## What SKR does in the app (devnet test SKR only)

1. **Weekly rewards for the top streaks.** After each week a job pays 10, 5 and 3 SKR to the three longest streaks (at least 3 days, in challenges with two or more players; demo pools do not count). Paid by plain token transfers from a rewards wallet; paid at most once per wallet per week.
2. **A streak freeze costs 1 SKR.** It keeps your streak alive across one missed day. It does not change check-ins, days completed or payouts. The app checks the payment transaction on the phone before your wallet signs it; the server confirms it on chain.
SKR staking is not used. On devnet SKR is our own test token with no value; mainnet SKR is not touched. Details: `docs/progress.md` (Phase 8), `docs/verified-facts.md`, and the plug-in format in `docs/proof-provider-spec.md`.
