import { Keypair } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { NUDGE_MAX_PER_RECIPIENT_DAY, NUDGE_PAIR_COOLDOWN_SEC } from "../src/http/squads.js";
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
const post = (w: World, p: Person, url: string, payload: unknown = {}) => w.app.inject({ method: "POST", url, headers: p.headers, payload: payload as object });
const get = (w: World, p: Person, url: string) => w.app.inject({ method: "GET", url, headers: p.headers });

async function squadWith(w: World, owner: Person, members: Person[]) {
  const sq = (await post(w, owner, "/v1/squads", { name: "Morning crew" })).json();
  for (const m of members) expect((await post(w, m, "/v1/squads/join", { code: sq.inviteCode })).statusCode).toBe(200);
  return sq as { id: string; inviteCode: string; deepLink: string };
}

describe("squads", () => {
  it("creates a squad, joins by invite code (case-insensitive) and lists it", async () => {
    const w = await makeWorld();
    const [a, b] = [await person(w), await person(w)];
    const sq = (await post(w, a, "/v1/squads", { name: "  Gym rats " })).json();
    expect(sq).toMatchObject({ name: "Gym rats", owner: a.wallet, memberCount: 1 });
    expect(sq.inviteCode).toMatch(/^[A-Z0-9]{8}$/);
    expect(sq.deepLink).toBe(`https://vowed.app/join/${sq.inviteCode}`);
    const joined = await post(w, b, "/v1/squads/join", { code: sq.inviteCode.toLowerCase() });
    expect(joined.json()).toMatchObject({ id: sq.id, memberCount: 2 });
    expect((await post(w, b, "/v1/squads/join", { code: sq.inviteCode })).json().memberCount).toBe(2); // idempotent
    expect((await get(w, b, "/v1/squads")).json().squads).toHaveLength(1);
    expect((await post(w, b, "/v1/squads/join", { code: "ZZZZZZZZ" })).statusCode).toBe(404);
    expect((await post(w, b, "/v1/squads/join", { code: "bad" })).statusCode).toBe(400);
  });

  it("keeps squad data private to members", async () => {
    const w = await makeWorld();
    const [a, outsider] = [await person(w), await person(w)];
    const sq = await squadWith(w, a, []);
    for (const url of [`/v1/squads/${sq.id}`, `/v1/squads/${sq.id}/feed`, `/v1/squads/${sq.id}/leaderboard`]) {
      expect((await get(w, outsider, url)).statusCode, url).toBe(403);
    }
    expect((await post(w, outsider, `/v1/squads/${sq.id}/invite`, {})).statusCode).toBe(403);
    expect((await post(w, outsider, `/v1/squads/${sq.id}/nudge`, { recipient: a.wallet })).statusCode).toBe(403);
    expect((await get(w, a, `/v1/squads/${"0".repeat(32)}`)).statusCode).toBe(404);
  });

  it("only the owner can rotate the invite code, and the old code stops working", async () => {
    const w = await makeWorld();
    const [a, b, c] = [await person(w), await person(w), await person(w)];
    const sq = await squadWith(w, a, [b]);
    expect((await post(w, b, `/v1/squads/${sq.id}/invite`, { rotate: true })).statusCode).toBe(403);
    expect((await post(w, b, `/v1/squads/${sq.id}/invite`, {})).json().inviteCode).toBe(sq.inviteCode);
    const rotated = (await post(w, a, `/v1/squads/${sq.id}/invite`, { rotate: true })).json();
    expect(rotated.inviteCode).not.toBe(sq.inviteCode);
    expect((await post(w, c, "/v1/squads/join", { code: sq.inviteCode })).statusCode).toBe(404);
    expect((await post(w, c, "/v1/squads/join", { code: rotated.inviteCode })).statusCode).toBe(200);
  });

  it("links a pool to the squad only for its creator", async () => {
    const w = await makeWorld();
    const [a, b] = [await person(w), await person(w)];
    const sq = await squadWith(w, a, [b]);
    const c = seedChallenge(w, { creator: a.wallet });
    expect((await post(w, b, `/v1/squads/${sq.id}/challenges`, { pool: c.pool })).statusCode).toBe(403);
    expect((await post(w, a, `/v1/squads/${sq.id}/challenges`, { pool: c.pool })).statusCode).toBe(200);
    const detail = (await get(w, b, `/v1/squads/${sq.id}`)).json();
    expect(detail.challenges.map((x: { pool: string }) => x.pool)).toEqual([c.pool]);
    const other = await squadWith(w, b, []);
    expect((await post(w, b, `/v1/squads/${other.id}/challenges`, { pool: c.pool })).statusCode).toBe(403); // not the creator
  });

  it("ranks the leaderboard by days completed and shows who checked in today", async () => {
    const w = await makeWorld();
    const [a, b, c] = [await person(w), await person(w), await person(w)];
    const sq = await squadWith(w, a, [b, c]);
    const ch = seedChallenge(w, { creator: a.wallet, squadId: sq.id, durationDays: 5 });
    (w.chain as unknown as { time: number }).time = ch.startTs + 2 * 86_400 + 3_600; // day 2
    seedParticipant(w, ch.pool, a.wallet, { bitmap: 0b111n, days: 3 });
    seedParticipant(w, ch.pool, b.wallet, { bitmap: 0b011n, days: 2 });
    // c joined the squad but not the challenge
    const rows = (await get(w, a, `/v1/squads/${sq.id}/leaderboard`)).json().rows;
    expect(rows.map((r: { wallet: string }) => r.wallet)).toEqual([a.wallet, b.wallet, c.wallet]);
    expect(rows[0]).toMatchObject({ rank: 1, daysCompleted: 3, bestStreak: 3, checkedInToday: true });
    expect(rows[1]).toMatchObject({ rank: 2, daysCompleted: 2, bestStreak: 2, checkedInToday: false });
    expect(rows[2]).toMatchObject({ rank: 3, daysCompleted: 0, checkedInToday: false });
  });

  it("feed returns newest first and pages with `before`", async () => {
    const w = await makeWorld();
    const [a] = [await person(w)];
    const sq = await squadWith(w, a, []);
    for (let i = 0; i < 5; i++) w.s.db.prepare("INSERT INTO feed_events (squad_id, pool, wallet, kind, data_json, created_at) VALUES (?,?,?,?,?,?)").run(sq.id, null, a.wallet, "checked_in", JSON.stringify({ day: i }), w.clock.wall + i);
    const first = (await get(w, a, `/v1/squads/${sq.id}/feed?limit=2`)).json().events;
    expect(first.map((e: { data: { day: number } }) => e.data.day)).toEqual([4, 3]);
    const next = (await get(w, a, `/v1/squads/${sq.id}/feed?limit=10&before=${first[1].id}`)).json().events;
    expect(next.map((e: { data: { day: number } }) => e.data.day)).toEqual([2, 1, 0]);
  });
});

