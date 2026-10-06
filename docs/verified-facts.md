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
