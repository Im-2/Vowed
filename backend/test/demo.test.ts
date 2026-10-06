/** DEMO POOLS in the backend: schedule math (checked against the shared vectors), exclusion from coach and reminders, labelling. */
import { existsSync, readFileSync } from "node:fs";
import { Keypair } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { DEMO_MAX_DAY_SECS, DEMO_MIN_DAY_SECS, demoCheckinGrace, demoDayIndex, demoLabel, demoWindowOk, dayIndexFor, windowFor, windowOkFor } from "../src/domain/schedule.js";
import { recordMissedDays, sendReminders } from "../src/jobs/daily.js";
import { seedChallenge, seedParticipant } from "./helpers/seed.js";
import { makeWorld, signIn } from "./helpers/world.js";

function vector(name: string) {
  const candidates = [process.env.VOWED_SHARED && `${process.env.VOWED_SHARED}/test-vectors/${name}`, new URL(`../../shared/test-vectors/${name}`, import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1")].filter(Boolean) as string[];
  const path = candidates.find((p) => existsSync(p));
  if (!path) throw new Error(`vector ${name} not found`);
  return JSON.parse(readFileSync(path, "utf8"));
}

describe("demo pool schedule", () => {
  const v = vector("day-index.json");
  it("matches the program's demo day index and window on every shared vector", () => {
    expect(v.demo_day_index.length).toBeGreaterThan(50);
    for (const c of v.demo_day_index) expect(demoDayIndex(c.now, c.start_ts, c.day_secs), JSON.stringify(c)).toBe(c.day_index);
    expect(v.demo_window.length).toBeGreaterThan(100);
    for (const c of v.demo_window) expect(demoWindowOk(c.now, c.start_ts, c.day_secs, c.day), JSON.stringify(c)).toBe(c.ok);
  });

  it("chooses the demo or the normal schedule from the challenge row, and ignores the timezone for demos", () => {
    const demo = { start_ts: 1_000, is_demo: 1, day_secs: 60 };
    const normal = { start_ts: 1_799_971_200, is_demo: 0, day_secs: 86_400 };
    expect(windowFor(demo, 840, 1)).toEqual({ opensAt: 1_060, closesAt: 1_060 + 60 + demoCheckinGrace(60) });
    expect(windowFor(demo, -720, 1)).toEqual(windowFor(demo, 840, 1));
    expect(dayIndexFor(demo, 300, 1_125)).toBe(2);
    expect(windowOkFor(demo, 0, 0, 1_074)).toBe(true);
    expect(windowOkFor(demo, 0, 0, 1_075)).toBe(false);
    // a normal pool is still on real days: a 60 s step never leaves day 0
    expect(dayIndexFor(normal, 0, normal.start_ts + 60)).toBe(0);
    expect(windowFor(normal, 0, 0).closesAt - windowFor(normal, 0, 0).opensAt).toBe(86_400 + 7_200);
  });

  it("states the demo limits and a plain label", () => {
    expect([DEMO_MIN_DAY_SECS, DEMO_MAX_DAY_SECS]).toEqual([60, 3_600]);
    expect(demoLabel(60)).toBe('DEMO POOL: each "day" lasts 60 seconds. For demonstration only, with test money.');
    expect(demoLabel(600)).toContain("10 minutes");
    expect(demoLabel(60)).toContain("DEMO POOL");
  });
});

describe("demo pools in jobs and the coach", () => {
  it("never feed the coach", async () => {
    const w = await makeWorld();
    const kp = Keypair.generate();
    const { headers } = await signIn(w, kp);
    const ch = seedChallenge(w, { durationDays: 10, requiredDays: 7, isDemo: true, daySecs: 60 });
    (w.chain as unknown as { time: number }).time = ch.startTs + 9 * 60 + 30;
    seedParticipant(w, ch.pool, kp.publicKey.toBase58(), { bitmap: 0b1n, days: 1 });
    expect((await w.app.inject({ method: "GET", url: "/v1/coach/suggestions", headers })).json()).toEqual({ suggestions: [] });
    // control: the same history in a normal pool does produce advice
    const normal = seedChallenge(w, { durationDays: 10, requiredDays: 7 });
    (w.chain as unknown as { time: number }).time = normal.startTs + 8 * 86_400 + 12 * 3_600;
    seedParticipant(w, normal.pool, kp.publicKey.toBase58(), { bitmap: 0b00010011n, days: 3 });
    expect((await w.app.inject({ method: "GET", url: "/v1/coach/suggestions", headers })).json().suggestions).toHaveLength(1);
  });

  it("send no evening reminders, while a normal pool still gets one", async () => {
    const w = await makeWorld();
    const kp = Keypair.generate();
    const wallet = kp.publicKey.toBase58();
    const { headers } = await signIn(w, kp);
    await w.app.inject({ method: "POST", url: "/v1/push/register", headers, payload: { token: "t".repeat(40) } });
    // long demo days so the pool is still running at 20:00: only the demo flag can keep it out of the reminders
    const demo = seedChallenge(w, { durationDays: 60, isDemo: true, daySecs: 3_600 });
    const normal = seedChallenge(w, { durationDays: 3 });
    seedParticipant(w, demo.pool, wallet, {});
    seedParticipant(w, normal.pool, wallet, {});
    (w.chain as unknown as { time: number }).time = normal.startTs + 20 * 3_600; // 20:00 UTC on day 0, past the 18:00 reminder hour
    expect(await sendReminders(w.s)).toBe(1);
    expect(w.push.sent).toHaveLength(1);
    expect(w.push.sent[0]!.message.data).toEqual({ type: "reminder", pool: normal.pool });
  });

  it("record missed days on the demo schedule", async () => {
    const w = await makeWorld();
    const owner = Keypair.generate().publicKey.toBase58();
    const mate = Keypair.generate().publicKey.toBase58();
    const squad = "a".repeat(32);
    w.s.db.prepare("INSERT INTO squads (id, name, owner, invite_code, created_at) VALUES (?,?,?,?,?)").run(squad, "s", owner, "ABCDEFGH", 0);
    const ch = seedChallenge(w, { durationDays: 3, squadId: squad, isDemo: true, daySecs: 60 });
    seedParticipant(w, ch.pool, mate, { bitmap: 0n });
    const set = (t: number) => ((w.chain as unknown as { time: number }).time = t);
    set(ch.startTs + 74); // day 0 window (60 s + 15 s grace) still open
    expect(recordMissedDays(w.s)).toBe(0);
    set(ch.startTs + 75);
    expect(recordMissedDays(w.s)).toBe(1); // day 0 closed
    set(ch.startTs + 135 + 15); // days 0 and 1 closed, day 2 still open
    expect(recordMissedDays(w.s)).toBe(1); // day 1 added, day 0 not repeated
    const days = (w.s.db.prepare("SELECT data_json FROM feed_events WHERE kind = 'missed' ORDER BY id").all() as { data_json: string }[]).map((r) => JSON.parse(r.data_json).day);
    expect(days).toEqual([0, 1]);
  });
});
