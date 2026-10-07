/**
 * Weekly SKR rewards for the top streaks (SPEC 9.6). On devnet the token is our own test SKR (no value). Paid by plain token transfers from
 * a dedicated rewards wallet; nothing here touches the Vowed program, stakes or payouts.
 *
 * Who is eligible, and the anti-abuse rules (all enforced here):
 *  - the streak is counted over check-ins that the program recorded (oracle-verified proofs) plus streak freezes the person bought;
 *  - only challenges that were running during that week, not voided;
 *  - demo pools (minutes-long days) do not count unless the operator turns that on for a demo of this feature;
 *  - the challenge must have at least two participants (a pool of one cannot farm rewards);
 *  - a minimum streak, one reward per wallet per week, a fixed ladder of amounts, and an idempotent payout (rerunning pays nobody twice).
 * Limits that remain: a person can run two wallets in one challenge. The weekly total is capped by the ladder, so the cost of that is small.
 */
import { PublicKey } from "@solana/web3.js";
import { signAndSend } from "../chain/tx.js";
import { getChallenge, type ChallengeRow, type ParticipantRow } from "../challenges/sync.js";
import { rewardsEnabled } from "../config.js";
import { dayIndexFor } from "../domain/schedule.js";
import { currentStreak } from "../domain/time.js";
import { frozenBitmap, effectiveBitmap } from "../perks/streaks.js";
import type { Services } from "../services.js";
import { ataAddress, ixCreateAtaIdempotent, ixTransferChecked, tokenAmount } from "../util/token.js";

export const WEEK_SECONDS = 604_800;
export const REWARD_DECIMALS = 6;
export const REWARDS_LABEL = "TEST SKR: weekly rewards are paid in a test token on devnet. It has no value.";
/** The rewards wallet must keep this much SOL for fees and token-account rent. */
export const REWARDS_MIN_LAMPORTS = 5_000_000n;

export const weekOf = (ts: number) => Math.floor(ts / WEEK_SECONDS);

export interface Standing {
  wallet: string;
  streak: number;
  daysCompleted: number;
  pool: string;
}

export function ladder(s: Services): bigint[] {
  return s.config.REWARDS_AMOUNTS.split(",").map((x) => BigInt(x.trim())).filter((x) => x > 0n);
}

/** The best streak of every eligible wallet for [week], highest first. */
export function standingsForWeek(s: Services, week: number): Standing[] {
  const weekStart = week * WEEK_SECONDS;
  const weekEnd = weekStart + WEEK_SECONDS;
  const pools = s.db.prepare("SELECT pool FROM challenges WHERE status != 'Voided' AND start_ts < ? AND end_ts > ? AND participant_count >= 2").all(weekEnd, weekStart) as { pool: string }[];
  const best = new Map<string, Standing>();
  for (const { pool } of pools) {
    const c = getChallenge(s, pool) as ChallengeRow;
    if (c.is_demo && !s.config.REWARDS_INCLUDE_DEMO) continue;
    const parts = s.db.prepare("SELECT * FROM participants WHERE pool = ?").all(pool) as unknown as ParticipantRow[];
    for (const p of parts) {
      const today = dayIndexFor(c, p.tz_offset_minutes, weekEnd - 1);
      if (today < 0) continue;
      const bits = effectiveBitmap(BigInt(p.checkin_bitmap), frozenBitmap(s, p.wallet, pool));
      const streak = currentStreak(bits, today, c.duration_days);
      const cur = best.get(p.wallet);
      if (!cur || streak > cur.streak || (streak === cur.streak && p.days_completed > cur.daysCompleted)) best.set(p.wallet, { wallet: p.wallet, streak, daysCompleted: p.days_completed, pool });
    }
  }
  return [...best.values()].sort((a, b) => b.streak - a.streak || b.daysCompleted - a.daysCompleted || a.wallet.localeCompare(b.wallet));
}

export interface PayoutSummary {
  week: number;
  paid: { wallet: string; rank: number; amount: string; signature: string }[];
  skipped: string[];
  errors: string[];
}

let running = false;

