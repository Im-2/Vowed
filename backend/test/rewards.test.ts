import { Keypair, PublicKey } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { runWeeklyRewards, standingsForWeek, WEEK_SECONDS } from "../src/rewards/service.js";
import { ataAddress } from "../src/util/token.js";
import { TOKEN_PROGRAM_ID } from "../src/program/client.js";
import { seedChallenge, seedParticipant } from "./helpers/seed.js";
import { newMint, TokenChain } from "./helpers/token-chain.js";
import { makeWorld, signIn, StubChain, type World } from "./helpers/world.js";

const W = 2_500; // the week being rewarded
const WEEK_START = W * WEEK_SECONDS;
const SKR_VAULT = 100_000_000n; // 100 test SKR

function bits(...days: number[]) {
  return days.reduce((a, d) => a | (1n << BigInt(d)), 0n);
}

function tokenAccount(chain: TokenChain, owner: string, mint: string, amount: bigint) {
  const data = new Uint8Array(165);
  data.set(new PublicKey(mint).toBytes(), 0);
  data.set(new PublicKey(owner).toBytes(), 32);
  Buffer.from(data.buffer).writeBigUInt64LE(amount, 64);
  chain.accounts.set(ataAddress(owner, mint).toBase58(), { data, lamports: 2_039_280n, owner: TOKEN_PROGRAM_ID.toBase58() });
}

async function rewardsWorld(extraEnv: Record<string, string> = {}, vault = SKR_VAULT, moveClock = true) {
  const rewards = Keypair.generate();
  const skr = newMint();
  const chain = new TokenChain();
  chain.fund(rewards.publicKey.toBase58(), 1_000_000_000n);
  tokenAccount(chain, rewards.publicKey.toBase58(), skr, vault);
  const w = await makeWorld({
    chain,
    env: { REWARDS_SECRET_KEY: JSON.stringify(Array.from(rewards.secretKey)), FAUCET_SKR_MINT: skr, FAUCET_USDC_MINT: newMint(), ...extraEnv },
  });
  // the world's wall clock and chain clock are both set to just after the end of week W
  if (moveClock) {
    w.clock.wall = (W + 1) * WEEK_SECONDS + 100;
    (chain as unknown as StubChain).time = (W + 1) * WEEK_SECONDS + 100;
  }
  return { w, chain, rewards, skr };
}

/** A 7-day challenge that covers week W exactly. */
function weekPool(w: World, over: { count?: number; demo?: boolean } = {}) {
  const c = seedChallenge(w, { startTs: WEEK_START, durationDays: 7, requiredDays: 5, isDemo: over.demo, daySecs: 86_400 });
  w.s.db.prepare("UPDATE challenges SET participant_count = ?, end_ts = ? WHERE pool = ?").run(over.count ?? 5, WEEK_START + 7 * 86_400, c.pool);
  return c;
}
const addr = () => Keypair.generate().publicKey.toBase58();

