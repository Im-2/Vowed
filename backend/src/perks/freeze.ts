/**
 * The SKR perk: a streak freeze (see streaks.ts). The person pays SKR (a plain token transfer to the rewards wallet, signed in their own
 * wallet after the phone has checked the transaction), then the server confirms that exact payment on chain and records the freeze.
 * One payment buys one freeze, once: the payment signature is unique. Nothing is credited before the payment is confirmed.
 */
import { PublicKey, Transaction } from "@solana/web3.js";
import { rewardsEnabled } from "../config.js";
import { getChallenge, getParticipant } from "../challenges/sync.js";
import { dayIndexFor, windowFor } from "../domain/schedule.js";
import { ApiError, badRequest, conflict, notFound } from "../errors.js";
import type { Services } from "../services.js";
import { ataAddress, ixCreateAtaIdempotent, ixTransferChecked } from "../util/token.js";
import { REWARD_DECIMALS } from "../rewards/service.js";

/** A day can be frozen for this many days after it closes (a demo pool counts its own short days). */
export const FREEZE_LOOKBACK_DAYS = 3;

export function assertPerksEnabled(s: Services): void {
  if (!rewardsEnabled(s.config)) throw new ApiError(404, "perks_disabled", "SKR perks are not available on this server");
}

/** Why this day cannot be frozen, or null when it can. */
export function freezeProblem(s: Services, wallet: string, pool: string, day: number): string | null {
  const c = getChallenge(s, pool);
  if (!c) return "unknown challenge";
  if (c.status !== "Open") return "only a challenge that is still running can have a freeze";
  const p = getParticipant(s, pool, wallet);
  if (!p || p.status !== "Active") return "you are not an active player in this challenge";
  const now = s.now();
  const today = dayIndexFor(c, p.tz_offset_minutes, now);
  if (!Number.isInteger(day) || day < 0 || day >= c.duration_days) return "that day is not part of this challenge";
  if (day >= today) return "a freeze is for a day that is already over";
  if (now < windowFor(c, p.tz_offset_minutes, day).closesAt) return "that day can still be checked in; do that instead";
  if (today - day > FREEZE_LOOKBACK_DAYS) return `a freeze must be used within ${FREEZE_LOOKBACK_DAYS} days of the missed day`;
  if (((BigInt(p.checkin_bitmap) >> BigInt(day)) & 1n) === 1n) return "you already checked in that day";
  if (s.db.prepare("SELECT 1 FROM freezes WHERE wallet = ? AND pool = ? AND day_index = ?").get(wallet, pool, day)) return "that day is already frozen";
  const n = (s.db.prepare("SELECT COUNT(*) AS n FROM freezes WHERE wallet = ? AND pool = ?").get(wallet, pool) as { n: number }).n;
  if (n >= s.config.PERK_FREEZE_MAX_PER_POOL) return `at most ${s.config.PERK_FREEZE_MAX_PER_POOL} freezes per challenge`;
  return null;
}

export interface FreezeQuote {
  price: string;
  mint: string;
  /** the wallet that receives the payment */
  payee: string;
  payeeToken: string;
  decimals: number;
  label: string;
  /** unsigned transaction: create the payee's token account if needed, then TransferChecked */
  transaction: string;
}

export async function buildFreezeTx(s: Services, wallet: string, pool: string, day: number): Promise<FreezeQuote> {
  assertPerksEnabled(s);
  const problem = freezeProblem(s, wallet, pool, day);
  if (problem) throw badRequest("freeze_not_allowed", problem);
  const mint = new PublicKey(s.config.FAUCET_SKR_MINT!);
  const payee = s.config.REWARDS_SECRET_KEY!.publicKey;
  const owner = new PublicKey(wallet);
  const price = s.config.PERK_FREEZE_PRICE;
  const src = ataAddress(owner, mint);
  const acc = await s.chain.getAccount(src.toBase58());
  if (!acc) throw badRequest("no_skr", "you have no SKR yet (the Today screen has the test-token button on devnet)");
  const dest = ataAddress(payee, mint);
  const bh = await s.chain.latestBlockhash();
  const tx = new Transaction({ feePayer: owner, blockhash: bh.blockhash, lastValidBlockHeight: bh.lastValidBlockHeight });
  tx.add(ixCreateAtaIdempotent(owner, payee, mint), ixTransferChecked(src, mint, dest, owner, price, REWARD_DECIMALS));
  return {
    price: price.toString(),
    mint: mint.toBase58(),
    payee: payee.toBase58(),
    payeeToken: dest.toBase58(),
    decimals: REWARD_DECIMALS,
    label: "TEST SKR: this payment uses a test token on devnet. It has no value.",
    transaction: tx.serialize({ requireAllSignatures: false, verifySignatures: false }).toString("base64"),
  };
}

/** Confirms the payment on chain and records the freeze. */
export async function redeemFreeze(s: Services, wallet: string, pool: string, day: number, signature: string): Promise<{ pool: string; dayIndex: number }> {
  assertPerksEnabled(s);
  if (s.db.prepare("SELECT 1 FROM freezes WHERE signature = ?").get(signature)) throw conflict("payment_used", "this payment already bought a freeze");
  const problem = freezeProblem(s, wallet, pool, day);
  if (problem) throw badRequest("freeze_not_allowed", problem);
  const transfers = await s.chain.getTokenTransfers(signature);
  if (!transfers) throw notFound("confirmed payment (wait a few seconds after signing and try again)");
  const mint = s.config.FAUCET_SKR_MINT!;
  const dest = ataAddress(s.config.REWARDS_SECRET_KEY!.publicKey, mint).toBase58();
  const paid = transfers.find((t) => t.mint === mint && t.destination === dest && t.authority === wallet && t.amount >= s.config.PERK_FREEZE_PRICE);
  if (!paid) throw badRequest("payment_not_found", "that transaction does not contain the required SKR payment from your wallet");
  try {
    s.db.prepare("INSERT INTO freezes (wallet, pool, day_index, signature, amount, created_at) VALUES (?,?,?,?,?,?)").run(wallet, pool, day, signature, paid.amount.toString(), s.wallNow());
  } catch {
    throw conflict("payment_used", "this payment or this day is already recorded");
  }
  return { pool, dayIndex: day };
}
