import { Keypair } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { newMint, TokenChain } from "./helpers/token-chain.js";
import { makeWorld, signIn, type World } from "./helpers/world.js";

const LAMPORTS = 10_000_000n; // 0.01 SOL

async function solWorld(extra: Record<string, string> = {}, bank = 1_000_000_000n) {
  const authority = Keypair.generate();
  const solKey = Keypair.generate();
  const usdc = newMint();
  const skr = newMint();
  const chain = new TokenChain();
  chain.mintAuthority.set(usdc, authority.publicKey.toBase58());
  chain.mintAuthority.set(skr, authority.publicKey.toBase58());
  chain.fund(authority.publicKey.toBase58(), 1_000_000_000n);
  chain.fund(solKey.publicKey.toBase58(), bank);
  const w = await makeWorld({
    chain,
    env: {
      FAUCET_AUTHORITY_SECRET_KEY: JSON.stringify(Array.from(authority.secretKey)),
      FAUCET_SOL_SECRET_KEY: JSON.stringify(Array.from(solKey.secretKey)),
      FAUCET_USDC_MINT: usdc,
      FAUCET_SKR_MINT: skr,
      FAUCET_SOL_LAMPORTS: LAMPORTS.toString(),
      ...extra,
    },
  });
  return { w, chain, solKey };
}

async function person(w: World) {
  const kp = Keypair.generate();
  return { wallet: kp.publicKey.toBase58(), headers: (await signIn(w, kp)).headers };
}
type P = Awaited<ReturnType<typeof person>>;
const claim = async (w: World, p: P) => w.app.inject({ method: "POST", url: "/v1/faucet/claim", headers: p.headers, payload: {} });
const lamportsOf = (chain: TokenChain, wallet: string) => chain.accounts.get(wallet)?.lamports ?? 0n;

