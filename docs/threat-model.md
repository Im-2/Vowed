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
