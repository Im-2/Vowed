# Runbook

How to run, deploy and operate the Vowed backend and program. Devnet only until the project owner approves anything else.

## Run locally

```powershell
cd backend
npm ci
copy .env.example .env      # then fill JWT_SECRET, ORACLE_SECRET_KEY, CRANK_SECRET_KEY (throwaway devnet keys)
npm run dev                 # tsx watch; or: npm run build && npm start
```

No hosted database or account is needed: SQLite (`node:sqlite`, built into Node 24) lives in `DATABASE_PATH`. Set `RUN_JOBS=true` to run the indexer,
oracle retries, settlement crank and daily jobs in the same process. API docs: `docs/openapi.json` (regenerate with `npm run openapi`),
also served at `GET /v1/openapi.json`.

## Tests

| What | Command | Notes |
|---|---|---|
| Everything, including the real program in an in-process VM | `wsl -d Ubuntu -u root -- bash /mnt/c/Users/hp/Vowed/scripts/backend-test.sh` | Needs `scripts/program-build.sh` first. 89 tests. |
| Fast logic tests on Windows | `cd backend; npm test` | The 13 tests that need the VM are skipped (LiteSVM for Node has no Windows binary). |
| Mutation sanity check | `... bash scripts/backend-mutation-check.sh` | Breaks 10 guards one at a time (signature, nonce binding, replay, stake cap, auth nonce, squad membership, wallet signature, attestation challenge, day window, in-flight race); every one must be CAUGHT. |
| Whole repo | `powershell scripts/check-all.ps1` | Backend (Windows and WSL), Android build, program tests. |

## Devnet end-to-end gate

```powershell
cd backend
npm run gate:devnet -- --plan    # prints which wallets need how much devnet SOL; sends nothing
npm run gate:devnet -- --fund    # tops them up FROM THE DEPLOYER KEY (never a faucet)
npm run gate:devnet              # stage A now; run again after the printed time for stage B
```

Throwaway keys and state live in `backend/.devnet/` (gitignored). The script is resumable and never calls a faucet.
Settlement can only open about 26 hours after a pool starts (24h day + 2h settle grace), hence two stages.

## Keys

| Key | File / variable | Power | Funding |
|---|---|---|---|
| Upgrade authority and admin | `backend/.devnet/deployer.json` (copy of the WSL deployer) | Replace the program, `init_config`, pause, rotate oracle, void pools | Devnet SOL from the faucet website, by hand |
| Oracle | `ORACLE_SECRET_KEY` | Only `record_checkin` | A few cents of SOL for fees |
| Crank | `CRANK_SECRET_KEY` | Nothing; pays fees for `settle_participation` and `sweep_treasury` | A few cents of SOL for fees and the treasury token account rent |
| Treasury | `backend/.devnet/treasury.json` | Receives fees/forfeits; holds no SOL itself | none |

Never put keys in git, the APK or logs. `.env` files and `.devnet/` are gitignored. The config loader never echoes secret values in errors.

### Rotate the oracle key
1. Generate a new keypair, fund it with a little SOL.
2. As admin: `update_oracle(new_oracle)` (the program rejects the old key immediately).
3. Put the new key in `ORACLE_SECRET_KEY` and restart. Pending check-ins are retried by the oracle-retry job.

### Pause everything
Admin signs `set_paused(true)`: new pools and joins stop; check-ins, settlement and claims keep working so users can always get out.
`set_paused(false)` resumes.

### Void a pool (fraud or bug found, before any payout)
Admin signs `void_pool(pool)`. Everyone can then claim their full stake. Not possible after the pool is Settled.

### Replace the upgrade authority
Before any mainnet use: move the upgrade authority to a multisig with a timelock (or make the program immutable). Not done; devnet only.

## Deploy the backend

