# Progress

## Phase 0: Foundations — NOT PASSED (partially done), 2026-10-06

Done:
- SPEC section 14 researched; see `docs/verified-facts.md` (items 1,3,4,5,6,7,8,10,11 partial or verified; 9, 12 open).
- Repo layout, `.gitignore`, `shared/` JSON schemas (GoalPlan, ProofPackage), `docs/`.
- Backend (Fastify + TypeScript + vitest): builds, `/v1/health` test passes. `.env.example` has placeholders only.
- Installed via winget: Microsoft OpenJDK 17, Rustup (default toolchain not yet fetched), Android Studio.
- One-command check so far: `powershell scripts/check-all.ps1` (backend only).

Not done (blocking the gate):
- Android SDK + emulator: Android Studio needs its first-run wizard (GUI) to download the SDK/system image. Then the hello-world app + Mock MWA Wallet build.
- Anchor program: Anchor needs WSL on Windows and no WSL distro is installed (needs admin and likely a reboot). Then Solana CLI + Anchor 1.2.0.
- Hackathon portal facts (deadline, form fields) need a human to read the portal.
