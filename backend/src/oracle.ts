import { ChainError } from "./chain/types.js";
import { signAndSend } from "./chain/tx.js";
import { syncParticipation } from "./challenges/sync.js";
import { processLogs } from "./indexer.js";
import type { Services } from "./services.js";

export interface CheckinOutcome {
  status: "confirmed" | "pending" | "failed";
  signature?: string;
  error?: string;
}

/** Program errors that will never succeed on retry. */
const PERMANENT = new Set([
  "OutsideCheckinWindow",
  "ParticipationNotActive",
  "InvalidPoolStatus",
  "DayOutOfRange",
  "Paused",
  "WrongPool",
  "Unauthorized",
]);

const inflight = new Map<string, Promise<CheckinOutcome>>();

/**
 * Signs and submits record_checkin with the oracle key. Idempotent per (pool, wallet, day): concurrent calls share one
 * transaction and a day the program already has is reported as confirmed.
 */
export function submitCheckin(s: Services, pool: string, wallet: string, day: number): Promise<CheckinOutcome> {
  const k = `${pool}:${wallet}:${day}`;
  const existing = inflight.get(k);
  if (existing) return existing;
  const p = doSubmit(s, pool, wallet, day).finally(() => inflight.delete(k));
  inflight.set(k, p);
  return p;
}

async function doSubmit(s: Services, pool: string, wallet: string, day: number): Promise<CheckinOutcome> {
  const now = s.wallNow();
  s.db
    .prepare(
      `INSERT INTO checkins (pool, wallet, day_index, status, attempts, created_at) VALUES (?,?,?, 'pending', 0, ?)
       ON CONFLICT(pool, wallet, day_index) DO NOTHING`,
    )
    .run(pool, wallet, day, now);
  const row = s.db.prepare("SELECT status, tx_sig FROM checkins WHERE pool=? AND wallet=? AND day_index=?").get(pool, wallet, day) as { status: string; tx_sig: string | null };
  if (row.status === "confirmed") return { status: "confirmed", signature: row.tx_sig ?? undefined };

  s.db.prepare("UPDATE checkins SET attempts = attempts + 1 WHERE pool=? AND wallet=? AND day_index=?").run(pool, wallet, day);
  try {
    const res = await signAndSend(s.chain, [s.program.ixRecordCheckin(s.oracle.publicKey, pool, wallet, day)], [s.oracle]);
    s.db.prepare("UPDATE checkins SET status='confirmed', tx_sig=?, last_error=NULL WHERE pool=? AND wallet=? AND day_index=?").run(res.signature, pool, wallet, day);
    await processLogs(s, res.signature, res.logs);
    return { status: "confirmed", signature: res.signature };
  } catch (e) {
    const code = e instanceof ChainError ? e.anchorError?.code : undefined;
    if (code === "DuplicateCheckin") {
      s.db.prepare("UPDATE checkins SET status='confirmed', last_error=NULL WHERE pool=? AND wallet=? AND day_index=?").run(pool, wallet, day);
      await syncParticipation(s, pool, wallet);
      return { status: "confirmed" };
    }
    const msg = code ?? (e instanceof Error ? e.message.slice(0, 200) : "unknown error");
    if (code && PERMANENT.has(code)) {
      s.db.prepare("UPDATE checkins SET status='failed', last_error=? WHERE pool=? AND wallet=? AND day_index=?").run(msg, pool, wallet, day);
      return { status: "failed", error: msg };
    }
    s.db.prepare("UPDATE checkins SET last_error=? WHERE pool=? AND wallet=? AND day_index=?").run(msg, pool, wallet, day);
    return { status: "pending", error: msg };
  }
}

/** Retries check-ins that passed verification but could not be sent (RPC outage etc.). */
export async function retryPending(s: Services, maxAttempts = 20): Promise<number> {
  const rows = s.db
    .prepare("SELECT pool, wallet, day_index FROM checkins WHERE status = 'pending' AND attempts < ? ORDER BY created_at LIMIT 50")
    .all(maxAttempts) as { pool: string; wallet: string; day_index: number }[];
  let done = 0;
  for (const r of rows) {
    const out = await submitCheckin(s, r.pool, r.wallet, r.day_index);
    if (out.status === "confirmed") done++;
  }
  return done;
}
