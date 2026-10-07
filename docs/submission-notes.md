# Submission notes

The single source for the submission form, README, demo script and pitch deck. **Only things that were built and verified are listed as done.**
Every entry names the proof (a test or a demo you can run) and the commit that added it. Plans live in `SPEC.md`, not here.

Rules for this file (also in `CLAUDE.md`):
1. After every phase, add what was actually built and verified. Never list a feature as done unless it works.
2. Say *where* it was verified: unit test, in-process VM with the real program, emulator, devnet, or a real phone. Do not blur these.
3. Anything not yet proven goes in "Not verified yet" or "Do not claim", never in the feature tables.
4. Commit ids are short hashes in this repository (https://github.com/Im-2/Vowed). A feature changed later keeps its first commit and lists the fix commit.

Last updated: after the Phase 2 gate (2026-10-07): the full loop has run on real devnet with demo pools. Still no real phone and no Android UI for it.

---

## 1. Status of the product features (SPEC section 2)

| Feature | Status | What exists |
|---|---|---|
| F1 Stake on a goal | **Verified on devnet with test tokens; no app UI yet** | Program + backend tx builders. Real stake custody in a program vault, tested in the VM and run on devnet (demo pools, test USDC). The Android app cannot create or join yet (Phase 3) |
| F2 Plain-language goals | **Not started** (only the GoalPlan schema, hashing and validation exist) | `backend/src/domain/plan.ts`; no parser, no templates, no AI yet (Phase 5) |
| F3 Daily proof check-in | **Backend verified (incl. devnet, with a script as the "device"); no phone proofs yet** | Device-signed proof verification and onchain check-ins, tested with simulated devices and on devnet with a software key (trust tier low). No camera, steps, usage, geofence or timer code on Android yet (Phases 4-5) |
| F4 Squads | **Backend verified; no app UI** | Squads, invites, feed, leaderboard, nudges (tested). Push delivery needs a Firebase project (not created) |
| F5 Streaks and payouts | **Verified on devnet (settlement, claim, treasury sweep); no app UI** | Settlement, payout math, claims; streak calculation. No calendar heatmap or payout animation (app) |
| Demo pools (minutes-long "days") | **Built and verified on devnet** | For demos and tests only, clearly labelled, test tokens only; see the Phase 2 gate section |
| F6 Yield / soft stakes | **Soft mode verified onchain. Yield not started** | Soft penalty (max 50%) works in tests. No yield integration; nothing simulated is shown anywhere yet |
| F7 Adaptive coach | **Rules verified in backend; no app UI** | Deterministic suggestions with tests. No LLM wording (not planned for now) |
| F8 Future-self letters | **Not started** | |
| F9 Open proof plug-ins | **Not started** | |
| SKR rewards and perks (prize) | **Not started** | Design decided (rewards for top streaks + SKR-paid perks, not staking); nothing built |

---

## 2. What is built and verified, by phase

### Phase 0: foundations (gate passed 2026-10-06)

| What works | Proof | Commit |
|---|---|---|
| A native Android app (Kotlin, Jetpack Compose) builds and, on an Android 36 emulator, connects to the Mock MWA Wallet through Mobile Wallet Adapter and shows the wallet's public key | Screenshot `docs/phase0-mwa-connect.png`; steps and gotchas in `docs/verified-facts.md` (items 1-2). Repeat with `scripts/emulator.ps1` | `cf1b700` (app), `f24342c` (gate) |
| Backend, Android and Anchor projects all build and test from one command | `powershell scripts/check-all.ps1` exits 0 (last run after Phase 2) | `f24342c`, `1223d51`, extended in `2c1fc10` |
| SPEC section 14 facts checked against official sources and recorded with URLs (MWA 2.2.0 artifact and its Android SDK 37 / AGP 9 requirement, SKR mint with 6 decimals read from chain, USDC mints, Anchor provenance) | `docs/verified-facts.md` | `1b5bd87`, `6466399`, `1223d51` |
| Hackathon rules from the official portal recorded; SKR design changed to rewards and perks, not staking | `SPEC.md` sections 1.1 and 9.6 | `6466399` |

Limits: wallet connection only, no transaction signing in the app yet. Emulator and the Mock wallet only, no real phone or Seed Vault Wallet yet.

### Phase 1: Solana program (gate passed 2026-10-06)

Program id `BMTXJRZ4QxzCg4UCHKo6qGGiGXKW26ARPAtPaXA8k7EL`. Anchor 1.2.0 (pinned to tag `v1.2.0`).

| What works | Proof | Commit |
|---|---|---|
| Stake custody: stakes sit in a vault owned by the program; only `claim` (to the owner) and `sweep_treasury` move money out | `programs/vowed/tests/src/t_settle.rs`, `t_pool.rs` (56 integration + 4 unit tests, all pass) | `8e3ddce` |
| Pools with Soft (penalty capped at 50%) and Hard (full forfeit) modes, joins with limits, pause switch | `t_pool.rs`, `t_config.rs` | `8e3ddce` |
| Oracle-signed daily check-ins, bounded to each day's window in the user's timezone; no duplicates | `t_checkin.rs` | `8e3ddce` |
| Permissionless settlement, payouts: winners get their stake plus a share of forfeits; fees and rounding dust go to the treasury; all edge cases from SPEC 4.4 | `t_settle.rs`; payout property tests on pure math and on-chain with random pools: money is conserved and the vault ends at zero (`t_math.rs`) | `8e3ddce` |
| Admin can void a pool before any payout and everyone gets their full stake back | `t_void.rs` | `8e3ddce` |
| Security checks: wrong signers, wrong accounts, wrong mints, double claim, reinitialisation, config only by the upgrade authority | `t_config.rs`, `t_settle.rs`, `t_checkin.rs`, `t_pool.rs`; threat model `docs/threat-model.md` | `8e3ddce` |
| The tests really catch bugs: removing the oracle check, the settle-time check or the claim-state update each makes tests fail (3 of 3) | `scripts/program-mutation-check.sh` | `8e3ddce` |
| Rust, TypeScript and the program agree on day-index, check-in window and payout math | Shared vectors `shared/test-vectors/*.json` (generated by an independent Python implementation, 1,135 cases) checked in Rust and in the backend | `8e3ddce`, tightened in `2c1fc10` |
| **Deployed to devnet**, and the onchain bytes equal the local build | Deploy tx `3bWZkcUugU72LA6kAPEhnr4e17fdpuh9xBQFjibqgDQVcsJNLGcqNgfr9J4Gh4cXihDMLARNYit7aG3gWfJvFr9q`; `scripts/program-verify-devnet.sh` shows identical SHA-256 `7f787026...` | `b4c397a` |

Limits: the deployed program has **not been used live on devnet yet** (`init_config` not run). Unaudited; the oracle is a trusted backend key (see "Trust model" below). Token-2022 extension rejection has no integration test.

### Phase 2: backend (gate passed on devnet 2026-10-07)

Unless a row says "devnet", it is verified **in an in-process VM running the real program binary** (and in unit tests). Run: `wsl -d Ubuntu -u root -- bash scripts/backend-test.sh` (100 tests pass); `cd backend; npm test` runs 82 of them natively on Windows.

| What works | Proof | Commit |
|---|---|---|
| Wallet sign-in with a signed Sign-In-With-Solana message, single-use nonce, short-lived token | `backend/test/auth.test.ts` (11 tests: replay, wrong key, wrong domain, expired, rate limit) | `d4992fa` |
| The API builds unsigned create / join / claim transactions (safe to retry with `Idempotency-Key`); the wallet signs and sends them; the stake is bound to the user's registered device key | `backend/test/e2e/lifecycle.test.ts` (full lifecycle and the builder refusals); API reference `docs/openapi.json` (26 endpoints, kept current by a test) | `d4992fa`, `ace0612` |
| Device registration with Android hardware key attestation: verifies Google's certificate chain, the challenge and the attested key; sets a trust cap from security level and boot state | `backend/test/attestation.test.ts`: **six real device attestation chains** (TEE and StrongBox, Android SDK levels 28 to 37) pass; tampered, replayed, wrong-key, foreign-root, spliced and revoked chains fail | `d4992fa` |
| Proof sessions and verification: per-session nonce, device signature, day window, per-type plausibility (reps per second, steps per day, focus time, usage, dwell), trust tier | `backend/test/proof-verify.test.ts`, `lifecycle.test.ts` (rejects wrong nonce, other device, tampering, expiry, future and stale timestamps, strangers) | `d4992fa`, `ace0612` |
| The oracle records an accepted proof onchain exactly once; survives an RPC outage by retrying; replays return the stored result | `lifecycle.test.ts` (replay, outage and concurrent-submit tests) | `d4992fa`, `ace0612`; race fix `2c1fc10` |
| Settlement crank: settles every due participation and sweeps leftovers to the treasury; claims and balances checked | `lifecycle.test.ts` (winner, loser, nobody-wins sweep, void refunds) | `d4992fa`, `ace0612` |
| Event indexer keeps a database mirror of onchain state (also via polling when the app never reports a transaction) | `lifecycle.test.ts` ("the poller mirrors...") | `d4992fa` |
| Squads: create, invite code and link, join, linked pools, activity feed (joined, checked in, missed, settled), leaderboard, nudges with spam limits, squad goals hidden from non-members | `backend/test/squads.test.ts` (13 tests), `lifecycle.test.ts` | `d4992fa`, `ace0612`, visibility fix `2c1fc10` |
| Coach: deterministic suggestions (easier below 70%, harder only after two strong cycles, never touches an active challenge) | `backend/test/coach.test.ts`, `squads.test.ts` ("coach endpoint") | `d4992fa` |
| Push-token registry; FCM HTTP v1 sender (unit-tested with a fake network; **not** tested against Google) | `backend/test/fcm.test.ts`, `squads.test.ts` | `d4992fa` |
| The tests catch real bugs: 10 of 10 deliberately broken guards fail the suite | `scripts/backend-mutation-check.sh` | `2c1fc10` |
| Runs locally with SQLite and no outside account; no secrets in the repo; secret scan of the whole history is clean | `bash scripts/secret-scan.sh`; `docs/runbook.md` | `2c1fc10`, `7d0ce16` |

Limits: the app does not call the backend yet; Seeker and real-phone attestation unverified; Google's live revocation endpoint was unreachable from the development network (stub-tested only).

#### Phase 2 gate: demo pools and the full loop on real devnet (2026-10-07)

| What works | Proof | Commit |
|---|---|---|
| **Demo pools:** pools whose "days" last 60 to 3600 seconds, so a whole challenge (stake, daily proofs, settlement, payout) can be shown in minutes. Normal pools keep real 24-hour days. Clearly labelled on chain (`is_demo`, `day_secs`), in the API (`isDemo`, `daySecs`, `demoLabel`) and, later, in the UI | `programs/vowed/tests/src/t_demo.rs` (9 tests) and shared demo vectors; `backend/test/demo.test.ts`, `backend/test/e2e/demo-pools.test.ts` (API lifecycle in about three minutes of cluster time) | program `a503b58`, backend `c34b9f6`, spec `5ad353c` |
| **Demo pools cannot be looser than normal pools:** all normal rules still apply, plus: switched on in the program config, only for tokens flagged for demos, a lower stake cap (20 vs 100 tokens on devnet), at most 20 participants, start within 24 h, join window within one demo day, config can only be stricter, admin kill switch. A real-money deployment should disable demo pools | The same tests, including every refusal; 7 deliberately broken demo rules are all caught by `scripts/program-mutation-check.sh` (10 of 10 program mutations caught) | `a503b58`, `5ad353c` |
| **The whole product loop ran on real Solana devnet in 389 seconds**: `init_config`; two demo pools created through the API; two wallets joined with test USDC; device-signed proofs accepted and recorded on chain by the oracle; crank settled three participations and swept one pool; the winner claimed 20 test USDC; every final balance matched (alice 110, bob 85, treasury 5, vaults 0) | `cd backend; npm run gate:devnet` (state and keys are throwaway files under `backend/.devnet`). Transaction ids and addresses: `docs/verified-facts.md` ("Phase 2 gate on devnet") | `c34b9f6` (gate script); run on 2026-10-06/07 |
| The devnet program was **upgraded in place** to the demo-pool build (same program id) and the onchain bytes equal the local build | `scripts/program-finish-upgrade.sh` prints identical SHA-256 `fe9470fb...`; upgrade tx `5rrULz4H...` | `1309b5a` (upload tooling) |

Honest limits of the devnet run: the "device" was a software key held by the script (no attestation, so proofs got trust tier **low**), not a phone; test tokens we minted ourselves; demo pools only (normal 24-hour pools are covered by the VM tests, not by a devnet run); the app was not involved.

---

## 3. Trust model (use this wording; do not oversell)

- Funds are controlled only by the program. Users sign their own deposits and claims with their wallet.
- Daily completion is recorded by a backend **oracle** key after it verifies a device-signed proof. The oracle can be wrong or malicious, and a determined user with a rooted phone could try to fake sensor data. Mitigations that exist and are tested: hardware-backed device key with attestation, per-session nonces, trust tiers that cap stakes, plausibility limits, the admin's ability to void a pool before payouts, and the oracle's power being limited to recording check-ins.
- Not built (roadmap only): multiple attestors, squad voting on disputes, oracle rotation with a timelock.
- The program is unaudited and the upgrade authority is a single devnet key. Devnet only.

---

## 4. Do not claim yet

- Camera pose, step counting, usage-limit, geofence or focus-timer proofs on a phone (backend verifies the data; nothing collects it).
- Plain-language goals or any AI (no parser exists).
- Yield on stakes, SKR rewards or SKR perks, letters, widget, plug-ins.
- That the app can stake, join or claim (the Android app only connects a wallet today).
- A working end-to-end flow **on a real phone or a Seeker**, or one driven from the Android app. What is true: the loop works on devnet driven by a script, with test tokens and demo pools.
- That demo pools are "the product": they are a labelled demonstration mode. Normal pools use real 24-hour days.
- Push notifications reaching a phone.
- Audited, mainnet, or "first habit-staking app" (prior art exists; see SPEC 1.2).

## 5. Not verified yet (tracked, will move to section 2 when proven)

| Item | Blocked on |
|---|---|
| A normal (24-hour) pool run through settlement on devnet | About 26 hours of waiting and a little SOL; covered by VM tests until then |
| Real phone and Seeker attestation | A physical device (`docs/device-tests.md`) |
| Live FCM delivery | A Firebase project (free) |
| The Android app staking, proving and claiming through the backend | Phase 3 onward |

## 6. Demo and pitch material that already exists

- The lifecycle test is a faithful narrated script of the product loop (create, join, prove, miss, settle, claim) against the real program; its steps map to demo beats 3-6 of SPEC 13.3 once the app UI exists.
- The devnet gate log is a ready-made demo script: create, join, two proofs, settle, claim, sweep in about six minutes, with explorer links for every transaction (`docs/verified-facts.md`). With the app UI it becomes the 3-minute video, on a demo pool labelled as such.
- Verified numbers safe to quote (reproducible with the commands above): 71 program tests, 100 backend tests, 10/10 program and 10/10 backend mutations caught, 6 real attestation chains verified, 1,391 shared vectors, 26 documented API endpoints, devnet loop in 389 s.


---

## Phase 3 additions (Android app on the emulator)

| Feature that really works | Proof | Commit |
|---|---|---|
| Native Compose app with onboarding, challenge list, new-challenge flow, review screen, challenge detail, settings (no WebView) | Run on the emulator; screenshots `docs/phase3-joined.png`, `docs/phase3-claimed.png` | `6501cb0`, `06f6553` |
| Mobile Wallet Adapter connect + Sign-In-With-Solana, backend session, phone proof-key registration (emulator falls back to the low trust cap) | Backend log shows nonce, verify, device register 400 then 200; app lands on the challenge list | `6501cb0` |
| **Stake-join-claim from the app against devnet**: create a demo pool, join with 1 test USDC, backend crank settles, claim 0.7 back (30% Soft penalty) | alice test USDC 110, 109, 109.7 read from chain; transactions `4HRF662n...` (create), `4RDmUcXG...` (join), `669FYTaj...` (claim) | `6501cb0`, `d533985` |
| The phone re-checks every backend-built transaction before opening the wallet (decode, derive all addresses, compare amounts, mode, dates, plan hash, device binding) | 11 JVM unit tests incl. tampered-byte and wrong-intent rejections (`scripts/android-test.ps1`); the on-screen review shows the decoded values | `6501cb0` |
| DEMO pools are labelled in the app (red banner on list and detail, "DEMO POOL" row on review) | Screenshots | `6501cb0` |

Limits to state honestly: emulator and Mock wallet only (not a real phone or Seeker); test tokens; demo pool only; daily proofs are not in the app yet, so the claim was the Soft-mode refund after a fully missed run, not a success payout; the first wallet connection on a cold emulator can take over 30 seconds.

Do not claim yet (additions): any proof captured by the app, a release APK, a real-device run, or squads in the UI.


---

## Phase 4 additions (daily proofs and the success payout)

| Feature that really works | Proof | Commit |
|---|---|---|
| Daily proofs signed by a Keystore key and verified by the backend; the oracle writes the check-in on Solana | `npm run gate:proofs`: 6 proof types confirmed on devnet (tx links in the script output) | `8b82bf9` and later |
| Replay, tamper, forged-device, missed-goal and early-day attempts refused | Same gate: 6 refusals + 3 replay checks pass | same |
| Real focus-timer proof from the app (foreground-only counting) recorded on devnet | Emulator run: tx `4dpF...Kv1X`; screenshot `docs/phase4-checkin-recorded.png` | `8b82bf9` |
| **Success payout end to end from the app**: stake 2, friend's 2 forfeited, claim 4; chain balance 107.7 to 111.7 | Emulator run, `docs/phase4-success-payout.png`, balances read from chain | `8b82bf9` |
| Same day schedule in app, backend and program | 4 shared-vector suites; Android `ScheduleTest`, backend and program tests | `8b82bf9` |
| Test-data injection exists only in debug builds | Release class `DebugProofs` decompiled: only throws "not available in release builds" | `8b82bf9` |

Limits to state honestly: collectors for steps, location and usage were not run on real sensors yet (device checklist P1-P14); proofs from the emulator carry trust "low"; the "friend" in the payout demo was a script-controlled test wallet, not a second phone; camera proof is not built yet.
Do not claim yet (additions): proofs from real sensors, a second phone in a squad, any AI, yield or SKR features.


---

## Addendum: test tokens and devnet wallets

| Feature that really works | Proof |
|---|---|
| In-app "Get test tokens": fixed 20 tUSDC + 20 tSKR to the signed-in wallet, once per wallet per 24 h, global daily cap, labelled TEST TOKENS | Emulator run: alice 109.9 to 129.9 tUSDC and 0 to 20 tSKR read from chain; bob claimed through the live API, then refused (429 `faucet_cooldown`); `backend/test/faucet.test.ts` (13 tests, each limit mutation-checked) |
| Normal pool creation with Circle's devnet USDC from the app | Pool `55DPY...1EcY2` on devnet, mint `4zMMC...DncDU` |

Limits to state honestly: testers also need devnet SOL (the tokens do not include it); joining and claiming with Circle USDC is **not verified**; Phantom and Solflare devnet switching is from their documentation, not run by us; Seed Vault Wallet and Backpack devnet support is unverified.


---

## Phase 5 additions (goals in plain words, camera counting)

| Feature that really works | Proof | Not proven |
|---|---|---|
| Plain-language goal to a plan, with **13 built-in templates and no AI needed** (squats, push-ups, steps, walk outside, gym, focus, reading, meditation, usage limit, no-use window, early wake, sleep window, hydration) | `backend/test/goals-templates.test.ts`: 37 tests incl. 14 sample sentences and 7 goals no phone can verify | |
| The model's answer is never trusted: strict schema, realistic bounds, whitelisted parameters, hidden characters stripped, trust tier decided by the proof type, fallback to templates | `backend/test/goals-llm.test.ts`: 37 tests against a fake model returning 15 kinds of bad answer, an injection attempt and key-handling cases; 4 of 4 deliberately broken versions caught | **A live Gemini answer has not been seen**: this network blocks the Gemini host (see `docs/verified-facts.md`). The request shape for the live endpoint is from the docs but untested |
| Only the typed goal text goes to the model; the key never leaves the server; free-tier use is capped and cached | Tests check the request body, URL, headers, responses and errors for the key and wallet address | |
| The app shows the plan with trust tier and limitations, lets the user edit it, validates it on the server, hashes it on the phone, and checks the create-pool transaction against it | Emulator run: "Do 20 squats every day" became a plan, then the create-pool review (matching token, penalty, 7 days) | Staking from an edited AI plan was not run end to end because the Mock wallet kept timing out (a known quirk, see `docs/verified-facts.md`) |
| Camera rep counting logic (angles, hysteresis, confidence gating, side choice), randomised hand-raise liveness, session rules | 18 + 1 unit tests on a synthetic skeleton | **Counting a real person has not been run**: checklist C1-C12 in `docs/device-tests.md` |
| Camera pipeline starts on the emulator (CameraX preview, ML Kit pose, countdown, "I cannot see a person" with an empty scene) | Emulator run; the Camera practice screen needs no wallet or server | |

Do not claim: that goals are understood by AI in practice (no live answer seen), that pose counting is accurate (no real-person test), or that the camera is liveness-proof against a determined second person off camera.

## Phase 6 (2026-10-07)
Verified on the emulator with two accounts (alice in the app, bob scripted): squad create, join by invite code, a squad challenge created and joined from plain words, the leaderboard and activity feed showing the other person's check-in and a nudge, a local notification for the nudge, and joining a sample public challenge from Explore with the review-before-sign screen. Evidence: `docs/progress.md` (Phase 6), screenshots `docs/phase6-*.png`. Backend tests: squads, Explore listing and public/private rules, moderation, restart recovery.
**Do not claim:** push notifications when the app is closed (needs Firebase); the hosted backend (not deployed yet); a two-physical-phone run; live Gemini; alice's own squad check-in (not driven).
## Phase 7 (2026-10-07)
Built: adaptive coach screen (backend rules from Phase 2), encrypted letters to future me (milestone or broken streak, Keystore key, text only, never uploaded), home-screen widget (Glance). Tested: unit tests for letter rules, encryption, tamper handling, delivery once, widget text, coach rules. **Do not claim:** voice letters; the widget or letter notification shown on a device (not yet run); push when the app is closed; any real-phone result. The wallet-session retry (`WalletSessionGate`) is unit-tested, but the cancelled-association failures were only seen on a starved emulator.

## Phase 8 (2026-10-07)
Built and verified: weekly test-SKR rewards for the top streaks (real devnet transfer, idempotent), SKR streak freeze (phone-side check, on-chain confirmation, no replay), open proof-provider format with a sample provider (spec, backend verification, shared test vector), simulated-and-labelled yield. **Do not claim:** real yield or lending; SKR on mainnet; that a freeze changes payouts or check-ins (it only keeps the streak); any third-party provider integration; the new screens shown on a device; the hosted weekly job. SKR here is a test token on devnet.
