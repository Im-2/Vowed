# Manual real-device tests

Run on a physical Android phone (and on a Seeker if available). Record the device model, Android version and date next to each result.
This file grows with each phase; the sections below are what the backend needs verified now.

## Device key attestation (Phase 2 backend, Phase 4 app)

| # | Test | Expected | Result |
|---|---|---|---|
| A1 | Generate a P-256 key in Android Keystore with `setAttestationChallenge(<challenge from /v1/devices/challenge>)`; send the certificate chain to `/v1/devices/register` | 200, `attestation.level` is `tee` or `strongbox`, `trustCap` is `high` on a locked, stock device | |
| A2 | Same on a Seeker | Same. If the chain is rejected or lacks hardware attestation, record the exact `attestation.note` and adjust expectations in the SPEC | |
| A3 | Re-register after changing the challenge by one byte | 400 `attestation_failed` or `bad_challenge` | |
| A4 | Phone with an unlocked bootloader (if available) | Accepted but `trustCap` is `low` (verified boot state is not "verified") | |
| A5 | Emulator | No hardware attestation: with `REQUIRE_ATTESTATION=false` registers with `trustCap` `low`; with `true` is refused | |
| A6 | The revocation list is reachable from the server's network | `attestation.note` says "verified", not "revocation list not reachable" | |

## Signing path

| # | Test | Expected | Result |
|---|---|---|---|
| S1 | Sign a ProofPackage with the Keystore key (`SHA256withECDSA`) over the canonical JSON described in `backend/src/proofs/verify.ts` | `/v1/proofs/submit` verifies the DER signature | |
| S2 | Wallet signs the device registration message through MWA `signMessages` | Backend accepts the 64-byte ed25519 signature | |

## Later phases add: pose counting, usage stats, step counting, geofence, notifications.


## Phase 3: wallet and app flow (real phone / Seeker)

| # | Test | Expected | Result |
|---|---|---|---|
| W1 | Install the debug APK and a real MWA wallet (Phantom, Solflare or Seed Vault); connect from onboarding | Wallet shows "Vowed wants to connect"; the app signs in and registers the phone | |
| W2 | Create a demo pool, join, wait, claim (devnet) | Same result as the emulator run; stake leaves and returns to the wallet | |
| W3 | Approve a transaction after waiting more than a minute | App shows the wallet-did-not-complete message; retrying works | |
| W4 | Backend URL: the debug build points at `http://10.0.2.2:8787` (emulator only). On a phone set the backend URL to a reachable HTTPS host or the PC's LAN address through the debug override | Connect works | |


## Phase 4: daily proofs (real phone / Seeker)

Install the **release** build for anything that must prove the proof is real (it has no test-data buttons). The debug build adds an "Inject test data" panel; on a phone use it only to skip waiting.
Use a demo pool with 2-minute days (New challenge: Demo pool on, "2 min days") so each test takes minutes. Demo goals ask for small amounts (20 s focus, 20 steps, 10 s at a place).

| # | Test | Expected | Result |
|---|---|---|---|
| P1 | Focus timer: start it, keep the app open for the target, submit | "Recorded", a Solana transaction id, streak +1 | |
| P2 | Focus timer: start it, press Home for 30 s, come back | The counter did not grow while away; submit stays disabled until enough foreground time | |
| P3 | Steps: allow Physical activity, walk about 30 steps | The counter rises (it counts since the check-in screen first opened that day, and says so); Submit enables at the target | |
| P4 | Steps: deny the permission | A clear explanation and an "Allow" button; nothing is sent | |
| P5 | Place goal: create it at your current spot (New challenge, gym goal, "Use my current location") then check in standing there | "You are at the place", time there grows, Submit enables after the target | |
| P6 | Place goal: walk 300 m away | "About N m away", the timer stops growing | |
| P7 | Place goal on a second phone with the same wallet | "The spot ... is not on this one": coordinates never left the first phone | |
| P8 | Usage goal (Instagram under 30 min): grant Usage access, open Instagram for a minute, check in | The reported usage matches reality within a few seconds; under the limit is accepted, over is refused with a reason | |
| P9 | No-use window (TikTok after 10pm): use the app during the window, check in | Refused: "used the app during the blocked window" | |
| P10 | Self-attest | Recorded with trust "low" and the lowest stake cap | |
| P11 | Replay: submit, then press Replay (debug build) | Same result, the day count does not change | |
| P12 | Release build | There is no "Inject" panel anywhere; `DebugProofs` in the APK only throws (verified on the compiled classes) | |
| P13 | Proof after the day window closed | "the check-in window for that day is not open" | |
| P14 | Phone clock set one hour ahead | Proof refused (timestamp in the future) | |


## Test tokens and wallets on devnet (real phone / Seeker)

| # | Test | Expected | Result |
|---|---|---|---|
| T1 | Phantom: enable Testnet Mode and Solana Devnet, connect Vowed | Connects; sign-in works; no `ERROR_CHAIN_NOT_SUPPORTED` | |
| T2 | Solflare: Settings -> Network -> Devnet, connect Vowed | Same | |
| T3 | Seed Vault Wallet on a Seeker: connect Vowed on devnet | Record exactly what happens (works / chain not supported / needs a setting) | |
| T4 | Today -> Get test tokens on a new wallet | 20 tUSDC and 20 tSKR arrive; the card says TEST TOKENS | |
| T5 | Press it again | Disabled with "Available again in ..." | |
| T6 | A wallet with no devnet SOL tries to create a pool | A clear failure from the wallet; the card tells the user to get devnet SOL | |
| T7 | Circle devnet USDC (get 20 from https://faucet.circle.com/ by hand): create a normal pool, join, check in the next day, claim after it ends | Everything works like the test tokens | |
