# Verified facts (SPEC section 14)

Checked 2026-10-06 against official sources. Status: VERIFIED, PARTIAL (more to confirm), or OPEN.
Never hardcode these from memory; re-check before mainnet.

## 1. MWA Kotlin client: PARTIAL
- Artifact: `com.solanamobile:mobile-wallet-adapter-clientlib-ktx` (Maven Central).
  Source: https://docs.solanamobile.com/android-native/using_mobile_wallet_adapter
- Versions on Maven Central: ... 2.1.0, 2.1.1, **2.2.0**, 2.2.0-agp9-beta1, 2.2.0-nostr-beta1.
  Source: https://repo1.maven.org/maven2/com/solanamobile/mobile-wallet-adapter-clientlib-ktx/
  Decision: pin stable `2.2.0` (metadata "latest" points at the agp9 beta, which we avoid).
- API shape: `MobileWalletAdapter(connectionIdentity = ConnectionIdentity(identityUri, iconUri, identityName))`;
  `connect(ActivityResultSender)`, `signIn(sender, SignInWithSolana.Payload(domain, statement))`,
  `transact(sender) { authResult -> signAndSendTransactions(arrayOf(bytes)) }`.
  `signTransactions` is deprecated by MWA 2.0; use `signAndSendTransactions`.
- **Verified by building (2026-10-06):** MWA `clientlib-ktx` 2.2.0 forces `compileSdk 37`, and the current Compose BOM (2026.09.00)
  requires **AGP 9.1+** and compileSdk 37. Working stack: AGP 9.4.1, Gradle 9.8.0, Kotlin Compose plugin 2.4.10 (AGP 9 has built-in Kotlin,
  so no `kotlin-android` plugin), JDK 17, compileSdk/targetSdk 37, minSdk 26 (compiles and runs; MWA's own minSdk not separately confirmed).
  AGP 8.13 + compileSdk 36 fails the AAR metadata check.
- **Verified end to end on the emulator:** `MobileWalletAdapter(...).connect(ActivityResultSender)` returned the wallet's public key
  from the Mock MWA Wallet (screenshot `docs/phase0-mwa-connect.png`).

## 2. Mock MWA Wallet: VERIFIED
- Source: https://github.com/solana-mobile/mock-mwa-wallet
- No APK release is mentioned: clone and build (`gradlew assembleDebug`; its stack is AGP 8.13, Gradle 8.13, compileSdk 36; built fine from the CLI, no Android Studio needed).
  Without a screen lock the AUTHENTICATE button does nothing: set a PIN on the emulator (`adb shell locksettings set-pin 1234`), press AUTHENTICATE,
  enter the PIN (the prompt is a secure window, screenshots show black; use `adb shell input text`). Then the connect sheet appears in the wallet and works.
  A random keypair is generated unless `privateKey=` is set in the wallet's `local.properties`.
  Optional Ed25519 key in `local.properties`. Supports authorize, SIWS, sign transactions and messages,
  bottom-sheet approval, biometrics. Press **Authenticate** in the wallet first (valid 15 minutes).
  Testing only, never real funds.

## 3. SKR token: PARTIAL
- Mint: `SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3` (mainnet). Source: https://solanamobile.com/blog/skr-is-live
- Staking program id (informational, we do not integrate it): `SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ` (same source).
- Decimals **6**, token program **classic SPL Token** (`TokenkegQ...`), no freeze authority.
  Source: on-chain read via Solana mainnet RPC `getAccountInfo` on the mint, 2026-10-06.
- Prize rule (see item 12): staking does not qualify; we use SKR for rewards and perks.
- OPEN: no official SKR devnet mint exists that we know of. Plan: create our own devnet test mint labeled
  "SKR (test)" with 6 decimals. Mainnet SKR is never touched without approval.
- OPEN: "community SKR integration thread" from the announcement not located; check solanamobile.com/skr and Discord.

## 4. USDC mints: VERIFIED
- Mainnet `EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v`, devnet `4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU`, 6 decimals.
  Source: https://developers.circle.com/stablecoins/usdc-contract-addresses
  The devnet mint is live on devnet (supply read via RPC `getTokenSupply`).
- Devnet test tokens: Circle faucet lists Solana Devnet: https://faucet.circle.com/ (rate limited, manual).
  Our demo mode will use our own test mint (backend-minted) so the faucet is not a dependency.

## 5. Anchor and tooling: PARTIAL
- Docs show Anchor CLI **1.2.0**, Rust 1.85.0, Solana (Agave) CLI **4.1.2**, Node 23.9 / Yarn 1.22.1 examples.
  Default test template is **LiteSVM** (no validator, no TS needed). Source: https://www.anchor-lang.com/docs/installation
- **Windows users must use WSL.** Installed Ubuntu (WSL2) with `wsl --install -d Ubuntu --no-launch`; no restart was needed; we run as root
  (`wsl -u root`), no Ubuntu user created. Installed in WSL: Rust 1.99.0, Solana (Agave) CLI (installer gave 4.3.0, Anchor then switched it to 4.1.2), Anchor 1.2.0 via AVM from
  `https://github.com/otter-sec/anchor` (this is the old coral-xyz/anchor repo after a transfer; it is the repo the official install page uses).
  First `anchor` run downloads Solana platform-tools (slow). `anchor init --test-template rust` gives a Rust-client template whose test needs a validator: `anchor test --validator legacy` (the `solana-test-validator`; the default `surfpool` is deliberately not installed). Template pins Rust 1.89.0.
- **Anchor repo provenance (checked 2026-10-06):**
  - Official install page https://www.anchor-lang.com/docs/installation links to `github.com/otter-sec/anchor` (raw HTML contains the `otter-sec/anchor` repo,
    its `docs/content/docs/installation.mdx`, and issue 3392); the install command is `cargo install --git https://github.com/otter-sec/anchor avm`.
  - `https://github.com/coral-xyz/anchor` returns **HTTP 301 -> https://github.com/otter-sec/anchor**; `github.com/solana-foundation/anchor` also 301s to it.
    GitHub API: `api.github.com/repositories/325891672` is `otter-sec/anchor` (same id the old coral-xyz URL resolves to). The org `otter-sec` is "OtterSec",
    GitHub-verified, blog osec.io.
  - Announcement: Solana Compass's Breakpoint talk summary (https://solanacompass.com/learn/breakpoint-25/anchor-today-and-tomorrow) says OtterSec (CEO Robert Chen)
    and the Solana Foundation (Jacob Creech) took over Anchor maintenance and shipped Anchor 1.0. It does not literally say "repository transferred";
    the transfer evidence is the official docs plus the redirects. We could not find a formal GitHub-side announcement.
  - Result: confirmed enough to trust; no fallback to a coral-xyz source needed (that URL now serves the same repo anyway).
  - **Pinned version:** Anchor **1.2.0** = tag `v1.2.0` = commit `84a63f9f7112b23581816436fbcf3f6c515779f9` (tagged 2026-09-04). AVM is built from the same tag with `--locked`
    (`scripts/avm-pin.sh`, `scripts/wsl-setup.sh`). Latest release at check time was v1.2.1; we stay on 1.2.0 deliberately. The `anchor-lang` crate in the program is pinned to the same version.
- Token interface: use Anchor `anchor_spl::token_interface` so both Token and Token-2022 mints work; we restrict to an
  allowed-mints list in Config. Source: https://solana.com/docs/core/tokens (general), details re-checked in Phase 1.

## 6. Key attestation: PARTIAL
- Source: https://developer.android.com/privacy-and-security/security-key-attestation
- Chain must be verified on a server, never on-device. Check `attestationSecurityLevel` = TrustedEnvironment or StrongBox,
  check Google's revocation list `https://android.googleapis.com/attestation/status`, parse only the first attestation extension.
