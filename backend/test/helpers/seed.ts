import { Keypair } from "@solana/web3.js";
import { canonicalJson, planHash, type GoalPlan } from "../../src/domain/plan.js";
import type { World } from "./world.js";
import { samplePlan } from "./world.js";

export interface SeedChallenge {
  pool: string;
  creator: string;
  startTs: number;
  durationDays: number;
  status: string;
  planHash: string;
}

/** Inserts a mirrored challenge row directly (for tests that do not need the chain). */
export function seedChallenge(w: World, over: Partial<{ pool: string; creator: string; startTs: number; durationDays: number; requiredDays: number; status: string; squadId: string | null; plan: GoalPlan | null }> = {}): SeedChallenge {
  const pool = over.pool ?? Keypair.generate().publicKey.toBase58();
  const creator = over.creator ?? Keypair.generate().publicKey.toBase58();
  const startTs = over.startTs ?? 1_799_971_200;
  const durationDays = over.durationDays ?? 3;
  const plan = over.plan === undefined ? samplePlan({ cadence: { periodDays: 1, totalDays: durationDays, requiredDays: over.requiredDays ?? 2 } }) : over.plan;
  const hash = plan ? planHash(plan) : "0".repeat(64);
  w.s.db
    .prepare(
      `INSERT INTO challenges (pool, creator, pool_id, mint, vault, kind, mode, penalty_bps, fee_bps, start_ts, end_ts, join_deadline_ts, settle_after_ts,
         duration_days, required_days, goal_hash, max_participants, participant_count, settled_count, pending_claims, total_deposits, total_forfeit,
         total_success_stake, distributable, status, plan_json, squad_id, updated_at)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
    )
    .run(
      pool, creator, "1", Keypair.generate().publicKey.toBase58(), Keypair.generate().publicKey.toBase58(), "Squad", "Hard", 10_000, 0,
      startTs, startTs + durationDays * 86_400, startTs + 3_600, startTs + durationDays * 86_400 + 7_200, durationDays, over.requiredDays ?? 2, hash, 50,
      0, 0, 0, "0", "0", "0", "0", over.status ?? "Open", plan ? canonicalJson(plan) : null, over.squadId ?? null, w.s.wallNow(),
    );
  return { pool, creator, startTs, durationDays, status: over.status ?? "Open", planHash: hash };
}

export function seedParticipant(w: World, pool: string, wallet: string, over: Partial<{ tz: number; bitmap: bigint; days: number; status: string; deviceId: string; stake: string }> = {}) {
  w.s.db
    .prepare(
      `INSERT OR REPLACE INTO participants (pool, wallet, stake, tz_offset_minutes, checkin_bitmap, days_completed, status, device_key_hash, updated_at)
       VALUES (?,?,?,?,?,?,?,?,?)`,
    )
    .run(pool, wallet, over.stake ?? "10000000", over.tz ?? 0, (over.bitmap ?? 0n).toString(), over.days ?? 0, over.status ?? "Active", over.deviceId ?? "d".repeat(64), w.s.wallNow());
}
