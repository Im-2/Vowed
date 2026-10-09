# Vowed

**Stake on any goal, prove it each day with your phone, play with friends.** A native Android app (Kotlin and Jetpack Compose) with a Solana program, built for the Solana Mobile "CLOCK IN" hackathon.

> **Status: hackathon build, Solana devnet only.** All money in the app is test tokens with no value. No mainnet, no real funds. The program is unaudited. Everything below that says "works" was run on the Android emulator against Solana devnet; what has *not* been run on a real phone is listed in [What is and is not verified](#what-is-and-is-not-verified).

## The idea

Everyone fails their goals. In Vowed you type a goal in plain words ("No TikTok after 10pm for a week"), put a small stake behind it, and prove it each day with something the phone can check: the camera counting reps, the step counter, app-usage data, a focus timer, a place. Finish and you get your stake back (plus a share of friends' forfeits); miss days and part of it is forfeited. Squads, streaks, a coach and letters to your future self make it social.

## Architecture

```
Android app (Kotlin, Compose, MVVM)                 Wallet app (Mobile Wallet Adapter)
  - wallet connect, sign-in, signing  <-------------> Seed Vault Wallet / Phantom / Solflare / Mock wallet
  - proof engine on the phone (camera pose, steps, usage, timer, place)
  - proof key in the Android Keystore (hardware-backed when the phone has it)
  - decodes every transaction before the wallet signs it
        |  HTTPS, JWT from wallet sign-in (kept encrypted with a Keystore key)
        v
Backend (TypeScript, Fastify, SQLite)
  - API, goal parser (templates + Gemini), proof verifier, oracle signer,
    chain indexer, settlement crank, test-token and SOL faucet, SKR rewards
        |  Solana RPC
        v
Solana program (Anchor, Rust), devnet: BMTXJRZ4QxzCg4UCHKo6qGGiGXKW26ARPAtPaXA8k7EL
  - config, pools, participations, token vaults owned by the pool PDA
```

| Path | What |
|---|---|
| `programs/vowed` | Anchor program and tests (LiteSVM) |
| `backend` | TypeScript API, oracle, indexer, crank, faucets, rewards |
| `android` | Kotlin and Compose app |
| `shared` | JSON schemas and test vectors used by all three |
| `docs` | progress, verified facts, threat model, runbook, device tests, demo script, screenshots |

The backend builds each unsigned transaction; **the app decodes it and checks the program id, accounts and amounts against what it expects before asking the wallet to sign** (`TxChecker`). The wallet is the only signer of user funds.

## Trust model (honest version)

- **Funds are controlled only by the program.** Users sign their own deposits and claims. The oracle, admin and backend cannot move stakes.
- **Daily completion is recorded by a backend oracle key** after it verifies a proof signed by the phone's hardware-backed key (nonce per proof, key attestation checked against Google's roots, plausibility limits). The oracle can be wrong or malicious, and a determined user can try to fake a proof. That is why every goal has a **trust tier** (high, medium, low) that caps the stake, and why pools have stake and size caps.
- **Not built (roadmap):** multiple attestors, squad dispute voting, timelocked oracle rotation.
- The full review (what the program guarantees, who can do what, threats and gaps) is in [`docs/threat-model.md`](docs/threat-model.md). Open gaps are listed there too: privacy policy, in-app data deletion, age gate.

## Solana Mobile Stack components used

| Component | Used? | Where |
|---|---|---|
| **Mobile Wallet Adapter** (`clientlib-ktx` 2.2.0) | Yes | connect, Sign-In-With-Solana, message signing, sign-and-send; the only signing path for user funds |
| **SKR token** | Yes (devnet test SKR) | weekly rewards and a streak freeze, see below |
| Seed Vault Wallet (Seeker) | Reachable through MWA, **not tested**; Seed Vault SDK signing is **not used** | see `docs/device-tests.md` |
| Solana dApp Store | Not published; the app is meant to be publishable | `docs/verified-facts.md` |
| Android Keystore key attestation | Yes (Android platform feature, not Solana Mobile) | proof key and trust tiers |

## How SKR is used (devnet test SKR only)

1. **Weekly rewards for the top streaks.** After each week a job pays 10, 5 and 3 SKR to the three longest streaks (at least 3 days, in challenges with two or more players; demo pools do not count), by plain token transfers from a rewards wallet, once per wallet per week. The **Rewards** screen shows the balance, a countdown, the podium, your rank and the leaderboards ("This week" and "All time").
2. **A streak freeze costs 1 SKR.** It keeps your streak across one missed day. It never changes check-ins, days completed or payouts. The app checks the payment transaction on the phone before your wallet signs it; the server confirms it on chain.

SKR staking is not used. On devnet SKR is our own test token with no value; mainnet SKR is never touched. Leaderboards show other people only as an avatar, a short name and a streak number, and a person can hide themselves in You.

## How AI is used (and what it costs)

- **13 built-in templates work with no AI** (squats, push-ups, steps, walking, gym visits, focus time, reading, meditation, app limits, no-use windows, early wake, phone-free sleep, hydration). A deterministic matcher reads amount, app, time and length. Common goals are answered this way, instantly and for free.
- **Free-form goals use Google's Gemini free tier, through our server.** The app never holds a key. Only the **typed goal text** is sent: no wallet address, device information or history. The free tier lets Google use submitted content to improve its products and allows human review, so the app says so before sending, and has a switch to turn AI off (then only the templates are used). On the hosted server a Gemini answer takes about 12 to 15 seconds, so the server waits up to 28 seconds before falling back to the templates.
- **The model's answer is never trusted.** It must pass a strict schema and a second set of rules (the proof type must fit the target, amounts must be realistic, only whitelisted parameters survive, hidden characters are removed) and **the trust tier is decided by the proof type, never by the plan**. Anything that fails falls back to templates or asks a question.
- **Camera rep counting is on the phone** (CameraX and ML Kit pose detection, the model is inside the app). Frames are analysed in memory and dropped; only the rep count and a hand-raise check result leave the phone.
- **Cost: $0.** Default model `gemini-3.5-flash-lite` on the free tier, with per-wallet and daily caps, an answer cache and backoff.

## Honesty labels

Anything simulated, sample or test is labelled where it appears, with a short tag and an (i) that explains it:

| Label | Meaning |
|---|---|
| **TEST**, **TEST SKR**, tUSDC, tSKR | Devnet test tokens with no value |
| **DEMO** / **DEMO MODE** | Demo pool: minutes-long "days", test money only, for trying the loop quickly |
| **SAMPLE** | Made-up or team-created content (sample challenges, sample leaderboard players); never paid |
| **SIMULATED** | Yield on stakes is not real in this version; the number shown is illustrative |
| Trust level (High / Medium / Low) | How hard the proof is to fake; caps the stake |

## Run it

Prerequisites: Windows 11 with Git Bash and PowerShell, Node 24 (the backend uses the built-in `node:sqlite`), JDK 17, Android SDK (API 36 emulator), and WSL Ubuntu with Rust, Solana CLI and Anchor 1.2.0 for the program. All commands are also listed in `CLAUDE.md` and `docs/runbook.md`.

```bash
# backend (Windows): fast tests, then dev server against devnet
cd backend && npm ci && npm test
powershell scripts/dev-backend.ps1            # port 8787, throwaway devnet keys from backend/.devnet

# program build and tests (WSL), then the backend suite that runs the real program in a VM
wsl -d Ubuntu -u root -- bash /mnt/c/Users/hp/Vowed/scripts/program-build.sh
wsl -d Ubuntu -u root -- bash /mnt/c/Users/hp/Vowed/scripts/backend-test.sh

# Android: unit tests and debug APK, emulator, release APK
powershell scripts/android-test.ps1
powershell scripts/emulator.ps1               # AVD vowed_api36 and the Mock MWA Wallet
powershell -ExecutionPolicy Bypass -File scripts/android-release.ps1   # signed release APK, prints size and SHA-256
```

The app talks to the hosted devnet backend `https://vowed-backend.onrender.com` by default (release builds can only use that URL; debug builds can switch to a local backend in You). Never put secrets in the repo: use `backend/.env.example` as the template for environment variables; `bash scripts/secret-scan.sh` scans the tree and the whole history before every push.

### Switching your wallet to the practice network (devnet)

Vowed uses Solana devnet, a practice network, so no real money is used. A wallet that is on the real network (mainnet) turns the connection down, and an app cannot flip that switch for you, so the first time you tap Connect wallet Vowed shows "One quick step before connecting": open Phantom, then **Settings, Developer Settings, Testnet Mode** (Solana Devnet), and come back. The same screen appears again if a wallet turns the connection down for a network reason, and **You > Wallet setup help** reopens it. If the app cannot reach its server, **Check connection** (in You and on the error) tests it and shows why. Sources and what we could not verify: `docs/verified-facts.md`.

### Test tokens, devnet SOL and wallets

- **Get test tokens** (Home, tap the test-token pill): a small fixed amount of tUSDC and tSKR, once per wallet per 24 hours. The **first claim also sends 0.01 devnet SOL** for network fees, once per wallet, from a dedicated small wallet (never a public faucet), with daily and total caps. See `docs/runbook.md`.
- **Using a real wallet:** use a throwaway wallet, switch it to devnet, never share a seed phrase. The app has a "Using a real wallet" screen in You. Phantom (Settings, Developer Settings, Testnet Mode) and Solflare (Settings, Network) document a devnet switch; neither was run by us. The Seed Vault Wallet is unverified. The Mock MWA Wallet works on devnet (verified). Sources in `docs/verified-facts.md`; a real-wallet test checklist is in `docs/device-tests.md`.
- Circle's devnet USDC is on the allow list for normal pools; creating a pool with it works, joining and claiming with it are not verified.

## What is and is not verified

**Verified** (details and proofs in `docs/submission-notes.md` and `docs/progress.md`): the full loop on devnet with demo pools (create, join, check in, settle, claim) through the app on the emulator with the Mock wallet; the hosted backend; goals, Explore, squads, coach, letters, widget, rewards and streak freeze; leaderboards; the Keystore-encrypted session surviving an app restart; a signed release APK that launches. Tests: program 66, backend 289 passing (18 need the WSL VM), Android 114 unit tests.

**Not verified yet:** any real phone or Seeker (camera counting of a real person, step counter, usage stats, hardware key attestation), Phantom, Solflare and the Seed Vault Wallet over MWA, Circle USDC join and claim, push notifications when the app is closed (needs a Firebase project), real yield (shown as SIMULATED), voice letters.

## License

[MIT](LICENSE). Nunito font: SIL Open Font License 1.1 (`docs/licenses`). The Solana logomark and the SKR icon are used as supplied; see `docs/verified-facts.md` and `docs/photo-credits.md` for sources and terms.