describe("devnet SOL gift with the test-token claim", () => {
  it("sends 0.01 SOL once with the first claim and says so", async () => {
    const { w, chain, solKey } = await solWorld();
    const p = await person(w);
    const st = (await w.app.inject({ method: "GET", url: "/v1/faucet", headers: p.headers })).json();
    expect(st.sol).toEqual({ enabled: true, lamports: LAMPORTS.toString(), received: false });
    const r = await claim(w, p);
    expect(r.statusCode).toBe(200);
    expect(r.json().sol).toMatchObject({ status: "sent", lamports: LAMPORTS.toString() });
    expect(lamportsOf(chain, p.wallet)).toBe(LAMPORTS);
    expect(lamportsOf(chain, solKey.publicKey.toBase58())).toBe(1_000_000_000n - LAMPORTS);
    const after = (await w.app.inject({ method: "GET", url: "/v1/faucet", headers: p.headers })).json();
    expect(after.sol.received).toBe(true);
    expect(after.balances.sol).toBe(LAMPORTS.toString());
  });

  it("never sends a second gift to the same wallet, even after the token cooldown", async () => {
    const { w, chain } = await solWorld();
    const p = await person(w);
    await claim(w, p);
    w.clock.wall += 2 * 86_400;
    const again = await claim(w, p);
    expect(again.statusCode).toBe(200);
    expect(again.json().sol).toMatchObject({ status: "skipped", reason: "already_received" });
    expect(lamportsOf(chain, p.wallet)).toBe(LAMPORTS);
  });

  it("a wallet in its token cooldown that never got SOL can still get the gift", async () => {
    const { w, chain, solKey } = await solWorld({}, 0n);
    const p = await person(w);
    chain.fund(solKey.publicKey.toBase58(), 1_000_000_000n); // the SOL faucet gets money only later
    const first = await claim(w, p);
    expect(first.json().sol.status).toBe("sent");
    // a second wallet claimed tokens while the SOL faucet was empty
    const { w: w2, chain: c2, solKey: k2 } = await solWorld({}, 0n);
    const q = await person(w2);
    const r1 = await claim(w2, q);
    expect(r1.json().sol).toMatchObject({ status: "failed", reason: "sol_faucet_empty" });
    c2.fund(k2.publicKey.toBase58(), 1_000_000_000n);
    const r2 = await claim(w2, q); // still inside the 24-hour token cooldown
    expect(r2.statusCode).toBe(200);
    expect(r2.json().minted).toEqual({ tUSDC: "0", tSKR: "0" });
    expect(r2.json().sol.status).toBe("sent");
    expect(lamportsOf(c2, q.wallet)).toBe(LAMPORTS);
  });

  it("stops at the daily cap and at the total cap, and still gives the tokens", async () => {
    const { w } = await solWorld({ FAUCET_SOL_DAILY_CAP: "2" });
    const sols: string[] = [];
    for (let i = 0; i < 3; i++) {
      const r = await claim(w, await person(w));
      expect(r.statusCode).toBe(200);
      sols.push(r.json().sol.status + (r.json().sol.reason ? `:${r.json().sol.reason}` : ""));
    }
    expect(sols).toEqual(["sent", "sent", "skipped:daily_cap"]);
    const t = await solWorld({ FAUCET_SOL_TOTAL_CAP_LAMPORTS: (LAMPORTS * 2n).toString() });
    const out: string[] = [];
    for (let i = 0; i < 3; i++) out.push((await claim(t.w, await person(t.w))).json().sol.status);
    expect(out).toEqual(["sent", "sent", "skipped"]);
  });

  it("gives nothing to a wallet that already holds SOL", async () => {
    const { w, chain } = await solWorld();
    const p = await person(w);
    chain.fund(p.wallet, 200_000_000n);
    const r = await claim(w, p);
    expect(r.json().sol).toMatchObject({ status: "skipped", reason: "already_has_sol" });
    expect(lamportsOf(chain, p.wallet)).toBe(200_000_000n);
  });

  it("a failed SOL send does not fail the token claim and does not use up the gift", async () => {
    const { w, chain } = await solWorld();
    const p = await person(w);
    // the token transaction goes first; make only the second send (the SOL transfer) fail
    const original = chain.sendAndConfirm.bind(chain);
    let n = 0;
    chain.sendAndConfirm = async (tx) => {
      if (++n === 2) throw new Error("rpc unavailable");
      return original(tx);
    };
    const r = await claim(w, p);
    expect(r.statusCode).toBe(200);
    expect(r.json().minted.tUSDC).not.toBe("0");
    expect(r.json().sol).toMatchObject({ status: "failed", reason: "send_failed" });
    chain.sendAndConfirm = original;
    w.clock.wall += 2 * 86_400;
    const retry = await claim(w, p);
    expect(retry.json().sol.status).toBe("sent");
  });

  it("is off, and says so, when no SOL key is configured; the token claim is unchanged", async () => {
    const authority = Keypair.generate();
    const usdc = newMint();
    const skr = newMint();
    const chain = new TokenChain();
    chain.mintAuthority.set(usdc, authority.publicKey.toBase58());
    chain.mintAuthority.set(skr, authority.publicKey.toBase58());
    chain.fund(authority.publicKey.toBase58(), 1_000_000_000n);
    const w = await makeWorld({ chain, env: { FAUCET_AUTHORITY_SECRET_KEY: JSON.stringify(Array.from(authority.secretKey)), FAUCET_USDC_MINT: usdc, FAUCET_SKR_MINT: skr } });
    const p = await person(w);
    expect((await w.app.inject({ method: "GET", url: "/v1/faucet", headers: p.headers })).json().sol.enabled).toBe(false);
    const r = await claim(w, p);
    expect(r.statusCode).toBe(200);
    expect(r.json().sol.status).toBe("off");
  });

  it("never exposes the SOL faucet key and ignores any amount the caller sends", async () => {
    const { w, solKey } = await solWorld();
    const p = await person(w);
    const r = await w.app.inject({ method: "POST", url: "/v1/faucet/claim", headers: p.headers, payload: { lamports: "999999999999", sol: 5 } });
    expect(r.json().sol.lamports).toBe(LAMPORTS.toString());
    expect(r.body).not.toContain(Buffer.from(solKey.secretKey).toString("base64"));
    expect(r.body).not.toContain(JSON.stringify(Array.from(solKey.secretKey)));
  });
});
