# Threat model

Scope: the Vowed Anchor program (Phase 1) and what it assumes about the oracle, backend and clients.
Later phases extend this file (device attestation, deep links, signing-request phishing). Nothing here is audited.

## What the program guarantees

- Funds sit in a per-pool token account whose authority is the pool PDA. Only `claim` (to the participant's own token account)
  and `sweep_treasury` (to the treasury's token account) move money out, and both are checked.
- Total paid out never exceeds total deposited: `claim` enforces `paid_out <= total_deposits`, and payout math uses u128 intermediates
  with checked conversions. A property test checks conservation for random stakes, outcomes, penalties and fees, both on pure math and
  on-chain in LiteSVM.
- Users can always get out. `settle_participation` and `claim` are not blocked by pause, and settlement is permissionless.
- Config can only be initialised by the program's upgrade authority, so nobody can front-run the deployer.

## Trust assumptions (the honest part)

| Party | Can do | Cannot do |
|---|---|---|
| **Oracle key** | Record a check-in for any participant on any day inside that day's window (so it can fake or withhold successes). | Move funds, change rules, void pools, settle early, record outside the window, record a day twice. |
| **Admin key** | Pause new pools/joins, rotate the oracle, void a pool **before any payout**. | Take or redirect stakes, settle early, change a live pool's penalty/fee (snapshotted), void after payouts began. |
| **Upgrade authority** | Replace the program code. This is the biggest power in the system. | n/a. **On devnet it is a throwaway key. Before any mainnet use it must move to a multisig with a timelock, or the program must be made immutable.** |
| **Treasury** | Receive fees, hard-mode forfeits nobody can claim, and rounding dust. | Touch anything while claims are pending. |

## Threats and mitigations

1. **Oracle compromise.** A stolen or malicious oracle can mark failing users as succeeded (and vice versa), skewing payouts. Mitigations:
   the oracle cannot move funds; check-ins are bounded to the day window; `update_oracle` rotates the key; `void_pool` lets the admin refund
   everyone if fraud is found before settlement; per-pool participant and per-user stake caps limit the blast radius while unaudited.
   Roadmap (not built): multiple attestors, squad dispute voting, timelocked oracle rotation.
2. **Forged or replayed proofs.** Handled off-chain (nonce sessions, device-key signatures, attestation; SPEC 7). Onchain, each day can be
   recorded only once (bitmap), so a replay cannot add a second day.
3. **Griefing by non-settlement.** If nobody settles, claims never open. `settle_participation` is permissionless, so any user, friend or
   crank can do it; there is no deadline after which it stops working. A participant cannot block others by staying silent.
4. **Griefing by not claiming.** A winner who never claims blocks `sweep_treasury` (fee and dust stay in the vault). Funds stay safe and
   claimable. Hard-mode losers have nothing to claim and are excluded from the pending count, so they cannot block the sweep.
5. **Front-running on join.** Pool PDAs include the creator and a creator-chosen id, so nobody can squat another creator's pool.
   The join window is public, and a user's stake size is visible before others join; joining later does not change the payout rules, only
   the pool size. A late joiner can see who is ahead but check-ins are oracle-gated, so this gives no edge. Residual risk: whales joining a
   pool they expect others to fail; limited by `max_stake` and `max_participants`.
6. **Account substitution.** Every account is checked by PDA seeds and stored bumps (config, pool, vault, participation), pool/participation
   linkage, mint equality, token-account owner and mint, and vault address. Tests try wrong pool, wrong user, wrong vault, wrong mint,
   wrong treasury account and a missing signer.
7. **Malicious or odd mints.** Only mints on the admin-curated allow list can be used, they must be owned by the token program passed
   in, and mints longer than the plain 82 bytes (Token-2022 extensions such as transfer fees) are rejected, because a fee-on-transfer token
   would break the vault accounting. This extension check is a one-line size test; it is not exercised by an integration test yet (see
   open items).
8. **Rounding and dust.** Winners' shares round down; the remainder goes to the treasury at sweep. Fees are capped at 10%.
9. **Time manipulation.** The program uses the cluster clock only. Day boundaries use the participant's registered timezone offset
   (limited to UTC-12..UTC+14) fixed at join time; check-ins have a 2h grace after local midnight; settlement waits `settle_grace_secs`
   (up to 2 days) past the end so late check-ins and timezone spread are covered and check-in and settlement windows never overlap.
10. **Reinitialisation and closing.** All `init` accounts use Anchor's `init` (fails if the account exists). The program never closes accounts
    in v1, so rent stays locked in finished pools (a documented cost, not a risk to funds).
11. **Denial of service by size.** Pools are limited to 1,000 participants and 60 days; a participation is a fixed-size account.

## Known limitations / open items

- Token-2022 extension rejection is by account size and has no integration test. Add one when a Token-2022 mint with extensions can be
  created cheaply in LiteSVM (Phase 8 hardening).
- No account closing, so rent is not reclaimed.
- `void_pool` is a centralisation trade-off by design; it is disabled once a pool is fully settled.
- Unaudited. Keep stake and pool caps small; no mainnet deployment without explicit approval.
- Wallet-side risks (phishing of signing requests, malicious deep links) are covered when the Android app exists (Phase 3 and 9).


---

# Backend (Phase 2)

Scope: the TypeScript API, oracle, indexer, crank and push. Written against the code in `backend/src`; tests are named in brackets.

## Trust boundaries

- **Wallet** proves identity by signing a Sign-In With Solana message with a server nonce (single use, 5 minutes, bound to the wallet and domain) [auth.test]. The token is an HS256 JWT valid for 1 hour.
- **Device key** (Android Keystore, P-256) proves a proof package came from the phone the stake committed to. The stake commits to `sha256(device public key)` onchain at join time; the backend only accepts proofs signed by that key, registered to that wallet [lifecycle, proof-verify].
- **Backend** decides whether a proof satisfies the plan, then the **oracle key** records the day onchain. The program trusts the oracle completely for check-ins (see the program threat model above).
- **Chain state is the source of truth.** The SQLite mirror is rebuilt by re-reading accounts after each event; request bodies never feed it.

## Threats and mitigations

| Threat | Mitigation | Residual risk |
|---|---|---|
| Replay of an old proof | Per-session nonce signed into the package; session single-use, 5 min TTL, bound to wallet, pool, day and type; the program also rejects a second check-in for a day | none known |
| Resubmission or double send | Accepted sessions return the stored result; the oracle sends once per (pool, wallet, day), shares in-flight calls and treats `DuplicateCheckin` as success; sessions are claimed atomically while processing [race test and mutation] | none known |
| Forged proof from a modified app | Signature by the registered hardware-backed key; key attestation chain verified on the server against Google roots (by public key), challenge-bound, extension only in the leaf, revocation list; trust cap by security level and boot state | A rooted or modified phone with an honest-looking key can still lie about sensor data. Mitigated only by plausibility limits, trust tiers and stake caps. Stated openly in the pitch |
| Attestation unavailable (emulator, odd ROM) | Accepted only when `REQUIRE_ATTESTATION=false`, and capped at **low** trust. Never silently upgraded | Real-device behaviour on Seeker and common phones is untested until `docs/device-tests.md` is run |
| Fabricated metrics | Per-type plausibility (rep rate, steps per day, focus time within session length, dwell within session), unit conversion, direction checks, required liveness flag | Limits catch absurd values, not clever ones |
| Stolen JWT | 1 h expiry; per-wallet rate limits; tokens grant API access only, never signing power (funds always need a wallet signature) | Token theft exposes that wallet's data for up to 1 h |
| Malicious transaction from the API (compromised backend) | The app must decode every returned transaction and check program id, accounts and amounts before the wallet signs (SPEC 3.1). The API returns unsigned transactions plus a summary; summaries are never trusted by the app | Depends on the Android implementation (Phase 3) |
| Enumeration or scraping of goals | Squad pools are unlisted: listed only to creator, participants and squad members; fetching by address works (the address is the invite). Open pools are public by design. Joining a squad-linked pool requires membership | Pool accounts themselves are public onchain (no goal text, only a hash) |
| Invite code guessing | 8 characters from a 30-letter alphabet (about 6.5e11 codes), 20 attempts per wallet per hour, codes rotate on request | low |
| Nudge spam | 1 per pair per 6 h, 10 per sender per day, 5 per recipient per day, only for people with an open check-in | low |
| Oracle key compromise or outage | Key can only call `record_checkin`; rotation via `update_oracle`; accepted proofs queue and retry; `void_pool` for fraud before settlement | An attacker with the key can mark days done for anyone until it is rotated |
| Crank key compromise | Holds no authority; can only pay fees for permissionless instructions | Loses its SOL balance |
| Direct onchain joins bypass the API | The program enforces only global limits (max stake per participant, participants per pool). The per-trust-tier stake cap, plan hash linkage and device registration are backend rules | A direct joiner has no registered device, so cannot prove and will forfeit; no risk to others. Per-pool onchain caps are a possible later hardening |
| Denial of service | 64 KB body limit, schema validation on every input, SQLite rate limits per IP (300/min) and per wallet (240/min) plus stricter per-endpoint limits, bounded open sessions | Limits are per instance; add a shared limiter if scaled out |
| Secrets in logs or errors | Config errors never echo values; error responses carry codes and short messages; raw evidence is never stored (hash only); goal text is stored only as the structured plan | Operators must keep request logging free of bodies |
| Dependency risk | Exact version pins; `@solana/spl-token` and `@anchor-lang/core` removed because of advisories; the remaining advisories are inside web3.js 1.x RPC JSON handling, reachable only through our own configured RPC | Re-audit each release (see runbook) |

## Privacy

Camera frames, sensor streams and raw location never reach the backend (the API has no field for them). Stored per proof: type, trust tier, evidence hash, local hour of day (for the coach), accept or reject reason. Stored per goal: the structured GoalPlan. Push messages never contain goal text or wallet addresses.
