# Vowed: Product and Technical Spec

Version 1.0. Source of truth for the build. If something here conflicts with your memory of an SDK, check the official docs and record the answer in `docs/verified-facts.md`.

---

## 0. How to read this

- Sections 1-3 explain what we are building and why.
- Sections 4-10 are the technical design.
- Section 11 is the build order with gates. Follow it in order (backend first, then UI).
- Section 13 is the hackathon submission checklist and the demo script.
- Section 14 lists things that MUST be verified against current docs before use.

---

## 1. Product summary

**Vowed** is an Android app where you type any goal in plain words ("study 2 hours a day", "no TikTok after 10pm", "gym 4 times a week"), stake money on it, prove it each day using proofs only a phone can produce, and compete with friends in squads. Hit your goal and you keep your stake plus a share of what others forfeited. Miss it and your stake is forfeited (or partly, in soft mode).

### 1.1 Hackathon context (verified from official sources)

- Event: CLOCK IN, the Solana Mobile hackathon, run with RadiantsDAO. Source: https://solanamobile.com/blog/clock-in-the-solana-mobile-hackathon
- **Submissions close Oct 12, 2026 at 12:59 PM GMT+1.** Judging opens Oct 13; results Nov 10; winners must publish on the dApp Store within 30 days. (Source: official portal https://solanamobile.radiant.nexus, supplied by the user.)
- Submit: (1) a functional Android APK (the form asks for a link), (2) a GitHub repo with source (may be **private**: install the Radiants Align GitHub app, share the repo, and register through Align; the repo must be cloneable and runnable by someone else), (3) a **3-minute** demo video that must show the app running on a **real device**, not only a simulator, (4) a pitch deck or short presentation.
- Judges review GitHub commits up to the deadline: commit small and often.
- Prizes: $125k USDC across 10 teams (1st $30k ... 6th-10th $5k each), plus a separate optional $10k SKR integration prize. Winners must publish on the Solana dApp Store to claim.
- **SKR prize rule (official): SKR staking integrations do NOT qualify.** Our SKR integration is weekly SKR rewards for top streaks plus SKR-paid perks (for example streak freezes). USDC is the main deposit token; SKR deposits are optional and are not pitched as the SKR integration. The ORE prize is out of scope.
- Judging criteria, 25% each: (a) Stickiness and product-market fit, (b) User experience, (c) Innovation, (d) Presentation and demo.
- A Seeker device is not required. Any Android device or emulator works. Seeker behaves like a normal Android phone.
- **Organizer requirements (from the official Event Info panel):** Android only, and the app must produce a functional APK; it **must integrate the Solana Mobile Stack and Mobile Wallet Adapter**; it must be designed for mobile from the ground up (direct ports and PWA wrappers score poorly); and it should interact meaningfully with the Solana network. Section 3.3 maps each of these to this design.

### 1.2 Prior art (we must not claim to be first at "stake on habits")

- **Moonwalk**: Solana step-goal staking with teams and forfeit pools; already on the Seeker dApp Store and featured in Seeker Summer.
- **Kinlog** (MONOLITH honorable mention): camera squat counting, streaks, NFT badges.
- **Ascend**: Solana habit staking with friends (web first).
- **Echo Protocol** (MONOLITH winner): GPS-proof claims signed via Seed Vault.
- Other chains: Lockin, Hard Habits, LockD.

**Our differentiation (the pitch):** the first commitment app that works for *any* goal described in plain words, verified by phone-native proofs (camera pose, phone usage, steps, location, focus timer), with stakes that can earn yield, an adaptive coach, and an open proof-provider interface. Do not claim "first habit-stake app".

### 1.3 Scoring map

| Judging criterion | Features that carry it |
|---|---|
| Stickiness / PMF | F4 squads, F5 streaks and payouts, F7 adaptive coach, F8 future-self letters |
| User experience | F2 plain-language goals, F3 one-tap proofs, widget, haptics, practice mode |
| Innovation | F2 + F3 universal proof engine, F6 yield-backed stakes, F9 proof plug-ins |
| Presentation / demo | Clear loop (type goal, stake, prove, get paid), F8 letter moment, demo mode |
| SKR prize | Weekly SKR rewards for top streaks and SKR-paid perks such as streak freezes (section 9.6); not SKR staking |

---

## 2. Features and acceptance criteria

Each feature has acceptance criteria (AC). A feature is done only when all its AC pass.

### F1. Stake on a goal
- Connect a wallet via Mobile Wallet Adapter (MWA). No keys inside the app.
- Create or join a challenge by staking **USDC** (main token; devnet test tokens on devnet). SKR deposits are optional and secondary.
- Three stake modes: **Practice** (no money, same flow), **Soft** (capped penalty), **Hard** (full forfeit on failure).
- AC: stake is held in a program-owned vault; user can see the stake, the pool and the rules before signing; the signed transaction matches what the UI showed.

### F2. Plain-language goals
- User types a goal. The app returns a structured plan (GoalPlan) showing cadence, target, proof methods, and difficulty.
- If a goal cannot be verified, the app says so and proposes the closest verifiable version.
- AC: at least 12 goal templates work with no AI (fallback); free-form text works via the free-tier LLM; invalid LLM output is rejected by schema validation; the user can edit the plan before staking.

### F3. Daily proof check-in
- Proof types: camera pose reps, focus timer, phone-usage limit, steps, location geofence, self-attest (lowest trust).
- Each proof has a **trust tier** (High, Medium, Low) shown to squads; stake caps depend on tier.
- Proofs are signed by a hardware-backed device key; the backend verifies and the oracle records the day onchain.
- AC: each proof type works end to end; replaying an old proof is rejected; proof data stays on device except the minimal summary.

### F4. Squads
- Create a squad, invite via link or code, run a shared challenge, see who checked in today, nudge friends, see a leaderboard.
- AC: two test accounts can join one squad challenge; check-ins appear in the feed within seconds; nudges arrive as push notifications.

### F5. Streaks and payouts
- Streaks, a calendar heatmap per challenge, settlement after the challenge ends, claim of stake plus share.
- AC: payout math matches section 4.4 in tests, including the edge cases listed there.

### F6. Yield-backed stakes and soft stakes
- Idle stakes earn yield through a lending vault; soft mode limits what the user can lose.
- AC: soft mode works with capped penalty without any yield integration. Yield integration is real (verified against Kamino/Seed Vault docs) or clearly labeled "simulated" in the UI (see 9.5).

### F7. Adaptive difficulty coach
- Suggests the next goal's difficulty from the user's history so the success rate stays in a healthy band.
- AC: deterministic rules (no AI needed); suggestions never change an active challenge; optional LLM only rewrites the message text.

### F8. Letters from your future self
- User records a short text or voice message. It is delivered on a milestone or on a broken streak.
- AC: stored encrypted on the device; delivered by a local or push notification trigger; deleted after delivery if the user chose that; no content ever uploaded in plain form.

### F9. Open proof plug-ins
- A documented `ProofProvider` interface and a signed-attestation format so other apps can verify goals for Vowed.
- AC: interface and spec are written and tested with one sample provider shipped in the app. Do not claim any third-party integration unless it really exists.

---

## 3. Architecture

```
Android app (Kotlin, Compose)
  - MWA wallet connect/sign  <------------------>  Wallet (Seed Vault Wallet / Mock MWA Wallet)
  - Proof engine (on-device)
  - Device key (Android Keystore)
        |  HTTPS (JWT from wallet sign-in)
        v
Backend (TypeScript)
  - API, goal parser proxy (LLM), proof verifier, oracle signer, indexer, jobs, push
        |  RPC (free-tier provider)
        v
Solana program (Anchor, Rust)
  - Config, Pool, Challenge, Participation, token vaults
```

### 3.1 Tech stack decisions

- **Android:** Kotlin, Jetpack Compose, MVVM, Hilt, Room, WorkManager, CameraX, an on-device pose model (ML Kit Pose Detection or MediaPipe Pose Landmarker; pick after checking current docs), kotlinx.serialization, Ktor or Retrofit. Min SDK 26 (verify against MWA requirements). Native Android gives the best access to sensors, usage stats and the camera.
- **Solana program:** Anchor (latest stable; verify), SPL Token. Tests with a local validator or LiteSVM/bankrun.
- **Backend:** TypeScript (Node), Fastify or Hono, PostgreSQL (free-tier host; verify current limits) or SQLite for local dev, a free-tier RPC provider, Firebase Cloud Messaging (free) for push.
- **Transaction building:** the backend builds the unsigned transaction from the program IDL; the app **decodes and sanity-checks it** (program id, accounts, amounts) before asking the wallet to sign through MWA. This avoids weak Kotlin Anchor support while keeping the wallet as the only signer.
- **Networks:** devnet by default. A debug toggle switches RPC and mints. Mainnet only with user approval.

### 3.2 Trust model (be honest in the docs and the pitch)

- Funds are controlled only by the program. Users sign their own deposits and claims.
- Daily completion is recorded by a backend **oracle** key after verifying a signed proof. The oracle can be wrong or malicious, and a determined user can try to fake a proof. Mitigations: nonce challenge per proof, hardware-backed device key, key attestation checked server-side, trust tiers with stake caps, squad flagging, and per-pool stake caps.
- Roadmap (documented, not built): multiple attestors, squad voting on disputes, oracle key rotation with timelock.

### 3.3 Organizer requirements mapped to this design (all are hard requirements)

| Requirement | How Vowed meets it |
|---|---|
| Android only, functional APK | Native Kotlin + Jetpack Compose app; signed release APK (Phase 9). |
| Integrate the Solana Mobile Stack (SMS) | Use SMS components, not just a generic wallet: **Mobile Wallet Adapter** for connect, sign-in and transaction signing (works with Seed Vault Wallet on Seeker); **Seed Vault** signing for high-assurance proofs (7.3); **SKR** weekly rewards and perks (9.6); **Solana dApp Store** publishing readiness (13.2). Check docs.solanamobile.com for any other SMS pieces worth using (for example Seeker ID) and record them in `docs/verified-facts.md`. In the README, list exactly which SMS components are used and where. |
| Mobile Wallet Adapter | MWA is the only signing path for user funds (F1). Test with the Mock MWA Wallet and on a real device with a real MWA wallet. |
| Designed for mobile from the ground up (no ports, no PWA/web wrappers) | No WebView-based UI and no web app wrapped in an APK. Phone-native features are central: camera pose, usage stats, step sensor, geofence, Keystore, widget, push, haptics. |
| Interact meaningfully with the Solana network | Real onchain program: stake custody in vaults, per-day check-ins recorded onchain, settlement and payouts, SKR token use. Show transaction signatures and an explorer link in the app. |

---

## 4. Solana program (Anchor)

### 4.1 Accounts

- **Config** (PDA): admin, oracle authority pubkey, fee_bps (default 0), treasury, paused flag, max stake per participant, allowed mints.
- **Pool** (PDA): pool id, creator, mint, vault (token account PDA), kind (Squad or Open), mode (Practice not onchain; Soft or Hard), penalty_bps (Hard = 10000; Soft capped, e.g. max 5000), start_ts, end_ts, duration_days (max 60), required_days, goal_hash (hash of the GoalPlan JSON), participant_count, settled_count, total_forfeit, total_success_stake, status (Open, Active, Settling, Settled, Voided).
- **Participation** (PDA per pool+user): user, stake, tz_offset_minutes, checkin_bitmap (u64), days_completed, status (Active, Succeeded, Failed, Claimed), settled flag, device_key_hash (the registered device key).

Practice mode is offchain only (no program interaction).

### 4.2 Instructions

1. `init_config(admin, oracle, fee_bps, treasury, max_stake, ...)`
2. `create_pool(params)` creates the Pool and its vault.
3. `join_pool(stake, tz_offset, device_key_hash)` transfers stake into the vault and creates Participation. Only before start_ts (or within a short join window). Enforces max stake and allowed mint.
4. `record_checkin(day_index)` signed by the oracle. Validates the pool is Active, day_index is in range, the bit is not already set, and current time is within the day's window for that user's tz offset. Sets the bit, increments days_completed.
5. `settle_participation()` permissionless, callable after end_ts + grace. Marks Succeeded if days_completed >= required_days, else Failed; adds penalty to total_forfeit or stake to total_success_stake; increments settled_count. When settled_count == participant_count, the pool becomes Settled.
6. `claim()` for the owner after the pool is Settled. Succeeded: stake back plus share of distributable forfeits. Failed: stake minus penalty returned (soft mode) or nothing (hard mode).
7. `void_pool()` admin only. Allows full refunds if the oracle failed or a bug is found. Must emit an event. Document this as a centralization trade-off.
8. `set_paused`, `update_oracle` (admin).

### 4.3 Events

Emit events for pool created, joined, check-in recorded, participation settled, pool settled, claimed, voided. The backend indexer consumes these.

### 4.4 Payout math (use integer math, u128 intermediates)

- penalty_i = stake_i * penalty_bps / 10000 for each Failed participant.
- F = sum of penalty_i. fee = F * fee_bps / 10000. D = F - fee.
- Each Succeeded participant j receives: stake_j + D * stake_j / S, where S = sum of stakes of all Succeeded participants.
- Rounding dust goes to the treasury.
- Edge cases (each needs a test): no Succeeded participants (D goes to treasury); single participant pool; all succeed (everyone just gets their stake back); soft mode with partial penalty; participant joins at the last allowed moment; duplicate check-in for the same day; check-in outside window; claim twice; settle twice; settle before end; claim before Settled; voided pool refunds.

### 4.5 Security checklist (must have tests for each)

- Signer checks: only oracle can `record_checkin`; only admin can admin instructions; only owner can `claim`.
- PDA seed and bump validation on every account; no arbitrary account substitution.
- Token checks: mint matches the pool mint; token account owner and mint verified; vault authority is the pool PDA.
- Checked arithmetic everywhere; no overflow in payout math.
- Account closing and rent handled safely; no reinitialization.
- Max stake and duration limits enforced.
- Pause switch honored by join and create.
- Fuzz or property tests for payout math: total paid out never exceeds total deposited.
- Write `docs/threat-model.md` covering oracle compromise, replay, griefing by non-settlement, and front-running on join.

---

## 5. Backend

### 5.1 Responsibilities

1. Wallet sign-in (nonce + signed message via MWA, then short-lived JWT).
2. Goal parser (LLM proxy, section 6).
3. Proof sessions and verification (section 7.4).
4. Oracle: signs and submits `record_checkin` after a proof passes.
5. Transaction builder for create/join/claim.
6. Indexer: reads program events and mirrors state into the database.
7. Squads, feed, nudges, leaderboard, coach stats.
8. Jobs: reminders, settlement crank (calls `settle_participation` for finished pools), letter triggers, cleanups.

### 5.2 API sketch (REST, JSON, versioned under /v1)

- `POST /auth/nonce`, `POST /auth/verify`
- `POST /goals/parse` (text in, GoalPlan out)
- `GET /challenges`, `GET /challenges/:id`, `POST /challenges/tx/create`, `POST /challenges/tx/join`, `POST /challenges/tx/claim`
- `POST /devices/register` (device public key + attestation chain; requires a wallet-signed registration message)
- `POST /proofs/session` (returns nonce and expiry), `POST /proofs/submit`
- `POST /squads`, `POST /squads/:id/invite`, `POST /squads/join`, `GET /squads/:id/feed`, `POST /squads/:id/nudge`
- `GET /coach/suggestions`
- `POST /letters/trigger-events` (the app reports milestone events; the backend only sends a push to say "open your letter")
- `GET /health`

### 5.3 Data model (minimum)

users, devices, challenges (mirror of onchain state plus GoalPlan JSON), proofs (hash, tier, status, session id, timestamps; no raw evidence), checkins, squads, squad_members, nudges, push_tokens, coach_stats, rate_limits.

### 5.4 Backend security

- Validate every input with a schema. Rate limit by wallet and IP. Reject oversized bodies.
- JWT secret and oracle private key only from environment or a secrets manager; never logged.
- Oracle key can only sign `record_checkin`; keep it separate from any admin key.
- Idempotency keys on proof submission and on tx building.
- Log without personal content; do not store raw goal text longer than needed for the challenge.
- Provide `.env.example` and a `docs/runbook.md` (deploy, rotate keys, pause the program).

---

## 6. AI design (free tiers and on-device only)

### 6.1 Where AI is used

| Need | Approach | Cost |
|---|---|---|
| Plain-language goal to GoalPlan | Free-tier Gemini Flash / Flash-Lite via the backend, with built-in templates as fallback | $0 |
| Camera pose reps | On-device pose model | $0 |
| Coach | Deterministic rules; optional LLM only for wording | $0 |
| Everything else | Not AI | $0 |

Verify the current Gemini free-tier models and limits in Google AI Studio before building. Limits have changed several times in 2026. Do not rely on Pro models being free. The free tier may use submitted content to improve Google's products and may exclude commercial use, so send **only the typed goal text**, and recheck the terms before any commercial launch. Confirm the key works from the developer's region.

### 6.2 GoalPlan schema (put in /shared/goal-plan.schema.json)

```
{
  "title": string,
  "category": "fitness" | "study" | "detox" | "sleep" | "steps" | "location" | "custom",
  "cadence": { "periodDays": 1, "totalDays": int (1..60), "requiredDays": int },
  "target": { "metric": string, "value": number, "unit": string, "direction": "atLeast" | "atMost" },
  "proofMethods": [ { "type": ProofType, "params": object, "trustTier": "high" | "medium" | "low" } ],
  "window": { "startLocalTime": "HH:MM", "endLocalTime": "HH:MM" } | null,
  "difficulty": 1..5,
  "verifiable": boolean,
  "unverifiableReason": string | null,
  "suggestedAlternative": string | null,
  "clarifyingQuestions": [string]
}
```

### 6.3 Rules

- The LLM output is **never trusted**. Parse JSON, validate against the schema, clamp numbers, and whitelist proof types. On any failure, fall back to templates or ask a clarifying question.
- The prompt and the model name live in backend config, not in the app. The API key never ships in the APK.
- Cache parsed plans by normalized text to save quota. Handle 429 with backoff and fall back to templates.
- Provide at least these templates: workout reps (squats, push-ups), steps per day, gym visit (geofence), focus study hours, app-usage limit, no-use window for an app, early wake (steps/motion plus unlock), walk outside, meditation timer, reading timer, hydration (self-attest, low trust), sleep window (usage-based).
- Every plan shows its trust tier and any limitations in plain language before the user stakes.

---

## 7. Proof engine

### 7.1 Proof types, APIs and trust

| Proof type | How it works | Android API | Trust |
|---|---|---|---|
| CAMERA_POSE | Count reps from joint angles on-device; random on-screen prompt as liveness check | CameraX + pose model | High |
| USAGE_LIMIT / NO_USE_WINDOW | Read foreground time of chosen apps | UsageStatsManager (user grants Usage Access) | High |
| STEPS | Step counter total for the day | Step counter sensor (ACTIVITY_RECOGNITION permission) or Health Connect | Medium |
| GEOFENCE | Be inside a radius during a window | Fused location, coarse where possible | Medium |
| FOCUS_TIMER | In-app timer that pauses if the app leaves the foreground | Foreground tracking | Medium |
| SELF_ATTEST | User taps "done" | none | Low |

Stake caps scale with the weakest trust tier in the plan (for example Low caps very small).

### 7.2 ProofPackage (put in /shared/proof-package.schema.json)

```
{
  "sessionId": string, "nonce": string, "challengeId": string, "dayIndex": int,
  "proofType": string, "metrics": object (e.g. { "reps": 32 }),
  "startedAt": ts, "endedAt": ts,
  "evidenceHash": string (hash of local evidence summary; evidence itself stays on device),
  "deviceKeyId": string, "signature": string
}
```

### 7.3 Device key

- Generate an ECDSA P-256 key in Android Keystore (StrongBox when available). Registered once per challenge via `POST /devices/register`, with the wallet signing the registration message.
- Server verifies the Keystore key attestation chain. **Verify this works on Seeker and on common phones; if attestation is unavailable, downgrade the proof trust tier and say so.**
- Every ProofPackage is signed by this key silently (no wallet prompt per check-in).
- Optional "high assurance" mode: sign through Seed Vault via MWA for a one-off proof.

### 7.4 Verification flow

1. App calls `POST /proofs/session` and receives a nonce with a short expiry.
2. App runs the proof, builds the ProofPackage, signs it with the device key.
3. `POST /proofs/submit`. Backend checks: signature and key registration, nonce unused and unexpired, timestamps inside the challenge day window, metrics plausible (for example reps per second, steps jumps), plan requirements met.
4. On pass, the oracle submits `record_checkin`. The response returns the new streak.
5. Replays and duplicates are rejected idempotently.

### 7.5 Emulator and real-device notes

- Emulator: the camera shows a simulated scene (you can load images), virtual sensors cover accelerometer and magnetometer, location can be set from the extended controls. Usage stats history and step counting are limited; pose models may not run well.
- Provide a debug-only "inject test proof" path so every flow can be exercised on the emulator. It must be impossible to enable in release builds.
- `docs/device-tests.md` lists the manual real-device tests: pose counting, usage stats, step counting, geofence, attestation, notifications.

### 7.6 Privacy

- No camera frames or video leave the device or are stored. Pose landmarks are processed in memory only.
- Location: check against the geofence on device; send only "inside/outside" plus coarse timing. Never upload a track.
- Usage stats: only the chosen packages' total foreground minutes.
- A clear permission rationale screen precedes every Android permission prompt.

---

## 8. Android app

### 8.1 Module structure

`app` (navigation, DI), `core:ui` (theme, components), `core:data` (Room, network), `core:wallet` (MWA, tx decode/check), `core:proof` (engine, providers), `feature:onboarding`, `feature:goals`, `feature:checkin`, `feature:challenge`, `feature:squads`, `feature:coach`, `feature:letters`, `feature:rewards`, `feature:settings`.

### 8.2 Screens and flows

1. **Onboarding:** value in 3 screens, connect wallet (MWA), choose Practice or real stakes, notification permission with rationale.
2. **Home:** today's goals, streak, one-tap "Check in" button, squad activity strip, pot and yield summary.
3. **New goal:** text box ("What do you want to commit to?"), live plan preview, edit target and proof method, show trust tier, choose mode (Practice / Soft / Hard), token (USDC / SKR), amount, review screen with the decoded transaction summary, sign via MWA.
4. **Check-in:** proof-specific screen (camera counter, timer, usage check, steps, location check), success animation with haptics, streak update.
5. **Challenge detail:** calendar heatmap, days left, pot size, participants, rules, yield display (real or labeled simulated).
6. **Squads:** list, create, invite (deep link and code), feed, leaderboard, nudge.
7. **Coach:** weekly insights, success rate, suggested next goal with reasons.
8. **Letters:** write or record, choose trigger (milestone or broken streak), preview, delete.
9. **Rewards:** balances, claimable payouts, history.
10. **Settings:** permissions status, notifications, network toggle (devnet/mainnet, hidden unless debug or confirmed), privacy info, data deletion.

### 8.3 Platform features

- Home-screen widget (Glance) showing today's status and streak.
- Notifications: daily reminders (WorkManager), squad nudges (FCM), settlement and payout alerts.
- Deep links for squad invites.
- Offline: cached state, queued check-ins that retry; clear error and empty states everywhere.
- Accessibility: content descriptions, large text support, sufficient contrast. All strings in resources.

### 8.4 Design direction (UX is a quarter of the score)

- Distinct visual identity, not a default template. Strong typography, a clear dark theme and light theme, generous spacing.
- Motion that means something: streak flame animation, check-in success burst, confetti on payout, but respect reduce-motion settings.
- Haptics on check-in and on failures.
- The primary action on each screen is obvious. Keep onboarding under a minute to first check-in via Practice mode.

### 8.5 Judge-friendly modes

- **Practice mode:** the full flow with no money, so anyone can try it.
- **Demo mode (devnet):** a "Get test funds" button (devnet airdrop and test token mint through the backend), and optional pre-seeded demo data (an existing squad with history) clearly labeled as demo data.

---

## 9. Feature designs that need extra detail

### 9.1 Squads
- A squad owns shared pool creation. Members stake into one pool. Forfeits go to squad members who succeed.
- Open Pool: solo users are grouped into a weekly open pool by (category, duration, mint). If a pool has a single participant, there is no redistribution; failure forfeits go to the treasury and success returns the stake only.
- Backend feed: events (checked in, missed, joined), nudges limited to prevent spam (rate limit per sender and recipient).

### 9.2 Streaks and settlement
- Day index is computed from the user's registered tz offset, using the same function in the app, backend and program tests (one shared test vector file).

### 9.3 Adaptive coach (deterministic)
- Inputs: last 14 days of attempts, success rate per goal category, streak breaks, time-of-day patterns.
- Target success band: roughly 70-85%. Below the band, suggest a smaller target or a shorter duration. Above it for 2+ cycles, suggest a harder target. Never modify an active challenge.
- Output: a suggestion object with a reason code; wording templates in resources; optional LLM rewrite of wording only.

### 9.4 Letters
- Stored on device, encrypted with a key held in Android Keystore. Triggers: milestone reached (for example 7 days) or streak broken.
- The app evaluates triggers locally from synced state; the backend can send a push saying "you have a letter" with no content. Document that losing the phone loses the letters.
- Voice letters are short, local audio files; do not upload them.

### 9.5 Yield (F6)
- Soft mode (capped penalty) is built first and works without any yield.
- Real yield: research how to route vault deposits to a lending market (Seed Vault Wallet's USDC Earn Vault uses Kamino lending per Solana Mobile's announcement; verify the integration path and risks). If a safe integration is feasible, implement behind a `YieldStrategy` interface in the program or via a separate vault program. Disclose lending-market risk in the UI.
- If real yield cannot be done safely, show an **illustrative** yield estimate labeled "simulated" and do not credit anyone with simulated yield. Never present it as earned.

### 9.6 SKR integration (for the $10k SKR prize)
- **SKR staking integrations do not qualify for the prize** (official rule). USDC is the main deposit token. Optional SKR deposits exist but are not the pitched SKR integration.
- The SKR integration is: (1) **weekly SKR rewards for top streaks**, paid from a rewards vault, and (2) **SKR-paid perks**, for example streak freezes bought with SKR.
- Verified: mint `SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3`, 6 decimals, classic SPL Token (see `docs/verified-facts.md`). Re-verify before mainnet; use a devnet test mint on devnet.
- Rewards and perks need design in Phase 8 (funding source, anti-abuse, who signs). Do not credit simulated SKR as real.
- Document in the README and pitch exactly what SKR does in the app.

### 9.7 Open proof plug-ins (F9)
- Define the `ProofProvider` interface (Kotlin) and an `Attestation` JSON format: provider id, user wallet, metric, value, window, nonce, Ed25519 or P-256 signature, provider public key registry.
- Ship one sample provider inside the app to prove the interface. Publish the spec in `/docs/proof-provider-spec.md`. Do not claim integrations with other apps.

---

## 10. Security, privacy and compliance

- Threat model in `docs/threat-model.md` (oracle compromise, forged proofs, replay, rooted devices, malicious deep links, phishing of signing requests).
- The app decodes every transaction and verifies program id, accounts and amounts before sending it to the wallet.
- Stake caps and pool caps while the program is unaudited; no mainnet deployment without explicit user approval.
- Privacy policy and in-app data deletion. Minimum data collection. No analytics SDKs that read personal data.
- Age gate: stakes with money are for adults (18+). Practice mode for everyone.
- This is a commitment contract with the user's own funds, not a wager against a house. Keep stakes and pools small, avoid "betting" language, and note that rules differ by country. This is not legal advice; flag it for the team to review.
- Dependency hygiene: pin versions, run dependency audits, no unmaintained crypto libraries.

---

## 11. Build phases and gates (backend first, then UI)

Always update `docs/progress.md` at the end of a phase. Stop at the gate and report.

**Phase 0: Foundations.** Create the repo layout, toolchains (Android Studio, Rust, Solana CLI, Anchor, Node), CI scripts, `.env.example`, `docs/verified-facts.md` (verify everything in section 14 first), install the Mock MWA Wallet.
Gate: all three projects build; an emulator runs a hello-world app that connects to the Mock MWA Wallet.

**Phase 1: Solana program.** Implement section 4 and the full test suite including the edge cases and security checks. Deploy to devnet. Export the IDL.
Gate: all tests pass with one command; payout property test passes; devnet deployment works.

**Phase 2: Backend.** Auth, tx builder, device registration, proof sessions and verification, oracle, indexer, settlement crank, squads API, coach, push, rate limits, tests, OpenAPI doc.
Gate: an automated script creates a pool, joins with two test wallets, submits proofs, records check-ins, settles and claims on devnet.

**Phase 3: Android skeleton and wallet.** Navigation, theme, onboarding, MWA connect, sign-in, create/join with template goals, tx decode check, challenge detail. Works on the emulator with the Mock MWA Wallet.
Gate: stake-join-claim runs end to end on the emulator against devnet.

**Phase 4: Proof engine v1.** Focus timer, steps, geofence, usage limit, self-attest, device key, attestation, debug inject path.
Gate: each proof type produces a recorded onchain check-in; replay attempts are rejected.

**Phase 5: AI goal parser and camera pose proofs.** Template fallback, LLM proxy with schema validation, plan preview and edit UI; pose-based rep counting.
Gate: 10 sample goals parse correctly; invalid LLM output is rejected; pose counting works on a real phone (documented test).

**Phase 6: Squads and notifications.** Squads, invites, feed, nudges, leaderboard, FCM.
Gate: two accounts complete a squad challenge flow with live feed updates and a nudge notification.

**Phase 7: Coach, letters, widget.**
Gate: coach suggestions change with test histories; a letter is delivered on a simulated milestone and on a broken streak; the widget updates.

**Phase 8: Yield, SKR, plug-in interface.** Per sections 9.5-9.7.
Gate: soft mode works; yield is either real or clearly labeled simulated; SKR weekly reward and a SKR-paid perk work on a devnet test token; a sample provider's attestation is accepted by the backend.

**Phase 9: Polish and release.** UX pass, animations, accessibility, security review against section 4.5 and 10, demo mode, real-device test on a borrowed Android phone, signed release APK, README, demo video, deck.
Gate: section 13 checklist complete.

If scope has to be cut, cut in this order: F9 plug-ins first, then F6 real yield (keep soft mode), then F8 voice letters (keep text), then widget. Never cut tests or the Phase 9 checks.

---

## 12. Testing

- Program: unit and integration tests, payout property tests, security failure-case tests.
- Backend: unit tests, API tests, a devnet end-to-end script.
- Android: unit tests for the proof engine logic and plan parsing; instrumented UI tests for the main flows with the Mock MWA Wallet; screenshot tests optional.
- Shared test vectors for day-index computation and payout math used by the program, backend and app.
- Manual real-device checklist in `docs/device-tests.md`.

---

## 13. Submission checklist (hackathon)

### 13.1 Required by the organizers
- [ ] Android only; functional APK produced.
- [ ] Solana Mobile Stack and Mobile Wallet Adapter integrated (see 3.3); README lists the SMS components used.
- [ ] Native mobile design, no WebView/PWA wrapper; app interacts meaningfully with the Solana network.
- [ ] Registered through Radiants Align (GitHub app installed, repo shared); deadline is **Oct 12, 2026 12:59 PM GMT+1**.
- [ ] Functional Android APK, signed release build, hosted at a stable download link (for example a GitHub Release or cloud storage link). Test the link on a clean phone.
- [ ] Public GitHub repo with source, README (what it is, how to run, architecture, trust model, SKR integration, AI usage and costs), license, and no secrets in history.
- [ ] Demo video, **max 3 minutes**, recorded on a real Android phone showing the full loop (simulator-only is not acceptable).
- [ ] Pitch deck or short presentation.
- [ ] Confirm whether the form wants an uploaded file or a link and whether there are video length limits.

### 13.2 For winning and publishing
- [ ] Review the dApp Store publishing guide (https://docs.solanamobile.com/dapp-store/submit-new-app) and keep the app ready to publish after results; winners must publish to claim prizes.
- [ ] ORE prize is out of scope.

### 13.3 Demo script (about 90 seconds)
1. Hook: "Everyone fails their goals. What if failing cost something, and winning paid?"
2. Type "No TikTok after 10pm for a week" and show the AI plan with its trust tier.
3. Pick Soft stake in SKR or USDC, review the decoded transaction, sign with the wallet.
4. Show a camera proof (for example 20 squats) completing and the streak updating.
5. Show the squad feed: a friend checked in, another missed; send a nudge.
6. Fast-forward (demo data) to settlement: payout animation, then the letter from your past self.
7. Close: what makes it different (any goal, phone-native proofs, yield-backed stakes), where it goes next (plug-ins).

### 13.4 Pitch deck outline
1. Problem. 2. Solution in one line. 3. How it works (3 steps). 4. Demo screenshots. 5. Why phone-native proofs. 6. Differentiation vs existing apps (honest). 7. SKR integration. 8. Trust and security model. 9. Roadmap (plug-ins, multiple attestors). 10. Team and ask.

---

## 14. Verify before use (do not guess)

Record each answer with its source in `docs/verified-facts.md`.

1. Current MWA Kotlin client library artifacts, versions, and the recommended way to send transactions.
2. Mock MWA Wallet installation steps (https://github.com/solana-mobile/mock-mwa-wallet) and what it supports.
3. SKR token mint address, decimals, token program, and any official integration guidance (including the community SKR integration thread linked from the announcement).
4. USDC mint addresses for devnet and mainnet; how to get devnet test tokens.
5. Current Anchor version, test tooling, and token-program interface best practices.
6. Whether the Android Keystore key attestation chain is available and verifiable on Seeker and common phones.
7. Current Gemini API free-tier models, rate limits, region availability and terms.
8. Pose model choice (ML Kit Pose Detection vs MediaPipe Pose Landmarker): current status, licensing, accuracy and performance on mid-range phones.
9. Kamino or other lending integration path for vault deposits; risks and whether a program CPI is feasible.
10. dApp Store publishing requirements (signing, assets, publisher setup).
11. Free-tier limits for the chosen RPC provider, database host and FCM.
12. Hackathon portal: deadline, video and repo rules are now recorded (see 1.1). Still confirm the APK link-vs-upload field on the form.

---

## 15. Non-goals for this version

- No iOS app.
- No open "bet against a friend" market; this is not a betting product.
- No custody of user keys; no email/password accounts.
- No claim of third-party plug-in integrations unless they exist.
- No paid AI or infrastructure without explicit approval.
