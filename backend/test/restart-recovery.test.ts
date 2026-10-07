import { Keypair } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { kvGet } from "../src/db.js";
import { canonicalJson, planHash } from "../src/domain/plan.js";
import { buildFromTemplate, CATALOG } from "../src/goals/catalog.js";
import { pollOnce } from "../src/indexer.js";
import { recoverSamples } from "../src/jobs/seed.js";
import { seedChallenge } from "./helpers/seed.js";
import { makeWorld } from "./helpers/world.js";

describe("starting on a database that was wiped (a host with a temporary disk)", () => {
  it("reads the program's whole history in pages, not just the newest 200 signatures", async () => {
    const w = await makeWorld();
    const all = Array.from({ length: 450 }, (_, i) => ({ signature: `sig${450 - i}`, slot: 450 - i, failed: false })); // newest first
    const calls: { before?: string; until?: string }[] = [];
    (w.s.chain as unknown as { getSignaturesForAddress: unknown }).getSignaturesForAddress = async (_a: string, o: { before?: string; until?: string; limit?: number } = {}) => {
      calls.push({ before: o.before, until: o.until });
      let start = o.before ? all.findIndex((x) => x.signature === o.before) + 1 : 0;
      let end = Math.min(all.length, start + (o.limit ?? 100));
      if (o.until) end = Math.min(end, all.findIndex((x) => x.signature === o.until));
      start = Math.min(start, end);
      return all.slice(start, end);
    };
    (w.s.chain as unknown as { getTransactionLogs: unknown }).getTransactionLogs = async () => null;
    await pollOnce(w.s);
    expect(calls).toHaveLength(3); // 200 + 200 + 50
    expect(kvGet(w.s.db, "indexer:last_signature")).toBe("sig450");
    // the next poll only asks for what is new
    calls.length = 0;
    await pollOnce(w.s);
    expect(calls).toHaveLength(1);
    expect(calls[0]!.until).toBe("sig450");
  });

  it("recognises the sample pools again by their plan hash and lists them with their titles", async () => {
    const seeder = Keypair.generate();
    const w = await makeWorld({ env: { SEED_SECRET_KEY: JSON.stringify([...seeder.secretKey]) } });
    const t = CATALOG.find((x) => x.id === CATALOG[3]!.id)!;
    const plan = buildFromTemplate(t, { app: t.proofParams.app, place: t.needsPlace ? t.proofParams.place : undefined });
    const c = seedChallenge(w, { creator: seeder.publicKey.toBase58(), plan: null });
    w.s.db.prepare("UPDATE challenges SET goal_hash = ? WHERE pool = ?").run(planHash(plan), c.pool); // what the indexer would mirror from the chain
    const stranger = seedChallenge(w, { plan: null }); // someone else's pool with an unknown plan stays unlisted
    expect(recoverSamples(w.s)).toBe(1);
    const meta = w.s.db.prepare("SELECT title, visibility, seeded FROM challenge_meta WHERE pool = ?").get(c.pool) as { title: string; visibility: string; seeded: number };
    expect(meta).toMatchObject({ title: plan.title, visibility: "public", seeded: 1 });
    expect((w.s.db.prepare("SELECT plan_json FROM challenges WHERE pool = ?").get(c.pool) as { plan_json: string }).plan_json).toBe(canonicalJson(plan));
    expect(w.s.db.prepare("SELECT 1 FROM challenge_meta WHERE pool = ?").get(stranger.pool)).toBeUndefined();
    expect(recoverSamples(w.s)).toBe(0); // nothing left to recover
  });
});
