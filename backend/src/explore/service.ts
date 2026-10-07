/**
 * Explore: a browsable list of PUBLIC challenges. Only data that is already public on chain plus the typed goal text is returned: no
 * wallets (other than "created by you" and "you joined"), no profiles, no proofs, no devices. A challenge is listed only when all of
 * these hold: its creator made it public, it is an Open pool (squad pools are never listed), it is not linked to a squad, nobody has
 * hidden it by repeated reports, it is still open to join and not full.
 */
import { z } from "zod";
import { faucetEnabled } from "../config.js";
import type { GoalPlan } from "../domain/plan.js";
import { badRequest, conflict, notFound } from "../errors.js";
import { enforce } from "../http/ratelimit.js";
import { checkText, NOT_ALLOWED_MESSAGE } from "../moderation/text.js";
import type { Services } from "../services.js";

export const CIRCLE_DEVNET_USDC = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU";
export const CATEGORIES = ["fitness", "study", "detox", "sleep", "steps", "location", "custom"] as const;
export const REPORT_REASONS = ["spam", "offensive", "scam", "other"] as const;
/** This many different people reporting a challenge hides it from Explore until someone looks at it. */
export const HIDE_AFTER_REPORTS = 3;
export const MAX_PUBLIC_TITLE = 80;
export const MAX_OPEN_PUBLIC_PER_WALLET = 5;

export const exploreQuery = z.object({
  category: z.enum(CATEGORIES).optional(),
  mint: z.string().min(32).max(44).optional(),
  /** "include" (default: demo and normal), "only" (DEMO pools only: the Quick challenges section), "exclude" (normal pools only) */
  demo: z.enum(["include", "only", "exclude"]).default("include"),
  /** only pools whose join window closes within the next 6 hours, soonest first */
  endingSoon: z.coerce.boolean().default(false),
  limit: z.coerce.number().int().min(1).max(30).default(20),
  cursor: z.string().max(200).optional(),
});
export type ExploreQuery = z.infer<typeof exploreQuery>;

export interface ExploreItem {
  pool: string;
  title: string;
  category: string;
  proofType: string | null;
  trustTier: string | null;
  mode: string;
  mint: string;
  tokenSymbol: string;
  /** true for our own test tokens: they have no value */
  tokenIsTest: boolean;
  durationDays: number;
  requiredDays: number;
  startTs: number;
  joinDeadlineTs: number;
  participantCount: number;
  maxParticipants: number;
  /** total staked so far, in token base units (6 decimals) */
  totalDeposits: string;
  /** the largest stake the program allows for this goal's trust level */
  stakeCap: string | null;
  isDemo: boolean;
  daySecs: number;
  demoLabel: string | null;
  /** true for sample challenges the Vowed team created so the list is never empty */
  sample: boolean;
  createdByYou: boolean;
  joined: boolean;
}

export function tokenInfo(s: Services, mint: string): { symbol: string; isTest: boolean } {
  if (faucetEnabled(s.config)) {
    if (mint === s.config.FAUCET_USDC_MINT) return { symbol: "tUSDC", isTest: true };
    if (mint === s.config.FAUCET_SKR_MINT) return { symbol: "tSKR", isTest: true };
  }
  if (mint === CIRCLE_DEVNET_USDC) return { symbol: "USDC (devnet)", isTest: true };
  return { symbol: `${mint.slice(0, 4)}…${mint.slice(-4)}`, isTest: true };
}

interface Row {
  pool: string;
  creator: string;
  mint: string;
  mode: string;
  start_ts: number;
  join_deadline_ts: number;
  duration_days: number;
  required_days: number;
  participant_count: number;
  max_participants: number;
  total_deposits: string;
  is_demo: number;
  day_secs: number;
  plan_json: string | null;
  title: string;
  category: string;
  proof_type: string | null;
  seeded: number;
  listed_at: number;
  mine: number | null;
}

