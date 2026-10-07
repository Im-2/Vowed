import { randomBytes } from "node:crypto";
import type { FastifyInstance } from "fastify";
import type { ZodTypeProvider } from "fastify-type-provider-zod";
import { z } from "zod";
import { getChallenge, type ChallengeRow, type ParticipantRow } from "../challenges/sync.js";
import { MESSAGES, suggestFor, WINDOW_DAYS, type CategoryHistory } from "../domain/coach.js";
import { dayIndexFor, windowFor } from "../domain/schedule.js";
import { currentStreak } from "../domain/time.js";
import { ApiError, conflict, forbidden, notFound, tooMany } from "../errors.js";
import { pushToWallet } from "../push/service.js";
import type { Services } from "../services.js";
import { addFeed } from "../squads/feed.js";
import { makePrivate } from "../explore/service.js";
import { authenticate, pubkeySchema } from "./auth.js";
import { enforce } from "./ratelimit.js";

const MAX_SQUADS_PER_WALLET = 20;
const MAX_MEMBERS = 50;
const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"; // no 0/O/1/I/L/U

export const NUDGE_PAIR_COOLDOWN_SEC = 6 * 3600;
export const NUDGE_MAX_PER_SENDER_DAY = 10;
export const NUDGE_MAX_PER_RECIPIENT_DAY = 5;

function newCode(): string {
  const b = randomBytes(8);
  return Array.from(b, (x) => CODE_ALPHABET[x % CODE_ALPHABET.length]).join("");
}

const squadSchema = z.object({ id: z.string(), name: z.string(), owner: z.string(), inviteCode: z.string(), deepLink: z.string(), memberCount: z.number() });

interface SquadRow {
  id: string;
  name: string;
  owner: string;
  invite_code: string;
  created_at: number;
}

function squadView(s: Services, row: SquadRow) {
  const n = s.db.prepare("SELECT COUNT(*) AS n FROM squad_members WHERE squad_id = ?").get(row.id) as { n: number };
  return { id: row.id, name: row.name, owner: row.owner, inviteCode: row.invite_code, deepLink: `https://${s.config.AUTH_DOMAIN}/join/${row.invite_code}`, memberCount: n.n };
}

function requireMember(s: Services, squadId: string, wallet: string): SquadRow {
  const row = s.db.prepare("SELECT * FROM squads WHERE id = ?").get(squadId) as SquadRow | undefined;
  if (!row) throw notFound("squad");
  const m = s.db.prepare("SELECT 1 FROM squad_members WHERE squad_id = ? AND wallet = ?").get(squadId, wallet);
  if (!m) throw forbidden("not_member", "you are not a member of this squad");
  return row;
}

function squadChallenges(s: Services, squadId: string): ChallengeRow[] {
  return s.db.prepare("SELECT * FROM challenges WHERE squad_id = ? ORDER BY start_ts DESC").all(squadId) as unknown as ChallengeRow[];
}

