import type { ChallengeRow, ParticipantRow } from "../challenges/sync.js";
import { dayIndex, windowBounds } from "../domain/time.js";
import { pushToWallet } from "../push/service.js";
import type { Services } from "../services.js";
import { addFeed } from "../squads/feed.js";

/**
 * Adds a "missed" feed entry for every closed day without a check-in (squad challenges only), once per (pool, wallet, day).
 * Returns the number of entries added.
 */
export function recordMissedDays(s: Services): number {
  const now = s.now();
  const challenges = s.db.prepare("SELECT * FROM challenges WHERE status = 'Open' AND squad_id IS NOT NULL AND start_ts <= ?").all(now) as unknown as ChallengeRow[];
  let added = 0;
  for (const c of challenges) {
    const parts = s.db.prepare("SELECT * FROM participants WHERE pool = ? AND status = 'Active'").all(c.pool) as unknown as ParticipantRow[];
    for (const p of parts) {
      const bitmap = BigInt(p.checkin_bitmap);
      for (let d = 0; d < c.duration_days; d++) {
        const { closesAt } = windowBounds(c.start_ts, p.tz_offset_minutes, d);
        if (now < closesAt) break; // later days are still open
        if (((bitmap >> BigInt(d)) & 1n) === 1n) continue;
        const key = `missed:${c.pool}:${p.wallet}:${d}`;
        const done = s.db.prepare("SELECT 1 FROM kv WHERE k = ?").get(key);
        if (done) continue;
        s.db.prepare("INSERT INTO kv (k, v) VALUES (?, '1')").run(key);
        addFeed(s, c.squad_id!, c.pool, p.wallet, "missed", { day: d });
        added++;
      }
    }
  }
  return added;
}

/**
 * Evening reminder: for each active participant whose local time is past `hour` and who has not checked in today,
 * send one push per (pool, wallet, day). Contains no goal text.
 */
export async function sendReminders(s: Services, hour = 18): Promise<number> {
  const now = s.now();
  const challenges = s.db.prepare("SELECT * FROM challenges WHERE status = 'Open' AND start_ts <= ? AND end_ts > ?").all(now, now) as unknown as ChallengeRow[];
  let sent = 0;
  for (const c of challenges) {
    const parts = s.db.prepare("SELECT * FROM participants WHERE pool = ? AND status = 'Active'").all(c.pool) as unknown as ParticipantRow[];
    for (const p of parts) {
      const d = dayIndex(now, c.start_ts, p.tz_offset_minutes);
      if (d < 0 || d >= c.duration_days) continue;
      const localHour = new Date((now + p.tz_offset_minutes * 60) * 1000).getUTCHours();
      if (localHour < hour) continue;
      if (((BigInt(p.checkin_bitmap) >> BigInt(d)) & 1n) === 1n) continue;
      const key = `reminded:${c.pool}:${p.wallet}:${d}`;
      if (s.db.prepare("SELECT 1 FROM kv WHERE k = ?").get(key)) continue;
      s.db.prepare("INSERT INTO kv (k, v) VALUES (?, '1')").run(key);
      sent += await pushToWallet(s, p.wallet, { title: "Today's check-in", body: "You have not checked in yet today.", data: { type: "reminder", pool: c.pool } });
    }
  }
  return sent;
}
