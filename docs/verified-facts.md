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
