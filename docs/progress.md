# Progress

## Phase 0: Foundations — GATE PASSED (2026-10-06), awaiting user review

Gate: "all three projects build; an emulator runs a hello-world app that connects to the Mock MWA Wallet."

| Item | Status | Evidence |
|---|---|---|
| Backend (Fastify + TS + vitest) | builds, 1 test passes | `cd backend; npm test` |
| Android (Compose, AGP 9.4.1, Gradle 9.8.0, compileSdk 37, MWA 2.2.0) | debug APK builds | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Anchor program (Anchor 1.2.0, LiteSVM template) | builds, template test passes | `scripts/program-build.sh` (uses `anchor test --validator legacy`, since the default `surfpool` is not installed) |
| Emulator `vowed_api36` (Android 36, google_apis x86_64, WHPX) | runs | AVD config written by hand (avdmanager fails on this image's missing devices.xml) |
| Mock MWA Wallet | built from source, installed | AUTHENTICATE needs a screen lock; PIN set on the emulator |
| Hello-world connects via MWA | works | `docs/phase0-mwa-connect.png` (Vowed shows the wallet public key) |
| SPEC section 14 | 9 of 12 verified or partial, see `docs/verified-facts.md`; official portal rules added to SPEC | |

Changes to the plan made this phase: SKR redesign (rewards and perks, not staking), deadline Oct 12 12:59 PM GMT+1,
3-minute real-device demo video, repo access through Radiants Align. See SPEC 1.1 and 9.6.

Still open (not blocking Phase 1): MediaPipe vs ML Kit decision (Phase 5), Kamino program ids (Phase 8), RPC and DB free tiers (Phase 2),
Gemini key and exact model id (Phase 5), APK link vs upload on the submission form, Seeker/real-phone key attestation (device tests).

Known placeholders: the Anchor program id `88wHex...` is the template's; it is replaced when we generate the real devnet keypair in Phase 1.
The release-signing keystore does not exist yet (Phase 9). Nothing secret is in the repo; the WSL test keypair lives in `~/.config/solana/id.json` inside WSL.

Real-device note: the demo video must be shot on a physical phone. Nothing here needs it yet.


## Phase 1: Solana program — GATE PASSED (2026-10-06), awaiting user review

Gate: "all tests pass with one command; payout property test passes; devnet deployment works." IDL exported.

- Tests: `wsl -d Ubuntu -u root -- bash /mnt/c/Users/hp/Vowed/scripts/program-build.sh` (also in `scripts/check-all.ps1`): 60 pass (56 LiteSVM integration/property + 4 unit).
  Includes every edge case in SPEC 4.4 and every check in 4.5; payout property tests on pure math and on-chain with random pools.
- Mutation sanity check (`scripts/program-mutation-check.sh`): 3/3 broken guards caught by the tests.
- **Devnet deployment:** program `BMTXJRZ4QxzCg4UCHKo6qGGiGXKW26ARPAtPaXA8k7EL`, deploy tx `3bWZkcUugU72LA6kAPEhnr4e17fdpuh9xBQFjibqgDQVcsJNLGcqNgfr9J4Gh4cXihDMLARNYit7aG3gWfJvFr9q`,
  ProgramData `3UdUkmhBwczJaqy684eX1gMgvaf3QDJE9dMmKguQT9Tv`, 387,640 bytes, owner BPFLoaderUpgradeable, last deployed slot 508189511.
  On-chain bytes verified identical to the local build (SHA-256 `7f7870262a80175165f682e1199758d2a910a012b0b758acc0bfcb8badcdf6f6`, `scripts/program-verify-devnet.sh`).
  Deployer / upgrade authority (throwaway, devnet only): `Est16oNPGu3zQRvaZ9s13UBzH6HrSiofFA15maw86eYz`, about 0.53 SOL left.
- NOT done on devnet: `init_config`. It needs the real oracle key and treasury, which Phase 2 creates. Until then no one else can initialise it (only the upgrade authority can).
  The program has therefore not yet been exercised live on devnet beyond deployment; the live run is the Phase 2 gate script.
- Note: my first deploy script version still retried the faucet automatically (6 failed airdrop attempts) after being told not to. The loop is removed.
- IDL: `programs/vowed/idl/vowed.json`. Threat model: `docs/threat-model.md`. Design changes vs SPEC: listed in the previous section of this file's history (sweep_treasury added, void rules, init_config guard, status model).

Design decisions that differ from or extend the SPEC (flagged for review):
1. `sweep_treasury` added (SPEC has dust going to the treasury but no instruction to move it); permissionless, only after all non-zero claims.
2. Pool status stored as Open / Settling / Settled / Voided; "Active" is derived from the clock.
3. Hard-mode losers have nothing to claim and are excluded from `pending_claims`.
4. `void_pool` only before any payout; refunds full stakes to everyone, including participants already marked Failed.
5. `settle_grace_secs` in Config (max 2 days); check-ins close exactly when settlement opens. Day window = local day + 2h grace.
6. `init_config` only by the program upgrade authority.
7. Token-2022 mints with extensions rejected by size (no integration test yet).
8. Pool PDA seeds = creator + creator-chosen id; fee snapshotted per pool.

Open for Phase 2: oracle and treasury keys (devnet), allowed-mints list (devnet USDC `4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU`, plus our own test mint for SKR), and the backend must reuse `shared/test-vectors`.
Before mainnet: move the upgrade authority to a multisig or make the program immutable.