describe("weekly SKR rewards for the top streaks", () => {
  it("pays the ladder to the longest streaks, in order, and nobody under the minimum", async () => {
    const { w, chain, skr } = await rewardsWorld();
    const c = weekPool(w);
    const [a, b, d, e] = [addr(), addr(), addr(), addr()];
    seedParticipant(w, c.pool, a, { bitmap: bits(0, 1, 2, 3, 4, 5, 6), days: 7 }); // streak 7
    seedParticipant(w, c.pool, b, { bitmap: bits(2, 3, 4, 5, 6), days: 5 }); // streak 5
    seedParticipant(w, c.pool, d, { bitmap: bits(4, 5, 6), days: 3 }); // streak 3
    seedParticipant(w, c.pool, e, { bitmap: bits(5, 6), days: 2 }); // streak 2: under the minimum of 3
    const r = await runWeeklyRewards(w.s, W);
    expect(r.errors).toEqual([]);
    expect(r.paid.map((p) => [p.wallet, p.rank, p.amount])).toEqual([[a, 1, "10000000"], [b, 2, "5000000"], [d, 3, "3000000"]]);
    expect(chain.balance(a, skr)).toBe(10_000_000n);
    expect(chain.balance(b, skr)).toBe(5_000_000n);
    expect(chain.balance(d, skr)).toBe(3_000_000n);
    expect(chain.balance(e, skr)).toBe(0n);
  });

  it("never pays the same wallet twice for a week, however many times it runs", async () => {
    const { w, chain, skr } = await rewardsWorld();
    const c = weekPool(w);
    const a = addr();
    seedParticipant(w, c.pool, a, { bitmap: bits(3, 4, 5, 6), days: 4 });
    seedParticipant(w, c.pool, addr(), { bitmap: bits(), days: 0 });
    await runWeeklyRewards(w.s, W);
    const again = await runWeeklyRewards(w.s, W);
    await runWeeklyRewards(w.s, W);
    expect(again.paid).toEqual([]);
    expect(chain.balance(a, skr)).toBe(10_000_000n);
    expect((w.s.db.prepare("SELECT COUNT(*) AS n FROM rewards WHERE week = ?").get(W) as { n: number }).n).toBe(1);
  });

  it("does not pay for a week that is not over, and does not run when it is not configured", async () => {
    const { w } = await rewardsWorld();
    expect((await runWeeklyRewards(w.s, W + 1)).skipped[0]).toMatch(/not over/);
    const plain = await makeWorld({ chain: new TokenChain() });
    expect((await runWeeklyRewards(plain.s, W)).skipped[0]).toMatch(/not configured/);
  });

  it("ignores demo pools (minutes-long days) unless the operator turns them on, and pools with a single player", async () => {
    const { w, chain, skr } = await rewardsWorld();
    const demo = weekPool(w, { demo: true });
    const solo = weekPool(w, { count: 1 });
    const [g, h] = [addr(), addr()];
    seedParticipant(w, demo.pool, g, { bitmap: bits(0, 1, 2, 3, 4, 5, 6), days: 7 });
    seedParticipant(w, solo.pool, h, { bitmap: bits(0, 1, 2, 3, 4, 5, 6), days: 7 });
    const r = await runWeeklyRewards(w.s, W);
    expect(r.paid).toEqual([]);
    expect(chain.balance(g, skr) + chain.balance(h, skr)).toBe(0n);

    const on = await rewardsWorld({ REWARDS_INCLUDE_DEMO: "true" });
    const d2 = weekPool(on.w, { demo: true });
    const k = addr();
    seedParticipant(on.w, d2.pool, k, { bitmap: bits(3, 4, 5, 6), days: 4 });
    expect((await runWeeklyRewards(on.w.s, W)).paid.map((p) => p.wallet)).toEqual([k]);
  });

  it("counts only challenges that were running during the week and are not voided", async () => {
    const { w } = await rewardsWorld();
    const old = seedChallenge(w, { startTs: WEEK_START - 20 * 86_400, durationDays: 7 });
    w.s.db.prepare("UPDATE challenges SET participant_count = 5, end_ts = ? WHERE pool = ?").run(WEEK_START - 13 * 86_400, old.pool);
    seedParticipant(w, old.pool, addr(), { bitmap: bits(0, 1, 2, 3, 4, 5, 6), days: 7 });
    const voided = weekPool(w);
    w.s.db.prepare("UPDATE challenges SET status = 'Voided' WHERE pool = ?").run(voided.pool);
    seedParticipant(w, voided.pool, addr(), { bitmap: bits(0, 1, 2, 3, 4, 5, 6), days: 7 });
    expect(standingsForWeek(w.s, W)).toEqual([]);
  });

  it("breaks ties by days completed, then by wallet, so the order is the same every time", async () => {
    const { w } = await rewardsWorld();
    const c = weekPool(w);
    const [x, y, z] = [addr(), addr(), addr()].sort();
    seedParticipant(w, c.pool, z, { bitmap: bits(4, 5, 6), days: 3 });
    seedParticipant(w, c.pool, y, { bitmap: bits(4, 5, 6), days: 6 });
    seedParticipant(w, c.pool, x, { bitmap: bits(4, 5, 6), days: 3 });
    expect(standingsForWeek(w.s, W).map((s) => s.wallet)).toEqual([y, x, z]);
  });

  it("a streak freeze keeps the streak alive for the ranking but never changes days completed", async () => {
    const { w } = await rewardsWorld();
    const c = weekPool(w);
    const [a, b] = [addr(), addr()];
    seedParticipant(w, c.pool, a, { bitmap: bits(0, 1, 2, 3, 4, 5, 6), days: 7 }); // streak 7
    seedParticipant(w, c.pool, b, { bitmap: bits(0, 1, 2, 3, 4, 6), days: 6 }); // missed day 5: streak 1 without a freeze
    expect(standingsForWeek(w.s, W).find((s) => s.wallet === b)!.streak).toBe(1);
    w.s.db.prepare("INSERT INTO freezes (wallet, pool, day_index, signature, amount, created_at) VALUES (?,?,?,?,?,?)").run(b, c.pool, 5, "sig-1", "1000000", 1);
    const after = standingsForWeek(w.s, W);
    expect(after.find((s) => s.wallet === b)).toMatchObject({ streak: 7, daysCompleted: 6 });
  });

  it("records a failure when the vault is empty and pays on a later run once it is topped up", async () => {
    const { w, chain, skr, rewards } = await rewardsWorld({}, 1_000_000n); // only 1 SKR in the vault: the 10 SKR first prize cannot be paid
    const c = weekPool(w);
    const a = addr();
    seedParticipant(w, c.pool, a, { bitmap: bits(3, 4, 5, 6), days: 4 });
    seedParticipant(w, c.pool, addr(), { bitmap: bits(), days: 0 });
    const first = await runWeeklyRewards(w.s, W);
    expect(first.paid).toEqual([]);
    expect(first.errors[0]).toMatch(/too little SKR/);
    expect((w.s.db.prepare("SELECT status FROM rewards WHERE wallet = ?").get(a) as { status: string }).status).toBe("failed");
    tokenAccount(chain, rewards.publicKey.toBase58(), skr, SKR_VAULT);
    const second = await runWeeklyRewards(w.s, W);
    expect(second.paid.map((p) => p.wallet)).toEqual([a]);
    expect(chain.balance(a, skr)).toBe(10_000_000n);
  });

  it("a network failure while sending is recorded and retried, not paid twice", async () => {
    const { w, chain, skr } = await rewardsWorld();
    const c = weekPool(w);
    const a = addr();
    seedParticipant(w, c.pool, a, { bitmap: bits(3, 4, 5, 6), days: 4 });
    seedParticipant(w, c.pool, addr(), { bitmap: bits(), days: 0 });
    chain.failNext = 1;
    const first = await runWeeklyRewards(w.s, W);
    expect(first.errors).toHaveLength(1);
    expect(chain.balance(a, skr)).toBe(0n);
    const second = await runWeeklyRewards(w.s, W);
    expect(second.paid).toHaveLength(1);
    expect(chain.balance(a, skr)).toBe(10_000_000n);
  });

  it("shows the running week's standings over the API, labelled as test SKR, and needs a signed-in wallet", async () => {
    const { w } = await rewardsWorld({}, SKR_VAULT, false);
    const kp = Keypair.generate();
    const me = await signIn(w, kp);
    const week = Math.floor(w.s.wallNow() / WEEK_SECONDS);
    const c = seedChallenge(w, { startTs: week * WEEK_SECONDS, durationDays: 7 });
    w.s.db.prepare("UPDATE challenges SET participant_count = 3, end_ts = ? WHERE pool = ?").run(week * WEEK_SECONDS + 7 * 86_400, c.pool);
    seedParticipant(w, c.pool, kp.publicKey.toBase58(), { bitmap: bits(0), days: 1 });
    seedParticipant(w, c.pool, addr(), { bitmap: bits(), days: 0 });
    const res = await w.app.inject({ method: "GET", url: "/v1/rewards", headers: me.headers });
    expect(res.statusCode).toBe(200);
    const j = res.json();
    expect(j.enabled).toBe(true);
    expect(j.label).toMatch(/TEST SKR/);
    expect(j.currentWeek).toBe(week);
    expect(j.minStreak).toBe(3);
    expect(j.ladder).toEqual(["10000000", "5000000", "3000000"]);
    expect((await w.app.inject({ method: "GET", url: "/v1/rewards" })).statusCode).toBe(401);
  });
});
