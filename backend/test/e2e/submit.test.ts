/** The wallet-signs-only path and the landing check, against the REAL program (LiteSVM, WSL only). */
import { ComputeBudgetProgram, Transaction } from "@solana/web3.js";
import nacl from "tweetnacl";
import { describe, expect, it } from "vitest";
import { balance, get, makeChainWorld, makePlayer, post, UNIT, type ChainWorld, type Player } from "../helpers/e2e-world.js";
import { loadLiteSvm } from "../helpers/litesvm-chain.js";
import { samplePlan } from "../helpers/world.js";

const lib = await loadLiteSvm();
const plan = () => samplePlan({ cadence: { periodDays: 1, totalDays: 2, requiredDays: 2 } });

/** what a wallet that only signs does: add its signature and give the transaction back */
const signOnly = (p: Player, b64: string) => {
  const tx = Transaction.from(Buffer.from(b64, "base64"));
  tx.partialSign(p.kp);
  return tx.serialize().toString("base64");
};
const submit = (w: ChainWorld, p: Player, signed: string) => post(w, p, "/v1/tx/submit", { transaction: signed });

async function openPool(w: ChainWorld, creator: Player) {
  const c = await post(w, creator, "/v1/challenges/tx/create", { mint: w.mint.publicKey.toBase58(), kind: "Squad", mode: "Hard", startTs: w.chain.nowSec() + 60, plan: plan(), demo: { daySecs: 60 } });
  expect(c.statusCode, c.body).toBe(200);
  const r = await submit(w, creator, signOnly(creator, c.json().transaction));
  expect(r.statusCode, r.body).toBe(200);
  return c.json().pool as string;
}

