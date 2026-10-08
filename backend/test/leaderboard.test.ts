import { Keypair } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { WEEK_SECONDS } from "../src/rewards/service.js";
import { seedChallenge, seedParticipant } from "./helpers/seed.js";
import { newMint, TokenChain } from "./helpers/token-chain.js";
import { makeWorld, signIn, StubChain, type World } from "./helpers/world.js";

const W = 2_500;
const WEEK_START = W * WEEK_SECONDS;
const NOW = WEEK_START + 5 * 86_400 + 100; // day 5 of the running week

const bits = (...days: number[]) => days.reduce((a, d) => a | (1n << BigInt(d)), 0n);
const addr = () => Keypair.generate().publicKey.toBase58();

async function lbWorld(extraEnv: Record<string, string> = {}) {
  const rewards = Keypair.generate();
  const chain = new TokenChain();
  chain.fund(rewards.publicKey.toBase58(), 1_000_000_000n);
  const w = await makeWorld({
    chain,
    env: { REWARDS_SECRET_KEY: JSON.stringify(Array.from(rewards.secretKey)), FAUCET_SKR_MINT: newMint(), FAUCET_USDC_MINT: newMint(), ...extraEnv },
  });
  // sign in first (tokens expire against the real clock), then call move() to put the clock inside the week
  const move = () => {
    w.clock.wall = NOW;
    (chain as unknown as StubChain).time = NOW;
  };
  return { w, rewards, move };
}

function weekPool(w: World) {
  const c = seedChallenge(w, { startTs: WEEK_START, durationDays: 7, requiredDays: 5, daySecs: 86_400 });
  w.s.db.prepare("UPDATE challenges SET participant_count = 5, end_ts = ? WHERE pool = ?").run(WEEK_START + 7 * 86_400, c.pool);
  return c;
}

async function board(w: World, token: string, scope: string) {
  const r = await w.app.inject({ method: "GET", url: `/v1/leaderboard?scope=${scope}`, headers: { authorization: `Bearer ${token}` } });
  expect(r.statusCode).toBe(200);
  return r.json();
}

