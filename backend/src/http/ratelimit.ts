import type { Services } from "../services.js";
import { tooMany } from "../errors.js";

/** Fixed-window counter in SQLite. Returns the count after this hit. */
export function hit(s: Services, key: string, windowSec: number): { count: number; retryAfter: number } {
  const now = s.wallNow();
  const windowStart = Math.floor(now / windowSec) * windowSec;
  const row = s.db
    .prepare(
      `INSERT INTO rate_limits (key, window_start, count) VALUES (?, ?, 1)
       ON CONFLICT(key, window_start) DO UPDATE SET count = count + 1 RETURNING count`,
    )
    .get(key, windowStart) as { count: number };
  return { count: row.count, retryAfter: Math.max(1, windowStart + windowSec - now) };
}

/** Throws a 429 when `key` has been hit more than `limit` times in the current window. */
export function enforce(s: Services, key: string, limit: number, windowSec: number): void {
  const { count, retryAfter } = hit(s, key, windowSec);
  if (count > limit) throw tooMany(retryAfter);
}

/** Housekeeping: drop windows older than a day. */
export function pruneRateLimits(s: Services): void {
  s.db.prepare("DELETE FROM rate_limits WHERE window_start < ?").run(s.wallNow() - 86_400);
}
