/**
 * Sample public challenges, so Explore is never empty when someone opens it. They are REAL pools on the test network, created by a
 * throwaway "Vowed samples" key, public, joinable with test tokens, and labelled as samples in the list. Each lasts as long as the
 * program allows a join window (24 hours); the job tops the list up when they expire. Mixed across kinds of goal on purpose.
 */
import { PublicKey } from "@solana/web3.js";
import { randomBytes } from "node:crypto";
import { signAndSend } from "../chain/tx.js";
import { canonicalJson, planHash, type GoalPlan } from "../domain/plan.js";
import { buildFromTemplate, CATALOG, familyOf, type CatalogTemplate } from "../goals/catalog.js";
import { recordMeta } from "../explore/service.js";
import { syncPool } from "../challenges/sync.js";
import type { Services } from "../services.js";

/** Which templates to create next: kinds that are not already listed first, never more than one rep-based goal, never a repeat of a listed title. */
export function chooseSeeds(listedTitles: string[], need: number, pick: (n: number) => number = (n) => Math.floor(Math.random() * n)): CatalogTemplate[] {
  const listed = new Set(listedTitles);
  const options = CATALOG.filter((t) => !listed.has(buildFromTemplate(t).title));
  const hasReps = CATALOG.filter((t) => familyOf(t) === "reps").some((t) => listed.has(buildFromTemplate(t).title));
  const out: CatalogTemplate[] = [];
  const familiesNow = new Set(CATALOG.filter((t) => listed.has(buildFromTemplate(t).title)).map(familyOf));
  while (out.length < need) {
    const usedFam = new Set([...familiesNow, ...out.map(familyOf)]);
    let candidates = options.filter((t) => !out.includes(t) && !usedFam.has(familyOf(t)) && !(familyOf(t) === "reps" && (hasReps || out.some((o) => familyOf(o) === "reps"))));
    if (candidates.length === 0) candidates = options.filter((t) => !out.includes(t) && familyOf(t) !== "reps");
    if (candidates.length === 0) break;
    out.push(candidates[pick(candidates.length)]!);
  }
  return out;
}

let running = false;

export async function seedPublicChallenges(s: Services): Promise<{ created: string[]; errors: string[] }> {
  const created: string[] = [];
  const errors: string[] = [];
  const key = s.config.SEED_SECRET_KEY;
  const mint = s.config.FAUCET_USDC_MINT;
  if (!key || !mint || running) return { created, errors };
  running = true;
  try {
    const now = s.now();
    const rows = s.db
      .prepare(
        `SELECT m.title FROM challenge_meta m JOIN challenges c ON c.pool = m.pool
         WHERE m.seeded = 1 AND m.visibility = 'public' AND m.hidden = 0 AND c.status = 'Open' AND c.join_deadline_ts > ?`,
      )
      .all(now + 2 * 3_600) as { title: string }[];
    const need = s.config.SEED_TARGET - rows.length;
    if (need <= 0) return { created, errors };
    const bal = await s.chain.getAccount(key.publicKey.toBase58());
    if (!bal || bal.lamports < BigInt(need) * 6_000_000n) {
      errors.push("the sample-challenge wallet is low on SOL; top it up from the deployer (about 0.004 SOL per pool)");
      return { created, errors };
    }
    for (const t of chooseSeeds(rows.map((r) => r.title), need)) {
      try {
        const plan: GoalPlan = buildFromTemplate(t, { app: t.proofParams.app, place: t.needsPlace ? t.proofParams.place : undefined });
        const poolId = BigInt(`0x${randomBytes(8).toString("hex")}`);
        const startTs = s.now() + 180;
        const creator = key.publicKey;
        const ix = s.program.ixCreatePool(creator, new PublicKey(mint), {
          pool_id: poolId, kind: "Open", mode: "Soft", penalty_bps: 3_000, start_ts: BigInt(startTs), duration_days: plan.cadence.totalDays, required_days: plan.cadence.requiredDays,
          goal_hash: Buffer.from(planHash(plan), "hex"), join_window_secs: 86_400n, max_participants: 50, demo_day_secs: 0,
        });
        const hash = planHash(plan);
        s.db.prepare("INSERT OR IGNORE INTO plans (goal_hash, creator, plan_json, created_at) VALUES (?,?,?,?)").run(hash, creator.toBase58(), canonicalJson(plan), s.wallNow());
        const pool = s.program.poolPda(creator, poolId).toBase58();
        recordMeta(s, pool, creator.toBase58(), "public", plan.title, plan.category, plan.proofMethods[0]!.type, true);
        await signAndSend(s.chain, [ix], [key]);
        await syncPool(s, pool);
        created.push(pool);
      } catch (e) {
        errors.push(`${t.id}: ${e instanceof Error ? e.message : "failed"}`);
      }
    }
    return { created, errors };
  } finally {
    running = false;
  }
}
