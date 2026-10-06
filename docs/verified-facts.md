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
- OPEN: minSdk required by 2.2.0 and AGP/Gradle compatibility. Check at Phase 3 when the dependency resolves.

## 2. Mock MWA Wallet: VERIFIED
- Source: https://github.com/solana-mobile/mock-mwa-wallet
- No APK release is mentioned: clone, open in Android Studio, build and install on device or emulator.
  Optional Ed25519 key in `local.properties`. Supports authorize, SIWS, sign transactions and messages,
  bottom-sheet approval, biometrics. Press **Authenticate** in the wallet first (valid 15 minutes).
  Testing only, never real funds.

## 3. SKR token: PARTIAL
- Mint: `SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3` (mainnet). Source: https://solanamobile.com/blog/skr-is-live
- Staking program id (informational, we do not integrate it): `SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ` (same source).
- Decimals **6**, token program **classic SPL Token** (`TokenkegQ...`), no freeze authority.
  Source: on-chain read via Solana mainnet RPC `getAccountInfo` on the mint, 2026-10-06.
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
- **Windows users must use WSL.** This machine had no WSL distro installed (see progress.md).
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

## 12. Hackathon portal: OPEN
- https://solanamobile.com/hackathon redirects to https://docs.solanamobile.com/hackathon, then to https://solanamobile.radiant.nexus/
  ("Clock In"). The page content did not expose form fields, deadline, APK file-vs-link or video limits to our fetcher.
- **User action:** open the portal in a browser, confirm the current deadline (SPEC says original close 2026-10-08),
  and tell us the form fields, APK link vs upload, and video limits.
