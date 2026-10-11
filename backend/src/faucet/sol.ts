/**
 * Devnet SOL for testers (0.03 SOL a gift) from a dedicated small wallet, so a tester can pay network fees and account rent without a public faucet.
 * The wallet is funded by hand from the deployer. Rules, all enforced here:
 *  - a wallet gets a first gift, and a top-up once it is nearly empty again, never more than [PER_WALLET_LIMIT] in total (0.06 SOL);
 *  - a gift is never bigger than [GIFT_LIMIT] (0.03 SOL), whatever the environment says: these are the limits the project owner approved;
 *  - a wait between two gifts to the same wallet, a cap on gifts per UTC day and a cap on the total ever given out;
 *  - nothing for a wallet that already holds enough SOL;
 *  - the fixed amount is the operator choice, never the caller;
 *  - the gift is reserved in the database BEFORE sending (one pending gift per wallet), so two clicks cannot both pass.
 */
import { PublicKey, SystemProgram } from "@solana/web3.js";
import { signAndSend } from "../chain/tx.js";
import type { Services } from "../services.js";

export type SolStatus = "sent" | "skipped" | "failed" | "off";
export interface SolDrip {
  status: SolStatus;
  /** lamports sent (or that would be sent) */
  lamports: string;
  reason?: string;
  signature?: string;
}

/** The faucet wallet must keep this much for the transaction fee. */
const FEE_RESERVE = 10_000n;
/** The most one gift can be and the most one wallet can receive in total: the limits approved by the project owner, applied whatever the settings say. */
export const GIFT_LIMIT = 30_000_000n;
export const PER_WALLET_LIMIT = 60_000_000n;
const utcDay = (ts: number) => Math.floor(ts / 86_400);
const min = (a: bigint, b: bigint) => (a < b ? a : b);

export const solFaucetEnabled = (s: Services) => Boolean(s.config.FAUCET_SOL_SECRET_KEY);

export function solFaucetAddress(s: Services): string | null {
  return s.config.FAUCET_SOL_SECRET_KEY ? s.config.FAUCET_SOL_SECRET_KEY.publicKey.toBase58() : null;
}

export const giftLamports = (s: Services) => min(s.config.FAUCET_SOL_LAMPORTS, GIFT_LIMIT);
const walletLimit = (s: Services) => min(s.config.FAUCET_SOL_PER_WALLET_MAX_LAMPORTS, PER_WALLET_LIMIT);

/** What this wallet has received so far (gifts sent or being sent, including the one-per-wallet gifts of the first version) and when the last one was. */
export function solReceived(s: Services, wallet: string): { lamports: bigint; lastAt: number } {
  const legacy = s.db.prepare("SELECT lamports, created_at FROM sol_drips WHERE wallet = ? AND status IN ('pending','sent')").get(wallet) as { lamports: string; created_at: number } | undefined;
  const ev = s.db.prepare("SELECT COALESCE(SUM(CAST(lamports AS INTEGER)), 0) AS total, COALESCE(MAX(created_at), 0) AS last FROM sol_gift_events WHERE wallet = ? AND status IN ('pending','sent')").get(wallet) as { total: number; last: number };
  return { lamports: BigInt(ev.total) + (legacy ? BigInt(legacy.lamports) : 0n), lastAt: Math.max(ev.last, legacy?.created_at ?? 0) };
}

/** Has this wallet had any gift? */
export function solAlreadyGiven(s: Services, wallet: string): boolean {
  return solReceived(s, wallet).lamports > 0n;
}

function totals(s: Services, day: number) {
  const t1 = s.db.prepare("SELECT COALESCE(SUM(CAST(lamports AS INTEGER)), 0) AS total FROM sol_drips WHERE status IN ('pending','sent')").get() as { total: number };
  const t2 = s.db.prepare("SELECT COALESCE(SUM(CAST(lamports AS INTEGER)), 0) AS total FROM sol_gift_events WHERE status IN ('pending','sent')").get() as { total: number };
  const d1 = s.db.prepare("SELECT COUNT(*) AS n FROM sol_drips WHERE day = ? AND status IN ('pending','sent')").get(day) as { n: number };
  const d2 = s.db.prepare("SELECT COUNT(*) AS n FROM sol_gift_events WHERE day = ? AND status IN ('pending','sent')").get(day) as { n: number };
  return { total: BigInt(t1.total) + BigInt(t2.total), today: d1.n + d2.n };
}

