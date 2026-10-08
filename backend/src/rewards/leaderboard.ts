/**
 * Leaderboards for the Rewards screen: "this week" (the live standings that the weekly SKR payout will use) and "all time" (the longest
 * streak each person has reached). Other people appear only as a wallet (the app draws an avatar from it), a short name and a streak number.
 * A person can hide themselves; they are still paid, they just do not show up for others.
 *
 * SAMPLE rows: when LEADERBOARD_SAMPLES is on, a few made-up players fill the list so a new visitor does not see an empty screen. They are
 * flagged `sample`, are never paid, and a real person's rank and reward ignore them.
 */
import { getChallenge, type ChallengeRow, type ParticipantRow } from "../challenges/sync.js";
import { rewardsEnabled } from "../config.js";
import { dayIndexFor } from "../domain/schedule.js";
import { effectiveBitmap, frozenBitmap } from "../perks/streaks.js";
import type { Services } from "../services.js";
import { ladder, REWARDS_LABEL, standingsForWeek, weekOf, WEEK_SECONDS } from "./service.js";

export type Scope = "week" | "all";

export interface BoardEntry {
  rank: number;
  wallet: string;
  /** a chosen display name, or a short form of the wallet */
  name: string;
  streak: number;
  /** base units of test SKR the weekly payout would give this place, for a real person in the top places; null otherwise */
  reward: string | null;
  you: boolean;
  sample: boolean;
}

export interface Board {
  scope: Scope;
  label: string;
  enabled: boolean;
  weekEndsAt: number;
  minStreak: number;
  entries: BoardEntry[];
  /** this wallet own place among REAL people (null when it has no streak yet) */
  me: { rank: number | null; streak: number; rewardIfNow: string | null; hidden: boolean };
  hasSamples: boolean;
  sampleNote: string | null;
}

export const SAMPLE_NOTE = "SAMPLE: the rows marked SAMPLE are made-up players that show how the board looks. They are never paid and do not change anyone else rank or reward.";

/** Made-up players. The "wallets" only feed the avatar picker in the app; they are not real addresses. */
const SAMPLES: { name: string; week: number; all: number }[] = [
  { name: "Mira", week: 6, all: 21 },
  { name: "Jonas", week: 5, all: 14 },
  { name: "Aiko", week: 4, all: 18 },
  { name: "Tomas", week: 3, all: 9 },
  { name: "Lena", week: 3, all: 12 },
  { name: "Rui", week: 2, all: 7 },
  { name: "Priya", week: 2, all: 11 },
  { name: "Omar", week: 1, all: 5 },
];
const sampleWallet = (name: string) => `SAMPLE${name}111111111111111111111111111111`.slice(0, 44);

export const shortName = (wallet: string) => (wallet.length > 10 ? `${wallet.slice(0, 4)}…${wallet.slice(-4)}` : wallet);

/** The longest run of completed days (frozen days count) in [bitmap], over [days] days. */
export function longestStreak(bitmap: bigint, days: number): number {
  let best = 0;
  let run = 0;
  for (let d = 0; d < days; d++) {
    if (((bitmap >> BigInt(d)) & 1n) === 1n) best = Math.max(best, ++run);
    else run = 0;
  }
  return best;
}

/** The best streak ever reached by each eligible wallet (the same eligibility rules as the weekly rewards), highest first. */
export function standingsAllTime(s: Services): { wallet: string; streak: number }[] {
  const pools = s.db.prepare("SELECT pool FROM challenges WHERE status != 'Voided' AND participant_count >= 2").all() as { pool: string }[];
  const best = new Map<string, number>();
  for (const { pool } of pools) {
    const c = getChallenge(s, pool) as ChallengeRow;
    if (c.is_demo && !s.config.REWARDS_INCLUDE_DEMO) continue;
    const parts = s.db.prepare("SELECT * FROM participants WHERE pool = ?").all(pool) as unknown as ParticipantRow[];
    for (const p of parts) {
      const today = dayIndexFor(c, p.tz_offset_minutes, s.wallNow());
      if (today < 0) continue;
      const bits = effectiveBitmap(BigInt(p.checkin_bitmap), frozenBitmap(s, p.wallet, pool));
      const streak = longestStreak(bits, Math.min(today + 1, c.duration_days));
      if (streak > (best.get(p.wallet) ?? 0)) best.set(p.wallet, streak);
    }
  }
  return [...best.entries()].map(([wallet, streak]) => ({ wallet, streak })).sort((a, b) => b.streak - a.streak || a.wallet.localeCompare(b.wallet));
}