const enc = (n: number, pool: string) => Buffer.from(`${n}:${pool}`).toString("base64url");
function dec(c: string): { n: number; pool: string } {
  try {
    const [n, ...rest] = Buffer.from(c, "base64url").toString().split(":");
    const num = Number(n);
    const pool = rest.join(":");
    if (!Number.isFinite(num) || !pool) throw new Error();
    return { n: num, pool };
  } catch {
    throw badRequest("bad_cursor", "that page marker is not valid");
  }
}

const SOON = 6 * 3600;

export function listExplore(s: Services, wallet: string, q: ExploreQuery): { items: ExploreItem[]; nextCursor: string | null } {
  const now = s.now();
  const where: string[] = [
    "m.visibility = 'public'", "m.hidden = 0", "c.squad_id IS NULL", "c.kind = 'Open'", "c.status = 'Open'",
    "c.join_deadline_ts > @now", "c.participant_count < c.max_participants",
  ];
  const args: Record<string, string | number> = { now, wallet, limit: q.limit + 1 };
  if (q.category) { where.push("m.category = @category"); args.category = q.category; }
  if (q.mint) { where.push("c.mint = @mint"); args.mint = q.mint; }
  if (q.demo === "only") where.push("c.is_demo = 1");
  if (q.demo === "exclude") where.push("c.is_demo = 0");
  if (q.endingSoon) { where.push("c.join_deadline_ts <= @soon"); args.soon = now + SOON; }
  // keyset pagination: the sort key and the pool address together are unique
  const key = q.endingSoon ? "c.join_deadline_ts" : "m.created_at";
  const dir = q.endingSoon ? "ASC" : "DESC";
  if (q.cursor) {
    const c = dec(q.cursor);
    where.push(q.endingSoon ? `(${key} > @cn OR (${key} = @cn AND m.pool > @cp))` : `(${key} < @cn OR (${key} = @cn AND m.pool < @cp))`);
    args.cn = c.n;
    args.cp = c.pool;
  }
  const sql = `SELECT c.pool, c.creator, c.mint, c.mode, c.start_ts, c.join_deadline_ts, c.duration_days, c.required_days, c.participant_count, c.max_participants,
      c.total_deposits, c.is_demo, c.day_secs, c.plan_json, m.title, m.category, m.proof_type, m.seeded, m.created_at AS listed_at,
      (SELECT 1 FROM participants p WHERE p.pool = c.pool AND p.wallet = @wallet) AS mine
    FROM challenge_meta m JOIN challenges c ON c.pool = m.pool
    WHERE ${where.join(" AND ")}
    ORDER BY ${key} ${dir}, m.pool ${dir} LIMIT @limit`;
  const rows = s.db.prepare(sql).all(args as never) as unknown as Row[];
  const page = rows.slice(0, q.limit);
  const items = page.map((r) => toItem(s, wallet, r));
  const last = page[page.length - 1];
  const more = rows.length > q.limit && last;
  return { items, nextCursor: more ? enc(q.endingSoon ? last.join_deadline_ts : last.listed_at, last.pool) : null };
}

function toItem(s: Services, wallet: string, r: Row): ExploreItem {
  const t = tokenInfo(s, r.mint);
  let trust: string | null = null;
  try {
    if (r.plan_json) {
      const p = JSON.parse(r.plan_json) as { proofMethods?: { trustTier?: string }[] };
      trust = p.proofMethods?.[0]?.trustTier ?? null;
    }
  } catch {
    trust = null;
  }
  return {
    pool: r.pool,
    title: r.title,
    category: r.category,
    proofType: r.proof_type,
    trustTier: trust,
    mode: r.mode,
    mint: r.mint,
    tokenSymbol: t.symbol,
    tokenIsTest: t.isTest,
    durationDays: r.duration_days,
    requiredDays: r.required_days,
    startTs: r.start_ts,
    joinDeadlineTs: r.join_deadline_ts,
    participantCount: r.participant_count,
    maxParticipants: r.max_participants,
    totalDeposits: r.total_deposits,
    stakeCap: null,
    isDemo: r.is_demo === 1,
    daySecs: r.day_secs,
    demoLabel: r.is_demo === 1 ? `DEMO POOL: each "day" lasts ${r.day_secs >= 120 ? `${Math.round(r.day_secs / 60)} minutes` : `${r.day_secs} seconds`}. For demonstration only, with test money.` : null,
    sample: r.seeded === 1,
    createdByYou: r.creator === wallet,
    joined: r.mine === 1,
  };
}