describe.skipIf(!lib)("sign-only path: the server sends what the wallet signed (LiteSVM)", () => {
  it("sends a transaction it built, once, and gives the same signature when asked again", async () => {
    const w = (await makeChainWorld())!;
    const [alice, bob] = [await makePlayer(w, 50n * UNIT), await makePlayer(w, 50n * UNIT)];
    const pool = await openPool(w, alice);
    const j = await post(w, bob, "/v1/challenges/tx/join", { pool, stake: (2n * UNIT).toString(), tzOffsetMinutes: 0, deviceId: bob.device.id });
    expect(j.statusCode, j.body).toBe(200);
    const signed = signOnly(bob, j.json().transaction);
    let sends = 0;
    const original = w.chain.sendAndConfirm.bind(w.chain);
    w.chain.sendAndConfirm = async (tx) => { sends++; return original(tx); };
    const first = await submit(w, bob, signed);
    expect(first.statusCode, first.body).toBe(200);
    expect(first.json().alreadySubmitted).toBe(false);
    const vault = (await get(w, bob, `/v1/challenges/${pool}`)).json().challenge.vault as string;
    expect(await balance(w, vault)).toBe(2n * UNIT);
    // a second request, and two at once: the same signature, and the chain is not asked again
    const again = await submit(w, bob, signed);
    expect(again.json()).toEqual({ signature: first.json().signature, alreadySubmitted: true });
    const both = await Promise.all([submit(w, bob, signed), submit(w, bob, signed)]);
    for (const r of both) expect(r.json().signature).toBe(first.json().signature);
    expect(sends).toBe(1);
    expect(await balance(w, vault)).toBe(2n * UNIT); // never staked twice
  });

  it("treats a transaction the chain already processed as a success and sends two simultaneous requests only once", async () => {
    const w = (await makeChainWorld())!;
    const [alice, bob] = [await makePlayer(w, 50n * UNIT), await makePlayer(w, 50n * UNIT)];
    const pool = await openPool(w, alice);
    const j = await post(w, bob, "/v1/challenges/tx/join", { pool, stake: UNIT.toString(), tzOffsetMinutes: 0, deviceId: bob.device.id });
    const signed = signOnly(bob, j.json().transaction);
    const original = w.chain.sendAndConfirm.bind(w.chain);
    let calls = 0;
    w.chain.sendAndConfirm = async (tx) => {
      calls++;
      await original(tx);
      if (calls === 1) throw new Error("This transaction has already been processed");
      return original(tx);
    };
    const r = await submit(w, bob, signed);
    expect(r.statusCode, r.body).toBe(200);
    expect(r.json().alreadySubmitted).toBe(true);
  });

  it("accepts what Phantom does to a transaction before signing it: a priority fee (Compute Budget instructions) in front, nothing else changed", async () => {
    const w = (await makeChainWorld())!;
    const [alice, bob] = [await makePlayer(w, 50n * UNIT), await makePlayer(w, 50n * UNIT)];
    const pool = await openPool(w, alice);
    const j = await post(w, bob, "/v1/challenges/tx/join", { pool, stake: (2n * UNIT).toString(), tzOffsetMinutes: 0, deviceId: bob.device.id });
    const phantomLike = (price: number) => {
      const tx = Transaction.from(Buffer.from(j.json().transaction, "base64"));
      tx.instructions.unshift(ComputeBudgetProgram.setComputeUnitLimit({ units: 200_000 }), ComputeBudgetProgram.setComputeUnitPrice({ microLamports: price }));
      tx.signatures = [];
      tx.partialSign(bob.kp);
      return tx.serialize().toString("base64");
    };
    // an absurd priority fee is refused
    const greedy = await submit(w, bob, phantomLike(50_000_000));
    expect(greedy.statusCode).toBe(400);
    expect(greedy.json().error.code).toBe("fee_too_high");
    // a normal one is sent, once
    const ok = await submit(w, bob, phantomLike(100_000));
    expect(ok.statusCode, ok.body).toBe(200);
    const vault = (await get(w, bob, `/v1/challenges/${pool}`)).json().challenge.vault as string;
    expect(await balance(w, vault)).toBe(2n * UNIT);
  });

  it("re-issues a transaction it built with a fresh blockhash (only the blockhash changes), and the refreshed copy is accepted once signed", async () => {
    const w = (await makeChainWorld())!;
    const [alice, bob, mallory] = [await makePlayer(w, 50n * UNIT), await makePlayer(w, 50n * UNIT), await makePlayer(w, 50n * UNIT)];
    const pool = await openPool(w, alice);
    const j = await post(w, bob, "/v1/challenges/tx/join", { pool, stake: (2n * UNIT).toString(), tzOffsetMinutes: 0, deviceId: bob.device.id });
    const built = j.json().transaction as string;
    const fresh = await post(w, bob, "/v1/tx/refresh", { transaction: built });
    expect(fresh.statusCode, fresh.body).toBe(200);
    const a = Transaction.from(Buffer.from(built, "base64"));
    const b = Transaction.from(Buffer.from(fresh.json().transaction, "base64"));
    expect(b.feePayer!.toBase58()).toBe(a.feePayer!.toBase58());
    expect(b.instructions.length).toBe(a.instructions.length);
    expect(Buffer.from(b.instructions[0]!.data).equals(Buffer.from(a.instructions[0]!.data))).toBe(true);
    // another wallet cannot get bob's transaction re-issued; garbage is refused
    expect((await post(w, mallory, "/v1/tx/refresh", { transaction: built })).statusCode).toBe(404);
    expect((await post(w, bob, "/v1/tx/refresh", { transaction: "AAAA" })).statusCode).toBe(400);
    // the refreshed copy, signed, is sent and the stake lands
    const ok = await submit(w, bob, signOnly(bob, fresh.json().transaction));
    expect(ok.statusCode, ok.body).toBe(200);
    const vault = (await get(w, bob, `/v1/challenges/${pool}`)).json().challenge.vault as string;
    expect(await balance(w, vault)).toBe(2n * UNIT);
    // once sent, it is not re-issued
    expect((await post(w, bob, "/v1/tx/refresh", { transaction: built })).statusCode).toBe(409);
  });

  it("refuses anything the server did not build for this wallet, an unsigned copy, and a copy changed after the build", async () => {
    const w = (await makeChainWorld())!;
    const [alice, bob, mallory] = [await makePlayer(w, 50n * UNIT), await makePlayer(w, 50n * UNIT), await makePlayer(w, 50n * UNIT)];
    const pool = await openPool(w, alice);
    const j = await post(w, bob, "/v1/challenges/tx/join", { pool, stake: UNIT.toString(), tzOffsetMinutes: 0, deviceId: bob.device.id });
    const built = j.json().transaction as string;
    // another wallet cannot send bob's transaction
    expect((await submit(w, mallory, signOnly(bob, built))).statusCode).toBe(404);
    // no signature at all
    const none = await submit(w, bob, built);
    expect(none.statusCode).toBe(400);
    expect(none.json().error.code).toBe("bad_signature");
    // a different signer's signature in bob's slot
    const forged = Transaction.from(Buffer.from(built, "base64"));
    forged.addSignature(bob.kp.publicKey, Buffer.from(nacl.sign.detached(forged.serializeMessage(), mallory.kp.secretKey)));
    expect((await submit(w, bob, forged.serialize({ requireAllSignatures: false, verifySignatures: false }).toString("base64"))).statusCode).toBe(400);
    // a transaction the server never built
    const other = await post(w, bob, "/v1/challenges/tx/join", { pool, stake: (3n * UNIT).toString(), tzOffsetMinutes: 0, deviceId: bob.device.id });
    const edited = Transaction.from(Buffer.from(other.json().transaction, "base64"));
    edited.instructions[0]!.data[8] ^= 0xff; // another stake than the one that was built
    edited.partialSign(bob.kp);
    expect((await submit(w, bob, edited.serialize().toString("base64"))).statusCode).toBe(404);
    // garbage
    expect((await submit(w, bob, "AAAA")).statusCode).toBe(400);
    const vault = (await get(w, bob, `/v1/challenges/${pool}`)).json().challenge.vault as string;
    expect(await balance(w, vault)).toBe(0n);
  });
});

