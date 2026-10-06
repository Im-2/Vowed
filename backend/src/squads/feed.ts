import type { Services } from "../services.js";

export type FeedKind = "checked_in" | "missed" | "joined" | "nudge" | "settled";

export function addFeed(s: Services, squadId: string, pool: string | null, wallet: string, kind: FeedKind, data: Record<string, unknown>): void {
  s.db
    .prepare("INSERT INTO feed_events (squad_id, pool, wallet, kind, data_json, created_at) VALUES (?,?,?,?,?,?)")
    .run(squadId, pool, wallet, kind, JSON.stringify(data), s.wallNow());
}