/** Registers how a pool is listed. Called when the create transaction is built; the pool appears in Explore once it exists on chain. */
export function recordMeta(s: Services, pool: string, creator: string, visibility: "public" | "private", title: string, category: string, proofType: string | null, seeded = false): void {
  s.db
    .prepare(
      `INSERT INTO challenge_meta (pool, creator, visibility, title, category, proof_type, seeded, hidden, created_at)
       VALUES (?,?,?,?,?,?,?,0,?)
       ON CONFLICT(pool) DO UPDATE SET visibility = excluded.visibility, title = excluded.title, category = excluded.category, proof_type = excluded.proof_type`,
    )
    .run(pool, creator, visibility, title, category, proofType, seeded ? 1 : 0, s.wallNow());
}

export function openPublicCount(s: Services, wallet: string): number {
  const r = s.db
    .prepare(
      `SELECT COUNT(*) AS n FROM challenge_meta m JOIN challenges c ON c.pool = m.pool
       WHERE m.creator = ? AND m.visibility = 'public' AND m.hidden = 0 AND c.status = 'Open' AND c.join_deadline_ts > ?`,
    )
    .get(wallet, s.now()) as { n: number };
  return r.n;
}

/** Linking a pool to a squad makes it private for good: squad challenges are never listed. */
export function makePrivate(s: Services, pool: string): void {
  s.db.prepare("UPDATE challenge_meta SET visibility = 'private' WHERE pool = ?").run(pool);
}

export function reportChallenge(s: Services, wallet: string, pool: string, reason: (typeof REPORT_REASONS)[number], note: string | undefined): { hidden: boolean } {
  const m = s.db.prepare("SELECT creator, visibility FROM challenge_meta WHERE pool = ?").get(pool) as { creator: string; visibility: string } | undefined;
  if (!m || m.visibility !== "public") throw notFound("public challenge");
  if (m.creator === wallet) throw conflict("own_challenge", "you cannot report your own challenge");
  const res = s.db
    .prepare("INSERT OR IGNORE INTO reports (pool, reporter, reason, note, created_at) VALUES (?,?,?,?,?)")
    .run(pool, wallet, reason, note?.slice(0, 200) ?? null, s.wallNow());
  if (Number(res.changes) === 0) return { hidden: false }; // the same person reporting twice changes nothing
  const n = s.db.prepare("SELECT COUNT(*) AS n FROM reports WHERE pool = ?").get(pool) as { n: number };
  const hide = n.n >= HIDE_AFTER_REPORTS;
  if (hide) s.db.prepare("UPDATE challenge_meta SET hidden = 1 WHERE pool = ?").run(pool);
  return { hidden: hide };
}

/**
 * Everything that must hold before a challenge may be listed publicly. Throws a clear 400/409/429 otherwise.
 * (The rate limits count attempts, so spamming the create button does not help.)
 */
export function assertPublicAllowed(s: Services, wallet: string, plan: GoalPlan, kind: string): void {
  if (kind !== "Open") throw badRequest("public_must_be_open", "a public challenge must be an Open pool; squad challenges are private");
  if (plan.title.length > MAX_PUBLIC_TITLE) throw badRequest("public_title_too_long", `a public goal name can have at most ${MAX_PUBLIC_TITLE} characters`);
  const texts = [plan.title, ...Object.values(plan.proofMethods[0]!.params).map(String)];
  if (texts.some((t) => !checkText(t).ok)) throw badRequest("public_text_not_allowed", NOT_ALLOWED_MESSAGE);
  enforce(s, `wallet:${wallet}:public-create-hour`, 3, 3_600);
  enforce(s, `wallet:${wallet}:public-create-day`, 10, 86_400);
  if (openPublicCount(s, wallet) >= MAX_OPEN_PUBLIC_PER_WALLET) {
    throw conflict("too_many_public", `you already have ${MAX_OPEN_PUBLIC_PER_WALLET} open public challenges; wait for one to start or finish`);
  }
}
