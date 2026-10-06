import { PublicKey, Transaction } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { planHash } from "../../src/domain/plan.js";
import { pollOnce } from "../../src/indexer.js";
import { recordMissedDays } from "../../src/jobs/daily.js";
import { runCrank } from "../../src/jobs/crank.js";
import { retryPending } from "../../src/oracle.js";
import { ataAddress } from "../../src/util/token.js";
import { balance, BASE, DAY, get, makeChainWorld, makePlayer, post, sha, signAndSendB64, UNIT, type ChainWorld, type Player } from "../helpers/e2e-world.js";
import { signAndSend } from "../../src/chain/tx.js";
import { loadLiteSvm } from "../helpers/litesvm-chain.js";
import { samplePlan } from "../helpers/world.js";

const lib = await loadLiteSvm();
const START = BASE + DAY;

/** create a pool through the API and have the creator "sign" it. Returns the pool address. */
async function createPool(w: ChainWorld, creator: Player, plan = samplePlan(), over: Record<string, unknown> = {}) {
  const res = await post(w, creator, "/v1/challenges/tx/create", { mint: w.mint.publicKey.toBase58(), kind: "Squad", mode: "Hard", startTs: START, plan, maxParticipants: 10, ...over });
  if (res.statusCode !== 200) throw new Error(`create failed ${res.body}`);
  await signAndSendB64(w, creator, res.json().transaction);
  return res.json().pool as string;
}
async function join(w: ChainWorld, p: Player, pool: string, stake = 30n * UNIT, tz = 0) {
  const res = await post(w, p, "/v1/challenges/tx/join", { pool, stake: stake.toString(), tzOffsetMinutes: tz, deviceId: p.device.id });
  if (res.statusCode !== 200) throw new Error(`join failed ${res.body}`);
  await signAndSendB64(w, p, res.json().transaction);
}

interface ProveOpts {
  metrics?: Record<string, unknown>;
  tweak?: (pkg: Record<string, unknown>) => Record<string, unknown>;
  signWith?: Player["device"];
  endedOffset?: number;
}
/** Runs the session + submit flow the way the app would. */
async function prove(w: ChainWorld, p: Player, pool: string, day: number, o: ProveOpts = {}) {
  const sess = await post(w, p, "/v1/proofs/session", { pool, dayIndex: day, proofType: "CAMERA_POSE" });
  if (sess.statusCode !== 200) return { sess, sub: sess };
  const s = sess.json();
  const now = w.chain.nowSec();
  const base = {
    sessionId: s.sessionId, nonce: s.nonce, challengeId: pool, dayIndex: day, proofType: "CAMERA_POSE" as const,
    metrics: o.metrics ?? { reps: 25, livenessPassed: true },
    startedAt: now - 60 + (o.endedOffset ?? 0), endedAt: now - 5 + (o.endedOffset ?? 0),
    evidenceHash: sha(`evidence-${p.wallet}-${day}`), deviceKeyId: p.device.id,
  };
  const signed = (o.signWith ?? p.device).signPackage(base);
  const payload = o.tweak ? o.tweak(signed as unknown as Record<string, unknown>) : signed;
  const sub = await post(w, p, "/v1/proofs/submit", payload);
  return { sess, sub, payload };
}
const dayOf = (day: number, hours = 1) => START + day * DAY + hours * 3600;