- Roots: old RSA root valid until 2026-02-01; **new ECDSA root effective 2026-02-01**; trust both during transition.
  Android 16+ uses Remote Key Provisioning only.
- Recommended verifier: https://github.com/android/keyattestation (Kotlin; we are in TypeScript, so use a maintained
  ASN.1 parse and pin Google roots; decide in Phase 2).
- OPEN: not yet tested on Seeker or a real phone. Goes in `docs/device-tests.md`; downgrade trust tier if absent.

## 7. Gemini free tier: PARTIAL
- Free-tier text models listed: Gemini 3.8 / 3.7 / 3.6 / 3.5 Flash, 3.5 Flash-Lite, 2.5 Pro, 2.5 Flash, 2.5 Flash-Lite.
  **Every free-tier model: "Content used to improve our products: Yes."** Source: https://ai.google.dev/gemini-api/docs/pricing
  Consequence: send only typed goal text (already SPEC rule), tell users in the privacy screen.
- Nigeria is on the supported-regions list. Source: https://ai.google.dev/gemini-api/docs/available-regions
  (The page does not separate free from paid regions; confirm with a real key.)
- Rate limits are per project and visible only in AI Studio (https://aistudio.google.com/rate-limit). OPEN until the user creates a key.
- Plan: model name lives in backend config; default to a Flash-Lite model, verify the exact id in AI Studio at Phase 5.

## 8. Pose model: PARTIAL
- ML Kit Pose Detection: still **beta**, "no SLA or deprecation policy"; 33 landmarks; ~30 fps on Pixel 4 (base model);
  needs face visible; one person only. Source: https://developers.google.com/ml-kit/vision/pose-detection
- MediaPipe Pose Landmarker: page redirected, not yet read. OPEN: read https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/android
  and decide in Phase 5. Leaning ML Kit for easy setup, behind a `PoseEstimator` interface so it can be swapped.

## 9. Kamino / yield: PARTIAL
- Kamino docs mention a Rust crate usable for on-chain CPI and a TypeScript SDK (`depositIxs`). Source: https://kamino.com/docs
- OPEN: program ids, devnet availability and risk parameters not confirmed. Per SPEC 9.5, default is the "simulated" label.
  Revisit in Phase 8.

## 10. dApp Store publishing: VERIFIED (requirements list)
- Need: release-signed APK, metadata (name, description, screenshots, icon), publisher wallet with ~0.2 SOL,
  publisher account with **KYC/KYB** at https://publish.solanamobile.com, storage provider (ArDrive recommended),
  review queue about 3-5 business days. Publisher wallet must never be lost.
  Source: https://docs.solanamobile.com/dapp-store/submit-new-app
- User action needed later: KYC and a funded publisher wallet. We do not do this.

## 11. Free tiers (RPC, DB, FCM): PARTIAL
- FCM: no-cost on Spark plan, no limits listed. Source: https://firebase.google.com/pricing
- OPEN: RPC provider (public devnet RPC works for dev; pick Helius/other free tier) and DB host (SQLite for dev).
  Confirm limits in Phase 2.

## 12. Hackathon portal: VERIFIED (supplied by the user from https://solanamobile.radiant.nexus, 2026-10-06; our fetcher could not read it)
- Submissions close **Oct 12, 2026, 12:59 PM GMT+1**. Judging opens Oct 13; results Nov 10; winners must publish on the dApp Store within 30 days.
- Demo video: **3 minutes**, must show the app on a **real device** (not only a simulator).
- Repo: may be private; install the Radiants Align GitHub app, share the repo, register through Align. Must be cloneable and runnable by someone else.
- Judges review GitHub commits up to the deadline.
- **SKR prize: SKR staking integrations do NOT qualify.** Design changed: USDC main token; SKR = weekly rewards for top streaks + SKR-paid perks (e.g. streak freezes); SKR deposits optional. ORE prize out of scope.
- Scoring: 25% each stickiness/PMF, UX, innovation, presentation/demo.
- OPEN: APK link vs upload field on the form (the SPEC assumes link).

## Phase 1 findings (program), 2026-10-06
- **Test tooling:** LiteSVM 0.17.0 + litesvm-token 0.17.0 (crates.io) run the program in-process, no validator, with clock control via `set_sysvar::<Clock>`.
  LiteSVM uses newer Solana types than Anchor 1.2.0 (transaction/message/instruction 4.x vs 3.x), so the test harness converts instructions field by field.
  LiteSVM deploys programs as upgradeable with `upgrade_authority: None`; the harness patches the programdata header to test the `init_config` guard.
  Needs host Rust >= 1.97 for the test crate (we pin 1.99.0 in `rust-toolchain.toml`); the SBF build uses Solana's own toolchain.
- **Anchor 1.2.0 API shapes used (compiled and tested):** `CpiContext::new(program_id: Pubkey, accounts)`, `anchor_spl::token_interface` (`transfer_checked`, `InterfaceAccount<Mint|TokenAccount>`, `Interface<TokenInterface>`),
  `token::mint/authority/token_program` constraints, `crate::program::Vowed` + `Account<ProgramData>` for the upgrade-authority check. `anchor build` emits the IDL (10 instructions, 11 events, 27 errors) copied to `programs/vowed/idl/vowed.json`.
- **Program binary:** 387,640 bytes. Deploy needs about 2 SOL on devnet (CLI said 1.97 SOL).
- **Devnet faucet:** both `solana airdrop` and raw `requestAirdrop` returned HTTP 429 ("reached your airdrop limit today or the faucet has run dry"). The web faucet (https://faucet.solana.com) is the documented alternative.
- **Program id (devnet and localnet):** `BMTXJRZ4QxzCg4UCHKo6qGGiGXKW26ARPAtPaXA8k7EL`. The program keypair and the throwaway deployer key are in WSL `~/.config/solana/` and never in the repo.

## Phase 2 findings (backend), 2026-10-06
- **Node and SQLite:** Node 24.15 ships `node:sqlite` (`DatabaseSync`), used for the local database: no native add-on, no hosted DB, no account. Supports `RETURNING`.
- **LiteSVM for Node** (`litesvm` 1.5.0) is built on `@solana/kit` 8.x and ships binaries for Linux and macOS only (no Windows). Backend VM tests therefore run in WSL with a Linux Node 24.15.0 (tarball SHA-256 checked against nodejs.org SHASUMS256.txt).
  A web3.js v1 transaction converts with `getTransactionDecoder().decode(tx.serialize())`.
- **Anchor TypeScript client not used.** `@anchor-lang/core` 1.2.0 failed to encode our enums and pulled `toml` (high advisory). We wrote an IDL-driven Borsh codec (`backend/src/program/borsh.ts`), exercised end to end against the real program binary.
  `@solana/spl-token` was also dropped (`bigint-buffer` high advisory); the few token helpers are hand-written.
- **Android key attestation, verified against real data:** Google's open-source verifier repo (https://github.com/android/keyattestation, Apache-2.0) publishes `roots.json` (two roots: the RSA root valid to 2042 and the newer "Key Attestation CA1" EC root, valid 2025-2035) and real device chains used as its own test data.
  `https://android.googleapis.com/attestation/root` and `/status` were unreachable from this network (TLS reset), so the roots come from that repo. Copy: `backend/src/devices/google-roots.json`; fixtures: `backend/test/fixtures/attestation/` (6 real chains, TEE and StrongBox, SDK 28-37).
  **Trust anchors must be compared by root public key, not by full-certificate fingerprint:** real chains carry a re-issued copy of the old root whose fingerprint differs from the published one but whose key is identical. All six chains verify this way.
  Rules implemented: chain signatures, root self-signature and root key in the trusted set, extension only in the leaf (a later occurrence could be forged), challenge equality, attested key equals registered key, revocation list when reachable (a fetch failure is reported as "not checked", never as revoked), security level and verified-boot state from RootOfTrust tag 704.
  The live revocation endpoint is therefore untested from here; a stubbed fetch covers the logic.
- **Seeker attestation** is still unverified (needs the physical device); tracked in `docs/device-tests.md`.
- **JSON vectors:** integers above 2^53 are not exact in JavaScript's `JSON.parse`; the shared vectors now carry all amounts as decimal strings (caught by the vector test).
- **Devnet faucet:** unreliable for scripted use (HTTP 429). The gate script never calls it; funding is moved from the deployer or provided by hand.
- **Devnet timing:** real 24-hour days plus `settle_grace_secs` mean a pool can be settled about 26 hours after its start, so the gate script is resumable in two stages. See `docs/progress.md` for the recommendation about demo pools.

## Phase 2 gate on devnet, 2026-10-06 / 07
- **Program upgraded in place, same program id** `BMTXJRZ4QxzCg4UCHKo6qGGiGXKW26ARPAtPaXA8k7EL` (nothing to update anywhere; `init_config` had never been run, so there was no state to migrate).
  Upgrade tx `5rrULz4HxpyEHTihFRjDs7KoLVmKeAcgap1QQyJXRiHZY7nGN8MSwLRHNCAcgrB1DRBejocPjvEPbapLTW9QHGfS`, slot 508239912, 398,280 bytes, SHA-256 `fe9470fbd3ee52fbb4e61b45b46d08ba01c86ae6cf2b5362acc1677dca1ff47d`, identical to the local build (`scripts/program-finish-upgrade.sh`).
  The first deploy (387,640 bytes, hash `7f787026...`) is superseded; the account was extended to 398,280 bytes (`solana program extend`).
- **`init_config` on devnet** (irreversible for this id): tx `4wr96hBinaD2TdDVwenY2aeTWiaMVTj22TPztuvWA8Z8aq9UXdbYX6hSE22JRjT3H2PmkiG5GpnfTC5KBrugX8d5`. Oracle `8SvB51yoFX4DPA3YS3FfbYL8ZgbwJ7aL9hFVG1cMfEuE`, treasury `HgQyATNJjsPYHVpCuTs9uG5AaYJrPbpSmuhSgxCLBvFj`, fee 0, max stake 100 tokens, settle grace 2 h.
  Allowed tokens: Circle devnet USDC `4zMMC9...` (normal pools only), **USDC (test)** `C6pXRRmoHsf7Mqa1ZrW3JfspyknhSR1cRqHMUqao63Hv`, **SKR (test)** `J6X9udvWis7jYpVHic3Kn5iTG3ac6gbBeFixnYKPUkHB` (both our own 6-decimal test mints, mint authority = the deployer). **Demo pools enabled for the two test mints only**, demo stake cap 20 tokens.
  This configuration cannot be changed except `set_paused`, `set_demo_enabled`, `update_oracle`; other changes need a new program id.
- **Gate result** (`backend/scripts/devnet-gate.ts`, 389 s from start to verified end): two demo pools with 60-second days, alice and bob join pool A, alice proves both days (oracle check-ins confirmed on chain), bob does not; pool B has bob alone and no proofs.
  Crank settled 3 participations and swept 1 pool; alice claimed 20 test USDC. Final balances matched the expectation exactly (alice 110, bob 85, treasury 5, both vaults 0).
  Pools `HHS35UAnybrAv5G5N99xu2TKBs2GXYpBgCk6BzFeFdC7` and `6pHiEZDbUQR9jvV5bTw8FpSAt268NZ9fmXdAKWitN5DS`; check-ins `atezVZHq...` (day 0) and `3t27vA6w...` (day 1); alice's claim `2rfxSBbE...`.
  **What this did not exercise:** the device key was a software P-256 key held by the script (no attestation), so proofs were capped at trust tier "low"; no phone, no Seeker; no normal 24-hour pool on devnet (those are covered by the VM tests only).
- **Uploading a 400 KB program to the public devnet RPC is fragile.** `https://api.devnet.solana.com` rate-limits per IP (HTTP 429 "Too many requests from your IP"). Observed:
  - default `solana program deploy` failed after a few minutes with "Max retries exceeded", leaving a 2 SOL upload buffer;
  - `--use-rpc --max-sign-attempts 30` crawled for 40 minutes (and our own read-only calls competed for the same rate limit);
  - resuming into the half-written buffer with the CLI **does not rewrite missing chunks**: the loader then rejects it ("Verifier error: unknown eBPF opcode 0x0").
  What worked: `backend/scripts/fill-buffer.ts` reads the buffer, writes only the 900-byte chunks that differ at 5 tx/s with backoff, repeats until byte-identical (404 chunks in about 2 minutes), then `solana program upgrade <buffer>` (`scripts/program-finish-upgrade.sh`).
  A buffer's rent (about 2 SOL) is refunded on upgrade or `solana program close --buffers`, so a failed attempt costs nothing but time. A paid RPC key would avoid this entirely; none was needed or created.
- **Tooling lessons recorded for next time:** PowerShell expands `$HOME` inside double-quoted strings (use script files for WSL commands); WSL does not reliably keep orphaned background jobs alive after the launching command returns (run long jobs as a tool background task with a local log); Windows Python could not write a file while a WSL job was rsyncing it (retry).


## Phase 3: Android app on the emulator against devnet (2026-10-07)

Verified by running it (emulator `vowed_api36`, Mock MWA Wallet with alice's throwaway devnet key, backend on devnet via `scripts/dev-backend.ps1`):

- MWA `clientlib-ktx` 2.2.0: `transact` + `authorize` gives the account; `signMessagesDetached` signs the Sign-In-With-Solana text built with `SignInWithSolana.Payload(...).prepareMessage`; `signAndSendTransactions` returns the transaction signature. The Mock wallet **submits to https://api.devnet.solana.com itself**.
- The Mock wallet shows a PIN prompt (BiometricPrompt, test PIN 1234 on the emulator) after "Connect"; screenshots of it are black (secure window). Its key is auth-bound: if the stored authorization is silently reused after the PIN window has lapsed, the wallet **crashes** with `UserNotAuthenticatedException` and the app only sees a generic failure. Workaround for tests: clear the wallet's data (`pm clear com.solana.mwallet`) or reconnect so the Connect + PIN prompt appears again. This is a Mock wallet quirk, not something to ship around.
- A transaction built by the backend has a recent blockhash; approving more than about a minute later makes devnet reject it (the wallet logs `payloads invalid for signing`). Emulator wallet start-up can take 35+ seconds the first time and the library's association then times out ("Failed establishing local association"); retry once the wallet is warm.
- The emulator cannot produce hardware key attestation: the backend answered 400 `attestation_failed` for the attested attempt, the app retried without a chain and registered with trust cap `low`, as designed.
- On-chain account roles in a real transaction: when the wallet is also the fee payer, its account is signer **and writable** in every instruction that lists it (found by the unit test against the real claim transaction; the checker now expects that).


## Devnet wallets, test tokens and Circle USDC (2026-10-07)

**Which wallets can be set to devnet** (official sources only; "not run by us" means we read the documentation but did not install that wallet):

| Wallet | Finding | Source |
|---|---|---|
| Phantom | Yes. Settings -> Developer Settings -> Testnet Mode; the Solana test networks offered are Devnet and Testnet. Not run by us. | https://docs.phantom.com/developer-powertools/testnet-mode |
| Solflare | Yes. The settings menu has a "Network" option to switch between Solana mainnet, devnet and testnet; the article gives no step-by-step detail. One third-party page claimed Solflare "does not properly support devnet"; Solflare's own help center says otherwise, and we have not run it, so treat devnet in Solflare as documented but untested. | https://help.solflare.com/en/articles/6328814-differences-between-mainnet-devnet-and-testnet-and-how-to-switch-between-on-solflare |
| Seed Vault Wallet (Seeker) | **Unverified.** The Solana Mobile pages we read (development setup, Seed Vault, wallet help "Connecting to popular Solana dApps") say nothing about devnet or testnet. To be tested on a Seeker. | https://docs.solanamobile.com/get-started/development-setup , https://docs.solanamobile.com/solana-mobile-stack/seed-vault , https://wallet-help.solanamobile.com/en/articles/11521030-connecting-to-popular-solana-dapps |
| Backpack | **Unverified.** No official statement found in our search. | (none) |
| Mock MWA Wallet | Works on devnet: it submits transactions to https://api.devnet.solana.com (seen in its logs and by on-chain results in Phases 3 and 4). Development only; its key is built in from `local.properties`. | run by us |

**MWA and chains.** `authorize` takes an optional `chain` (`solana:mainnet` default, `solana:testnet`, `solana:devnet`). A wallet that does not support the requested chain returns `ERROR_CHAIN_NOT_SUPPORTED`; if a chain was not previously authorized for an auth token the wallet must ask the user again. Our app uses the library default, devnet. Source: https://solana-mobile.github.io/mobile-wallet-adapter/spec/spec.html

**Circle devnet USDC.** Address `4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU` (Circle's docs list it as the Solana Devnet USDC token; https://developers.circle.com/stablecoins/usdc-contract-addresses). Read from devnet RPC on 2026-10-07: owned by the classic SPL Token program (`TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA`), 6 decimals, initialised, **has a freeze authority** (`CJtyoKSLrktozQzjERTiK3btQtiTK3nN4QrqGHLidyCT`: Circle can freeze token accounts, including a pool vault; the same is true of mainnet USDC and is worth disclosing), mint authority held by Circle. Circle's public testnet faucet (https://faucet.circle.com/) gives 20 USDC per address per chain every 2 hours without an account; we did not call it.
In the app: a normal (non-demo) pool created on the emulator used this mint (pool `55DPYqqvwyg2JAoPTCpAET7zAC2vgeqLiUQMF61EcY2`, Open, Soft, 24-hour days, mint equal to the address above), confirmed in the backend mirror. The join step was refused by the backend with `insufficient_funds` because no test wallet holds any. Join, check-in and claim with Circle USDC are therefore **not verified**.

**Test-token faucet.** Implemented in `backend/src/faucet/service.ts`. Checked by running it on devnet through the app: alice's balance rose from 109.9 to 129.9 tUSDC and from 0 to 20 tSKR with one claim; bob (who had no SKR token account) got 20 tUSDC and 20 tSKR, the account being created by the faucet's fee wallet in the same transaction; a second claim from bob was refused with `faucet_cooldown` (HTTP 429, `nextClaimAt` 24 hours after the first); a request body asking for 999,999,999 was ignored. The mint authority of both test mints is the throwaway deployer key (also the program upgrade authority), so the dev backend process holds it in its environment. Before any wider tester rollout, move the mint authority to a dedicated faucet key (a one-time `SetAuthority` on each mint) so that the program upgrade authority is not in a long-running server.


## Phase 5: Gemini goal parser and camera pose (2026-10-07)

**Gemini (read from official pages today).**
- Stable model ids: `gemini-3.8-flash`, `gemini-3.7-flash`, `gemini-3.6-flash`, `gemini-3.5-flash`, `gemini-3.5-flash-lite`, `gemini-3.1-flash-lite`; `gemini-2.0-flash` and `-flash-lite` are shut down; the page recommends 3.5 Flash-Lite or 3.8 Flash for new projects. We default to `gemini-3.5-flash-lite` (config `GEMINI_MODEL`). https://ai.google.dev/gemini-api/docs/models
- Free-tier request limits are **not published** on the docs page; they are shown per project in Google AI Studio (page updated 2026-09-02). We therefore cap our own use (12 model calls per wallet per hour, 300 per UTC day, cache of answers, backoff after a 429) and never assume a number. https://ai.google.dev/gemini-api/docs/rate-limits
- REST endpoint `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` is documented as current and not deprecated; its `generationConfig` accepts `responseMimeType`, `responseSchema`, `temperature`, `maxOutputTokens`. The structured-output page now shows the newer "Interactions" API and a `responseJsonSchema` field; **we use generateContent with `responseJsonSchema` and, if the endpoint answers 400 about the schema, retry once without it** (every reply is validated by our own schema either way). This exact request shape could **not** be tested live (see below). https://ai.google.dev/api/generate-content , https://ai.google.dev/gemini-api/docs/structured-output
- The API key goes in the `x-goog-api-key` header (we never put it in a URL, so it cannot reach a log line); Google says not to hard-code keys in mobile apps and to use a backend proxy, which is what we do. https://ai.google.dev/gemini-api/docs/api-key
- Terms for the free (unpaid) tier: Google uses submitted content and responses to provide, improve and develop its products; human reviewers may read, annotate and process input and output; users must not submit sensitive, confidential or personal information; in the EEA, Switzerland and the UK the paid-tier data terms apply even to unpaid quota. This is why the app sends **only the typed goal text**, tells the user so before sending, and has an off switch. https://ai.google.dev/gemini-api/terms

**Live Gemini check: blocked by this network, not run.** From the development PC the TLS handshake to `generativelanguage.googleapis.com` is dropped for every address of the host (curl exit 35, Node `ECONNRESET`, repeated over about an hour) while `ai.google.dev`, `oauth2.googleapis.com` and `aiplatform.googleapis.com` connect normally. That pattern looks like filtering of that hostname on this network (the Phase 2 note that Google's revocation endpoint was unreachable is probably the same cause). Sending the key to `aiplatform.googleapis.com` (reachable) returned 403 "Agent Platform API has not been used in project ... or it is disabled": the key is recognised there, but it is not a Vertex key, so that route is not an option. Nothing was done to get around the block. To run the live check: `cd backend; npm run gate:goals` from a network that reaches the host (the script reads `GEMINI_API_KEY` or `backend/.devnet/gemini-key.txt`, never prints the key, and exits with a clear message when the host is unreachable).

**Pose detection.** ML Kit Pose Detection `com.google.mlkit:pose-detection:18.0.0-beta5` (latest; still labelled beta, last published August 2024): model statically linked (about 10 MB), no download, minSdk 23, `STREAM_MODE` tracks one person; 33 landmarks each with an in-frame likelihood. Input should be at least 480x360. https://developers.google.com/ml-kit/vision/pose-detection/android . CameraX 1.6.2 is the latest stable (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`; Google Maven metadata). This settles the open "MediaPipe vs ML Kit" question: ML Kit, because it needs no model download and no extra asset.
Counting method (ours, unit-tested on a synthetic skeleton, not yet on a real person): angle at knee (squat) or elbow (push-up) from 2D landmarks, exponential smoothing, hysteresis (down below 105 degrees / 95 degrees, up above 160 / 155), minimum rep time 0.6 s and bottom hold 0.12 s, landmarks below 0.5 likelihood ignored, the better-seen side of the body used. Liveness: at a random time 2 to 6 s after counting starts, a random side of the screen is named and a wrist must rise clearly above the shoulders within 6 s; two misses end the session. Limits of this method: 2D angles are distorted if the camera looks down or up at the person, loose clothes and poor light reduce landmark confidence, and a looped video of someone doing squats cannot pass the hand prompt but a second person off camera could pose for it (the server also caps reps per second and requires a minimum duration).
## Phase 7 (2026-10-07)
- **Jetpack Glance:** `androidx.glance:glance-appwidget:1.2.0` exists on Google Maven (https://dl.google.com/android/maven2/androidx/glance/glance-appwidget/maven-metadata.xml lists 1.0.0, 1.1.0, 1.1.1 and 1.2.0; the newest entry is the 1.3.0-alpha02 pre-release, which we do not use). The Intent form `actionStartActivity(Intent)` from `androidx.glance.appwidget.action` is used; the build compiles with it.
- **Android Keystore AES-GCM:** key generated with `KeyGenParameterSpec` (AES 256, GCM, no padding) under alias `vowed-letters-v1`; a fresh 12-byte IV per encryption; the stored form is IV followed by ciphertext and tag (https://developer.android.com/privacy-and-security/keystore). Round trip, tampering and wrong-key behaviour are tested with a JVM AES key standing in for the Keystore key (the Keystore itself needs a device).
- **Mint authority move:** SPL Token `SetAuthority` (instruction 6, authority type 0 = MintTokens) signed by the current authority; both test mints now show the faucet key as authority (checked by reading the mint accounts).

## Phase 8 (2026-10-07): yield, SKR, plug-ins
- **Real yield: not built, shown as SIMULATED.** Sources checked: Solana Mobile's USDC Earn Vault in Seed Vault Wallet lends USDC through Kamino's SOL/BTC Market USDC pool, variable yield, no lockup, APY shown net of a 0.25% platform fee (https://solanamobile.com/blog/introducing-the-usdc-earn-vault-in-seed-vault-wallet, disclosures at https://legal.solanamobile.com/earn-usdc-vault-disclosures). Kamino Lending is an Anchor program with a devnet deployment (program id `KLend2g3cP87fffoy8q1mQqGKjrxjC8boSyAYavgmjD` per its docs, https://www.mintlify.com/kamino-finance/klend/operations/deployment, https://github.com/Kamino-Finance/klend). Why we did not integrate: routing the pool vault into a lending market means a new cross-program call inside our unaudited Vowed program (a program change and a new loss risk for stakes), our devnet stakes use our own test tokens that no lending market accepts, and Circle's devnet USDC has no verified Kamino reserve. So the app shows an illustrative number labelled SIMULATED (assumed 4% a year) and credits nobody. Mainnet integration would need the program change, an audit and lending-risk disclosure.
- **SKR design (no program change):** weekly rewards are plain SPL TransferChecked payments from a dedicated rewards wallet; the streak-freeze perk is a plain SPL TransferChecked payment from the person to the same wallet, checked on the phone before signing and confirmed on chain by the server (it reads the parsed transaction through the RPC node's `getParsedTransaction`). On devnet SKR is our own test mint (no official SKR devnet mint is known). Mainnet SKR is never touched.
- **Devnet run (real transactions, 2026-10-07, `backend/scripts/gate-skr.ts`):** reward payout of 10 test SKR to the winner of a seeded week (the winner's balance went 40 to 50; a second run paid nobody), then a streak freeze bought with 1 test SKR (balance 50 to 49), recorded after the server read the transfer from the chain, and a replay of the same payment refused. The challenge rows that decided eligibility were seeded in a throwaway in-memory database, because a real week of check-ins takes a week.

## UI polish (2026-10-08): brand assets
- **Solana logomark:** downloaded unchanged from the Solana Foundation brand page https://solana.com/branding (file https://solana.com/src/img/branding/solanaLogoMark.svg, copy in `design/brand/`); converted 1:1 into the Android vector `res/drawable/solana_logomark.xml` (same path and gradient stops, not recolored or rotated). Terms read on that page: the marks belong to the Solana Foundation; do not alter, recolor or rotate them, do not combine them with other names or logos without permission, do not imply sponsorship or affiliation (contact operations@solana.foundation). The app uses it only as an unaltered decorative mark on a plain white disc in the Home banner; **the owner should confirm this use is acceptable under those terms.**
- **SKR token logo: NOT included.** The only official source found is the Solana Mobile press kit linked from https://solanamobile.com/skr (a Google Drive folder, https://drive.google.com/drive/folders/1nBAP8JjbqvqDgIhzdESU_GjmuG3QWQDZ), which cannot be downloaded by a script, and the page itself carries no labeled SKR artwork. Nothing was redrawn or guessed. Waiting for the owner to supply the official file.
- **Owner's logo:** `design/logo-3d.png` (supplied by the owner); `design/logo-3d-transparent.png` is the cutout made by `scripts/gen-logo-3d.py`.
- **Nunito font:** SIL Open Font License 1.1, https://github.com/google/fonts/tree/main/ofl/nunito (license text in `docs/licenses/Nunito-OFL.txt`).

## SKR token icon (2026-10-08, supplied by the owner)
- `design/brand/skr-logo.png` (320x320, white S on a black circle with a gray ring) was **supplied by the project owner** and is used as the SKR icon in the token pill, the banner, balances and the SKR rewards and freeze screens (`res/drawable-nodpi/skr_logo.png`, 256 px).
- **Comparison with an official-looking source:** Jupiter's token list gives the SKR icon as https://r2.solanamobiledappstore.com/skr/seeker.png (a Solana Mobile dApp store host; 500x500). It shows the same S glyph on a black circle, without the gray ring and with the S a little larger. No mismatch in the mark itself; the owner's file is that mark with a ring (a rendering variant). Nothing was switched. I could not confirm which of the two is the preferred brand file: the press kit linked from https://solanamobile.com/skr (Google Drive) cannot be fetched by a script.

## Rewards diagnosis (2026-10-08)
- Hosted `https://vowed-backend.onrender.com`: `/v1/rewards` enabled; rewards wallet public address reported by the server equals `7rgAagae2f4vepEFBcsSjwUEzEmx6ddMTUKYFQDFWgQ9`; devnet RPC (api.devnet.solana.com) shows 0.019995 SOL and 91 tSKR in its vault. Reward payouts need at least 0.005 SOL for fees.


## Real wallets and devnet (2026-10-08)

Read from official pages today; **no real wallet other than the Mock MWA Wallet was run by us.**

- **MWA chain selection.** The dApp chooses the chain in `authorize`: `chain` is optional and "defaults to `solana:mainnet`"; values include `solana:devnet`, `solana:testnet`, `solana:mainnet`. A wallet that does not support the requested chain returns `ERROR_CHAIN_NOT_SUPPORTED` (-7). The spec defines no fallback, does not say which chains a wallet must support besides mainnet, and `get_capabilities` does not report supported chains. Source: https://solana-mobile.github.io/mobile-wallet-adapter/spec/spec.html . Our app uses the library default for the Solana client, which is devnet in this build (see Phase 3 note); the app therefore treats an unsupported-chain failure as "wrong network" (`WalletErrors.isWrongNetwork`), and a failed fee payment as "wrong network or no devnet SOL".
- **Phantom.** Documents a Testnet Mode: Settings, Developer Settings, Testnet Mode; Solana Devnet and Testnet are listed, and when enabled "Phantom will display testnet balances and allow transactions on supported test networks". The page describes the Phantom Connect SDKs and does not say how Testnet Mode interacts with Mobile Wallet Adapter on Android, so MWA plus Phantom on devnet is **untested**. A community guide (docs.wingbits.com, undated) says some Phantom versions did not show devnet tokens properly. Source: https://docs.phantom.com/developer-powertools/testnet-mode
- **Solflare.** Its help center says the wallet can switch between mainnet, devnet and testnet in its network setting (no step-by-step detail). Untested by us. Source: https://help.solflare.com/en/articles/6328814-differences-between-mainnet-devnet-and-testnet-and-how-to-switch-between-on-solflare
- **Seed Vault Wallet (Seeker).** A search of Solana Mobile's docs found nothing about a user-facing network switch; the documentation treats the dApp as choosing the cluster (for example the CLI playground's `--cluster`). Whether the built-in wallet serves devnet over MWA is **unverified** and must be tested on a Seeker (checklist R1 to R6). Sources: https://docs.solanamobile.com/solana-mobile-stack/seed-vault , https://docs.solanamobile.com/solana-mobile-stack/seeker
- **Mock wallet quirks handled in the app** (from Phase 3): PIN window lapse crashes the wallet with `UserNotAuthenticatedException` (shown as "open your wallet and unlock it"), a cold start can need 35+ seconds and times out the association (retried once, then a clear message), transactions expire after about a minute (own message).

## Session and Keystore (2026-10-08)
- The backend session token is stored only as AES-256-GCM ciphertext under an Android Keystore key (alias `vowed-session-v1`); the plain token is never written to storage. The Keystore refuses a caller-chosen IV ("Caller-provided IV not permitted"): found on the emulator on the first real sign-in of this change, which also meant the Letters feature (same cipher) would have failed on a real device; both now let the cipher pick the IV. The unit tests use a software key, which cannot catch this class of bug: **Keystore behaviour is only verified by running on the emulator or a phone.**
- Server: tokens last 6 hours; `POST /v1/auth/refresh` swaps a still-valid token for a new one up to 7 days after the wallet signed in.


## Real-phone bugs, round 1 (2026-10-09): "Unable to resolve host" and the wallet network

### Bug 1, "Unable to resolve host ... No address associated with hostname" after the wallet returns
Checked on the built release APK (`app-release.apk`), not on the source:
- **Permissions.** `aapt2 dump permissions` on the release APK lists `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE` (identical to the debug APK). The merged release manifest has no network security config and no cleartext flag; the debug manifest adds a network security config only for the `10.0.2.2` emulator address. Not the cause.
- **Base URL.** `BuildConfig.BACKEND_URL` in the release build is exactly `https://vowed-backend.onrender.com` (35 bytes including the dex length byte, no space or hidden character; read back from `classes3.dex`). Not the cause.
- **R8 and DNS classes.** The release build has `isMinifyEnabled = false`, so nothing is stripped or renamed. OkHttp 5.5.0 with its default system resolver, no custom `Dns`. Not the cause.
- **Where the failing call runs.** The sign-in asked the server for its nonce *inside* the Mobile Wallet Adapter session (`WalletManager.connectAndSignIn`, `fetchNonce(address)` inside `adapter.transact`), that is, while the wallet app is in front and Vowed is in the background. Every retry runs the same call in the same place, which matches "repeats on every retry".
- **Why that fails on a phone.** Android gives an app whose network access is blocked at that moment (background app under Data Saver or a battery restriction, such as Samsung's sleeping or deep-sleeping apps) `UnknownHostException: Unable to resolve host "...": No address associated with hostname` (`EAI_NODATA`). This was observed on the emulator: with Data Saver on, a background Google process logged exactly that text for `phonedeviceverification-pa.googleapis.com` (a background process; with Data Saver on, `dumpsys netpolicy` lists `blocked=APP_BACKGROUND|DATA_SAVER` as the rules that apply to an app that is not in the foreground).
- **Reproduction.** **Not reproduced** with the release APK and the Mock MWA Wallet: while the Mock wallet is in front, `dumpsys netpolicy` still shows Vowed as `procState=TOP, blocked_state ... effective=NONE`, because the Mock wallet's activity runs inside Vowed's task. Phantom opens in its own task, which makes Vowed a background app. So the root cause below is the best-supported explanation, **not confirmed on a real Phantom and Samsung phone**.
- **Root cause (best supported).** A network request was made while Vowed was in the background behind the wallet, and Samsung's background network limits block that.
- **Fix.** The nonce is now fetched *before* the wallet opens (`POST /v1/auth/nonce` with no wallet returns an "open" nonce valid 10 minutes, single use, still bound to the wallet by the signature); nothing needs the network while the wallet is in front. Older servers fall back to the old order. Also added: two quiet retries (1 s apart, DNS and connect failures only), a plain error message with the raw text under "Details", and "Check connection".

### Bug 2, the wallet refuses because Vowed asks for devnet
- **MWA `authorize` takes a chain.** The spec: `chain` (alias `cluster`) is optional, "defaults to `solana:mainnet`"; values `solana:mainnet`, `solana:testnet`, `solana:devnet` (and the old names `mainnet-beta`, `testnet`, `devnet`); a wallet that does not support it returns `ERROR_CHAIN_NOT_SUPPORTED` (-7). Source: https://solana-mobile.github.io/mobile-wallet-adapter/spec/spec.html . Vowed's client library (`mobile-wallet-adapter-clientlib-ktx` 2.2.0, read from the jars) defaults to `Solana.Devnet` and names the constant `ProtocolContract.ERROR_CLUSTER_NOT_SUPPORTED = -7` (other codes: -1 `ERROR_AUTHORIZATION_FAILED`, -2 invalid payloads, -3 not signed, -4 not submitted). A failure arrives as `TransactionResult.Failure(message, e)` where `e` can be a `JsonRpc20Client.JsonRpc20RemoteException` with an int `code`; the app reads that code (looking through wrapped causes).
- **What Phantom actually returns on a mismatch: not captured.** No official page says what Phantom answers when a dApp asks for devnet while it is on mainnet, and we have no Phantom on the emulator. So the app treats code -7, a "chain/cluster not supported" or network-and-testnet wording as a certain mismatch, and an authorization failure (-1) that is not a decline as a *possible* mismatch (softer wording, same setup screen). This must be confirmed on a real Phantom (checklist R1).
- **Can an app switch the wallet's network or open its network setting?** No supported way was found. The MWA spec has no call that changes a wallet's network (only the dApp's request for a chain), and Phantom's documentation describes Testnet Mode only as a manual setting (Settings, Developer Settings, Testnet Mode) and says nothing about MWA or deep links to it. We therefore do not try: no accessibility tricks, UI automation or hacks. Source: https://docs.phantom.com/developer-powertools/testnet-mode
- **Phantom's menu names:** Settings, then Developer Settings, then Testnet Mode; the Solana networks listed are Devnet and Testnet (same page). The page does not say whether the steps differ by platform.
- **Phantom's Android package** is `app.phantom` (Phantom's own announcements link https://play.google.com/store/apps/details?id=app.phantom , see https://phantom.com/learn/blog/android-beta-launch ). The app checks whether it is installed and otherwise opens that Play page.
- **Other wallets (docs only).** Solana Mobile's MWA page lists Solflare, Phantom and Seed Vault Wallet ("Coming soon!") as MWA-compatible Android wallets and says nothing about devnet or testnet (https://docs.solanamobile.com/mobile-wallet-adapter/mobile-apps). Solflare's help center says there is a Network option in its settings (Settings, Network, Devnet) and gives no mobile-specific steps or word on dApp requests (https://help.solflare.com/en/articles/6328814-differences-between-mainnet-devnet-and-testnet-and-how-to-switch-between-on-solflare). Nothing in the documentation says Solflare is easier for beginners; we say only what it documents.


## Real-phone bug, round 3 (2026-10-10): "Cannot send in CLOSED", Phantom and Solflare

Evidence from screen recordings (release 0.1.0-rc1.1, a real phone, an experienced wallet user): Phantom shows "Connect, vowed.app" with "This app's identity could not be verified", the Connect button greyed for about 3 s, the sheet disappears, Phantom stays on its home screen and never returns to Vowed, and about a minute later Vowed shows "Cannot send in CLOSED". Solflare opens on its portfolio with no approval prompt and never returns.

### 1. Where "Cannot send in CLOSED" comes from (read from the library, not guessed)
- The text is thrown by `MobileWalletAdapterSessionCommon.send(byte[])` in `mobile-wallet-adapter-common` 2.2.0: `IOException("Cannot send in " + state)` when the session is not in `ENCRYPTED_SESSION`. So a request was sent after the session was closed.
- Vowed's sign-in used ONE session with TWO requests: `transact` (which sends `authorize`) and then, inside the block, `sign_messages`. The official client source closes the session in a `finally` after the block, and, when the wallet's activity returns to the app, a coroutine waits 5 s and calls `scenario.close()` (source: https://github.com/solana-mobile/mobile-wallet-adapter , `android/clientlib-ktx/.../MobileWalletAdapter.kt`, read on 2026-10-10). The MWA spec says a wallet does not close the session after `authorize` (https://solana-mobile.github.io/mobile-wallet-adapter/spec/spec.html), but Phantom's behaviour in the recording (sheet closes after Connect, wallet stays on its own screen) is what a wallet does when it treats the approval as finished: its activity returns, the library closes the session 5 s later, and the second request has nowhere to go.
- **Root cause: best-supported, not confirmed.** The first request (authorize) succeeded; the second request (sign_messages) was sent on a session the wallet had already ended. It is confirmed that the exception means "send on a closed session" (library source and bytecode) and that Vowed sends two requests in one session; it is not confirmed on a real Phantom *why* Phantom ends the session (we have no Phantom on the emulator).
- **Library finding that matters:** in clientlib-ktx 2.2.0 the convenience `signIn(sender, payload)` does NOT put Sign In With Solana inside `authorize` for a protocol-V2 wallet: with a saved token it calls `reauthorize` (no payload), otherwise the 4-argument `authorize(identityUri, iconUri, name, rpcCluster)`, and when no `signInResult` comes back it falls back to `signMessagesDetached` in the same session (bytecode of `MobileWalletAdapter$transact$2`; only the V1 branch passes the payload to `authorize`). So the library's own SIWS helper would make the same two requests in one session. Therefore Vowed does not use it.
- **What Vowed does instead:** after a "session closed" failure it makes ONE automatic retry in two fresh sessions: session 1 `authorize` only, then Vowed waits until it is on screen again (asking the person to switch back if the wallet stayed in front), then session 2 `sign_messages` only. It remembers that choice for next time. This is the pattern the official dApps use (a fresh session for each wallet interaction).

### 2. Comparison with the official example and docs (docs.solanamobile.com "Using Mobile Wallet Adapter")
| Point | Official | Vowed before | Vowed now |
|---|---|---|---|
| Dependency | `com.solanamobile:mobile-wallet-adapter-clientlib-ktx` | 2.2.0 | 2.2.0 (unchanged) |
| Identity | `ConnectionIdentity(identityUri, iconUri relative to the uri, identityName)` | `https://vowed.app` (not ours), `favicon.ico` (nothing served) | the backend host `https://vowed-backend.onrender.com`, `icon.png` (served), name "Vowed" |
| ActivityResultSender | `ActivityResultSender(this)` created from the activity before it starts | created in `onCreate` | unchanged |
| Chain | library default, `Solana.Devnet`; V1 sends `solana:devnet` (`blockchain.fullName`), V2+ sends the legacy cluster `devnet` (`blockchain.rpcCluster`) | same (library) | same; both forms are accepted by the spec (`cluster` is the legacy alias of `chain`) |
| Features requested | none | none | none |
| Sign-in payload | `signIn(sender, Payload(domain, statement))` or `transact(sender, payload)` | own SIWS message signed with `signMessagesDetached` in the same session | same message, but in a separate fresh session when the wallet ends the first one |
| Order of requests | authorize (or reauthorize), then the block | authorize, then sign_messages in one session | one session per wallet interaction after a closed session |
| Auth token | stored in memory, reused (reauthorize) | reused even when it belonged to another wallet | cleared at the start of every "Connect wallet" so a new connection always asks the wallet |
| Timeouts | request timeout parameter (default 90 s); association waits 20 s to launch and 10 s to connect (fixed constants) | default 90 s | 120 s for requests; the fixed 20 s and 10 s are not configurable |
| Launch mode | not covered by the docs page | standard | standard (nothing recreates the activity, see 3) |
Source for the official column: https://docs.solanamobile.com/android-native/using_mobile_wallet_adapter and the client source above. The docs page does not cover launch mode, manifest entries or where to create the sender.

### 3. Lifecycle
`MainActivity` has no `launchMode` and no configuration-change handling that would recreate it; the view model and its coroutine scope survive while the wallet is in front, and the library delivers the wallet's result to the activity result launcher. The diagnostics trace now records onCreate, onStart, onResume, onPause, onStop, onSaveInstanceState and onDestroy (with "restored from saved state" when the app was recreated) and the process start time, so a restart during the wallet session is visible. Not yet observed on a real phone.

### 4. Release build
`isMinifyEnabled = false` in the release build type: no R8 and no keep rules are needed, and nothing in the Mobile Wallet Adapter library is stripped or renamed. The signed release APK was run through the full connect flow on the emulator with the Mock MWA Wallet (see docs/progress.md).

### 5. App identity and Phantom's "could not be verified" warning
- **What the spec says** (https://solana-mobile.github.io/mobile-wallet-adapter/spec/spec.html): `identity.uri` is optional but strongly recommended; for an Android native app the wallet should check a Digital Asset Links file at `/.well-known/assetlinks.json` on that URI's host and confirm the calling package is signed by a certificate listed in an `android_app` statement; if there is no URI or the check fails the spec recommends answering `ERROR_AUTHORIZATION_FAILED`. The icon is a path relative to the URI. The spec does not name the statement's relation string or show the JSON.
- **What we did:** the identity URI is now the backend host, which serves `/.well-known/assetlinks.json` (package `app.vowed`, release certificate SHA-256 `28:11:C2:29:2B:DD:...:E7:B8`, relation `delegate_permission/common.handle_all_urls`, the usual Digital Asset Links form) and `/icon.png`. The sign-in message domain follows the same host (Render sets `RENDER_EXTERNAL_HOSTNAME`), so the identity and the sign-in domain agree; the older domain is still accepted by the server.
- **Not confirmed:** whether Phantom or Solflare actually use that file, and so whether the warning goes away. Phantom's documentation does not describe its identity check for Mobile Wallet Adapter. The "One quick step" screen therefore also says: "Phantom may say Vowed's identity could not be verified. That is expected for a hackathon test app on a practice network." If Phantom keeps showing the warning after this change, that is Phantom's own check and not something the app can fix from its side.
- **Solflare, no prompt:** unexplained. One concrete bug found while comparing: the saved wallet authorization (from the first wallet) was reused for the next wallet, which makes the library send `reauthorize` with a token the second wallet does not know instead of `authorize`; that is fixed (the token is cleared on every new connect). Whether that is what Solflare did is not confirmed.


## Round 4 (2026-10-10): transaction approval, the camera screen, the stake token

### Transaction path (Problem A): what is verified and what is not
- **Path traced** (code and library bytecode): the stake and claim transactions are built by the backend as unsigned legacy transactions (fee payer = the person), checked on the phone (`TxChecker`), then signed with MWA `sign_and_send_transactions` in a FRESH wallet session of their own (`WalletManager.signAndSend`). In that session the library first reauthorizes with the saved authorization token (protocol V2) or authorizes again (V1), then sends the one request. This is the same pattern as the official dApps (a fresh session per wallet interaction). Sources: https://docs.solanamobile.com/android-native/using_mobile_wallet_adapter and the client source https://github.com/solana-mobile/mobile-wallet-adapter (`MobileWalletAdapter.kt`).
- **Not found, so not "fixed":** a defect in that path that would make Phantom show nothing. **The root cause of "Phantom shows no approval prompt for a stake" is NOT confirmed.** What the app now does differently, all of which removes a way for the problem to hide: (1) the backend runs every transaction against the cluster (`simulateTransaction`, no signatures needed) before it hands it out and refuses a failing one with a plain reason; (2) a pre-flight check of devnet SOL and token balances happens before the wallet is opened; (3) after the wallet answers, the app waits until Vowed is on screen before any network call (Phantom leaves its own screen up); (4) one retry in a fresh session after a "session closed" error and a bounded wait (160 s) ending in "your wallet did not answer"; (5) a diagnostics timeline for this path (kind, size, simulation, method, session events, lifecycle, close reason). The next real-phone run will show where it stops. Candidates, in order of how much the evidence supports them: Phantom treats the request like the sign-in (a second screen it cannot open from the background after returning to its home), Phantom finds the transaction not simulatable or flagged and shows nothing, the wallet is on another network than devnet, the wallet lacks SOL (ruled out by the tester's gift).
- **What the wallet needs for a prompt** (all verified by the new simulation tests against the real program in LiteSVM, not against Phantom): fee payer = the signer, a recent blockhash, correct accounts, and a successful run. Every create, join and claim transaction built in the end-to-end suite now simulates successfully with `skipped: false`.

### SOL needed per step (measured on devnet, 2026-10-10, lamports; 1 SOL = 1,000,000,000)
| Item | Value | Source |
|---|---|---|
| Fee per signature | 5,000 | Solana base fee |
| Participation account (125 bytes) rent | 1,285,240 | `getMinimumBalanceForRentExemption(125)`; size read from real accounts of the program |
| Pool account (260 bytes) rent | 1,971,040 | same |
| Vault token account (165 bytes) rent | 1,488,440 | same |
| Rent-exempt minimum of the wallet itself (0 bytes) | 650,240 | a wallet that keeps a balance must keep this much or the transaction fails with InsufficientFundsForRent (seen in a test) |
| **Join** | fee 5,000 + participation rent 1,285,240 + keep 650,240 = **1,940,480** (0.0019 SOL) | |
| **Create a pool** | fee 5,000 + pool 1,971,040 + vault 1,488,440 + keep 650,240 = **4,114,720** (0.0041 SOL) | |
| **Create and then join it yourself** (two transactions) | 10,000 + 1,971,040 + 1,488,440 + 1,285,240 + 650,240 = **5,404,960** (0.0054 SOL) | |
| **Claim**, wallet token account exists | 5,000 + 650,240 = 655,240; if the token account is missing add 1,488,440 = **2,143,680** worst case | |
| Streak freeze payment | same as a claim (fee, plus the payee token account if missing) | |
- **Against the 0.01 SOL gift (10,000,000):** it covers one create-and-join (5.4M, about 4.6M left) plus three more joins (about 1.29M each), or about seven joins. A tester who creates and joins TWO challenges needs 2 x 4,754,720 + 650,240 = 10,159,680 and **falls about 160,000 lamports short**. Recommendation (NOT applied, needs approval): 0.02 SOL (20,000,000) per wallet, which covers about three create-and-join cycles; with the unchanged total cap of 0.5 SOL that is 25 testers instead of 50. The app now says when SOL is short before opening the wallet.

### Camera (Problem B): sources
- ML Kit Pose Detection (Android): input at least 480x360, the subject at least 256x256 pixels; `STREAM_MODE` for video; `inFrameLikelihood` is called "InFrameConfidence" on the page and there is no recommended threshold (we use 0.5 and a 3 percent margin from the picture edge); landmarks of a person partly outside the frame get coordinates outside the frame with low confidence; use CameraX `imageInfo.rotationDegrees` for rotation; keep `STRATEGY_KEEP_ONLY_LATEST`. The page says nothing about mirroring. Source: https://developers.google.com/ml-kit/vision/pose-detection/android
- CameraX `PreviewView`: default `FILL_CENTER`, which scales the frame by `max(dst.width/src.width, dst.height/src.height)` and cuts the middle window (the overlay uses exactly this: `CameraGeometry.toView`). The page does not say whether the front preview is mirrored; Vowed assumes `PreviewView` mirrors it (as it does in current CameraX) and mirrors the landmark x to match. **That alignment, for both cameras, is on the real-device checklist and has not been seen on a phone.** Source: https://developer.android.com/media/camera/camerax/preview
- CameraX `ImageAnalysis`: `setTargetResolution` / aspect ratio, `STRATEGY_KEEP_ONLY_LATEST`; the page does not describe `ResolutionSelector`, but the library has it (used here for a 16:9 analysis and preview picture). Source: https://developer.android.com/media/camera/camerax/analyze
- Full screen: the camera is a dialog window with system bars hidden (`WindowInsetsControllerCompat.hide(systemBars())`), the display cutout allowed (`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`) and the screen orientation locked while it is open, so a turned phone cannot restart a set. Android's own "Viewing full screen" notice appears the first time (seen on the emulator).

### Stake token (audit, 2026-10-10; read from the mint accounts and the program config, not guessed)
| Token | Address | Program | Bytes | Decimals | Mint authority | Freeze authority |
|---|---|---|---|---|---|---|
| Circle devnet USDC | `4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU` | classic SPL Token | 82 | 6 | Circle | Circle |
| tUSDC (our test USDC) | `C6pXRRmoHsf7Mqa1ZrW3JfspyknhSR1cRqHMUqao63Hv` | classic SPL Token | 82 | 6 | our devnet key | none |
| tSKR (our test SKR, NOT real SKR) | `J6X9udvWis7jYpVHic3Kn5iTG3ac6gbBeFixnYKPUkHB` | classic SPL Token | 82 | 6 | our devnet key | none |
- **The deployed program already accepts tSKR.** `/v1/meta` (program config on devnet): allowed mints = Circle USDC, tUSDC, tSKR; demo-pool mints = tUSDC, tSKR; max stake 100,000,000 (100 tokens), demo cap 20,000,000. A pool stores its own mint at creation; `create_pool` checks it against the allow list, `join` and `claim` check the token account's mint and owner and the vault (existing tests `create_pool_rejects_unlisted_mint_and_wrong_token_program`, `join_rejects_bad_token_accounts`, `claim_rejects_wrong_vault_mint_or_token_owner`). **No program change or upgrade is needed.** Both test tokens have the same 6 decimals, so the caps and the amount maths are identical.
- **What was missing:** the app, not the chain. The new-challenge screen took the FIRST mint of the list (Circle's devnet USDC for normal pools, which nobody can get from the app) and labelled every amount "test USDC". Fixed: a token selector (tUSDC / tSKR, icon, balance), the pool's own token on join, claim, Home and detail, an Explore token filter, per-token pre-flight messages. Tests: `StakeTokensTest`, `preflight.test.ts`, `e2e/tokens.test.ts` (two pools in two mints, wrong-mint refusals including a hand-made join).
