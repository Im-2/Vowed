# Vowed: project memory for Claude Code

Vowed is an Android app (Kotlin + Jetpack Compose) built for the Solana Mobile "CLOCK IN" hackathon. Users stake USDC or SKR on any goal they type in plain words, prove it each day with phone-native proofs, and play with friends in squads. All product detail lives in `SPEC.md`.

## Hard requirements from the hackathon organizers

- Android only; the app must produce a functional APK.
- Must integrate the Solana Mobile Stack and Mobile Wallet Adapter (MWA). See SPEC 3.3.
- Native mobile design from the ground up. No WebView UI, no PWA wrapper, no ported web app.
- Must interact meaningfully with the Solana network (our onchain program does this).
- Deliverables: APK, GitHub repo, demo video, pitch deck.

## First thing every session

1. Read `SPEC.md` fully before writing code. It is the source of truth.
2. Read `docs/progress.md` (create it in Phase 0 if missing) to see which phase we are in.
3. Work on one phase at a time, in the order given in SPEC section 11. Backend first, then UI.

## Working rules

- **Stop at each phase gate.** When a phase's gate in SPEC section 11 passes, update `docs/progress.md`, summarise what works in a few lines, and wait for the user before starting the next phase.
- **Do not guess SDK facts from memory.** Versions, package names, program addresses (including the SKR mint) and API shapes must be checked against current official docs (docs.solanamobile.com, Anchor docs, Android docs) before use. Record what you verified in `docs/verified-facts.md` with the source URL.
- **Never put secrets in the repo or the APK.** No private keys, API keys or JWT secrets in source, in `BuildConfig`, or in resources. Use environment variables on the backend and `.env.example` files with placeholders.
- **Never ask the user to share a seed phrase or private key.** Test with the Mock MWA Wallet and devnet test keypairs only.
- **No mainnet deploys, no real funds, and no paid services without explicit approval from the user in chat.** Default network is devnet. All AI features must run on free tiers or on-device (SPEC section 6).
- **Label anything simulated.** If a feature uses mock data or a simulated yield, the UI must say so. Never present simulated numbers as real.
- **Privacy rule.** Camera frames, sensor streams and raw location never leave the phone. Only derived, minimal proof summaries and the typed goal text go to the backend.
- **Security rule.** Every onchain instruction needs signer, owner, PDA-seed and mint checks, plus tests for the failure cases (SPEC section 4.5).
- **Test as you go.** Each phase ships with tests that run from one command. Do not mark a phase done with failing tests.
- **Commit small and often** with clear messages. Do not commit build output, keystores or `.env` files.
- **Keep `docs/submission-notes.md` current.** After each phase, add what was actually built and verified, the test or demo that proves it, and the commit that added it. Never list a feature as done unless it works; unproven items go under "Not verified yet" or "Do not claim". It feeds the submission form, README, demo script and pitch deck.
- **Push after every phase gate** to `origin` (https://github.com/Im-2/Vowed, public, branch `main`). Before every push run `bash scripts/secret-scan.sh`; if it reports anything, stop, tell the user, and do not push. Judges read the commit history, so keep commits small.
- **Prefer the emulator first.** Features that need real hardware (camera pose, step counter, usage stats) get an emulator-friendly fallback or a debug "inject test data" path, plus a manual real-device test checklist in `docs/device-tests.md`.

## Repo layout (create in Phase 0)

```
/programs/vowed      Anchor program (Rust) + tests
/backend             TypeScript API, oracle, indexer, jobs + tests
/android             Kotlin + Compose app
/shared              JSON schemas (GoalPlan, ProofPackage) used by backend and app
/docs                progress.md, verified-facts.md, device-tests.md, threat-model.md, pitch/
```

## Commands (fill in as they are created)

- Everything: `powershell scripts/check-all.ps1`
- Program build + tests (WSL Ubuntu as root): `wsl -d Ubuntu -u root -- bash /mnt/c/Users/hp/Vowed/scripts/program-build.sh`
- Backend dev / fast tests (Windows): `cd backend; npm run dev` / `npm test` (13 VM tests are skipped there)
- Backend full suite incl. real-program VM tests (WSL): `wsl -d Ubuntu -u root -- bash /mnt/c/Users/hp/Vowed/scripts/backend-test.sh` (run `program-build.sh` first)
- Backend devnet gate (demo pools, about 6 minutes, resumable, never uses a faucet): `cd backend; npm run gate:devnet -- --plan` (read-only), `-- --fund --fund-only` (top up from the deployer), then no flag; delete `backend/.devnet/gate-state.json` to run a fresh round. See `docs/runbook.md`
- Upgrade the devnet program (public RPC is rate limited; the CLI cannot resume a half-written buffer): `scripts/program-deploy-devnet.sh`; if it fails, `npx tsx backend/scripts/fill-buffer.ts <BUFFER> backend/.devnet/vowed.so` (copy the binary with `scripts/wsl-copy-so.sh`) then `scripts/program-finish-upgrade.sh <BUFFER>`
- WSL gotchas: put WSL commands in script files (PowerShell expands `$HOME` in double quotes); run long jobs as tool background tasks with a log file, not as WSL-detached processes
- Regenerate API docs: `cd backend; npm run openapi` (a test fails if `docs/openapi.json` is stale)
- Android build: `cd android; .\gradlew.bat assembleDebug` (needs `JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`; SDK at `C:\Users\hp\Android\Sdk`; `sdk.dir` in `android/local.properties` must use forward slashes)
- Emulator + install: `scripts/emulator.ps1` (AVD `vowed_api36`; Mock MWA Wallet built at `C:\Users\hp\Android\tools\mock-mwa-wallet`; a test PIN is set on the emulator, needed to AUTHENTICATE the wallet)
- Test-token faucet tests: `cd backend; npx vitest run test/faucet.test.ts` (env and limits: `docs/runbook.md`)
- Android unit tests + debug APK: `powershell scripts/android-test.ps1`
- Dev backend for the emulator (devnet, port 8787, throwaway keys from backend/.devnet): `powershell scripts/dev-backend.ps1`
- Drive the emulator through create/join/claim with the Mock wallet: `scripts/emu-drive.ps1`, `scripts/emu-claim.ps1` (the latter clears wallet and app data first). Mock wallet quirks are in `docs/verified-facts.md` ("Phase 3")
- Phase 4 proof gate on devnet: `cd backend; npm run gate:proofs`; friend for demos: `npm run demo:friend -- <pool> --stake 2`; emulator helpers in `scripts/emu-lib.ps1`
- Build release APK: `TODO` (the release lint step needs a network download and failed once with a network error; a signed release build is Phase 9)
- Gotchas: run `wsl` commands from PowerShell (Git Bash rewrites /mnt paths); use a Windows-style `JAVA_HOME` for Gradle.

## Definition of done for the whole project

Everything in SPEC section 13 (submission checklist) is complete and the demo script in section 13.3 runs end to end on a real Android phone.