/** Pays the ladder for the last COMPLETED week (or [forWeek] in tests). Safe to run any number of times. */
export async function runWeeklyRewards(s: Services, forWeek?: number): Promise<PayoutSummary> {
  const out: PayoutSummary = { week: 0, paid: [], skipped: [], errors: [] };
  if (!rewardsEnabled(s.config)) {
    out.skipped.push("rewards are not configured on this server");
    return out;
  }
  const week = forWeek ?? weekOf(s.wallNow()) - 1;
  out.week = week;
  if (week >= weekOf(s.wallNow())) {
    out.skipped.push("that week is not over yet");
    return out;
  }
  if (running) {
    out.skipped.push("a payout is already running");
    return out;
  }
  running = true;
  try {
    const key = s.config.REWARDS_SECRET_KEY!;
    const mint = new PublicKey(s.config.FAUCET_SKR_MINT!);
    const winners = standingsForWeek(s, week).filter((w) => w.streak >= s.config.REWARDS_MIN_STREAK).slice(0, ladder(s).length);
    for (const [i, w] of winners.entries()) {
      const amount = ladder(s)[i]!;
      const existing = s.db.prepare("SELECT status FROM rewards WHERE week = ? AND wallet = ?").get(week, w.wallet) as { status: string } | undefined;
      if (existing && existing.status !== "failed") {
        out.skipped.push(`${w.wallet}: already ${existing.status}`);
        continue;
      }
      s.db
        .prepare("INSERT INTO rewards (week, wallet, rank, streak, amount, status, created_at) VALUES (?,?,?,?,?,'pending',?) ON CONFLICT(week, wallet) DO UPDATE SET status = 'pending', error = NULL, rank = excluded.rank, streak = excluded.streak, amount = excluded.amount")
        .run(week, w.wallet, i + 1, w.streak, amount.toString(), s.wallNow());
      try {
        const sol = await s.chain.getAccount(key.publicKey.toBase58());
        if (!sol || sol.lamports < REWARDS_MIN_LAMPORTS) throw new Error("the rewards wallet is low on SOL");
        const vault = ataAddress(key.publicKey, mint);
        const vaultAcc = await s.chain.getAccount(vault.toBase58());
        if (!vaultAcc || tokenAmount(vaultAcc.data) < amount) throw new Error("the rewards vault holds too little SKR");
        const sent = await signAndSend(
          s.chain,
          [ixCreateAtaIdempotent(key.publicKey, w.wallet, mint), ixTransferChecked(vault, mint, ataAddress(w.wallet, mint), key.publicKey, amount, REWARD_DECIMALS)],
          [key],
        );
        s.db.prepare("UPDATE rewards SET status = 'sent', signature = ? WHERE week = ? AND wallet = ?").run(sent.signature, week, w.wallet);
        out.paid.push({ wallet: w.wallet, rank: i + 1, amount: amount.toString(), signature: sent.signature });
      } catch (e) {
        const msg = e instanceof Error ? e.message : "send failed";
        s.db.prepare("UPDATE rewards SET status = 'failed', error = ? WHERE week = ? AND wallet = ?").run(msg.slice(0, 200), week, w.wallet);
        out.errors.push(`${w.wallet}: ${msg}`);
      }
    }
    return out;
  } finally {
    running = false;
  }
}

export interface RewardsStatus {
  enabled: boolean;
  label: string;
  includesDemoPools: boolean;
  minStreak: number;
  ladder: string[];
  /** the week now running, and when it ends (unix seconds) */
  currentWeek: number;
  weekEndsAt: number;
  /** live standings for the running week, best first (not paid until the week ends) */
  standings: { rank: number; wallet: string; streak: number; you: boolean; qualifies: boolean }[];
  mine: { week: number; rank: number; streak: number; amount: string; status: string; signature: string | null }[];
}

export function rewardsStatus(s: Services, wallet: string): RewardsStatus {
  const week = weekOf(s.wallNow());
  const enabled = rewardsEnabled(s.config);
  const lad = ladder(s);
  const standings = enabled ? standingsForWeek(s, week).slice(0, 10) : [];
  const mine = s.db.prepare("SELECT week, rank, streak, amount, status, signature FROM rewards WHERE wallet = ? ORDER BY week DESC LIMIT 12").all(wallet) as RewardsStatus["mine"];
  return {
    enabled,
    label: REWARDS_LABEL,
    includesDemoPools: s.config.REWARDS_INCLUDE_DEMO,
    minStreak: s.config.REWARDS_MIN_STREAK,
    ladder: lad.map(String),
    currentWeek: week,
    weekEndsAt: (week + 1) * WEEK_SECONDS,
    standings: standings.map((x, i) => ({ rank: i + 1, wallet: x.wallet, streak: x.streak, you: x.wallet === wallet, qualifies: x.streak >= s.config.REWARDS_MIN_STREAK && i < lad.length })),
    mine,
  };
}
