/** More than one stake token through the API against the REAL program (LiteSVM): each pool has ONE mint, and the wrong mint is refused everywhere. */
import { Keypair, PublicKey } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { signAndSend } from "../../src/chain/tx.js";
import { ataAddress, ixCreateAtaIdempotent, ixMintTo } from "../../src/util/token.js";
import { balance, get, makeChainWorld, makePlayer, post, signAndSendB64, UNIT, type ChainWorld, type Player } from "../helpers/e2e-world.js";
import { loadLiteSvm } from "../helpers/litesvm-chain.js";
import { samplePlan } from "../helpers/world.js";

const lib = await loadLiteSvm();
const plan = () => samplePlan({ cadence: { periodDays: 1, totalDays: 7, requiredDays: 5 } });

/** gives a player a token account and some tokens of mint B (makePlayer only handles the first mint) */
async function fundB(w: ChainWorld, p: Player, tokens: bigint) {
  const mintB = w.mintB.publicKey;
  await signAndSend(w.chain, [ixCreateAtaIdempotent(w.admin.publicKey, p.kp.publicKey, mintB), ixMintTo(mintB, ataAddress(p.kp.publicKey, mintB), w.admin.publicKey, tokens, 6)], [w.admin]);
  return ataAddress(p.kp.publicKey, mintB).toBase58();
}

async function createPool(w: ChainWorld, creator: Player, mint: PublicKey, over: Record<string, unknown> = {}) {
  const res = await post(w, creator, "/v1/challenges/tx/create", { mint: mint.toBase58(), kind: "Squad", mode: "Hard", startTs: w.chain.nowSec() + 3_600, plan: plan(), ...over });
  return res;
}

describe.skipIf(!lib)("two stake tokens, one per pool (LiteSVM)", () => {
  it("a pool in either token is created, joined and held in that token, with the right amounts", async () => {
    const w = (await makeChainWorld())!;
    const [alice, bob] = [await makePlayer(w, 50n * UNIT), await makePlayer(w, 50n * UNIT)];
    const bobB = await fundB(w, bob, 30n * UNIT);
    const aliceB = await fundB(w, alice, 30n * UNIT);

    // one at a time: the in-memory chain expires the blockhash after every sent transaction
    const poolA = await createPool(w, alice, w.mint.publicKey);
    expect(poolA.statusCode, poolA.body).toBe(200);
    await signAndSendB64(w, alice, poolA.json().transaction);
    const poolB = await createPool(w, alice, w.mintB.publicKey);
    expect(poolB.statusCode, poolB.body).toBe(200);
    await signAndSendB64(w, alice, poolB.json().transaction);

    const viewA = (await get(w, bob, `/v1/challenges/${poolA.json().pool}`)).json().challenge;
    const viewB = (await get(w, bob, `/v1/challenges/${poolB.json().pool}`)).json().challenge;
    expect(viewA.mint).toBe(w.mint.publicKey.toBase58());
    expect(viewB.mint).toBe(w.mintB.publicKey.toBase58());
    expect(viewA.vault).not.toBe(viewB.vault);

    // bob stakes 2.5 tokens in pool A and 7.25 in pool B: the join transaction names the pool's own token account and mint
    for (const [pool, stake, mintTok] of [[poolA, 2_500_000n, w.mint], [poolB, 7_250_000n, w.mintB]] as const) {
      const j = await post(w, bob, "/v1/challenges/tx/join", { pool: pool.json().pool, stake: stake.toString(), tzOffsetMinutes: 0, deviceId: bob.device.id });
      expect(j.statusCode, j.body).toBe(200);
      expect(j.json().summary).toMatchObject({ stake: stake.toString(), mint: mintTok.publicKey.toBase58() });
      await signAndSendB64(w, bob, j.json().transaction);
    }
    expect(await balance(w, viewA.vault)).toBe(2_500_000n);
    expect(await balance(w, viewB.vault)).toBe(7_250_000n);
    expect(await balance(w, bob.token)).toBe(50n * UNIT - 2_500_000n); // token A only moved by the pool A stake
    expect(await balance(w, bobB)).toBe(30n * UNIT - 7_250_000n);
    expect(aliceB).toBeTruthy();
    const mine = (await get(w, bob, `/v1/challenges/${poolB.json().pool}`)).json();
    expect(mine.participants.find((p: { wallet: string }) => p.wallet === bob.wallet).stake).toBe("7250000");
  });

  it("refuses the wrong token in every place: unlisted mint, demo with a normal-only mint, no token account, and a hand-made join with the other mint", async () => {
    const w = (await makeChainWorld())!;
    const [alice, bob] = [await makePlayer(w, 50n * UNIT), await makePlayer(w, 50n * UNIT)]; // both hold ONLY token A
    // a mint the program does not list
    const other = Keypair.generate().publicKey;
    const unlisted = await createPool(w, alice, other);
    expect(unlisted.statusCode).toBe(400);
    expect(unlisted.json().error.code).toBe("mint_not_allowed");
    // token B is allowed for normal pools but not for demo pools
    const demoB = await createPool(w, alice, w.mintB.publicKey, { demo: { daySecs: 60 }, startTs: w.chain.nowSec() + 60 });
    expect(demoB.statusCode).toBe(400);
    expect(demoB.json().error.code).toBe("demo_mint_not_allowed");

    const poolB = await createPool(w, alice, w.mintB.publicKey);
    await signAndSendB64(w, alice, poolB.json().transaction);
    const pool = poolB.json().pool as string;
    // bob has no token B account: the API says so plainly and builds nothing
    const join = await post(w, bob, "/v1/challenges/tx/join", { pool, stake: "1000000", tzOffsetMinutes: 0, deviceId: bob.device.id });
    expect(join.statusCode).toBe(409);
    expect(join.json().error.code).toBe("insufficient_funds");
    // the pre-flight check says the same thing, per token
    const pre = (await get(w, bob, `/v1/preflight?kind=join&pool=${pool}&stake=1000000`)).json();
    expect(pre.ok).toBe(false);
    expect(pre.reasons.map((r: { code: string }) => r.code)).toContain("no_token_account");
    // a transaction built by hand with token A's account against pool B is refused by the program itself
    const vault = (await get(w, bob, `/v1/challenges/${pool}`)).json().challenge.vault as string;
    const bad = w.program.ixJoinPool(bob.kp.publicKey, { key: new PublicKey(pool), mint: w.mint.publicKey, vault: new PublicKey(vault) }, ataAddress(bob.kp.publicKey, w.mint.publicKey), UNIT, 0, Buffer.from(bob.device.id, "hex"));
    await expect(signAndSend(w.chain, [bad], [bob.kp])).rejects.toThrow();
    expect(await balance(w, bob.token)).toBe(50n * UNIT); // nothing moved
  });
});

