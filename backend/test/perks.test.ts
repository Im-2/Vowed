import { Keypair, PublicKey, Transaction } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { TOKEN_PROGRAM_ID } from "../src/program/client.js";
import { ataAddress } from "../src/util/token.js";
import { seedChallenge, seedParticipant } from "./helpers/seed.js";
import { newMint, TokenChain } from "./helpers/token-chain.js";
import { makeWorld, signIn, StubChain } from "./helpers/world.js";

const START = 1_800_000_000 - (1_800_000_000 % 86_400); // a UTC midnight
const DAY = 86_400;

function tokenAccount(chain: TokenChain, owner: string, mint: string, amount: bigint) {
  const data = new Uint8Array(165);
  data.set(new PublicKey(mint).toBytes(), 0);
  data.set(new PublicKey(owner).toBytes(), 32);
  Buffer.from(data.buffer).writeBigUInt64LE(amount, 64);
  chain.accounts.set(ataAddress(owner, mint).toBase58(), { data, lamports: 2_039_280n, owner: TOKEN_PROGRAM_ID.toBase58() });
}

async function perkWorld(extraEnv: Record<string, string> = {}) {
  const rewards = Keypair.generate();
  const skr = newMint();
  const chain = new TokenChain();
  chain.fund(rewards.publicKey.toBase58(), 1_000_000_000n);
  const w = await makeWorld({ chain, env: { REWARDS_SECRET_KEY: JSON.stringify(Array.from(rewards.secretKey)), FAUCET_SKR_MINT: skr, FAUCET_USDC_MINT: newMint(), ...extraEnv } });
  const kp = Keypair.generate();
  const wallet = kp.publicKey.toBase58();
  const me = await signIn(w, kp);
  tokenAccount(chain, wallet, skr, 5_000_000n); // 5 test SKR
  // a 6-day challenge that started 4 days ago: day 0 and 1 are over and missed, day 2 was done, day 3 is today
  (chain as unknown as StubChain).time = START + 4 * DAY + 10_000; // day 3 closed two hours after midnight (the grace period)
  w.clock.wall = Math.floor(Date.now() / 1000); // real-time for sessions; the chain clock drives the challenge days
  const c = seedChallenge(w, { startTs: START, durationDays: 6, requiredDays: 4 });
  w.s.db.prepare("UPDATE challenges SET participant_count = 2 WHERE pool = ?").run(c.pool);
  seedParticipant(w, c.pool, wallet, { bitmap: 1n << 2n, days: 1 });
  return { w, chain, rewards, skr, kp, wallet, me, pool: c.pool };
}

type Ctx = Awaited<ReturnType<typeof perkWorld>>;

const quote = (x: Ctx, day: number, headers = x.me.headers) => x.w.app.inject({ method: "POST", url: "/v1/perks/freeze/tx", headers, payload: { pool: x.pool, dayIndex: day } });
const redeem = (x: Ctx, day: number, signature: string, headers = x.me.headers) => x.w.app.inject({ method: "POST", url: "/v1/perks/freeze", headers, payload: { pool: x.pool, dayIndex: day, signature } });

/** Builds the payment the app would build, signs it as the wallet and sends it. */
async function pay(x: Ctx, day: number, signer = x.kp): Promise<string> {
  const q = await quote(x, day);
  if (q.statusCode !== 200) throw new Error(q.body);
  expect(q.statusCode).toBe(200);
  const tx = Transaction.from(Buffer.from(q.json().transaction, "base64"));
  tx.partialSign(signer);
  return (await x.chain.sendAndConfirm(tx)).signature;
}

