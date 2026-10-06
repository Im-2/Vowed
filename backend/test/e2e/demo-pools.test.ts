/** DEMO POOLS through the API against the real program: minutes-long days, clearly labelled, with tighter limits than normal pools. */
import { PublicKey } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { runCrank } from "../../src/jobs/crank.js";
import { signAndSend } from "../../src/chain/tx.js";
import { balance, BASE, get, makeChainWorld, makePlayer, post, sha, signAndSendB64, UNIT, type ChainWorld, type Player } from "../helpers/e2e-world.js";
import { loadLiteSvm } from "../helpers/litesvm-chain.js";
import { samplePlan } from "../helpers/world.js";

const lib = await loadLiteSvm();
const plan = (days = 2, required = 2) => samplePlan({ cadence: { periodDays: 1, totalDays: days, requiredDays: required } });

async function createDemo(w: ChainWorld, creator: Player, over: Record<string, unknown> = {}) {
  const startTs = w.chain.nowSec() + 60;
  const res = await post(w, creator, "/v1/challenges/tx/create", { mint: w.mint.publicKey.toBase58(), kind: "Squad", mode: "Hard", startTs, plan: plan(), demo: { daySecs: 60 }, ...over });
  return { res, startTs };
}

async function prove(w: ChainWorld, p: Player, pool: string, day: number) {
  const sess = await post(w, p, "/v1/proofs/session", { pool, dayIndex: day, proofType: "CAMERA_POSE" });
  if (sess.statusCode !== 200) return { sess, sub: sess };
  const s = sess.json();
  const now = w.chain.nowSec();
  const pkg = p.device.signPackage({
    sessionId: s.sessionId, nonce: s.nonce, challengeId: pool, dayIndex: day, proofType: "CAMERA_POSE",
    metrics: { reps: 25, livenessPassed: true }, startedAt: now - 20, endedAt: now - 2,
    evidenceHash: sha(`demo-${p.wallet}-${day}`), deviceKeyId: p.device.id,
  });
  return { sess, sub: await post(w, p, "/v1/proofs/submit", pkg) };
}

