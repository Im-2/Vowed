/**
 * Test-token faucet: sends a small fixed amount of our own TEST USDC and TEST SKR to a signed-in wallet so testers can try the app on
 * devnet without an outside faucet. These tokens have no value and exist only on test networks.
 *
 * Limits (all enforced here, on the server):
 *  - once per wallet per cooldown window (default 24 hours, rolling);
 *  - a global cap on claims per UTC day;
 *  - fixed amounts chosen by the operator, never by the caller;
 *  - the faucet refuses to run without its key and mints, and stops when its fee wallet is nearly empty.
 * A claim is reserved in the database BEFORE anything is sent, so two simultaneous requests cannot both pass, and a failed send
 * releases the reservation so the tester can try again.
 */
import { PublicKey } from "@solana/web3.js";
import { faucetEnabled } from "../config.js";
import { signAndSend } from "../chain/tx.js";
import { ApiError } from "../errors.js";
import type { Services } from "../services.js";
import { ataAddress, ixCreateAtaIdempotent, ixMintTo, tokenAmount } from "../util/token.js";

export const FAUCET_DECIMALS = 6;
/** The authority wallet must keep at least this much SOL for fees and token-account rent (about two new accounts per claim). */
export const FAUCET_MIN_AUTHORITY_LAMPORTS = 10_000_000n;

export interface FaucetToken {
  symbol: "tUSDC" | "tSKR";
  name: string;
  mint: string;
  /** base units handed out per claim */
  amount: string;
  decimals: number;
}

export interface FaucetStatus {
  enabled: boolean;
  network: string;
  /** A plain-language label the app must show next to these tokens. */
  label: string;
  tokens: FaucetToken[];
  /** Can this wallet claim right now? */
  canClaim: boolean;
  /** unix seconds when this wallet may claim again (0 when it can claim now) */
  nextClaimAt: number;
  /** claims still available to everyone today */
  claimsLeftToday: number;
  balances: { tUSDC: string; tSKR: string };
}

export const FAUCET_LABEL = "TEST TOKENS: tUSDC and tSKR exist only on Solana devnet and have no real value.";

const utcDay = (ts: number) => Math.floor(ts / 86_400);
const nextUtcMidnight = (ts: number) => (utcDay(ts) + 1) * 86_400;

function tokens(s: Services): FaucetToken[] {
  const c = s.config;
  return [
    { symbol: "tUSDC", name: "Test USDC", mint: c.FAUCET_USDC_MINT!, amount: c.FAUCET_USDC_AMOUNT.toString(), decimals: FAUCET_DECIMALS },
    { symbol: "tSKR", name: "Test SKR", mint: c.FAUCET_SKR_MINT!, amount: c.FAUCET_SKR_AMOUNT.toString(), decimals: FAUCET_DECIMALS },
  ];
}

async function balanceOf(s: Services, wallet: string, mint: string): Promise<string> {
  try {
    const acc = await s.chain.getAccount(ataAddress(wallet, mint).toBase58());
    return acc ? tokenAmount(acc.data).toString() : "0";
  } catch {
    return "0";
  }
}

interface ClaimRow {
  created_at: number;
}

/** The earliest time this wallet may claim again, or 0. Failed claims never count. */
function nextClaimAtFor(s: Services, wallet: string): number {
  const last = s.db
    .prepare("SELECT created_at FROM faucet_claims WHERE wallet = ? AND status IN ('pending','sent') ORDER BY created_at DESC LIMIT 1")
    .get(wallet) as ClaimRow | undefined;
  if (!last) return 0;
  const next = last.created_at + s.config.FAUCET_COOLDOWN_SECS;
  return next > s.wallNow() ? next : 0;
}

function claimsToday(s: Services): number {
  const r = s.db.prepare("SELECT COUNT(*) AS n FROM faucet_claims WHERE day = ? AND status IN ('pending','sent')").get(utcDay(s.wallNow())) as { n: number };
  return r.n;
}

export async function faucetStatus(s: Services, wallet: string): Promise<FaucetStatus> {
  const enabled = faucetEnabled(s.config);
  if (!enabled) {
    return { enabled: false, network: s.config.NETWORK, label: FAUCET_LABEL, tokens: [], canClaim: false, nextClaimAt: 0, claimsLeftToday: 0, balances: { tUSDC: "0", tSKR: "0" } };
  }
  const next = nextClaimAtFor(s, wallet);
  const left = Math.max(0, s.config.FAUCET_GLOBAL_DAILY_CLAIMS - claimsToday(s));
  const [a, b] = await Promise.all([balanceOf(s, wallet, s.config.FAUCET_USDC_MINT!), balanceOf(s, wallet, s.config.FAUCET_SKR_MINT!)]);
  return {
    enabled: true,
    network: s.config.NETWORK,
    label: FAUCET_LABEL,
    tokens: tokens(s),
    canClaim: next === 0 && left > 0,
    nextClaimAt: next,
    claimsLeftToday: left,
    balances: { tUSDC: a, tSKR: b },
  };
}