describe("challenge visibility", () => {
  it("lists Open pools to everyone but Squad pools only to creator, participants and squad members; the address still works as an invite", async () => {
    const w = await makeWorld();
    const [owner, member, outsider, participant] = [await person(w), await person(w), await person(w), await person(w)];
    const sq = await squadWith(w, owner, [member]);
    const open = seedChallenge(w, { creator: owner.wallet });
    w.s.db.prepare("UPDATE challenges SET kind = 'Open' WHERE pool = ?").run(open.pool);
    const squadPool = seedChallenge(w, { creator: owner.wallet, squadId: sq.id });
    const unlinked = seedChallenge(w, { creator: owner.wallet });
    seedParticipant(w, unlinked.pool, participant.wallet);
    const listed = async (p: Person) => ((await get(w, p, "/v1/challenges")).json().challenges as { pool: string }[]).map((c) => c.pool).sort();
    expect(await listed(outsider)).toEqual([open.pool]);
    expect(await listed(member)).toEqual([open.pool, squadPool.pool].sort());
    expect(await listed(owner)).toEqual([open.pool, squadPool.pool, unlinked.pool].sort());
    expect(await listed(participant)).toEqual([open.pool, unlinked.pool].sort());
    // by address any signed-in user can still read it (needed to show the goal before joining from an invite)
    expect((await get(w, outsider, `/v1/challenges/${squadPool.pool}`)).statusCode).toBe(200);
  });
});