/**
 * The rules that need only the database: may this wallet get a gift at all right now? [balance] is the wallet's SOL (lamports) when known.
 * Returns null when yes, otherwise the reason.
 */
export function solEligibility(s: Services, wallet: string, balance: bigint | null): string | null {
  if (!solFaucetEnabled(s)) return "off";
  const c = s.config;
  const gift = giftLamports(s);
  const { lamports: got, lastAt } = solReceived(s, wallet);
  const now = s.wallNow();
  if (got + gift > walletLimit(s)) return "wallet_limit";
  if (got > 0n) {
    if (now - lastAt < c.FAUCET_SOL_TOPUP_WAIT_SECS) return "too_soon";
    if (balance !== null && balance >= c.FAUCET_SOL_TOPUP_BELOW_LAMPORTS) return "balance_ok";
  } else if (balance !== null && balance >= c.FAUCET_SOL_SKIP_ABOVE_LAMPORTS) {
    return "already_has_sol";
  }
  const { total, today } = totals(s, utcDay(now));
  if (today >= c.FAUCET_SOL_DAILY_CAP) return "daily_cap";
  if (total + gift > c.FAUCET_SOL_TOTAL_CAP_LAMPORTS) return "total_cap";
  return null;
}

/** Sends a gift if every rule allows it. Never throws: the token claim must not fail because of SOL. */
export async function dripSol(s: Services, wallet: string): Promise<SolDrip> {
  const lamports = giftLamports(s);
  const out = (status: SolStatus, reason?: string, signature?: string): SolDrip => ({ status, lamports: lamports.toString(), ...(reason ? { reason } : {}), ...(signature ? { signature } : {}) });
  if (!solFaucetEnabled(s)) return out("off", "the SOL faucet is not switched on on this server");
  const key = s.config.FAUCET_SOL_SECRET_KEY!;
  const now = s.wallNow();
  const day = utcDay(now);
  let balance: bigint | null = null;
  try {
    const have = await s.chain.getAccount(wallet);
    balance = have ? have.lamports : 0n;
    const bank = await s.chain.getAccount(key.publicKey.toBase58());
    if (!bank || bank.lamports < lamports + FEE_RESERVE) return out("failed", "sol_faucet_empty");
  } catch {
    return out("failed", "chain_unavailable");
  }
  const why = solEligibility(s, wallet, balance);
  if (why) return out("skipped", why === "wallet_limit" ? "already_received" : why);
  // reserve: one pending gift per wallet (a partial unique index), so a second request that got here at the same time is turned away
  let id: number;
  try {
    id = Number(
      (s.db.prepare("INSERT INTO sol_gift_events (wallet, lamports, status, day, created_at) VALUES (?,?, 'pending', ?, ?)").run(wallet, lamports.toString(), day, now) as { lastInsertRowid: number | bigint }).lastInsertRowid,
    );
  } catch {
    return out("skipped", "already_in_progress");
  }
  try {
    const res = await signAndSend(s.chain, [SystemProgram.transfer({ fromPubkey: key.publicKey, toPubkey: new PublicKey(wallet), lamports })], [key]);
    s.db.prepare("UPDATE sol_gift_events SET status = 'sent', signature = ? WHERE id = ?").run(res.signature, id);
    return out("sent", undefined, res.signature);
  } catch (e) {
    s.db.prepare("UPDATE sol_gift_events SET status = 'failed', error = ? WHERE id = ?").run((e instanceof Error ? e.message : "send failed").slice(0, 200), id);
    return out("failed", "send_failed");
  }
}
