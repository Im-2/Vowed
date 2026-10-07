/**
 * Streak freezes. A freeze is bought with SKR and marks ONE missed, already finished day of ONE challenge as "frozen" so that it does not
 * break the person's STREAK. It never touches the onchain check-ins, the days completed, the payout, or the settlement: the program
 * pays by days completed and nothing here changes that. Frozen days count only where Vowed shows or rewards a streak (the Today screen, the
 * squad leaderboard, the weekly SKR rewards, the coach).
 */
import type { Services } from "../services.js";

/** Bitmap of the frozen days for one person in one challenge. */
export function frozenBitmap(s: Services, wallet: string, pool: string): bigint {
  const rows = s.db.prepare("SELECT day_index FROM freezes WHERE wallet = ? AND pool = ?").all(wallet, pool) as { day_index: number }[];
  let bits = 0n;
  for (const r of rows) bits |= 1n << BigInt(r.day_index);
  return bits;
}

/** Done days plus frozen days: what a streak is counted over. */
export const effectiveBitmap = (done: bigint, frozen: bigint): bigint => done | frozen;
