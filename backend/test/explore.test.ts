import { Keypair } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { buildFromTemplate, CATALOG } from "../src/goals/catalog.js";
import { assertPublicAllowed, HIDE_AFTER_REPORTS, MAX_OPEN_PUBLIC_PER_WALLET, MAX_PUBLIC_TITLE, recordMeta } from "../src/explore/service.js";
import { ApiError } from "../src/errors.js";
import { seedChallenge, seedParticipant } from "./helpers/seed.js";
import { makeWorld, signIn, type World } from "./helpers/world.js";

interface Person {
  kp: Keypair;
  wallet: string;
  headers: { authorization: string };
}
async function person(w: World): Promise<Person> {
  const kp = Keypair.generate();
  return { kp, wallet: kp.publicKey.toBase58(), headers: (await signIn(w, kp)).headers };
}
const get = (w: World, p: Person, url: string) => w.app.inject({ method: "GET", url, headers: p.headers });
const post = (w: World, p: Person, url: string, payload: unknown = {}) => w.app.inject({ method: "POST", url, headers: p.headers, payload: payload as object });

let counter = 0;
/** A listed challenge: a mirrored pool plus the way it is listed. Open join window, Open kind, public, unless overridden. */
function listPool(w: World, over: Partial<{ creator: string; title: string; category: string; visibility: "public" | "private"; kind: string; joinInSecs: number; demo: boolean; mint: string; full: boolean; squadId: string | null; status: string; createdAt: number; seeded: boolean }> = {}) {
  const now = w.chain.nowSec();
  const c = seedChallenge(w, { creator: over.creator, startTs: now + 600, status: over.status ?? "Open", squadId: over.squadId ?? null, isDemo: over.demo });
  w.s.db
    .prepare("UPDATE challenges SET kind = ?, join_deadline_ts = ?, mint = COALESCE(?, mint), max_participants = ?, participant_count = ? WHERE pool = ?")
    .run(over.kind ?? "Open", now + (over.joinInSecs ?? 3 * 3_600), over.mint ?? null, 5, over.full ? 5 : 0, c.pool);
  recordMeta(w.s, c.pool, c.creator, over.visibility ?? "public", over.title ?? `Goal ${counter++}`, over.category ?? "study", "FOCUS_TIMER", over.seeded ?? false);
  if (over.createdAt) w.s.db.prepare("UPDATE challenge_meta SET created_at = ? WHERE pool = ?").run(over.createdAt, c.pool);
  return c;
}