export interface ClaimResult {
  signature: string;
  minted: { tUSDC: string; tSKR: string };
  nextClaimAt: number;
  label: string;
}

export async function claimTestTokens(s: Services, wallet: string): Promise<ClaimResult> {
  if (!faucetEnabled(s.config)) throw new ApiError(404, "faucet_disabled", "the test-token faucet is not available on this server");
  const c = s.config;
  const authority = c.FAUCET_AUTHORITY_SECRET_KEY!;
  new PublicKey(wallet); // throws on a malformed address (auth already guarantees a valid one)

  // 1. reserve the claim atomically (node:sqlite is synchronous, so no other request can interleave inside this block)
  const now = s.wallNow();
  const next = nextClaimAtFor(s, wallet);
  if (next > 0) {
    throw new ApiError(429, "faucet_cooldown", `you already claimed test tokens; you can claim again in ${Math.ceil((next - now) / 60)} minute(s)`, { nextClaimAt: next, retryAfterSec: next - now });
  }
  if (claimsToday(s) >= c.FAUCET_GLOBAL_DAILY_CLAIMS) {
    const reset = nextUtcMidnight(now);
    throw new ApiError(429, "faucet_daily_cap", "the faucet has given out its test tokens for today; please try again tomorrow (UTC)", { nextClaimAt: reset, retryAfterSec: reset - now });
  }
  const id = Number(
    (s.db
      .prepare("INSERT INTO faucet_claims (wallet, created_at, day, status, usdc, skr) VALUES (?,?,?, 'pending', ?, ?)")
      .run(wallet, now, utcDay(now), c.FAUCET_USDC_AMOUNT.toString(), c.FAUCET_SKR_AMOUNT.toString()) as { lastInsertRowid: number | bigint }).lastInsertRowid,
  );
  const fail = (status: number, code: string, msg: string, err?: unknown): never => {
    s.db.prepare("UPDATE faucet_claims SET status = 'failed', error = ? WHERE id = ?").run(`${code}: ${err instanceof Error ? err.message : String(err ?? msg)}`.slice(0, 300), id);
    throw new ApiError(status, code, msg);
  };

  // 2. is the faucet's own fee wallet funded?
  try {
    const acc = await s.chain.getAccount(authority.publicKey.toBase58());
    if (!acc || acc.lamports < FAUCET_MIN_AUTHORITY_LAMPORTS) fail(503, "faucet_unfunded", "the faucet is out of fee funds; please tell the project team");
  } catch (e) {
    if (e instanceof ApiError) throw e;
    fail(502, "chain_unavailable", "could not reach the Solana network; please try again", e);
  }

  // 3. one transaction: create the wallet's token accounts if needed, then mint the fixed amounts
  const usdc = new PublicKey(c.FAUCET_USDC_MINT!);
  const skr = new PublicKey(c.FAUCET_SKR_MINT!);
  const owner = new PublicKey(wallet);
  try {
    const res = await signAndSend(
      s.chain,
      [
        ixCreateAtaIdempotent(authority.publicKey, owner, usdc),
        ixCreateAtaIdempotent(authority.publicKey, owner, skr),
        ixMintTo(usdc, ataAddress(owner, usdc), authority.publicKey, c.FAUCET_USDC_AMOUNT, FAUCET_DECIMALS),
        ixMintTo(skr, ataAddress(owner, skr), authority.publicKey, c.FAUCET_SKR_AMOUNT, FAUCET_DECIMALS),
      ],
      [authority],
    );
    s.db.prepare("UPDATE faucet_claims SET status = 'sent', signature = ? WHERE id = ?").run(res.signature, id);
    return {
      signature: res.signature,
      minted: { tUSDC: c.FAUCET_USDC_AMOUNT.toString(), tSKR: c.FAUCET_SKR_AMOUNT.toString() },
      nextClaimAt: now + c.FAUCET_COOLDOWN_SECS,
      label: FAUCET_LABEL,
    };
  } catch (e) {
    return fail(502, "faucet_failed", "the faucet could not send tokens right now; please try again", e);
  }
}