describe("streak freeze bought with SKR", () => {
  it("quotes one fixed price, labelled as test SKR, as an unsigned payment to the rewards wallet", async () => {
    const x = await perkWorld();
    const q = await quote(x, 1);
    expect(q.statusCode).toBe(200);
    const j = q.json();
    expect(j).toMatchObject({ price: "1000000", mint: x.skr, payee: x.rewards.publicKey.toBase58(), decimals: 6 });
    expect(j.label).toMatch(/TEST SKR/);
    const tx = Transaction.from(Buffer.from(j.transaction, "base64"));
    expect(tx.feePayer!.toBase58()).toBe(x.wallet);
    expect(tx.signatures.every((s) => s.signature === null)).toBe(true);
  });

  it("records the freeze only after the exact payment is confirmed on chain, and moves the tokens to the rewards wallet", async () => {
    const x = await perkWorld();
    const sig = await pay(x, 1);
    expect(x.chain.balance(x.wallet, x.skr)).toBe(4_000_000n);
    expect(x.chain.balance(x.rewards.publicKey.toBase58(), x.skr)).toBe(1_000_000n);
    const r = await redeem(x, 1, sig);
    expect(r.statusCode).toBe(200);
    expect(r.json()).toEqual({ pool: x.pool, dayIndex: 1 });
    const detail = (await x.w.app.inject({ method: "GET", url: `/v1/challenges/${x.pool}`, headers: x.me.headers })).json();
    const mine = detail.participants.find((p: { wallet: string }) => p.wallet === x.wallet);
    expect(mine.frozenBitmap).toBe("2"); // day 1
    expect(mine.checkinBitmap).toBe("4"); // the real check-ins are untouched
    expect(mine.daysCompleted).toBe(1);
  });

  it("refuses to credit anything for a signature that was never confirmed, or one that is not a payment", async () => {
    const x = await perkWorld();
    expect((await redeem(x, 1, "x".repeat(88))).statusCode).toBe(404);
    expect((await x.w.app.inject({ method: "POST", url: "/v1/perks/freeze", headers: x.me.headers, payload: { pool: x.pool, dayIndex: 1, signature: "short" } })).statusCode).toBe(400);
    expect(x.w.s.db.prepare("SELECT COUNT(*) AS n FROM freezes").get()).toEqual({ n: 0 });
  });

  it("one payment buys one freeze: it cannot be reused for another day or replayed", async () => {
    const x = await perkWorld();
    const sig = await pay(x, 1);
    expect((await redeem(x, 1, sig)).statusCode).toBe(200);
    const again = await redeem(x, 0, sig);
    expect(again.statusCode).toBe(409);
    expect(again.json().error.code).toBe("payment_used");
    expect((await redeem(x, 1, sig)).statusCode).toBe(409);
  });

  it("does not accept somebody else's payment, a payment to the wrong place, or too little", async () => {
    const x = await perkWorld();
    // a payment signed by another wallet
    const other = Keypair.generate();
    tokenAccount(x.chain, other.publicKey.toBase58(), x.skr, 5_000_000n);
    const otherSig = "OtherPay1xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
    x.chain.transfers.set(otherSig, [{ mint: x.skr, source: "s", destination: ataAddress(x.rewards.publicKey, x.skr).toBase58(), authority: other.publicKey.toBase58(), amount: 1_000_000n }]);
    expect((await redeem(x, 1, otherSig)).json().error.code).toBe("payment_not_found");
    // right wallet, wrong destination
    x.chain.transfers.set("WrongDestxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", [{ mint: x.skr, source: "s", destination: ataAddress(Keypair.generate().publicKey, x.skr).toBase58(), authority: x.wallet, amount: 1_000_000n }]);
    expect((await redeem(x, 1, "WrongDestxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")).json().error.code).toBe("payment_not_found");
    // right place, too little
    x.chain.transfers.set("TooLittlexxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", [{ mint: x.skr, source: "s", destination: ataAddress(x.rewards.publicKey, x.skr).toBase58(), authority: x.wallet, amount: 999_999n }]);
    expect((await redeem(x, 1, "TooLittlexxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")).json().error.code).toBe("payment_not_found");
    // the wrong token
    x.chain.transfers.set("WrongMintxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", [{ mint: newMint(), source: "s", destination: ataAddress(x.rewards.publicKey, x.skr).toBase58(), authority: x.wallet, amount: 1_000_000n }]);
    expect((await redeem(x, 1, "WrongMintxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")).json().error.code).toBe("payment_not_found");
    expect(x.w.s.db.prepare("SELECT COUNT(*) AS n FROM freezes").get()).toEqual({ n: 0 });
  });

  it("only allows a freeze for a missed day that is already over and recent, and never for a day that was done", async () => {
    const x = await perkWorld(); // today is day 4; day 2 was checked in; days 0, 1 and 3 were missed
    const why = async (day: number) => (await quote(x, day)).json().error?.message as string | undefined;
    expect(await why(2)).toMatch(/already checked in/);
    expect(await why(4)).toMatch(/already over/); // today
    expect(await why(5)).toMatch(/already over/); // still to come
    expect(await why(9)).toMatch(/not part of this challenge/);
    expect(await why(0)).toMatch(/within 3 days/); // too long ago
    expect((await quote(x, 1)).statusCode).toBe(200);
    expect((await quote(x, 3)).statusCode).toBe(200);
  });

  it("limits freezes per challenge and refuses a day that is already frozen", async () => {
    const x = await perkWorld({ PERK_FREEZE_MAX_PER_POOL: "1" });
    const sig = await pay(x, 1);
    expect((await redeem(x, 1, sig)).statusCode).toBe(200);
    expect((await quote(x, 1)).json().error.message).toMatch(/already frozen/);
    expect((await quote(x, 3)).json().error.message).toMatch(/at most 1 freezes/);
  });

  it("is off when SKR perks are not configured, needs a signed-in wallet, and only works for your own challenge", async () => {
    const plain = await makeWorld({ chain: new TokenChain() });
    const p = await signIn(plain, Keypair.generate());
    expect((await plain.app.inject({ method: "POST", url: "/v1/perks/freeze/tx", headers: p.headers, payload: { pool: Keypair.generate().publicKey.toBase58(), dayIndex: 1 } })).statusCode).toBe(404);
    const x = await perkWorld();
    expect((await x.w.app.inject({ method: "POST", url: "/v1/perks/freeze/tx", payload: { pool: x.pool, dayIndex: 1 } })).statusCode).toBe(401);
    const stranger = await signIn(x.w, Keypair.generate());
    const r = await quote(x, 1, stranger.headers);
    expect(r.statusCode).toBe(400);
    expect(r.json().error.message).toMatch(/not an active player/);
  });

  it("tells a wallet with no SKR so, instead of building a payment that cannot succeed", async () => {
    const x = await perkWorld();
    x.chain.accounts.delete(ataAddress(x.wallet, x.skr).toBase58());
    const r = await quote(x, 1);
    expect(r.statusCode).toBe(400);
    expect(r.json().error.code).toBe("no_skr");
  });

  it("keeps the squad leaderboard's streak alive with a freeze but not its days completed", async () => {
    const x = await perkWorld();
    // done: day 0, 2, 3 and missed 1 would break the streak; the leaderboard is per squad, so link the pool to a squad of one
    const sq = (await x.w.app.inject({ method: "POST", url: "/v1/squads", headers: x.me.headers, payload: { name: "Crew" } })).json();
    x.w.s.db.prepare("UPDATE challenges SET squad_id = ? WHERE pool = ?").run(sq.id, x.pool);
    x.w.s.db.prepare("UPDATE participants SET checkin_bitmap = ?, days_completed = 3 WHERE pool = ? AND wallet = ?").run(((1n << 0n) | (1n << 2n) | (1n << 3n)).toString(), x.pool, x.wallet);
    const before = (await x.w.app.inject({ method: "GET", url: `/v1/squads/${sq.id}/leaderboard`, headers: x.me.headers })).json().rows.find((r: { wallet: string }) => r.wallet === x.wallet);
    expect(before.bestStreak).toBe(2);
    expect((await redeem(x, 1, await pay(x, 1))).statusCode).toBe(200);
    const after = (await x.w.app.inject({ method: "GET", url: `/v1/squads/${sq.id}/leaderboard`, headers: x.me.headers })).json().rows.find((r: { wallet: string }) => r.wallet === x.wallet);
    expect(after.bestStreak).toBe(4);
    expect(after.daysCompleted).toBe(3);
  });
});