function userInfo(s: Services, wallets: string[]): Map<string, { name: string | null; hidden: boolean }> {
  const out = new Map<string, { name: string | null; hidden: boolean }>();
  const q = s.db.prepare("SELECT display_name, leaderboard_hidden FROM users WHERE wallet = ?");
  for (const w of wallets) {
    const r = q.get(w) as { display_name: string | null; leaderboard_hidden: number } | undefined;
    out.set(w, { name: r?.display_name ?? null, hidden: r?.leaderboard_hidden === 1 });
  }
  return out;
}

export function leaderboard(s: Services, wallet: string, scope: Scope, limit = 20): Board {
  const enabled = rewardsEnabled(s.config);
  const week = weekOf(s.wallNow());
  const lad = ladder(s);
  const real = (scope === "week" ? standingsForWeek(s, week, s.wallNow()).map((x) => ({ wallet: x.wallet, streak: x.streak })) : standingsAllTime(s)).filter((x) => x.streak > 0);
  const info = userInfo(s, [...real.map((x) => x.wallet), wallet]);
  const payRank = new Map(real.map((x, i) => [x.wallet, i + 1]));
  const rewardFor = (w: string, streak: number): string | null => {
    if (scope !== "week" || !enabled) return null;
    const r = payRank.get(w);
    return r !== undefined && r <= lad.length && streak >= s.config.REWARDS_MIN_STREAK ? String(lad[r - 1]) : null;
  };
  const visible = real.filter((x) => !info.get(x.wallet)!.hidden).map((x) => ({ wallet: x.wallet, streak: x.streak, name: info.get(x.wallet)!.name ?? shortName(x.wallet), sample: false }));
  const samples = s.config.LEADERBOARD_SAMPLES ? SAMPLES.map((x) => ({ wallet: sampleWallet(x.name), streak: scope === "week" ? x.week : x.all, name: x.name, sample: true })) : [];
  // fill with samples only while the board is short, so real people are never pushed off it
  const room = Math.max(0, 8 - visible.length);
  const merged = [...visible, ...samples.slice(0, room)].sort((a, b) => b.streak - a.streak || Number(a.sample) - Number(b.sample) || a.wallet.localeCompare(b.wallet));
  const entries: BoardEntry[] = merged.slice(0, limit).map((x, i) => ({
    rank: i + 1,
    wallet: x.wallet,
    name: x.name,
    streak: x.streak,
    reward: x.sample ? null : rewardFor(x.wallet, x.streak),
    you: x.wallet === wallet,
    sample: x.sample,
  }));
  const mineStreak = real.find((x) => x.wallet === wallet)?.streak ?? 0;
  return {
    scope,
    label: REWARDS_LABEL,
    enabled,
    weekEndsAt: (week + 1) * WEEK_SECONDS,
    minStreak: s.config.REWARDS_MIN_STREAK,
    entries,
    me: { rank: payRank.get(wallet) ?? null, streak: mineStreak, rewardIfNow: rewardFor(wallet, mineStreak), hidden: info.get(wallet)!.hidden },
    hasSamples: entries.some((e) => e.sample),
    sampleNote: entries.some((e) => e.sample) ? SAMPLE_NOTE : null,
  };
}

export function setLeaderboardHidden(s: Services, wallet: string, hidden: boolean): void {
  s.db
    .prepare("INSERT INTO users (wallet, created_at, leaderboard_hidden) VALUES (?, ?, ?) ON CONFLICT(wallet) DO UPDATE SET leaderboard_hidden = excluded.leaderboard_hidden")
    .run(wallet, s.wallNow(), hidden ? 1 : 0);
}