describe("Explore listing", () => {
  it("lists only public, open, not-full, not-hidden challenges that are not in a squad", async () => {
    const w = await makeWorld();
    const me = await person(w);
    const shown = listPool(w, { title: "Read for 20 minutes" });
    listPool(w, { visibility: "private", title: "private one" });
    listPool(w, { kind: "Squad", title: "squad kind" });
    listPool(w, { squadId: "sq1", title: "in a squad" });
    listPool(w, { full: true, title: "full" });
    listPool(w, { joinInSecs: -10, title: "join window over" });
    listPool(w, { status: "Settled", title: "settled" });
    const hidden = listPool(w, { title: "hidden" });
    w.s.db.prepare("UPDATE challenge_meta SET hidden = 1 WHERE pool = ?").run(hidden.pool);
    const r = (await get(w, me, "/v1/explore")).json();
    expect(r.items.map((i: { title: string }) => i.title)).toEqual(["Read for 20 minutes"]);
    expect(r.items[0].pool).toBe(shown.pool);
    expect(r.nextCursor).toBeNull();
  });

  it("returns only the goal text and public pool data: no wallets, no private fields", async () => {
    const w = await makeWorld();
    const me = await person(w);
    const other = await person(w);
    const c = listPool(w, { creator: other.wallet });
    seedParticipant(w, c.pool, other.wallet);
    const body = (await get(w, me, "/v1/explore")).body;
    expect(body).not.toContain(other.wallet); // neither the creator nor a participant is revealed
    const item = JSON.parse(body).items[0];
    expect(Object.keys(item).sort()).toEqual(
      ["category", "createdByYou", "daySecs", "demoLabel", "durationDays", "isDemo", "joinDeadlineTs", "joined", "maxParticipants", "mint", "mode", "participantCount", "pool", "proofType", "requiredDays", "sample", "stakeCap", "startTs", "title", "tokenIsTest", "tokenSymbol", "totalDeposits", "trustTier"].sort(),
    );
  });

  it("marks what you created and what you joined", async () => {
    const w = await makeWorld();
    const me = await person(w);
    const other = await person(w);
    const mine = listPool(w, { creator: me.wallet, title: "mine" });
    const joined = listPool(w, { creator: other.wallet, title: "joined" });
    seedParticipant(w, joined.pool, me.wallet);
    listPool(w, { creator: other.wallet, title: "neither" });
    const by = Object.fromEntries((await get(w, me, "/v1/explore")).json().items.map((i: { title: string }) => [i.title, i]));
    expect([by.mine.createdByYou, by.mine.joined]).toEqual([true, false]);
    expect([by.joined.createdByYou, by.joined.joined]).toEqual([false, true]);
    expect([by.neither.createdByYou, by.neither.joined]).toEqual([false, false]);
    void mine;
  });

  it("filters by category, token, demo mode and ending soon", async () => {
    const w = await makeWorld();
    const me = await person(w);
    const usdc = Keypair.generate().publicKey.toBase58();
    listPool(w, { title: "study usdc", category: "study", mint: usdc, joinInSecs: 36_000 });
    listPool(w, { title: "steps", category: "steps", joinInSecs: 40_000 });
    listPool(w, { title: "demo quick", category: "custom", demo: true, joinInSecs: 90 });
    listPool(w, { title: "soon", category: "detox", joinInSecs: 1_800 });
    const titles = async (q: string) => (await get(w, me, `/v1/explore${q}`)).json().items.map((i: { title: string }) => i.title).sort();
    expect(await titles("?category=study")).toEqual(["study usdc"]);
    expect(await titles(`?mint=${usdc}`)).toEqual(["study usdc"]);
    expect(await titles("?demo=only")).toEqual(["demo quick"]);
    expect(await titles("?demo=exclude")).toEqual(["soon", "steps", "study usdc"]);
    expect(await titles("?endingSoon=true")).toEqual(["demo quick", "soon"]);
    const soon = (await get(w, me, "/v1/explore?endingSoon=true")).json().items.map((i: { title: string }) => i.title);
    expect(soon).toEqual(["demo quick", "soon"]); // soonest deadline first
    expect((await get(w, me, "/v1/explore?category=nonsense")).statusCode).toBe(400);
    const demo = (await get(w, me, "/v1/explore?demo=only")).json().items[0];
    expect(demo.isDemo).toBe(true);
    expect(demo.demoLabel).toMatch(/DEMO POOL/);
  });

  it("pages through everything exactly once, newest first and soonest first", async () => {
    const w = await makeWorld();
    const me = await person(w);
    const now = w.s.wallNow();
    for (let i = 0; i < 45; i++) listPool(w, { title: `g${String(i).padStart(2, "0")}`, createdAt: now - i * 10, joinInSecs: 600 + i * 30 });
    for (const q of ["", "&endingSoon=true"]) {
      const seen: string[] = [];
      let cursor: string | null = null;
      let pages = 0;
      do {
        const r = (await get(w, me, `/v1/explore?limit=20${q}${cursor ? `&cursor=${cursor}` : ""}`)).json() as { items: { title: string }[]; nextCursor: string | null };
        seen.push(...r.items.map((i: { title: string }) => i.title));
        cursor = r.nextCursor;
        pages++;
      } while (cursor);
      expect(pages).toBe(3);
      expect(seen).toHaveLength(45);
      expect(new Set(seen).size).toBe(45);
      expect(seen).toEqual([...seen].sort()); // g00 (newest, soonest) first in both orders
    }
    expect((await get(w, me, "/v1/explore?cursor=not-a-cursor")).statusCode).toBe(400);
    expect((await get(w, me, "/v1/explore?limit=500")).statusCode).toBe(400);
  });

  it("needs a signed-in wallet", async () => {
    const w = await makeWorld();
    expect((await w.app.inject({ method: "GET", url: "/v1/explore" })).statusCode).toBe(401);
  });

  it("labels sample challenges and test tokens", async () => {
    const w = await makeWorld();
    const me = await person(w);
    listPool(w, { seeded: true });
    const item = (await get(w, me, "/v1/explore")).json().items[0];
    expect(item.sample).toBe(true);
    expect(item.tokenIsTest).toBe(true);
  });
});

