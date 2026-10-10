import { Keypair, PublicKey, SystemProgram, Transaction } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { ApiError } from "../src/errors.js";
import { explainSimulation, simulateOrThrow } from "../src/chain/simulate.js";
import { ACCOUNT_SPACE, FEE_PER_SIGNATURE_LAMPORTS, solCost } from "../src/chain/costs.js";
import { ataAddress } from "../src/util/token.js";
import { TOKEN_PROGRAM_ID } from "../src/program/client.js";
import { seedChallenge } from "./helpers/seed.js";
import { newMint, TokenChain } from "./helpers/token-chain.js";
import { makeWorld, signIn, type World } from "./helpers/world.js";

// the in-memory test chain charges 1,000,000 lamports of rent for any account, which keeps the arithmetic easy to read
const RENT = 1_000_000n;

function tokenAccount(chain: TokenChain, owner: string, mint: string, amount: bigint) {
  const data = new Uint8Array(165);
  data.set(new PublicKey(mint).toBytes(), 0);
  data.set(new PublicKey(owner).toBytes(), 32);
  Buffer.from(data.buffer).writeBigUInt64LE(amount, 64);
  chain.accounts.set(ataAddress(owner, mint).toBase58(), { data, lamports: 2_039_280n, owner: TOKEN_PROGRAM_ID.toBase58() });
}

async function world() {
  const chain = new TokenChain();
  const w = await makeWorld({ chain });
  const kp = Keypair.generate();
  const me = { wallet: kp.publicKey.toBase58(), headers: (await signIn(w, kp)).headers };
  return { w, chain, me };
}

const pre = (w: World, me: { headers: { authorization: string } }, q: string) => w.app.inject({ method: "GET", url: `/v1/preflight?${q}`, headers: me.headers });

describe("explaining a failed simulation in plain words", () => {
  it("maps the cluster's errors to a code and a sentence a person can act on", () => {
    expect(explainSimulation('"InsufficientFundsForRent"', [])).toMatchObject({ code: "insufficient_sol" });
    expect(explainSimulation('"AccountNotFound"', [])).toMatchObject({ code: "insufficient_sol" });
    expect(explainSimulation("{}", ["Transfer: insufficient lamports 5, need 10"])).toMatchObject({ code: "insufficient_sol" });
    expect(explainSimulation('{"InstructionError":[0,{"Custom":1}]}', ["Program log: Error: insufficient funds"])).toMatchObject({ code: "insufficient_tokens" });
    expect(explainSimulation('"BlockhashNotFound"', [])).toMatchObject({ code: "blockhash_expired" });
    expect(explainSimulation("{}", ["Program log: AnchorError thrown. Error Code: JoinClosed. Error Number: 6010. Error Message: nope"])).toMatchObject({ code: "program_rejected" });
    const odd = explainSimulation('"Weird"', []);
    expect(odd.code).toBe("simulation_failed");
    for (const f of [explainSimulation('"InsufficientFundsForRent"', []), odd]) {
      expect(f.message).not.toMatch(/InstructionError|Custom|\{|\}/);
    }
    expect(explainSimulation('"InsufficientFundsForRent"', []).message).toContain("Get test tokens");
  });
});

describe("simulating before handing a transaction out", () => {
  const tx = () => new Transaction({ feePayer: Keypair.generate().publicKey, blockhash: "11111111111111111111111111111111", lastValidBlockHeight: 1 }).add(SystemProgram.transfer({ fromPubkey: Keypair.generate().publicKey, toPubkey: Keypair.generate().publicKey, lamports: 1 }));

  it("passes a transaction that would succeed, and says so", async () => {
    const { chain } = await world();
    (chain as unknown as { simulate: unknown }).simulate = async () => ({ err: null, logs: [], unitsConsumed: 1234 });
    expect(await simulateOrThrow(chain, tx())).toEqual({ ok: true, skipped: false, unitsConsumed: 1234 });
  });

  it("refuses one that would fail, with a 422 and a plain reason", async () => {
    const { chain } = await world();
    (chain as unknown as { simulate: unknown }).simulate = async () => ({ err: '"InsufficientFundsForRent"', logs: ["x"] });
    const e = await simulateOrThrow(chain, tx()).catch((x) => x as ApiError);
    expect(e).toBeInstanceOf(ApiError);
    expect((e as ApiError).status).toBe(422);
    expect((e as ApiError).code).toBe("insufficient_sol");
    expect((e as ApiError).message).toContain("devnet SOL");
  });

  it("says it skipped the check when the chain cannot simulate", async () => {
    const { chain } = await world();
    expect(await simulateOrThrow(chain, tx())).toEqual({ ok: true, skipped: true });
  });
});