describe.skipIf(!lib)("demo pools through the API (LiteSVM)", () => {
  it("run the whole loop in three minutes, labelled as demo everywhere, with payouts like a normal pool", async () => {
    const w = (await makeChainWorld())!;
    const [alice, bob] = [await makePlayer(w), await makePlayer(w)];

    const { res, startTs } = await createDemo(w, alice);
    expect(res.statusCode, res.body).toBe(200);
    expect(res.json().summary).toMatchObject({ action: "create_pool", isDemo: true, daySecs: 60, maxStake: (5n * UNIT).toString() }); // the demo cap, not the normal 100
    expect(res.json().summary.label).toContain("DEMO POOL");
    await signAndSendB64(w, alice, res.json().transaction);
    const pool = res.json().pool as string;

    const view = (await get(w, bob, `/v1/challenges/${pool}`)).json().challenge;
    expect(view).toMatchObject({ isDemo: true, daySecs: 60, durationDays: 2, startTs, endTs: startTs + 120, settleAfterTs: startTs + 180, trustTier: "high" });
    expect(view.demoLabel).toContain("DEMO POOL");
    expect(view.demoLabel).toContain("test money");

    for (const [p, stake] of [[alice, 4n], [bob, 2n]] as [Player, bigint][]) {
      const j = await post(w, p, "/v1/challenges/tx/join", { pool, stake: (stake * UNIT).toString(), tzOffsetMinutes: 0, deviceId: p.device.id });
      expect(j.statusCode, j.body).toBe(200);
      expect(j.json().summary).toMatchObject({ isDemo: true, daySecs: 60 });
      await signAndSendB64(w, p, j.json().transaction);
    }

    // day 0 window is [start, start+75): alice proves at +10; day 1 is not open yet
    w.chain.setTime(startTs + 10);
    const d1early = await post(w, alice, "/v1/proofs/session", { pool, dayIndex: 1, proofType: "CAMERA_POSE" });
    expect(d1early.json().error.code).toBe("window_closed");
    const a0 = await prove(w, alice, pool, 0);
    expect(a0.sess.json()).toMatchObject({ isDemo: true, daySecs: 60, window: { opensAt: startTs, closesAt: startTs + 75 } });
    expect(a0.sub.statusCode, a0.sub.body).toBe(200);
    expect(a0.sub.json()).toMatchObject({ accepted: true, daysCompleted: 1, checkin: { status: "confirmed" } });

    // day 1 opens at start+60; bob never proves
    w.chain.setTime(startTs + 70);
    const a1 = await prove(w, alice, pool, 1);
    expect(a1.sub.json()).toMatchObject({ daysCompleted: 2, streak: 2 });
    // day 0 is over for good once its 15-second grace has passed (it closed at start+75)
    w.chain.setTime(startTs + 76);
    expect((await post(w, bob, "/v1/proofs/session", { pool, dayIndex: 0, proofType: "CAMERA_POSE" })).json().error.code).toBe("window_closed");

    // settlement opens one demo day after the end: start + 120 + 60
    w.chain.setTime(startTs + 179);
    expect((await runCrank(w.s)).settled).toBe(0);
    w.chain.setTime(startTs + 180);
    expect(await runCrank(w.s)).toMatchObject({ settled: 2, errors: [] });
    expect(w.chain.nowSec() - (startTs - 60)).toBeLessThanOrEqual(240); // from creation to settled: about four minutes of cluster time

    const me = (await get(w, alice, `/v1/challenges/${pool}`)).json();
    expect(me.challenge.status).toBe("Settled");
    expect(me.me.claimable).toBe((6n * UNIT).toString()); // her 4 plus bob's 2
    const claim = await post(w, alice, "/v1/challenges/tx/claim", { pool });
    await signAndSendB64(w, alice, claim.json().transaction);
    expect(await balance(w, alice.token)).toBe(102n * UNIT);
    expect(await balance(w, bob.token)).toBe(98n * UNIT);
    expect(await balance(w, w.program.vaultPda(pool).toBase58())).toBe(0n);

    // a demo pool is not habit history: nothing for the coach
    expect(w.s.db.prepare("SELECT COUNT(*) AS n FROM coach_stats").get()).toEqual({ n: 0 });
    expect((await get(w, alice, "/v1/coach/suggestions")).json()).toEqual({ suggestions: [] });
  });

  it("refuses demo pools that would be looser than the normal rules", async () => {
    const w = (await makeChainWorld())!;
    const alice = await makePlayer(w);
    const code = async (over: Record<string, unknown>) => (await createDemo(w, alice, over)).res.json().error?.code;

    expect(await code({ mint: w.mintB.publicKey.toBase58() })).toBe("demo_mint_not_allowed"); // allowed for normal pools only
    expect(await code({ maxParticipants: 21 })).toBe("demo_too_many_participants");
    expect(await code({ joinWindowSecs: 61 })).toBe("demo_join_window");
    expect(await code({ startTs: w.chain.nowSec() + 86_401 })).toBe("demo_start_too_far");
    expect(await code({ startTs: w.chain.nowSec() + 30 })).toBe("start_too_soon");
    expect(await code({ demo: { daySecs: 59 } })).toBe("validation_error");
    expect(await code({ demo: { daySecs: 3_601 } })).toBe("validation_error");
    expect(await code({ plan: plan(61, 5) })).toBe("validation_error"); // the 60-day limit still applies
    expect(await code({ mode: "Soft", penaltyBps: 5_001 })).toBe("bad_penalty");
    expect(await code({ plan: { ...plan(), verifiable: false, unverifiableReason: "cannot verify" } })).toBe("plan_not_verifiable");
    // the boundaries themselves are fine
    expect((await createDemo(w, alice, { demo: { daySecs: 60 }, maxParticipants: 20, joinWindowSecs: 60 })).res.statusCode).toBe(200);
    expect((await createDemo(w, alice, { demo: { daySecs: 3_600 }, joinWindowSecs: 3_600 })).res.statusCode).toBe(200);

    // and normal pools are untouched: real days, no label, any allowed token
    const normal = await post(w, alice, "/v1/challenges/tx/create", { mint: w.mintB.publicKey.toBase58(), kind: "Squad", mode: "Hard", startTs: w.chain.nowSec() + 86_400, plan: plan(7, 5) });
    expect(normal.statusCode, normal.body).toBe(200);
    expect(normal.json().summary).toMatchObject({ isDemo: false, daySecs: 86_400 });
    expect(normal.json().summary.label).toBeUndefined();
  });

  it("have a lower stake cap than normal pools", async () => {
    const w = (await makeChainWorld())!;
    const [alice, bob] = [await makePlayer(w), await makePlayer(w)];
    const demo = await createDemo(w, alice);
    await signAndSendB64(w, alice, demo.res.json().transaction);
    const normal = await post(w, alice, "/v1/challenges/tx/create", { mint: w.mint.publicKey.toBase58(), kind: "Squad", mode: "Hard", startTs: w.chain.nowSec() + 3_600, plan: plan(7, 5) });
    await signAndSendB64(w, alice, normal.json().transaction);
    const join = (pool: string, stake: bigint) => post(w, bob, "/v1/challenges/tx/join", { pool, stake: stake.toString(), tzOffsetMinutes: 0, deviceId: bob.device.id });
    const over = await join(demo.res.json().pool, 5n * UNIT + 1n);
    expect(over.json().error).toMatchObject({ code: "stake_over_cap", details: { cap: (5n * UNIT).toString() } });
    expect((await join(demo.res.json().pool, 5n * UNIT)).statusCode).toBe(200);
    expect((await join(normal.json().pool, 50n * UNIT)).statusCode).toBe(200); // the same bob can stake more in a normal pool
  });

  it("cannot be created while the program has demo pools switched off", async () => {
    const w = (await makeChainWorld({ demoEnabled: false, demoMaxStake: 0n }))!;
    const alice = await makePlayer(w);
    const { res } = await createDemo(w, alice);
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe("demo_not_enabled");
    expect((await post(w, alice, "/v1/challenges/tx/create", { mint: w.mint.publicKey.toBase58(), kind: "Squad", mode: "Hard", startTs: BASE + 86_400, plan: plan(7, 5) })).statusCode).toBe(200);
    const meta = (await w.app.inject({ method: "GET", url: "/v1/meta" })).json();
    expect(meta.config).toMatchObject({ demoEnabled: false, demoMaxStake: "0" });
  });

  it("the program itself refuses what the API refuses (the API is not the only guard)", async () => {
    const w = (await makeChainWorld())!;
    const alice = await makePlayer(w);
    const base = {
      pool_id: 999n, kind: "Squad" as const, mode: "Hard" as const, penalty_bps: 10_000, start_ts: BigInt(w.chain.nowSec() + 120), duration_days: 2, required_days: 2,
      goal_hash: new Uint8Array(32), join_window_secs: 30n, max_participants: 5, demo_day_secs: 60,
    };
    const send = (mint: string, over: Partial<typeof base>) => signAndSend(w.chain, [w.program.ixCreatePool(alice.kp.publicKey, new PublicKey(mint), { ...base, ...over })], [alice.kp]);
    await expect(send(w.mintB.publicKey.toBase58(), {})).rejects.toMatchObject({ anchorError: { code: "DemoMintNotAllowed" } });
    await expect(send(w.mint.publicKey.toBase58(), { demo_day_secs: 59 })).rejects.toMatchObject({ anchorError: { code: "InvalidDemoDay" } });
    await expect(send(w.mint.publicKey.toBase58(), { max_participants: 21 })).rejects.toMatchObject({ anchorError: { code: "InvalidPoolParams" } });
    await send(w.mint.publicKey.toBase58(), {}); // the valid one goes through
  });
});