describe.skipIf(!lib)("API lifecycle against the real program (LiteSVM)", () => {
  it("create, join, prove, settle, claim, with squad feed, replay safety and coach data", async () => {
    const w = (await makeChainWorld())!;
    const alice = await makePlayer(w);
    const bob = await makePlayer(w);
    const plan = samplePlan();

    // --- create: the API builds an unsigned tx; the wallet signs it
    const created = await post(w, alice, "/v1/challenges/tx/create", { mint: w.mint.publicKey.toBase58(), kind: "Squad", mode: "Hard", startTs: START, plan, maxParticipants: 10 });
    expect(created.statusCode).toBe(200);
    const tx = Transaction.from(Buffer.from(created.json().transaction, "base64"));
    expect(tx.feePayer!.toBase58()).toBe(alice.wallet);
    expect(tx.instructions).toHaveLength(1);
    expect(tx.instructions[0]!.programId.toBase58()).toBe(w.program.programId.toBase58()); // the app must check this before signing
    expect(tx.signatures.every((s) => s.signature === null)).toBe(true); // nothing is pre-signed
    expect(created.json().summary).toMatchObject({ action: "create_pool", mode: "Hard", trustTier: "high", goalHash: planHash(plan) });
    await signAndSendB64(w, alice, created.json().transaction);
    const pool = created.json().pool as string;

    const ch = (await get(w, alice, `/v1/challenges/${pool}`)).json();
    expect(ch.challenge).toMatchObject({ status: "Open", mode: "Hard", durationDays: 3, requiredDays: 2, goalHash: planHash(plan), trustTier: "high", participantCount: 0 });
    expect(ch.challenge.plan.title).toBe(plan.title); // the plan uploaded at build time is attached because its hash matches the onchain goal_hash

    // --- squad
    const sq = (await post(w, alice, "/v1/squads", { name: "Crew" })).json();
    await post(w, bob, "/v1/squads/join", { code: sq.inviteCode });
    expect((await post(w, alice, `/v1/squads/${sq.id}/challenges`, { pool })).statusCode).toBe(200);

    // --- join (stake moves into the program vault)
    await join(w, alice, pool);
    await join(w, bob, pool);
    expect(await balance(w, alice.token)).toBe(70n * UNIT);
    expect((await get(w, alice, `/v1/challenges/${pool}`)).json().challenge).toMatchObject({ participantCount: 2, totalDeposits: (60n * UNIT).toString() });
    expect(await balance(w, w.program.vaultPda(pool).toBase58())).toBe(60n * UNIT);

    // --- day 0: alice proves; replay is safe
    w.chain.setTime(dayOf(0));
    const a0 = await prove(w, alice, pool, 0);
    expect(a0.sub.statusCode, a0.sub.body).toBe(200);
    expect(a0.sub.json()).toMatchObject({ accepted: true, trustTier: "high", daysCompleted: 1, streak: 1, checkin: { status: "confirmed" } });
    const sig = a0.sub.json().checkin.signature as string;
    const replay = await post(w, alice, "/v1/proofs/submit", a0.payload);
    expect(replay.json().checkin.signature).toBe(sig); // same result, no second transaction
    expect(w.s.db.prepare("SELECT COUNT(*) AS n FROM checkins WHERE pool = ?").get(pool)).toEqual({ n: 1 });
    expect((await post(w, alice, "/v1/proofs/session", { pool, dayIndex: 0, proofType: "CAMERA_POSE" })).json().error.code).toBe("already_recorded");

    // bob cheats with too few reps: rejected; the session is burned
    const b0bad = await prove(w, bob, pool, 0, { metrics: { reps: 10, livenessPassed: true } });
    expect(b0bad.sub.statusCode).toBe(422);
    expect(b0bad.sub.json().error).toMatchObject({ code: "proof_rejected", message: "not enough reps" });
    expect((await post(w, bob, "/v1/proofs/submit", b0bad.payload)).json().error.code).toBe("session_closed");
    // then does it properly
    expect((await prove(w, bob, pool, 0)).sub.statusCode).toBe(200);

    // --- day 1: only alice
    w.chain.setTime(dayOf(1));
    const a1 = await prove(w, alice, pool, 1);
    expect(a1.sub.json()).toMatchObject({ daysCompleted: 2, streak: 2 });
    // after day 1's window (plus grace) closed, bob's miss is recorded in the squad feed
    w.chain.setTime(dayOf(2, 3));
    expect(recordMissedDays(w.s)).toBe(1);
    expect(recordMissedDays(w.s)).toBe(0); // once per day

    // --- settlement by the crank after end + grace
    w.chain.setTime(START + 3 * DAY + 7_199);
    expect((await runCrank(w.s)).settled).toBe(0); // too early: nothing is due
    w.chain.setTime(START + 3 * DAY + 7_200);
    const report = await runCrank(w.s);
    expect(report).toMatchObject({ settled: 2, errors: [] });
    const settled = (await get(w, alice, `/v1/challenges/${pool}`)).json();
    expect(settled.challenge).toMatchObject({ status: "Settled", distributable: (30n * UNIT).toString(), pendingClaims: 1 });
    expect(settled.me).toEqual({ joined: true, claimable: (60n * UNIT).toString() });
    expect((await get(w, bob, `/v1/challenges/${pool}`)).json().me.claimable).toBe("0");

    // --- claim
    const claim = await post(w, alice, "/v1/challenges/tx/claim", { pool });
    expect(claim.json().summary).toMatchObject({ action: "claim", expectedAmount: (60n * UNIT).toString() });
    await signAndSendB64(w, alice, claim.json().transaction);
    expect(await balance(w, alice.token)).toBe(130n * UNIT); // 100 - 30 staked + 60 back
    expect((await post(w, alice, "/v1/challenges/tx/claim", { pool })).json().error.code).toBe("already_claimed");
    expect((await post(w, bob, "/v1/challenges/tx/claim", { pool })).json().error.code).toBe("nothing_to_claim");
    expect(await balance(w, w.program.vaultPda(pool).toBase58())).toBe(0n);
    expect((await runCrank(w.s)).swept).toBe(0); // nothing left to sweep

    // --- squad feed and coach data
    const feed = (await get(w, bob, `/v1/squads/${sq.id}/feed?limit=50`)).json().events as { kind: string; wallet: string; data: Record<string, unknown> }[];
    const kinds = feed.map((e) => e.kind);
    expect(kinds.filter((k) => k === "joined")).toHaveLength(2);
    expect(kinds.filter((k) => k === "checked_in")).toHaveLength(3);
    expect(kinds.filter((k) => k === "settled")).toHaveLength(2);
    expect(feed.find((e) => e.kind === "missed")).toMatchObject({ wallet: bob.wallet, data: { day: 1 } });
    expect(w.s.db.prepare("SELECT outcome FROM coach_stats WHERE wallet = ?").get(alice.wallet)).toEqual({ outcome: "succeeded" });
    expect(w.s.db.prepare("SELECT outcome FROM coach_stats WHERE wallet = ?").get(bob.wallet)).toEqual({ outcome: "failed" });
    const lb = (await get(w, alice, `/v1/squads/${sq.id}/leaderboard`)).json().rows;
    expect(lb.map((r: { wallet: string; daysCompleted: number }) => [r.wallet, r.daysCompleted])).toEqual([[alice.wallet, 2], [bob.wallet, 1]]);
  });

  it("rejects bad proofs: wrong nonce, other device, tampering, expiry, timing, strangers", async () => {
    const w = (await makeChainWorld())!;
    const [alice, bob, mallory] = [await makePlayer(w), await makePlayer(w), await makePlayer(w)];
    const pool = await createPool(w, alice);
    await join(w, alice, pool);
    await join(w, bob, pool);
    w.chain.setTime(dayOf(0));
    const reason = (r: { sub: { json(): { error?: { message: string } } } }) => r.sub.json().error?.message;

    expect(reason(await prove(w, alice, pool, 0, { tweak: (p) => ({ ...p, nonce: "f".repeat(32) }) }))).toBe("package does not match the proof session");
    // signed with someone else's device key while claiming alice's device id
    expect(reason(await prove(w, alice, pool, 0, { signWith: bob.device }))).toBe("device signature is invalid");
    // different device id than the one the stake committed to
    expect(reason(await prove(w, alice, pool, 0, { tweak: (p) => ({ ...p, deviceKeyId: bob.device.id }) }))).toBe("proof was signed by a different device than the one joined with");
    // metrics changed after signing
    expect(reason(await prove(w, alice, pool, 0, { tweak: (p) => ({ ...p, metrics: { reps: 99, livenessPassed: true } }) }))).toBe("device signature is invalid");
    // timestamps: future, stale, previous day
    expect(reason(await prove(w, alice, pool, 0, { endedOffset: 600 }))).toBe("proof is timestamped in the future");
    expect(reason(await prove(w, alice, pool, 0, { endedOffset: -2_000 }))).toBe("proof is too old");
    expect(reason(await prove(w, alice, pool, 0, { endedOffset: -3_600 - 7_200 }))).toBeTruthy();
    expect(w.s.db.prepare("SELECT COUNT(*) AS n FROM checkins").get()).toEqual({ n: 0 }); // nothing reached the chain

    // sessions
    expect((await post(w, mallory, "/v1/proofs/session", { pool, dayIndex: 0, proofType: "CAMERA_POSE" })).json().error.code).toBe("not_participant");
    expect((await post(w, alice, "/v1/proofs/session", { pool, dayIndex: 1, proofType: "CAMERA_POSE" })).json().error.code).toBe("window_closed"); // day 1 not open yet
    expect((await post(w, alice, "/v1/proofs/session", { pool, dayIndex: 3, proofType: "CAMERA_POSE" })).json().error.code).toBe("day_out_of_range");
    expect((await post(w, alice, "/v1/proofs/session", { pool, dayIndex: 0, proofType: "STEPS" })).json().error.code).toBe("proof_type_not_in_plan");
    const sess = (await post(w, alice, "/v1/proofs/session", { pool, dayIndex: 0, proofType: "CAMERA_POSE" })).json();
    // another wallet cannot use or even see alice's session
    const pkg = alice.device.signPackage({ sessionId: sess.sessionId, nonce: sess.nonce, challengeId: pool, dayIndex: 0, proofType: "CAMERA_POSE", metrics: { reps: 25, livenessPassed: true }, startedAt: w.chain.nowSec() - 60, endedAt: w.chain.nowSec() - 5, evidenceHash: sha("x"), deviceKeyId: alice.device.id });
    expect((await post(w, mallory, "/v1/proofs/submit", pkg)).statusCode).toBe(404);
    // the session expires after five minutes of real time
    w.clock.wall += 301;
    expect((await post(w, alice, "/v1/proofs/submit", pkg)).json().error.code).toBe("session_expired");
    expect(w.s.db.prepare("SELECT COUNT(*) AS n FROM checkins").get()).toEqual({ n: 0 });
  });

  it("two concurrent submissions of one proof produce exactly one check-in and no server error", async () => {
    const w = (await makeChainWorld())!;
    const alice = await makePlayer(w);
    const pool = await createPool(w, alice);
    await join(w, alice, pool);
    w.chain.setTime(dayOf(0));
    const first = await prove(w, alice, pool, 0, {}); // establishes a valid session+package, then we race a fresh one
    expect(first.sub.statusCode).toBe(200);
    w.chain.setTime(dayOf(1));
    const sess = (await post(w, alice, "/v1/proofs/session", { pool, dayIndex: 1, proofType: "CAMERA_POSE" })).json();
    const now = w.chain.nowSec();
    const pkg = alice.device.signPackage({ sessionId: sess.sessionId, nonce: sess.nonce, challengeId: pool, dayIndex: 1, proofType: "CAMERA_POSE", metrics: { reps: 25, livenessPassed: true }, startedAt: now - 60, endedAt: now - 5, evidenceHash: sha("race"), deviceKeyId: alice.device.id });
    w.chain.delayMs = 25; // real RPC calls take time, so the two requests overlap
    const [r1, r2] = await Promise.all([post(w, alice, "/v1/proofs/submit", pkg), post(w, alice, "/v1/proofs/submit", pkg)]);
    w.chain.delayMs = 0;
    expect([r1.statusCode, r2.statusCode].every((c) => c < 500)).toBe(true);
    expect([r1.statusCode, r2.statusCode]).toContain(200);
    expect(w.s.db.prepare("SELECT COUNT(*) AS n FROM checkins WHERE day_index = 1").get()).toEqual({ n: 1 });
    expect(w.s.db.prepare("SELECT COUNT(*) AS n FROM proofs WHERE session_id = ?").get(sess.sessionId)).toEqual({ n: 1 });
    const again = await post(w, alice, "/v1/proofs/submit", pkg); // and a later retry still returns the stored result
    expect(again.statusCode).toBe(200);
  });

  it("only squad members can join a pool linked to a squad", async () => {
    const w = (await makeChainWorld())!;
    const [alice, bob, mallory] = [await makePlayer(w), await makePlayer(w), await makePlayer(w)];
    const pool = await createPool(w, alice);
    const sq = (await post(w, alice, "/v1/squads", { name: "Crew" })).json();
    await post(w, alice, `/v1/squads/${sq.id}/challenges`, { pool });
    const j = (p: Player) => post(w, p, "/v1/challenges/tx/join", { pool, stake: (10n * UNIT).toString(), tzOffsetMinutes: 0, deviceId: p.device.id });
    expect((await j(mallory)).json().error.code).toBe("not_in_squad");
    expect((await j(bob)).json().error.code).toBe("not_in_squad");
    await post(w, bob, "/v1/squads/join", { code: sq.inviteCode });
    expect((await j(bob)).statusCode).toBe(200);
    expect((await j(alice)).statusCode).toBe(200); // the creator
  });

  it("keeps an accepted proof when the network is down and sends the check-in later, exactly once", async () => {
    const w = (await makeChainWorld())!;
    const alice = await makePlayer(w);
    const pool = await createPool(w, alice);
    await join(w, alice, pool);
    w.chain.setTime(dayOf(0));
    w.chain.failNext = 1;
    const first = await prove(w, alice, pool, 0);
    expect(first.sub.statusCode).toBe(200);
    expect(first.sub.json().checkin).toMatchObject({ status: "pending", error: "rpc unavailable" });
    expect(first.sub.json().daysCompleted).toBe(0);
    // the retry job completes it
    expect(await retryPending(w.s)).toBe(1);
    expect(await retryPending(w.s)).toBe(0);
    const again = await post(w, alice, "/v1/proofs/submit", first.payload);
    expect(again.json()).toMatchObject({ checkin: { status: "confirmed" } });
    const view = (await get(w, alice, `/v1/challenges/${pool}`)).json();
    expect(view.participants.find((p: { wallet: string }) => p.wallet === alice.wallet).daysCompleted).toBe(1);
    expect(w.s.db.prepare("SELECT COUNT(*) AS n FROM checkins WHERE status = 'confirmed'").get()).toEqual({ n: 1 });
  });

  it("when nobody succeeds the crank sweeps everything to the treasury", async () => {
    const w = (await makeChainWorld({ feeBps: 500 }))!;
    const [alice, bob] = [await makePlayer(w), await makePlayer(w)];
    const pool = await createPool(w, alice);
    await join(w, alice, pool, 20n * UNIT);
    await join(w, bob, pool, 40n * UNIT);
    w.chain.setTime(START + 3 * DAY + 7_200);
    // one pass settles both participations and, since nothing is claimable, sweeps the vault
    expect(await runCrank(w.s)).toMatchObject({ settled: 2, swept: 1, errors: [] });
    expect(await balance(w, ataAddress(w.treasury.publicKey, w.mint.publicKey).toBase58())).toBe(60n * UNIT);
    expect(await balance(w, w.program.vaultPda(pool).toBase58())).toBe(0n);
    expect((await runCrank(w.s)).swept).toBe(0);
  });

  it("transaction builders refuse unsafe requests before anything is signed", async () => {
    const w = (await makeChainWorld())!;
    const [alice, bob, poor] = [await makePlayer(w), await makePlayer(w), await makePlayer(w, 5n * UNIT)];
    const mint = w.mint.publicKey.toBase58();
    const create = (over: Record<string, unknown>) => post(w, alice, "/v1/challenges/tx/create", { mint, kind: "Squad", mode: "Hard", startTs: START, plan: samplePlan(), ...over });
    expect((await create({ mint: new PublicKey(new Uint8Array(32).fill(7)).toBase58() })).json().error.code).toBe("mint_not_allowed");
    expect((await create({ plan: samplePlan({ verifiable: false, unverifiableReason: "cannot verify mood" }) })).json().error).toMatchObject({ code: "plan_not_verifiable", message: "cannot verify mood" });
    expect((await create({ startTs: w.chain.nowSec() + 30 })).json().error.code).toBe("start_too_soon");
    expect((await create({ mode: "Soft" })).json().error.code).toBe("bad_penalty");
    expect((await create({ mode: "Soft", penaltyBps: 6_000 })).json().error.code).toBe("bad_penalty");
    expect((await create({ plan: { ...samplePlan(), extra: 1 } })).statusCode).toBe(400);
    expect((await create({ plan: samplePlan({ cadence: { periodDays: 1, totalDays: 3, requiredDays: 5 } }) })).statusCode).toBe(400);

    // a low-trust plan is capped at 10% of the program's max stake
    const lowPlan = samplePlan({ proofMethods: [{ type: "SELF_ATTEST", params: {}, trustTier: "low" }] });
    const lowPool = await createPool(w, alice, lowPlan);
    const j = (p: Player, pool: string, stake: bigint, extra: Record<string, unknown> = {}) => post(w, p, "/v1/challenges/tx/join", { pool, stake: stake.toString(), tzOffsetMinutes: 0, deviceId: p.device.id, ...extra });
    expect((await j(alice, lowPool, 11n * UNIT)).json().error).toMatchObject({ code: "stake_over_cap", details: { cap: (10n * UNIT).toString() } });
    expect((await j(alice, lowPool, 10n * UNIT)).statusCode).toBe(200);

    const pool = await createPool(w, alice);
    expect((await j(alice, pool, 101n * UNIT)).json().error.code).toBe("stake_over_cap"); // global max 100
    expect((await j(alice, pool, 0n)).json().error.code).toBe("stake_zero");
    expect((await j(poor, pool, 30n * UNIT)).json().error.code).toBe("insufficient_funds");
    expect((await j(bob, pool, 30n * UNIT, { deviceId: "a".repeat(64) })).json().error.code).toBe("device_not_registered");
    expect((await j(bob, pool, 30n * UNIT, { tzOffsetMinutes: 900 })).statusCode).toBe(400);
    await join(w, bob, pool);
    expect((await j(bob, pool, 30n * UNIT)).json().error.code).toBe("already_joined");
    // after the join window closes
    w.chain.setTime(START + 3_601);
    expect((await j(alice, pool, 30n * UNIT)).json().error.code).toBe("join_closed");
    // claims before settlement
    expect((await post(w, bob, "/v1/challenges/tx/claim", { pool })).json().error.code).toBe("not_settled");
    expect((await post(w, poor, "/v1/challenges/tx/claim", { pool })).json().error.code).toBe("not_participant");
    expect((await post(w, alice, "/v1/challenges/tx/claim", { pool: new PublicKey(new Uint8Array(32).fill(9)).toBase58() })).statusCode).toBe(404);
  });

  it("returns the same transaction for a retried request with the same Idempotency-Key", async () => {
    const w = (await makeChainWorld())!;
    const alice = await makePlayer(w);
    const body = { mint: w.mint.publicKey.toBase58(), kind: "Squad", mode: "Hard", startTs: START, plan: samplePlan() };
    const a = await post(w, alice, "/v1/challenges/tx/create", body, { "idempotency-key": "retry-key-0001" });
    const b = await post(w, alice, "/v1/challenges/tx/create", body, { "idempotency-key": "retry-key-0001" });
    const c = await post(w, alice, "/v1/challenges/tx/create", body, { "idempotency-key": "retry-key-0002" });
    expect(b.json()).toEqual(a.json());
    expect(c.json().pool).not.toBe(a.json().pool);
    expect((await post(w, alice, "/v1/challenges/tx/create", body, { "idempotency-key": "x" })).statusCode).toBe(400);
  });

  it("the poller mirrors transactions the app never reported", async () => {
    const w = (await makeChainWorld())!;
    const alice = await makePlayer(w);
    const res = await post(w, alice, "/v1/challenges/tx/create", { mint: w.mint.publicKey.toBase58(), kind: "Squad", mode: "Hard", startTs: START, plan: samplePlan() });
    const tx = Transaction.from(Buffer.from(res.json().transaction, "base64"));
    tx.partialSign(alice.kp);
    await w.chain.sendAndConfirm(tx); // no /challenges/sync call
    expect((await get(w, alice, `/v1/challenges/${res.json().pool}`)).statusCode).toBe(404);
    expect(await pollOnce(w.s)).toBeGreaterThanOrEqual(1);
    expect((await get(w, alice, `/v1/challenges/${res.json().pool}`)).json().challenge.status).toBe("Open");
    expect(await pollOnce(w.s)).toBe(0); // nothing new
  });

  it("after the admin voids a pool everyone can claim their full stake", async () => {
    const w = (await makeChainWorld())!;
    const [alice, bob] = [await makePlayer(w), await makePlayer(w)];
    const pool = await createPool(w, alice);
    await join(w, alice, pool, 30n * UNIT);
    await join(w, bob, pool, 10n * UNIT);
    const res = await signAndSend(w.chain, [w.program.ixVoid(w.admin.publicKey, pool)], [w.admin]);
    await post(w, alice, "/v1/challenges/sync", { signature: res.signature });
    expect((await get(w, bob, `/v1/challenges/${pool}`)).json()).toMatchObject({ challenge: { status: "Voided" }, me: { claimable: (10n * UNIT).toString() } });
    // no more proofs on a voided challenge
    w.chain.setTime(dayOf(0));
    expect((await post(w, alice, "/v1/proofs/session", { pool, dayIndex: 0, proofType: "CAMERA_POSE" })).json().error.code).toBe("challenge_closed");
    for (const p of [alice, bob]) {
      const c = await post(w, p, "/v1/challenges/tx/claim", { pool });
      await signAndSendB64(w, p, c.json().transaction);
      expect(await balance(w, p.token)).toBe(100n * UNIT);
    }
  });

  it("refuses to build transactions while the program is paused", async () => {
    const w = (await makeChainWorld())!;
    const alice = await makePlayer(w);
    await signAndSend(w.chain, [w.program.ixSetPaused(w.admin.publicKey, true)], [w.admin]);
    const res = await post(w, alice, "/v1/challenges/tx/create", { mint: w.mint.publicKey.toBase58(), kind: "Squad", mode: "Hard", startTs: START, plan: samplePlan() });
    expect(res.json().error.code).toBe("paused");
  });
});