describe.skipIf(!lib)("create and stake in ONE transaction (LiteSVM)", () => {
  it("one signature creates the pool and takes the creator's stake; the transaction holds exactly two instructions", async () => {
    const w = (await makeChainWorld())!;
    const alice = await makePlayer(w, 50n * UNIT);
    const res = await createPool(w, alice, w.mint.publicKey, { join: { stake: "3000000", tzOffsetMinutes: 60, deviceId: alice.device.id } });
    expect(res.statusCode, res.body).toBe(200);
    const body = res.json();
    expect(body.summary.action).toBe("create_and_join_pool");
    expect(body.simulation).toMatchObject({ ok: true, skipped: false }); // the whole pair runs against the real program before the wallet sees it
    const raw = Buffer.from(body.transaction, "base64");
    const { Transaction } = await import("@solana/web3.js");
    expect(Transaction.from(raw).instructions).toHaveLength(2);
    await signAndSendB64(w, alice, body.transaction);
    expect(await balance(w, body.summary.vault)).toBe(3_000_000n);
    expect(await balance(w, alice.token)).toBe(50n * UNIT - 3_000_000n);
    const view = (await get(w, alice, `/v1/challenges/${body.pool}`)).json();
    expect(view.participants.find((p: { wallet: string }) => p.wallet === alice.wallet).stake).toBe("3000000");
  });

  it("keeps every join check: stake cap, unregistered device, not enough tokens", async () => {
    const w = (await makeChainWorld())!;
    const alice = await makePlayer(w, 2n * UNIT);
    const over = await createPool(w, alice, w.mint.publicKey, { join: { stake: "999999999999", tzOffsetMinutes: 0, deviceId: alice.device.id } });
    expect(over.statusCode).toBe(400);
    expect(over.json().error.code).toBe("stake_over_cap");
    const nodev = await createPool(w, alice, w.mint.publicKey, { join: { stake: "1000000", tzOffsetMinutes: 0, deviceId: "a".repeat(64) } });
    expect(nodev.json().error.code).toBe("device_not_registered");
    const poor = await createPool(w, alice, w.mint.publicKey, { join: { stake: "3000000", tzOffsetMinutes: 0, deviceId: alice.device.id } });
    expect(poor.json().error.code).toBe("insufficient_funds");
  });
});
