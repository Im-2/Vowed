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