describe("public and private rules", () => {
  const plan = (over: Record<string, unknown> = {}) => ({ ...buildFromTemplate(CATALOG.find((t) => t.id === "focus")!), ...over }) as never;
  const code = (f: () => void) => {
    try {
      f();
      return "ok";
    } catch (e) {
      return e instanceof ApiError ? e.code : "other";
    }
  };

  it("a public challenge must be an Open pool", async () => {
    const w = await makeWorld();
    const me = await person(w);
    expect(code(() => assertPublicAllowed(w.s, me.wallet, plan(), "Squad"))).toBe("public_must_be_open");
    expect(code(() => assertPublicAllowed(w.s, me.wallet, plan(), "Open"))).toBe("ok");
  });

  it("limits the goal name and rejects bad language, including in parameters", async () => {
    const w = await makeWorld();
    const me = await person(w);
    expect(code(() => assertPublicAllowed(w.s, me.wallet, plan({ title: "x".repeat(MAX_PUBLIC_TITLE + 1) }), "Open"))).toBe("public_title_too_long");
    expect(code(() => assertPublicAllowed(w.s, me.wallet, plan({ title: "Fuck this goal" }), "Open"))).toBe("public_text_not_allowed");
    const usage = buildFromTemplate(CATALOG.find((t) => t.id === "usage-limit")!, { app: "sh1t app" });
    expect(code(() => assertPublicAllowed(w.s, me.wallet, usage, "Open"))).toBe("public_text_not_allowed");
    expect(code(() => assertPublicAllowed(w.s, me.wallet, plan({ title: "Scunthorpe study hour" }), "Open"))).toBe("ok");
  });

  it("limits how fast one wallet can create public challenges", async () => {
    const w = await makeWorld();
    const me = await person(w);
    const results = Array.from({ length: 5 }, () => code(() => assertPublicAllowed(w.s, me.wallet, plan(), "Open")));
    expect(results).toEqual(["ok", "ok", "ok", "rate_limited", "rate_limited"]);
    const other = await person(w);
    expect(code(() => assertPublicAllowed(w.s, other.wallet, plan(), "Open"))).toBe("ok"); // per wallet
    w.clock.wall += 3_601; // an hour later the hourly window has reset
    expect(code(() => assertPublicAllowed(w.s, me.wallet, plan(), "Open"))).toBe("ok");
  });

  it("limits how many open public challenges one wallet may have at a time", async () => {
    const w = await makeWorld();
    const me = await person(w);
    for (let i = 0; i < MAX_OPEN_PUBLIC_PER_WALLET; i++) listPool(w, { creator: me.wallet });
    expect(code(() => assertPublicAllowed(w.s, me.wallet, plan(), "Open"))).toBe("too_many_public");
  });

  it("linking a pool to a squad makes it private for good", async () => {
    const w = await makeWorld();
    const me = await person(w);
    const friend = await person(w);
    const c = listPool(w, { creator: me.wallet, title: "was public" });
    expect((await get(w, friend, "/v1/explore")).json().items).toHaveLength(1);
    const squad = (await post(w, me, "/v1/squads", { name: "Crew" })).json();
    const link = await post(w, me, `/v1/squads/${squad.id}/challenges`, { pool: c.pool });
    expect(link.statusCode).toBe(200);
    expect(link.json().visibility).toBe("private");
    expect((await get(w, friend, "/v1/explore")).json().items).toHaveLength(0);
    expect(w.s.db.prepare("SELECT visibility FROM challenge_meta WHERE pool = ?").get(c.pool)).toEqual({ visibility: "private" });
  });
});

describe("reports", () => {
  it("hides a challenge once enough different people report it, and says nothing about it to the reporter", async () => {
    const w = await makeWorld();
    const creator = await person(w);
    const c = listPool(w, { creator: creator.wallet, title: "reported" });
    const viewer = await person(w);
    for (let i = 1; i <= HIDE_AFTER_REPORTS; i++) {
      const reporter = await person(w);
      const r = await post(w, reporter, `/v1/explore/${c.pool}/report`, { reason: "spam", note: "looks like spam" });
      expect(r.statusCode).toBe(200);
      expect(r.json()).toEqual({ ok: true }); // never reveals whether this report hid it
      const listed = (await get(w, viewer, "/v1/explore")).json().items.length;
      expect(listed).toBe(i < HIDE_AFTER_REPORTS ? 1 : 0);
    }
  });

  it("counts one report per person, refuses reports on your own or private challenges, and checks the input", async () => {
    const w = await makeWorld();
    const creator = await person(w);
    const reporter = await person(w);
    const c = listPool(w, { creator: creator.wallet });
    for (let i = 0; i < 5; i++) expect((await post(w, reporter, `/v1/explore/${c.pool}/report`, { reason: "scam" })).statusCode).toBe(200);
    expect(w.s.db.prepare("SELECT COUNT(*) AS n FROM reports WHERE pool = ?").get(c.pool)).toEqual({ n: 1 });
    expect((await get(w, reporter, "/v1/explore")).json().items).toHaveLength(1); // one person can never hide a challenge
    expect((await post(w, creator, `/v1/explore/${c.pool}/report`, { reason: "spam" })).statusCode).toBe(409);
    const priv = listPool(w, { visibility: "private" });
    expect((await post(w, reporter, `/v1/explore/${priv.pool}/report`, { reason: "spam" })).statusCode).toBe(404);
    expect((await post(w, reporter, `/v1/explore/${c.pool}/report`, { reason: "dislike" })).statusCode).toBe(400);
    expect((await post(w, reporter, `/v1/explore/${c.pool}/report`, { reason: "spam", note: "x".repeat(201) })).statusCode).toBe(400);
    expect((await post(w, reporter, `/v1/explore/${Keypair.generate().publicKey.toBase58()}/report`, { reason: "spam" })).statusCode).toBe(404);
  });

  it("rate limits reporting per wallet per day", async () => {
    const w = await makeWorld();
    const reporter = await person(w);
    const codes: number[] = [];
    for (let i = 0; i < 22; i++) {
      const c = listPool(w, { title: `t${i}` });
      codes.push((await post(w, reporter, `/v1/explore/${c.pool}/report`, { reason: "spam" })).statusCode);
    }
    expect(codes.slice(0, 20).every((c) => c === 200)).toBe(true);
    expect(codes.slice(20)).toEqual([429, 429]);
  });
});

