import { getChallenge, getParticipant, syncAllParticipants, syncParticipation, syncPool } from "./challenges/sync.js";
import { kvGet, kvSet } from "./db.js";
import { dayIndexFor } from "./domain/schedule.js";
import { currentStreak } from "./domain/time.js";
import type { Services } from "./services.js";
import { addFeed } from "./squads/feed.js";

type Ev = { name: string; data: Record<string, unknown> };

/**
 * Turns program events into database state. Handlers are idempotent because they re-read chain state,
 * and a processed-signature table stops feed entries from being duplicated.
 */
export async function processLogs(s: Services, signature: string, logs: readonly string[]): Promise<number> {
  const seen = s.db.prepare("SELECT 1 FROM processed_txs WHERE signature = ?").get(signature);
  if (seen) return 0;
  const events = s.program.decodeEvents(logs);
  for (const ev of events) await handle(s, ev);
  s.db.prepare("INSERT OR IGNORE INTO processed_txs (signature, processed_at) VALUES (?, ?)").run(signature, s.wallNow());
  return events.length;
}

async function handle(s: Services, ev: Ev): Promise<void> {
  const d = ev.data;
  const pool = d.pool as string | undefined;
  const user = d.user as string | undefined;
  switch (ev.name) {
    case "PoolCreated":
      await syncPool(s, pool!);
      break;
    case "PoolJoined": {
      await syncPool(s, pool!);
      await syncParticipation(s, pool!, user!);
      const c = getChallenge(s, pool!);
      if (c?.squad_id) addFeed(s, c.squad_id, pool!, user!, "joined", { stake: String(d.stake) });
      break;
    }
    case "CheckinRecorded": {
      await syncParticipation(s, pool!, user!);
      const c = getChallenge(s, pool!);
      const p = getParticipant(s, pool!, user!);
      if (c?.squad_id && p) {
        const today = dayIndexFor(c, p.tz_offset_minutes, s.now());
        addFeed(s, c.squad_id, pool!, user!, "checked_in", {
          day: Number(d.day_index),
          streak: currentStreak(BigInt(p.checkin_bitmap), today, c.duration_days),
        });
      }
      break;
    }
    case "ParticipationSettled": {
      await syncPool(s, pool!);
      await syncParticipation(s, pool!, user!);
      recordCoachStats(s, pool!, user!);
      const c = getChallenge(s, pool!);
      if (c?.squad_id) addFeed(s, c.squad_id, pool!, user!, "settled", { succeeded: d.succeeded === true });
      break;
    }
    case "PoolSettled":
    case "TreasurySwept":
      await syncPool(s, pool!);
      break;
    case "Claimed":
      await syncPool(s, pool!);
      await syncParticipation(s, pool!, user!);
      break;
    case "PoolVoided":
      await syncPool(s, pool!);
      await syncAllParticipants(s, pool!);
      break;
    default:
      break; // config events: nothing to mirror
  }
}

function recordCoachStats(s: Services, pool: string, wallet: string): void {
  const c = getChallenge(s, pool);
  const p = getParticipant(s, pool, wallet);
  if (!c || !p || c.is_demo) return; // demo pools are not habit history
  let category = "custom";
  let difficulty = 3;
  if (c.plan_json) {
    try {
      const plan = JSON.parse(c.plan_json) as { category: string; difficulty: number };
      category = plan.category;
      difficulty = plan.difficulty;
    } catch {
      /* keep defaults */
    }
  }
  s.db
    .prepare(
      `INSERT OR REPLACE INTO coach_stats (wallet, pool, category, difficulty, required_days, duration_days, days_completed, outcome, finished_at)
       VALUES (?,?,?,?,?,?,?,?,?)`,
    )
    .run(wallet, pool, category, difficulty, c.required_days, c.duration_days, p.days_completed, p.status === "Failed" ? "failed" : "succeeded", s.wallNow());
}

/** Polls the chain for program transactions we have not processed yet, oldest first. */
export async function pollOnce(s: Services): Promise<number> {
  const programId = s.program.programId.toBase58();
  const last = kvGet(s.db, "indexer:last_signature");
  const sigs = await s.chain.getSignaturesForAddress(programId, { until: last, limit: 200 });
  let processed = 0;
  for (const sig of [...sigs].reverse()) {
    if (sig.failed) continue;
    const logs = await s.chain.getTransactionLogs(sig.signature);
    if (logs) processed += await processLogs(s, sig.signature, logs);
  }
  if (sigs.length > 0) kvSet(s.db, "indexer:last_signature", sigs[0]!.signature);
  return processed;
}