describe("nudges", () => {
  async function setup() {
    const w = await makeWorld();
    const [sender, lazy, done] = [await person(w), await person(w), await person(w)];
    const sq = await squadWith(w, sender, [lazy, done]);
    const ch = seedChallenge(w, { creator: sender.wallet, squadId: sq.id, durationDays: 5 });
    (w.chain as unknown as { time: number }).time = ch.startTs + 3_600; // day 0
    seedParticipant(w, ch.pool, lazy.wallet, { bitmap: 0n });
    seedParticipant(w, ch.pool, done.wallet, { bitmap: 0b1n, days: 1 });
    seedParticipant(w, ch.pool, sender.wallet, { bitmap: 0b1n, days: 1 });
    return { w, sender, lazy, done, sq, ch };
  }

  it("delivers a push to a mate who has not checked in, and records it in the feed", async () => {
    const { w, sender, lazy, sq } = await setup();
    await post(w, lazy, "/v1/push/register", { token: "t".repeat(40) });
    const res = await post(w, sender, `/v1/squads/${sq.id}/nudge`, { recipient: lazy.wallet });
    expect(res.json()).toEqual({ delivered: 1 });
    expect(w.push.sent).toHaveLength(1);
    expect(w.push.sent[0]!.tokens).toEqual(["t".repeat(40)]);
    expect(JSON.stringify(w.push.sent[0]!.message)).not.toContain(sender.wallet); // no wallet addresses in notifications
    const feed = (await get(w, lazy, `/v1/squads/${sq.id}/feed`)).json().events;
    expect(feed[0]).toMatchObject({ kind: "nudge", wallet: sender.wallet, data: { recipient: lazy.wallet } });
  });

  it("refuses useless or abusive nudges", async () => {
    const { w, sender, lazy, done, sq } = await setup();
    const n = (to: Person, from = sender) => post(w, from, `/v1/squads/${sq.id}/nudge`, { recipient: to.wallet });
    expect((await n(done)).json().error.code).toBe("nothing_to_nudge"); // already checked in
    expect((await n(sender)).json().error.code).toBe("self_nudge");
    expect((await post(w, sender, `/v1/squads/${sq.id}/nudge`, { recipient: Keypair.generate().publicKey.toBase58() })).statusCode).toBe(404); // not a member
    expect((await n(lazy)).statusCode).toBe(200);
    expect((await n(lazy)).statusCode).toBe(429); // same pair within the cooldown
    w.clock.wall += NUDGE_PAIR_COOLDOWN_SEC + 1;
    expect((await n(lazy)).statusCode).toBe(200);
  });

  it("caps nudges per recipient per day across different senders", async () => {
    const { w, lazy, sq } = await setup();
    const senders: Person[] = [];
    for (let i = 0; i < NUDGE_MAX_PER_RECIPIENT_DAY + 1; i++) {
      const p = await person(w);
      await post(w, p, "/v1/squads/join", { code: sq.inviteCode });
      senders.push(p);
    }
    const codes: number[] = [];
    for (const p of senders) codes.push((await post(w, p, `/v1/squads/${sq.id}/nudge`, { recipient: lazy.wallet })).statusCode);
    expect(codes.filter((c) => c === 200)).toHaveLength(NUDGE_MAX_PER_RECIPIENT_DAY);
    expect(codes.at(-1)).toBe(429);
  });

  it("does not nudge once the day's window has closed or the challenge is over", async () => {
    const { w, sender, lazy, sq, ch } = await setup();
    (w.chain as unknown as { time: number }).time = ch.startTs + 5 * 86_400 + 8 * 3_600; // after the last day
    expect((await post(w, sender, `/v1/squads/${sq.id}/nudge`, { recipient: lazy.wallet })).json().error.code).toBe("nothing_to_nudge");
  });
});

describe("push tokens", () => {
  it("registers, moves and removes tokens; a token belongs to one wallet", async () => {
    const w = await makeWorld();
    const [a, b] = [await person(w), await person(w)];
    const token = "x".repeat(50);
    expect((await post(w, a, "/v1/push/register", { token })).statusCode).toBe(200);
    expect((await post(w, b, "/v1/push/register", { token })).statusCode).toBe(200); // device changed hands: token moves
    expect(w.s.db.prepare("SELECT wallet FROM push_tokens WHERE token = ?").get(token)).toEqual({ wallet: b.wallet });
    expect((await post(w, a, "/v1/push/unregister", { token })).statusCode).toBe(200); // a cannot delete b's token
    expect(w.s.db.prepare("SELECT COUNT(*) AS n FROM push_tokens").get()).toEqual({ n: 1 });
    expect((await post(w, b, "/v1/push/unregister", { token })).statusCode).toBe(200);
    expect(w.s.db.prepare("SELECT COUNT(*) AS n FROM push_tokens").get()).toEqual({ n: 0 });
  });
});

describe("coach endpoint", () => {
  it("suggests an easier next goal after a poor recent run and never touches the active challenge", async () => {
    const w = await makeWorld();
    const a = await person(w);
    const ch = seedChallenge(w, { durationDays: 10, requiredDays: 7 });
    (w.chain as unknown as { time: number }).time = ch.startTs + 8 * 86_400 + 12 * 3_600; // day 8 afternoon: days 0-7 closed
    seedParticipant(w, ch.pool, a.wallet, { bitmap: 0b00010011n, days: 3 }); // 3 of 8 closed days done
    const before = w.s.db.prepare("SELECT * FROM challenges WHERE pool = ?").get(ch.pool);
    const res = (await get(w, a, "/v1/coach/suggestions")).json();
    expect(res.suggestions).toHaveLength(1);
    expect(res.suggestions[0]).toMatchObject({ category: "fitness", action: "easier", reason: "LOW_SUCCESS", targetScale: 0.8, appliesTo: "next_challenge" });
    expect(w.s.db.prepare("SELECT * FROM challenges WHERE pool = ?").get(ch.pool)).toEqual(before);
  });

  it("returns no suggestions for a brand new user", async () => {
    const w = await makeWorld();
    expect((await get(w, await person(w), "/v1/coach/suggestions")).json()).toEqual({ suggestions: [] });
  });
});