describe("notifications", () => {
  it("shows nudges aimed at you and squad activity, never your own actions or other people's nudges", async () => {
    const w = await makeWorld();
    const a = await person(w);
    const b = await person(w);
    const c = await person(w);
    const squad = (await post(w, a, "/v1/squads", { name: "Crew" })).json();
    await post(w, b, "/v1/squads/join", { code: squad.inviteCode });
    await post(w, c, "/v1/squads/join", { code: squad.inviteCode });
    const add = (kind: string, wallet: string, data: object = {}) =>
      w.s.db.prepare("INSERT INTO feed_events (squad_id, pool, wallet, kind, data_json, created_at) VALUES (?,?,?,?,?,?)").run(squad.id, null, wallet, kind, JSON.stringify(data), w.s.wallNow());
    add("checked_in", b.wallet);
    add("checked_in", a.wallet); // my own action
    add("nudge", b.wallet, { recipient: a.wallet }); // to me
    add("nudge", b.wallet, { recipient: c.wallet }); // to somebody else
    const mine = (await get(w, a, "/v1/notifications")).json();
    expect(mine.items.map((i: { kind: string; wallet: string }) => `${i.kind}:${i.wallet === b.wallet ? "b" : "other"}`).sort()).toEqual(["checked_in:b", "nudge:b"]);
    expect(mine.items.every((i: { squadName: string }) => i.squadName === "Crew")).toBe(true);
    const theirs = (await get(w, c, "/v1/notifications")).json();
    expect(theirs.items.filter((i: { kind: string }) => i.kind === "nudge")).toHaveLength(1); // only the nudge aimed at c
    const later = (await get(w, a, `/v1/notifications?since=${mine.now + 5}`)).json();
    expect(later.items).toHaveLength(0);
    const outsider = await person(w);
    expect((await get(w, outsider, "/v1/notifications")).json().items).toHaveLength(0);
  });
});

describe("sample challenges", () => {
  it("chooseSeeds mixes kinds of goal, never lists the same title twice, and uses at most one rep-counting goal", async () => {
    const { chooseSeeds } = await import("../src/jobs/seed.js");
    const { buildFromTemplate: build, familyOf } = await import("../src/goals/catalog.js");
    for (let round = 0; round < 50; round++) {
      let n = 0;
      const pick = (k: number) => (n = (n * 7 + round * 13 + 3) % 1_000) % k;
      const first = chooseSeeds([], 6, pick);
      expect(first).toHaveLength(6);
      expect(new Set(first.map((t) => t.id)).size).toBe(6);
      expect(first.filter((t) => familyOf(t) === "reps").length).toBeLessThanOrEqual(1);
      expect(new Set(first.map(familyOf)).size).toBeGreaterThanOrEqual(5);
      // topping up: what is already listed is never repeated, and a second rep-based goal never joins the first
      const listed = first.slice(0, 3).map((t) => build(t).title);
      const more = chooseSeeds(listed, 3, pick);
      expect(more.every((t) => !listed.includes(build(t).title))).toBe(true);
      const allReps = [...first.slice(0, 3), ...more].filter((t) => familyOf(t) === "reps").length;
      expect(allReps).toBeLessThanOrEqual(1);
    }
  });
});

describe("goal text moderation in parsing", () => {
  it("stops language that is not allowed before it reaches the matcher, the cache or the model", async () => {
    const w = await makeWorld();
    const me = await person(w);
    const r = await post(w, me, "/v1/goals/parse", { text: "do 20 fuck squats every day" });
    expect(r.statusCode).toBe(200);
    expect(r.json()).toMatchObject({ status: "unclear", plan: null, source: "none" });
    expect(r.json().reason).toMatch(/rephrase/i);
    const ok = (await post(w, me, "/v1/goals/parse", { text: "do 20 squats every day" })).json();
    expect(ok.status).toBe("plan");
  });
});