export function registerSquadRoutes(app: FastifyInstance, s: Services) {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const auth = authenticate(s);
  const idParam = z.object({ id: z.string().regex(/^[0-9a-f]{32}$/) });

  r.post(
    "/v1/squads",
    {
      preHandler: auth,
      schema: { tags: ["squads"], summary: "Create a squad", body: z.object({ name: z.string().trim().min(1).max(40) }), response: { 200: squadSchema } },
    },
    async (req) => {
      const wallet = req.wallet!;
      enforce(s, `wallet:${wallet}:squadcreate`, 10, 3600);
      const mine = s.db.prepare("SELECT COUNT(*) AS n FROM squad_members WHERE wallet = ?").get(wallet) as { n: number };
      if (mine.n >= MAX_SQUADS_PER_WALLET) throw conflict("too_many_squads", "squad limit reached");
      const id = randomBytes(16).toString("hex");
      const code = newCode();
      s.db.prepare("INSERT INTO squads (id, name, owner, invite_code, created_at) VALUES (?,?,?,?,?)").run(id, req.body.name, wallet, code, s.wallNow());
      s.db.prepare("INSERT INTO squad_members (squad_id, wallet, joined_at) VALUES (?,?,?)").run(id, wallet, s.wallNow());
      return squadView(s, { id, name: req.body.name, owner: wallet, invite_code: code, created_at: s.wallNow() });
    },
  );

  r.get(
    "/v1/squads",
    { preHandler: auth, schema: { tags: ["squads"], summary: "My squads", response: { 200: z.object({ squads: z.array(squadSchema) }) } } },
    async (req) => {
      const rows = s.db.prepare("SELECT q.* FROM squads q JOIN squad_members m ON m.squad_id = q.id WHERE m.wallet = ? ORDER BY q.created_at DESC").all(req.wallet!) as unknown as SquadRow[];
      return { squads: rows.map((x) => squadView(s, x)) };
    },
  );

  r.post(
    "/v1/squads/join",
    {
      preHandler: auth,
      schema: { tags: ["squads"], summary: "Join a squad with an invite code", body: z.object({ code: z.string().trim().toUpperCase().regex(/^[A-Z0-9]{8}$/) }), response: { 200: squadSchema } },
    },
    async (req) => {
      const wallet = req.wallet!;
      enforce(s, `wallet:${wallet}:squadjoin`, 20, 3600); // invite codes are guessable in theory; slow brute force down
      const row = s.db.prepare("SELECT * FROM squads WHERE invite_code = ?").get(req.body.code) as SquadRow | undefined;
      if (!row) throw notFound("invite code");
      const members = s.db.prepare("SELECT COUNT(*) AS n FROM squad_members WHERE squad_id = ?").get(row.id) as { n: number };
      const already = s.db.prepare("SELECT 1 FROM squad_members WHERE squad_id = ? AND wallet = ?").get(row.id, wallet);
      if (!already) {
        if (members.n >= MAX_MEMBERS) throw conflict("squad_full", "this squad is full");
        s.db.prepare("INSERT INTO squad_members (squad_id, wallet, joined_at) VALUES (?,?,?)").run(row.id, wallet, s.wallNow());
      }
      return squadView(s, row);
    },
  );

  r.get(
    "/v1/squads/:id",
    {
      preHandler: auth,
      schema: {
        tags: ["squads"],
        summary: "Squad details with members and linked challenges",
        params: idParam,
        response: { 200: z.object({ squad: squadSchema, members: z.array(z.object({ wallet: z.string(), joinedAt: z.number() })), challenges: z.array(z.object({ pool: z.string(), status: z.string(), startTs: z.number(), endTs: z.number(), durationDays: z.number() })) }) },
      },
    },
    async (req) => {
      const row = requireMember(s, req.params.id, req.wallet!);
      const members = s.db.prepare("SELECT wallet, joined_at FROM squad_members WHERE squad_id = ? ORDER BY joined_at").all(row.id) as { wallet: string; joined_at: number }[];
      return {
        squad: squadView(s, row),
        members: members.map((m) => ({ wallet: m.wallet, joinedAt: m.joined_at })),
        challenges: squadChallenges(s, row.id).map((c) => ({ pool: c.pool, status: c.status, startTs: c.start_ts, endTs: c.end_ts, durationDays: c.duration_days })),
      };
    },
  );

  r.post(
    "/v1/squads/:id/invite",
    {
      preHandler: auth,
      schema: {
        tags: ["squads"],
        summary: "Get the invite code and link (owner can rotate the code)",
        params: idParam,
        body: z.object({ rotate: z.boolean().default(false) }),
        response: { 200: z.object({ inviteCode: z.string(), deepLink: z.string() }) },
      },
    },
    async (req) => {
      const row = requireMember(s, req.params.id, req.wallet!);
      let code = row.invite_code;
      if (req.body.rotate) {
        if (row.owner !== req.wallet) throw forbidden("not_owner", "only the owner can rotate the invite code");
        code = newCode();
        s.db.prepare("UPDATE squads SET invite_code = ? WHERE id = ?").run(code, row.id);
      }
      return { inviteCode: code, deepLink: `https://${s.config.AUTH_DOMAIN}/join/${code}` };
    },
  );

  r.post(
    "/v1/squads/:id/challenges",
    {
      preHandler: auth,
      schema: {
        tags: ["squads"],
        summary: "Link a pool you created to this squad so members see it in the feed and leaderboard",
        params: idParam,
        body: z.object({ pool: pubkeySchema }),
        response: { 200: z.object({ pool: z.string(), squadId: z.string(), visibility: z.literal("private") }) },
      },
    },
    async (req) => {
      const row = requireMember(s, req.params.id, req.wallet!);
      const c = getChallenge(s, req.body.pool);
      if (!c) throw notFound("challenge");
      if (c.creator !== req.wallet) throw forbidden("not_creator", "only the pool creator can link it to a squad");
      if (c.squad_id && c.squad_id !== row.id) throw conflict("already_linked", "this challenge belongs to another squad");
      s.db.prepare("UPDATE challenges SET squad_id = ? WHERE pool = ?").run(row.id, c.pool);
      makePrivate(s, c.pool); // squad challenges are never listed in Explore, even if they were public before
      return { pool: c.pool, squadId: row.id, visibility: "private" as const };
    },
  );

  r.get(
    "/v1/squads/:id/feed",
    {
      preHandler: auth,
      schema: {
        tags: ["squads"],
        summary: "Squad activity feed, newest first (use `before` for older pages)",
        params: idParam,
        querystring: z.object({ before: z.coerce.number().int().positive().optional(), limit: z.coerce.number().int().min(1).max(100).default(30) }),
        response: { 200: z.object({ events: z.array(z.object({ id: z.number(), kind: z.string(), wallet: z.string(), pool: z.string().nullable(), data: z.record(z.string(), z.unknown()), createdAt: z.number() })) }) },
      },
    },
    async (req) => {
      requireMember(s, req.params.id, req.wallet!);
      const rows = s.db
        .prepare("SELECT * FROM feed_events WHERE squad_id = ? AND (?2 IS NULL OR id < ?2) ORDER BY id DESC LIMIT ?3")
        .all(req.params.id, req.query.before ?? null, req.query.limit) as { id: number; kind: string; wallet: string; pool: string | null; data_json: string; created_at: number }[];
      return { events: rows.map((e) => ({ id: e.id, kind: e.kind, wallet: e.wallet, pool: e.pool, data: JSON.parse(e.data_json) as Record<string, unknown>, createdAt: e.created_at })) };
    },
  );

  r.get(
    "/v1/squads/:id/leaderboard",
    {
      preHandler: auth,
      schema: {
        tags: ["squads"],
        summary: "Leaderboard across the squad's linked challenges",
        params: idParam,
        response: { 200: z.object({ rows: z.array(z.object({ rank: z.number(), wallet: z.string(), daysCompleted: z.number(), bestStreak: z.number(), checkedInToday: z.boolean() })) }) },
      },
    },
    async (req) => {
      requireMember(s, req.params.id, req.wallet!);
      const now = s.now();
      const agg = new Map<string, { daysCompleted: number; bestStreak: number; checkedInToday: boolean }>();
      for (const c of squadChallenges(s, req.params.id)) {
        const parts = s.db.prepare("SELECT * FROM participants WHERE pool = ?").all(c.pool) as unknown as ParticipantRow[];
        for (const p of parts) {
          const today = dayIndexFor(c, p.tz_offset_minutes, now);
          const bitmap = BigInt(p.checkin_bitmap);
          const streak = currentStreak(bitmap, today, c.duration_days);
          const done = today >= 0 && today < c.duration_days && ((bitmap >> BigInt(today)) & 1n) === 1n;
          const cur = agg.get(p.wallet) ?? { daysCompleted: 0, bestStreak: 0, checkedInToday: false };
          cur.daysCompleted += p.days_completed;
          cur.bestStreak = Math.max(cur.bestStreak, streak);
          cur.checkedInToday ||= done;
          agg.set(p.wallet, cur);
        }
      }
      const members = s.db.prepare("SELECT wallet FROM squad_members WHERE squad_id = ?").all(req.params.id) as { wallet: string }[];
      for (const m of members) if (!agg.has(m.wallet)) agg.set(m.wallet, { daysCompleted: 0, bestStreak: 0, checkedInToday: false });
      const sorted = [...agg.entries()].sort((a, b) => b[1].daysCompleted - a[1].daysCompleted || b[1].bestStreak - a[1].bestStreak || a[0].localeCompare(b[0]));
      return { rows: sorted.map(([wallet, v], i) => ({ rank: i + 1, wallet, ...v })) };
    },
  );

  r.post(
    "/v1/squads/:id/nudge",
    {
      preHandler: auth,
      schema: {
        tags: ["squads"],
        summary: "Nudge a squad mate who has not checked in today (rate limited per pair, per sender and per recipient)",
        params: idParam,
        body: z.object({ recipient: pubkeySchema }),
        response: { 200: z.object({ delivered: z.number() }) },
      },
    },
    async (req) => {
      const sender = req.wallet!;
      const { recipient } = req.body;
      requireMember(s, req.params.id, sender);
      if (recipient === sender) throw new ApiError(400, "self_nudge", "you cannot nudge yourself");
      if (!s.db.prepare("SELECT 1 FROM squad_members WHERE squad_id = ? AND wallet = ?").get(req.params.id, recipient)) throw notFound("squad member");

      // only worth a nudge when they have an open day to do
      const now = s.now();
      let pending = false;
      for (const c of squadChallenges(s, req.params.id)) {
        if (c.status !== "Open") continue;
        const p = s.db.prepare("SELECT * FROM participants WHERE pool = ? AND wallet = ? AND status = 'Active'").get(c.pool, recipient) as ParticipantRow | undefined;
        if (!p) continue;
        const d = dayIndexFor(c, p.tz_offset_minutes, now);
        if (d < 0 || d >= c.duration_days) continue;
        if (now >= windowFor(c, p.tz_offset_minutes, d).closesAt) continue;
        if (((BigInt(p.checkin_bitmap) >> BigInt(d)) & 1n) === 0n) pending = true;
      }
      if (!pending) throw conflict("nothing_to_nudge", "they have already checked in today or have no open challenge");

      const wall = s.wallNow();
      const count = (sql: string, ...args: (string | number)[]) => (s.db.prepare(sql).get(...args) as { n: number }).n;
      const last = s.db.prepare("SELECT MAX(created_at) AS t FROM nudges WHERE sender = ? AND recipient = ?").get(sender, recipient) as { t: number | null };
      if (last.t && wall - last.t < NUDGE_PAIR_COOLDOWN_SEC) throw tooMany(NUDGE_PAIR_COOLDOWN_SEC - (wall - last.t));
      if (count("SELECT COUNT(*) AS n FROM nudges WHERE sender = ? AND created_at > ?", sender, wall - 86_400) >= NUDGE_MAX_PER_SENDER_DAY) throw tooMany(3600);
      if (count("SELECT COUNT(*) AS n FROM nudges WHERE recipient = ? AND created_at > ?", recipient, wall - 86_400) >= NUDGE_MAX_PER_RECIPIENT_DAY) throw tooMany(3600);

      s.db.prepare("INSERT INTO nudges (squad_id, sender, recipient, created_at) VALUES (?,?,?,?)").run(req.params.id, sender, recipient, wall);
      addFeed(s, req.params.id, null, sender, "nudge", { recipient });
      const delivered = await pushToWallet(s, recipient, { title: "A squad mate nudged you", body: "Your check-in for today is still open.", data: { type: "nudge", squad: req.params.id } });
      return { delivered };
    },
  );

  // ---------------------------------------------------------------- push tokens
  r.post(
    "/v1/push/register",
    {
      preHandler: auth,
      schema: {
        tags: ["push"],
        summary: "Register this device's push token",
        body: z.object({ token: z.string().min(20).max(4096), platform: z.enum(["android"]).default("android") }),
        response: { 200: z.object({ ok: z.literal(true) }) },
      },
    },
    async (req) => {
      s.db
        .prepare("INSERT INTO push_tokens (token, wallet, platform, created_at) VALUES (?,?,?,?) ON CONFLICT(token) DO UPDATE SET wallet = excluded.wallet")
        .run(req.body.token, req.wallet!, req.body.platform, s.wallNow());
      return { ok: true as const };
    },
  );
  r.post(
    "/v1/push/unregister",
    { preHandler: auth, schema: { tags: ["push"], summary: "Remove a push token", body: z.object({ token: z.string().min(20).max(4096) }), response: { 200: z.object({ ok: z.literal(true) }) } } },
    async (req) => {
      s.db.prepare("DELETE FROM push_tokens WHERE token = ? AND wallet = ?").run(req.body.token, req.wallet!);
      return { ok: true as const };
    },
  );

  // ---------------------------------------------------------------- coach
  r.get(
    "/v1/coach/suggestions",
    {
      preHandler: auth,
      schema: {
        tags: ["coach"],
        summary: "Deterministic difficulty suggestions for the next challenge (never changes an active one)",
        response: {
          200: z.object({
            suggestions: z.array(
              z.object({
                category: z.string(),
                action: z.enum(["collect_more_data", "easier", "keep", "harder"]),
                reason: z.string(),
                successRate: z.number().nullable(),
                suggestedDifficulty: z.number().nullable(),
                targetScale: z.number(),
                hints: z.array(z.string()),
                appliesTo: z.literal("next_challenge"),
                message: z.string(),
              }),
            ),
          }),
        },
      },
    },
    async (req) => {
      const wallet = req.wallet!;
      const now = s.now();
      const since = now - WINDOW_DAYS * 86_400;
      const hist = new Map<string, CategoryHistory>();
      const get = (category: string): CategoryHistory => {
        let h = hist.get(category);
        if (!h) hist.set(category, (h = { category, attemptedDays: 0, completedDays: 0, finished: [], proofHours: [], lastDifficulty: 3 }));
        return h;
      };
      const parts = s.db.prepare("SELECT * FROM participants WHERE wallet = ?").all(wallet) as unknown as ParticipantRow[];
      for (const p of parts) {
        const c = getChallenge(s, p.pool);
        if (!c?.plan_json || c.is_demo) continue; // demo pools never feed the coach: minutes-long days say nothing about real habits
        const plan = JSON.parse(c.plan_json) as { category: string; difficulty: number };
        const h = get(plan.category);
        h.lastDifficulty = plan.difficulty;
        const bitmap = BigInt(p.checkin_bitmap);
        for (let d = 0; d < c.duration_days; d++) {
          const { closesAt } = windowFor(c, p.tz_offset_minutes, d);
          if (closesAt > now || closesAt <= since) continue;
          h.attemptedDays++;
          if (((bitmap >> BigInt(d)) & 1n) === 1n) h.completedDays++;
        }
      }
      const finished = s.db.prepare("SELECT * FROM coach_stats WHERE wallet = ? ORDER BY finished_at DESC").all(wallet) as { category: string; difficulty: number; duration_days: number; days_completed: number }[];
      for (const f of finished) get(f.category).finished.push({ difficulty: f.difficulty, durationDays: f.duration_days, daysCompleted: f.days_completed });
      const hours = s.db.prepare("SELECT hour_of_day FROM proofs WHERE wallet = ? AND status = 'accepted' AND created_at > ? AND hour_of_day IS NOT NULL").all(wallet, s.wallNow() - WINDOW_DAYS * 86_400) as { hour_of_day: number }[];
      for (const h of hist.values()) h.proofHours = hours.map((x) => x.hour_of_day);
      return {
        suggestions: [...hist.values()].map((h) => {
          const sug = suggestFor(h);
          return { ...sug, message: MESSAGES[sug.reason] };
        }),
      };
    },
  );
}
