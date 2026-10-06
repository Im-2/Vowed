import type { PoolAccount, ParticipationAccount } from "../program/client.js";
import type { Services } from "../services.js";

const hex = (b: Uint8Array) => Buffer.from(b).toString("hex");

export interface ChallengeRow {
  pool: string;
  creator: string;
  pool_id: string;
  mint: string;
  vault: string;
  kind: string;
  mode: string;
  penalty_bps: number;
  fee_bps: number;
  start_ts: number;
  end_ts: number;
  join_deadline_ts: number;
  settle_after_ts: number;
  duration_days: number;
  required_days: number;
  goal_hash: string;
  max_participants: number;
  participant_count: number;
  settled_count: number;
  pending_claims: number;
  total_deposits: string;
  total_forfeit: string;
  total_success_stake: string;
  distributable: string;
  status: string;
  plan_json: string | null;
  squad_id: string | null;
  updated_at: number;
}

export interface ParticipantRow {
  pool: string;
  wallet: string;
  stake: string;
  tz_offset_minutes: number;
  checkin_bitmap: string;
  days_completed: number;
  status: string;
  device_key_hash: string;
  updated_at: number;
}

export function getChallenge(s: Services, pool: string): ChallengeRow | undefined {
  return s.db.prepare("SELECT * FROM challenges WHERE pool = ?").get(pool) as ChallengeRow | undefined;
}
export function getParticipant(s: Services, pool: string, wallet: string): ParticipantRow | undefined {
  return s.db.prepare("SELECT * FROM participants WHERE pool = ? AND wallet = ?").get(pool, wallet) as ParticipantRow | undefined;
}

/** Reads the pool account from chain and mirrors it into SQLite. Chain state is the source of truth. */
export async function syncPool(s: Services, pool: string): Promise<PoolAccount | null> {
  const acc = await s.chain.getAccount(pool);
  if (!acc || acc.owner !== s.program.programId.toBase58()) return null;
  const p = s.program.decodePool(acc.data);
  const goalHash = hex(p.goal_hash);
  const now = s.wallNow();
  s.db
    .prepare(
      `INSERT INTO challenges (pool, creator, pool_id, mint, vault, kind, mode, penalty_bps, fee_bps, start_ts, end_ts, join_deadline_ts,
         settle_after_ts, duration_days, required_days, goal_hash, max_participants, participant_count, settled_count, pending_claims,
         total_deposits, total_forfeit, total_success_stake, distributable, status, updated_at)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
       ON CONFLICT(pool) DO UPDATE SET participant_count=excluded.participant_count, settled_count=excluded.settled_count,
         pending_claims=excluded.pending_claims, total_deposits=excluded.total_deposits, total_forfeit=excluded.total_forfeit,
         total_success_stake=excluded.total_success_stake, distributable=excluded.distributable, status=excluded.status,
         updated_at=excluded.updated_at`,
    )
    .run(
      pool, p.creator, p.id.toString(), p.mint, p.vault, p.kind, p.mode, p.penalty_bps, p.fee_bps,
      Number(p.start_ts), Number(p.end_ts), Number(p.join_deadline_ts), Number(p.settle_after_ts),
      p.duration_days, p.required_days, goalHash, p.max_participants, p.participant_count, p.settled_count,
      p.pending_claims, p.total_deposits.toString(), p.total_forfeit.toString(), p.total_success_stake.toString(),
      p.distributable.toString(), p.status, now,
    );
  // attach the plan the creator uploaded when building the create transaction, if the hash matches
  s.db
    .prepare(
      `UPDATE challenges SET plan_json = (SELECT plan_json FROM plans WHERE goal_hash = challenges.goal_hash AND creator = challenges.creator)
       WHERE pool = ? AND plan_json IS NULL`,
    )
    .run(pool);
  return p;
}

export async function syncParticipation(s: Services, pool: string, wallet: string): Promise<ParticipationAccount | null> {
  const acc = await s.chain.getAccount(s.program.participationPda(pool, wallet).toBase58());
  if (!acc || acc.owner !== s.program.programId.toBase58()) return null;
  const p = s.program.decodeParticipation(acc.data);
  s.db
    .prepare(
      `INSERT INTO participants (pool, wallet, stake, tz_offset_minutes, checkin_bitmap, days_completed, status, device_key_hash, updated_at)
       VALUES (?,?,?,?,?,?,?,?,?)
       ON CONFLICT(pool, wallet) DO UPDATE SET stake=excluded.stake, tz_offset_minutes=excluded.tz_offset_minutes,
         checkin_bitmap=excluded.checkin_bitmap, days_completed=excluded.days_completed, status=excluded.status, updated_at=excluded.updated_at`,
    )
    .run(pool, wallet, p.stake.toString(), p.tz_offset_minutes, p.checkin_bitmap.toString(), p.days_completed, p.status, hex(p.device_key_hash), s.wallNow());
  return p;
}

/** Re-reads every known participant of a pool (used after a void). */
export async function syncAllParticipants(s: Services, pool: string): Promise<void> {
  const rows = s.db.prepare("SELECT wallet FROM participants WHERE pool = ?").all(pool) as { wallet: string }[];
  for (const r of rows) await syncParticipation(s, pool, r.wallet);
}