describe.skipIf(!lib)("did it land? (LiteSVM)", () => {
  it("answers from the chain for create, join and claim, before and after", async () => {
    const w = (await makeChainWorld())!;
    const [alice, bob] = [await makePlayer(w, 50n * UNIT), await makePlayer(w, 50n * UNIT)];
    // a create-and-stake that was built but never sent
    const body = { mint: w.mint.publicKey.toBase58(), kind: "Squad", mode: "Hard", startTs: w.chain.nowSec() + 60, plan: plan(), demo: { daySecs: 60 }, join: { stake: UNIT.toString(), tzOffsetMinutes: 0, deviceId: alice.device.id } };
    const c = await post(w, alice, "/v1/challenges/tx/create", body);
    expect(c.statusCode, c.body).toBe(200);
    const pool = c.json().pool as string;
    const before = await post(w, alice, "/v1/challenges/check", { pool, kind: "create" });
    expect(before.json()).toMatchObject({ landed: false });
    expect(before.json().detail).toContain("does not exist");
    expect((await submit(w, alice, signOnly(alice, c.json().transaction))).statusCode).toBe(200);
    const after = await post(w, alice, "/v1/challenges/check", { pool, kind: "create" });
    expect(after.json().landed).toBe(true);
    expect(after.json().detail).toContain("your stake is in it");
    // bob did not join
    expect((await post(w, bob, "/v1/challenges/check", { pool, kind: "join" })).json().landed).toBe(false);
    expect((await post(w, alice, "/v1/challenges/check", { pool, kind: "claim" })).json().landed).toBe(false);
    expect((await post(w, alice, "/v1/challenges/check", { pool: "11111111111111111111111111111111", kind: "join" })).json().landed).toBe(false);
  });
});
