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
