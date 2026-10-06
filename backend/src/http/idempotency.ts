import type { FastifyRequest } from "fastify";
import { badRequest } from "../errors.js";
import type { Services } from "../services.js";

const TTL_SEC = 600;

/**
 * Runs `compute` once per (wallet, endpoint, Idempotency-Key header) within ten minutes and replays the stored
 * response afterwards, so a retried transaction-build request returns the same transaction. Without the header it just runs.
 */
export async function idempotent<T>(s: Services, req: FastifyRequest, endpoint: string, compute: () => Promise<T>): Promise<T> {
  const raw = req.headers["idempotency-key"];
  if (raw === undefined) return compute();
  const key = Array.isArray(raw) ? raw[0]! : raw;
  if (!/^[A-Za-z0-9_-]{8,80}$/.test(key)) throw badRequest("bad_idempotency_key", "Idempotency-Key must be 8-80 URL-safe characters");
  const wallet = req.wallet!;
  const hit = s.db
    .prepare("SELECT response_json FROM idempotency WHERE wallet = ? AND endpoint = ? AND idem_key = ? AND created_at > ?")
    .get(wallet, endpoint, key, s.wallNow() - TTL_SEC) as { response_json: string } | undefined;
  if (hit) return JSON.parse(hit.response_json) as T;
  const out = await compute();
  s.db
    .prepare("INSERT OR REPLACE INTO idempotency (wallet, endpoint, idem_key, response_json, created_at) VALUES (?,?,?,?,?)")
    .run(wallet, endpoint, key, JSON.stringify(out), s.wallNow());
  return out;
}