describe("leaderboards", () => {
  it("this week: real people by live streak, the reward for the top places, and the public address of the rewards wallet", async () => {
    const { w, rewards, move } = await lbWorld({ LEADERBOARD_SAMPLES: "false" });
    const c = weekPool(w);
    const me = Keypair.generate();
    const [a, b] = [addr(), addr()];
    seedParticipant(w, c.pool, a, { bitmap: bits(0, 1, 2, 3, 4), days: 5 }); // streak 5
    seedParticipant(w, c.pool, b, { bitmap: bits(2, 3, 4), days: 3 }); // streak 3
    seedParticipant(w, c.pool, me.publicKey.toBase58(), { bitmap: bits(3, 4), days: 2 }); // streak 2: no reward yet
    const token = (await signIn(w, me)).token;
    move();
    const lb = await board(w, token, "week");
    expect(lb.entries.map((e: any) => [e.wallet, e.streak, e.reward])).toEqual([
      [a, 5, "10000000"],
      [b, 3, "5000000"],
      [me.publicKey.toBase58(), 2, null],
    ]);
    expect(lb.entries[2].you).toBe(true);
    expect(lb.me).toEqual({ rank: 3, streak: 2, rewardIfNow: null, hidden: false });
    expect(lb.hasSamples).toBe(false);
    const st = (await w.app.inject({ method: "GET", url: "/v1/rewards", headers: { authorization: `Bearer ${token}` } })).json();
    expect(st.rewardsWallet).toBe(rewards.publicKey.toBase58());
    expect(JSON.stringify(st)).not.toContain(Buffer.from(rewards.secretKey).toString("base64"));
    expect(st.standings.map((x: any) => x.streak)).toEqual([5, 3, 2]); // the live standings count to now, not to the end of the week
  });

  it("all time: the longest streak each person has reached, no rewards", async () => {
    const { w, move } = await lbWorld({ LEADERBOARD_SAMPLES: "false" });
    const c = weekPool(w);
    const me = Keypair.generate();
    seedParticipant(w, c.pool, addr(), { bitmap: bits(0, 1, 2, 4), days: 4 }); // longest run 3
    seedParticipant(w, c.pool, me.publicKey.toBase58(), { bitmap: bits(0, 1, 3, 4, 5), days: 5 }); // longest run 3 (days 3 to 5)
    seedParticipant(w, c.pool, addr(), { bitmap: bits(0, 1, 2, 3), days: 4 }); // longest run 4
    const t = (await signIn(w, me)).token;
    move();
    const lb = await board(w, t, "all");
    expect(lb.entries.map((e: any) => [e.streak, e.reward])).toEqual([[4, null], [3, null], [3, null]]);
  });

  it("SAMPLE rows only fill a short board, are flagged and never change a real rank", async () => {
    const { w, move } = await lbWorld({ LEADERBOARD_SAMPLES: "true" });
    const t = (await signIn(w, Keypair.generate())).token;
    move();
    const lb = await board(w, t, "week");
    expect(lb.entries.length).toBe(8);
    expect(lb.entries.every((e: any) => e.sample && e.reward === null)).toBe(true);
    expect(lb.sampleNote).toContain("SAMPLE");
    expect(lb.me.rank).toBeNull();
  });

  it("a hidden person is not shown to others, keeps their rank and reward, and can show again", async () => {
    const { w, move } = await lbWorld({ LEADERBOARD_SAMPLES: "false" });
    const c = weekPool(w);
    const me = Keypair.generate();
    const other = Keypair.generate();
    seedParticipant(w, c.pool, me.publicKey.toBase58(), { bitmap: bits(0, 1, 2, 3, 4), days: 5 });
    seedParticipant(w, c.pool, other.publicKey.toBase58(), { bitmap: bits(2, 3, 4), days: 3 });
    const mine = (await signIn(w, me)).token;
    const theirs = (await signIn(w, other)).token;
    move();
    const hide = await w.app.inject({ method: "POST", url: "/v1/profile/leaderboard", headers: { authorization: `Bearer ${mine}` }, payload: { hidden: true } });
    expect(hide.json()).toEqual({ hidden: true });
    const seenByOther = await board(w, theirs, "week");
    expect(seenByOther.entries.map((e: any) => e.wallet)).toEqual([other.publicKey.toBase58()]);
    expect(seenByOther.me.rank).toBe(2); // the hidden person still holds the first place for the payout
    expect(await board(w, mine, "week")).toMatchObject({ me: { rank: 1, hidden: true, rewardIfNow: "10000000" } });
    await w.app.inject({ method: "POST", url: "/v1/profile/leaderboard", headers: { authorization: `Bearer ${mine}` }, payload: { hidden: false } });
    expect((await board(w, theirs, "week")).entries.length).toBe(2);
  });

  it("shows no private data about other people: only wallet, short name and streak", async () => {
    const { w, move } = await lbWorld({ LEADERBOARD_SAMPLES: "false" });
    const c = weekPool(w);
    const other = addr();
    seedParticipant(w, c.pool, other, { bitmap: bits(2, 3, 4), days: 3 });
    const t = (await signIn(w, Keypair.generate())).token;
    move();
    const lb = await board(w, t, "week");
    expect(Object.keys(lb.entries[0]).sort()).toEqual(["name", "rank", "reward", "sample", "streak", "wallet", "you"]);
    expect(lb.entries[0].name).toBe(`${other.slice(0, 4)}…${other.slice(-4)}`);
  });

  it("needs a sign-in", async () => {
    const { w, move } = await lbWorld();
    expect((await w.app.inject({ method: "GET", url: "/v1/leaderboard" })).statusCode).toBe(401);
    expect((await w.app.inject({ method: "POST", url: "/v1/profile/leaderboard", payload: { hidden: true } })).statusCode).toBe(401);
  });
});