1. Any Node 24 host. Set the environment from `backend/.env.example`; `NETWORK=devnet` until approved.
2. Persist `DATABASE_PATH` on a volume (SQLite is single-writer; run one instance).
3. Behind a reverse proxy set `trustProxy` in `src/http/app.ts` so rate limits see client IPs, and terminate TLS at the proxy.
4. `RUN_JOBS=true` on exactly one instance.
5. Health: `GET /v1/health`. Program state: `GET /v1/meta`.

A hosted database, hosted RPC key or Firebase project is **not** required yet. Tell the project owner before any of those are introduced.

## Dependency audit

`npm audit --omit=dev` is clean of anything we actually exercise except advisories inside the Solana JavaScript stack
(`@solana/web3.js` 1.x pulls `jayson` -> `stream-json` and `uuid`): they matter only if the configured RPC server sends hostile JSON, and we only
talk to the RPC URL we configure. We removed `@solana/spl-token` (high-severity `bigint-buffer`) and `@anchor-lang/core` (`toml`) and wrote the few
token helpers and a Borsh codec ourselves. Re-run the audit before every release.

## Free-tier facts to re-check before launch

See `docs/verified-facts.md`: Gemini free tier uses submitted content to improve Google products (only goal text is ever sent, in Phase 5);
public devnet RPC is rate limited; FCM is free but needs a Firebase project.


## Phase 4 commands

- Proof-types gate on devnet (about 8 minutes, needs alice funded; each demo pool costs about 0.005 SOL of rent): `cd backend; npm run gate:proofs`
- Play a friend in a pool the app created (test wallet bob, no check-ins unless `--prove 0,1`): `cd backend; npm run demo:friend -- <pool> --stake 2`
- Dev backend for the emulator: `powershell scripts/dev-backend.ps1` (run it as a background task with the longest timeout; it stops when the timeout ends)
- Emulator helpers: `scripts/emu-lib.ps1` (dot-source; `Reset-And-Connect`, `Tap-Text`, `Sign-Reviewed`). The Mock wallet's key needs a recent PIN: if wallet requests start failing after a long idle, run `Reset-And-Connect`.


## Test-token faucet

- Enabled when the backend environment has `FAUCET_AUTHORITY_SECRET_KEY` (the mint authority of both test mints), `FAUCET_USDC_MINT` and `FAUCET_SKR_MINT`. `scripts/dev-backend.ps1` sets them from `backend/.devnet` for local runs. **Devnet SOL gift.** With `FAUCET_SOL_SECRET_KEY` set (a dedicated small wallet, never a public faucet), the first claim of each wallet also sends `FAUCET_SOL_LAMPORTS` (default 0.01 SOL) for network fees, once per wallet. Caps: `FAUCET_SOL_DAILY_CAP` gifts per UTC day (30), `FAUCET_SOL_TOTAL_CAP_LAMPORTS` ever (0.5 SOL), and nothing for a wallet that already holds `FAUCET_SOL_SKIP_ABOVE_LAMPORTS` (0.05 SOL). The wallet is created and funded by `cd backend; npx tsx scripts/sol-faucet-setup.ts --fund 0.1 --env` (moves SOL from the deployer; `--env` writes the Render line to the gitignored `backend/.devnet/render-env-sol-faucet.txt`). Expected use: 0.01 SOL per new tester, at most 0.5 SOL in total; when the wallet is empty the token claim still works and the app says the SOL could not be sent. Top up by sending devnet SOL to its address (`npx tsx scripts/sol-faucet-setup.ts` prints it).
Amounts and limits: `FAUCET_USDC_AMOUNT`, `FAUCET_SKR_AMOUNT` (base units), `FAUCET_COOLDOWN_SECS` (default 86400), `FAUCET_GLOBAL_DAILY_CLAIMS` (default 200).
- The authority wallet pays the token-account rent for new wallets (about 0.004 SOL for two accounts) and must keep at least 0.01 SOL; below that the faucet answers 503 `faucet_unfunded`.
- Every claim is a row in `faucet_claims` (status pending, sent or failed; only pending and sent count toward limits).
- Tests: `cd backend; npx vitest run test/faucet.test.ts`.