describe("what a transaction needs in SOL", () => {
  it("adds the fee, the rent of every account it creates, and the balance a wallet must keep", async () => {
    const { chain } = await world();
    const join = await solCost(chain, "join");
    expect(join).toMatchObject({ fee: FEE_PER_SIGNATURE_LAMPORTS, rent: RENT, keep: RENT, total: 5_000n + 2n * RENT });
    const create = await solCost(chain, "create");
    expect(create.rent).toBe(2n * RENT); // the pool and its vault token account
    const both = await solCost(chain, "create", { thenJoin: true });
    expect(both.fee).toBe(10_000n);
    expect(both.rent).toBe(3n * RENT);
    expect((await solCost(chain, "claim")).total).toBe(5_000n + RENT); // fee and the balance to keep, no new account
    expect((await solCost(chain, "claim", { userTokenAccountMissing: true })).rent).toBe(RENT);
    expect(ACCOUNT_SPACE).toEqual({ participation: 125, pool: 260, tokenAccount: 165, systemAccount: 0 });
  });
});

describe("pre-flight check before the wallet is opened", () => {
  it("says a wallet with no SOL cannot join, and names the numbers", async () => {
    const { w, chain, me } = await world();
    const mint = newMint();
    const c = seedChallenge(w, {});
    w.s.db.prepare("UPDATE challenges SET mint = ? WHERE pool = ?").run(mint, c.pool);
    tokenAccount(chain, me.wallet, mint, 50_000_000n);
    const r = (await pre(w, me, `kind=join&pool=${c.pool}&stake=2000000`)).json();
    expect(r.ok).toBe(false);
    expect(r.sol).toMatchObject({ balance: "0", needed: String(5_000n + 2n * RENT), enough: false });
    expect(r.reasons.map((x: { code: string }) => x.code)).toEqual(["low_sol"]);
    expect(r.reasons[0].message).toBe("Your wallet needs a little devnet SOL for network fees. Tap Get test tokens to receive some.");
    expect(r.token).toMatchObject({ balance: "50000000", needed: "2000000", enough: true, accountExists: true });
  });

  it("passes with enough SOL and tokens, and counts the balance a wallet must keep", async () => {
    const { w, chain, me } = await world();
    const mint = newMint();
    const c = seedChallenge(w, {});
    w.s.db.prepare("UPDATE challenges SET mint = ? WHERE pool = ?").run(mint, c.pool);
    tokenAccount(chain, me.wallet, mint, 50_000_000n);
    chain.fund(me.wallet, 5_000n + 2n * RENT - 1n);
    expect((await pre(w, me, `kind=join&pool=${c.pool}&stake=2000000`)).json().ok).toBe(false); // one lamport short
    chain.fund(me.wallet, 5_000n + 2n * RENT);
    const ok = (await pre(w, me, `kind=join&pool=${c.pool}&stake=2000000`)).json();
    expect(ok.ok).toBe(true);
    expect(ok.reasons).toEqual([]);
  });

  it("reports missing token accounts and too few tokens separately", async () => {
    const { w, chain, me } = await world();
    const mint = newMint();
    const c = seedChallenge(w, {});
    w.s.db.prepare("UPDATE challenges SET mint = ? WHERE pool = ?").run(mint, c.pool);
    chain.fund(me.wallet, 100_000_000n);
    const none = (await pre(w, me, `kind=join&pool=${c.pool}&stake=2000000`)).json();
    expect(none.reasons.map((x: { code: string }) => x.code)).toEqual(["no_token_account"]);
    expect(none.reasons[0].message).toContain("no test tokens");
    tokenAccount(chain, me.wallet, mint, 1_000_000n);
    const few = (await pre(w, me, `kind=join&pool=${c.pool}&stake=2000000`)).json();
    expect(few.reasons.map((x: { code: string }) => x.code)).toEqual(["low_tokens"]);
    expect(few.token).toMatchObject({ balance: "1000000", needed: "2000000", enough: false });
  });

  it("names the token in the message: test USDC and test SKR each get their own words", async () => {
    const usdc = newMint();
    const skr = newMint();
    const chain = new TokenChain();
    const w = await makeWorld({ chain, env: { FAUCET_USDC_MINT: usdc, FAUCET_SKR_MINT: skr, FAUCET_AUTHORITY_SECRET_KEY: JSON.stringify(Array.from(Keypair.generate().secretKey)) } });
    const kp = Keypair.generate();
    const me = { wallet: kp.publicKey.toBase58(), headers: (await signIn(w, kp)).headers };
    chain.fund(me.wallet, 100_000_000n);
    for (const [mint, symbol] of [[usdc, "tUSDC"], [skr, "tSKR"]] as const) {
      const none = (await pre(w, me, `kind=create&mint=${mint}&stake=1000000`)).json();
      expect(none.token).toMatchObject({ symbol, accountExists: false, enough: false });
      expect(none.reasons[0].message).toBe(`Your wallet has no ${symbol} yet. Tap Get test tokens to receive some.`);
      tokenAccount(chain, me.wallet, mint, 500_000n);
      const few = (await pre(w, me, `kind=create&mint=${mint}&stake=1000000`)).json();
      expect(few.reasons[0].message).toBe(`You do not have enough ${symbol} for this stake. Tap Get test tokens to receive some.`);
      tokenAccount(chain, me.wallet, mint, 5_000_000n);
      expect((await pre(w, me, `kind=create&mint=${mint}&stake=1000000`)).json().ok).toBe(true);
    }
  });

  it("a create is checked for the pool, its vault and the creator's own join, plus the stake", async () => {
    const { w, chain, me } = await world();
    const mint = newMint();
    tokenAccount(chain, me.wallet, mint, 10_000_000n);
    chain.fund(me.wallet, 10_000n + 3n * RENT + RENT - 1n);
    const short = (await pre(w, me, `kind=create&mint=${mint}&stake=2000000`)).json();
    expect(short.sol.needed).toBe(String(10_000n + 3n * RENT + RENT));
    expect(short.reasons.map((x: { code: string }) => x.code)).toEqual(["low_sol"]);
    chain.fund(me.wallet, 10_000n + 3n * RENT + RENT);
    expect((await pre(w, me, `kind=create&mint=${mint}&stake=2000000`)).json().ok).toBe(true);
  });

  it("a claim needs only the fee when the token account exists", async () => {
    const { w, chain, me } = await world();
    const mint = newMint();
    const c = seedChallenge(w, {});
    w.s.db.prepare("UPDATE challenges SET mint = ? WHERE pool = ?").run(mint, c.pool);
    tokenAccount(chain, me.wallet, mint, 0n);
    chain.fund(me.wallet, 5_000n + RENT);
    const r = (await pre(w, me, `kind=claim&pool=${c.pool}`)).json();
    expect(r.ok).toBe(true);
    expect(r.token).toBeNull();
  });

  it("needs a sign-in and validates its input", async () => {
    const { w, me } = await world();
    expect((await w.app.inject({ method: "GET", url: "/v1/preflight?kind=join" })).statusCode).toBe(401);
    expect((await pre(w, me, "kind=join")).statusCode).toBe(400); // no pool
    expect((await pre(w, me, "kind=bogus")).statusCode).toBe(400);
    expect((await pre(w, me, `kind=join&pool=${Keypair.generate().publicKey.toBase58()}&stake=1`)).statusCode).toBe(404);
  });
});
