/** Every transaction the backend builds is run against the REAL program before it is handed to a wallet (LiteSVM, WSL only). */
import { PublicKey, SystemProgram } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { signAndSend } from "../../src/chain/tx.js";
import { makeChainWorld, makePlayer, post, signAndSendB64, UNIT } from "../helpers/e2e-world.js";
import { loadLiteSvm } from "../helpers/litesvm-chain.js";
import { samplePlan } from "../helpers/world.js";

const lib = await loadLiteSvm();
const plan = () => samplePlan({ cadence: { periodDays: 1, totalDays: 2, requiredDays: 2 } });

describe.skipIf(!lib)("simulating the built transactions against the real program (LiteSVM)", () => {
  it("create, join and claim are each simulated successfully before they are handed out", async () => {
    const w = (await makeChainWorld())!;
    const [alice, bob] = [await makePlayer(w), await makePlayer(w)];
    const startTs = w.chain.nowSec() + 60;
    const create = await post(w, alice, "/v1/challenges/tx/create", { mint: w.mint.publicKey.toBase58(), kind: "Squad", mode: "Hard", startTs, plan: plan(), demo: { daySecs: 60 } });
    expect(create.statusCode, create.body).toBe(200);
    expect(create.json().simulation).toMatchObject({ ok: true, skipped: false });
    expect(create.json().simulation.unitsConsumed).toBeGreaterThan(0);
    await signAndSendB64(w, alice, create.json().transaction);
    const pool = create.json().pool as string;
    for (const p of [alice, bob]) {
      const j = await post(w, p, "/v1/challenges/tx/join", { pool, stake: (2n * UNIT).toString(), tzOffsetMinutes: 0, deviceId: p.device.id });
      expect(j.statusCode, j.body).toBe(200);
      expect(j.json().simulation).toMatchObject({ ok: true, skipped: false });
      await signAndSendB64(w, p, j.json().transaction);
    }
  });

  it("a wallet that cannot pay is refused with a plain reason instead of being handed a transaction", async () => {
    const w = (await makeChainWorld())!;
    const broke = await makePlayer(w);
    // leave the wallet with a little SOL (1,000,000 lamports: above the rent-exempt minimum, so the transfer itself is allowed) that is far too little for a pool and its vault
    const have = (await w.chain.getAccount(broke.wallet))!.lamports;
    await signAndSend(w.chain, [SystemProgram.transfer({ fromPubkey: broke.kp.publicKey, toPubkey: new PublicKey(w.admin.publicKey), lamports: have - 1_000_000n })], [broke.kp]);
    const create = await post(w, broke, "/v1/challenges/tx/create", { mint: w.mint.publicKey.toBase58(), kind: "Squad", mode: "Hard", startTs: w.chain.nowSec() + 60, plan: plan(), demo: { daySecs: 60 } });
    expect(create.statusCode).toBe(422);
    expect(create.json().error.code).toBe("insufficient_sol");
    expect(create.json().error.message).toContain("Get test SOL");
    expect(create.json().error.message).not.toMatch(/InstructionError|InsufficientFunds/);
  });

  it("the SOL the tester saw (0.00158) is refused BEFORE any simulation, with the exact numbers; costs are returned for the review screen", async () => {
    const w = (await makeChainWorld())!;
    const [rich, tester] = [await makePlayer(w), await makePlayer(w)];
    const have = (await w.chain.getAccount(tester.wallet))!.lamports;
    await signAndSend(w.chain, [SystemProgram.transfer({ fromPubkey: tester.kp.publicKey, toPubkey: new PublicKey(w.admin.publicKey), lamports: have - 1_580_000n })], [tester.kp]);
    const body = { mint: w.mint.publicKey.toBase58(), kind: "Squad", mode: "Hard", startTs: w.chain.nowSec() + 60, plan: plan(), demo: { daySecs: 60 }, join: { stake: "2000000", tzOffsetMinutes: 0, deviceId: tester.device.id } };
    const refused = await post(w, tester, "/v1/challenges/tx/create", body);
    expect(refused.statusCode).toBe(422);
    const err = refused.json().error;
    expect(err.code).toBe("insufficient_sol");
    expect(err.message).toMatch(/needs about 0\.\d+ SOL on devnet/);
    expect(err.message).toContain("You have 0.0016 SOL");
    expect(BigInt(err.details.needed)).toBeGreaterThan(5_000_000n); // pool + vault + participation rent, two fees and the balance a wallet must keep
    expect(err.details.balance).toBe("1575000"); // 1,580,000 minus the 5,000 fee of the draining transfer
    // a funded wallet gets the same numbers back with the transaction
    const ok = await post(w, rich, "/v1/challenges/tx/create", { ...body, join: { ...body.join, deviceId: rich.device.id } });
    expect(ok.statusCode, ok.body).toBe(200);
    const costs = ok.json().costs;
    expect(costs.enough).toBe(true);
    expect(BigInt(costs.total)).toBe(BigInt(costs.fee) + BigInt(costs.rent) + BigInt(costs.keep));
    expect(costs.fee).toBe("5000"); // create and join are ONE transaction with one signature
  });
});

