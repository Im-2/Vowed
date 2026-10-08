/**
 * Devnet SOL for new testers (about 0.01 SOL each) from a dedicated small wallet, so a tester can pay network fees without a public faucet.
 * The wallet is funded by hand from the deployer. Rules, all enforced here:
 *  - once per wallet (a failed send does not use it up);
 *  - a cap on gifts per UTC day and a cap on the total ever given out;
 *  - nothing for a wallet that already holds some SOL;
 *  - the fixed amount is the operator choice, never the caller;
 *  - the gift is reserved in the database BEFORE sending, so two clicks cannot both pass.
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
const utcDay = (ts: number) => Math.floor(ts / 86_400);

export const solFaucetEnabled = (s: Services) => Boolean(s.config.FAUCET_SOL_SECRET_KEY);

export function solFaucetAddress(s: Services): string | null {
  return s.config.FAUCET_SOL_SECRET_KEY ? s.config.FAUCET_SOL_SECRET_KEY.publicKey.toBase58() : null;
}

/** Has this wallet already had its gift? */
export function solAlreadyGiven(s: Services, wallet: string): boolean {
  const r = s.db.prepare("SELECT status FROM sol_drips WHERE wallet = ?").get(wallet) as { status: string } | undefined;
  return r !== undefined && r.status !== "failed";
}

function totals(s: Services, day: number) {
  const t = s.db.prepare("SELECT COALESCE(SUM(CAST(lamports AS INTEGER)), 0) AS total FROM sol_drips WHERE status IN ('pending','sent')").get() as { total: number };
  const d = s.db.prepare("SELECT COUNT(*) AS n FROM sol_drips WHERE day = ? AND status IN ('pending','sent')").get(day) as { n: number };
  return { total: BigInt(t.total), today: d.n };
}

/** Sends the gift if every rule allows it. Never throws: the token claim must not fail because of SOL. */
export async function dripSol(s: Services, wallet: string): Promise<SolDrip> {
  const c = s.config;
  const lamports = c.FAUCET_SOL_LAMPORTS;
  const out = (status: SolStatus, reason?: string, signature?: string): SolDrip => ({ status, lamports: lamports.toString(), ...(reason ? { reason } : {}), ...(signature ? { signature } : {}) });
  if (!solFaucetEnabled(s)) return out("off", "the SOL faucet is not switched on on this server");
  const key = c.FAUCET_SOL_SECRET_KEY!;
  const now = s.wallNow();
  const day = utcDay(now);
  if (solAlreadyGiven(s, wallet)) return out("skipped", "already_received");
  const { total, today } = totals(s, day);
  if (today >= c.FAUCET_SOL_DAILY_CAP) return out("skipped", "daily_cap");
  if (total + lamports > c.FAUCET_SOL_TOTAL_CAP_LAMPORTS) return out("skipped", "total_cap");
  try {
    const have = await s.chain.getAccount(wallet);
    if (have && have.lamports >= c.FAUCET_SOL_SKIP_ABOVE_LAMPORTS) return out("skipped", "already_has_sol");
    const bank = await s.chain.getAccount(key.publicKey.toBase58());
    if (!bank || bank.lamports < lamports + FEE_RESERVE) return out("failed", "sol_faucet_empty");
  } catch {
    return out("failed", "chain_unavailable");
  }
  // reserve (a failed earlier try is taken over; anything else already there means another request got in first)
  const reserved = s.db
    .prepare("INSERT INTO sol_drips (wallet, lamports, status, day, created_at) VALUES (?,?, 'pending', ?, ?) ON CONFLICT(wallet) DO UPDATE SET status = 'pending', error = NULL, day = excluded.day, created_at = excluded.created_at WHERE sol_drips.status = 'failed'")
    .run(wallet, lamports.toString(), day, now);
  if (Number(reserved.changes) !== 1) return out("skipped", "already_received");
  try {
    const res = await signAndSend(s.chain, [SystemProgram.transfer({ fromPubkey: key.publicKey, toPubkey: new PublicKey(wallet), lamports })], [key]);
    s.db.prepare("UPDATE sol_drips SET status = 'sent', signature = ? WHERE wallet = ?").run(res.signature, wallet);
    return out("sent", undefined, res.signature);
  } catch (e) {
    s.db.prepare("UPDATE sol_drips SET status = 'failed', error = ? WHERE wallet = ?").run((e instanceof Error ? e.message : "send failed").slice(0, 200), wallet);
    return out("failed", "send_failed");
  }
}
