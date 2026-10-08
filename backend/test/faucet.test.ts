import { Keypair } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { FAUCET_LABEL, FAUCET_MIN_AUTHORITY_LAMPORTS } from "../src/faucet/service.js";
import { newMint, TokenChain } from "./helpers/token-chain.js";
import { makeWorld, signIn, type World } from "./helpers/world.js";

const DAY = 86_400;
const USDC_AMOUNT = 20_000_000n;
const SKR_AMOUNT = 15_000_000n;

async function faucetWorld(extra: Record<string, string> = {}) {
  const authority = Keypair.generate();
  const usdc = newMint();
  const skr = newMint();
  const chain = new TokenChain();
  chain.mintAuthority.set(usdc, authority.publicKey.toBase58());
  chain.mintAuthority.set(skr, authority.publicKey.toBase58());
  chain.fund(authority.publicKey.toBase58(), 1_000_000_000n);
  const w = await makeWorld({
    chain,
    env: {
      FAUCET_AUTHORITY_SECRET_KEY: JSON.stringify(Array.from(authority.secretKey)),
      FAUCET_USDC_MINT: usdc,
      FAUCET_SKR_MINT: skr,
      FAUCET_USDC_AMOUNT: USDC_AMOUNT.toString(),
      FAUCET_SKR_AMOUNT: SKR_AMOUNT.toString(),
      ...extra,
    },
  });
  return { w, chain, authority, usdc, skr };
}

interface Person {
  wallet: string;
  headers: { authorization: string };
}
async function person(w: World): Promise<Person> {
  const kp = Keypair.generate();
  return { wallet: kp.publicKey.toBase58(), headers: (await signIn(w, kp)).headers };
}
const claim = (w: World, p: Person, payload: unknown = {}) => w.app.inject({ method: "POST", url: "/v1/faucet/claim", headers: p.headers, payload: payload as object });
const status = async (w: World, p: Person) => (await w.app.inject({ method: "GET", url: "/v1/faucet", headers: p.headers })).json();

describe("test-token faucet", () => {
  it("is off unless it is configured, and says so", async () => {
    const w = await makeWorld({ chain: new TokenChain() });
    const p = await person(w);
    expect((await status(w, p)).enabled).toBe(false);
    const r = await claim(w, p);
    expect(r.statusCode).toBe(404);
    expect(r.json().error.code).toBe("faucet_disabled");
  });

  it("stays off when only part of the configuration is present", async () => {
    const authority = Keypair.generate();
    const w = await makeWorld({ chain: new TokenChain(), env: { FAUCET_AUTHORITY_SECRET_KEY: JSON.stringify(Array.from(authority.secretKey)) } });
    expect((await status(w, await person(w))).enabled).toBe(false);
  });

  it("requires a signed-in wallet", async () => {
    const { w } = await faucetWorld();
    expect((await w.app.inject({ method: "POST", url: "/v1/faucet/claim", payload: {} })).statusCode).toBe(401);
    expect((await w.app.inject({ method: "GET", url: "/v1/faucet" })).statusCode).toBe(401);
  });

  it("sends exactly the fixed amounts of both test tokens to the signed-in wallet and labels them as test tokens", async () => {
    const { w, chain, usdc, skr } = await faucetWorld();
    const p = await person(w);
    const before = await status(w, p);
    expect(before).toMatchObject({ enabled: true, canClaim: true, nextClaimAt: 0, balances: { tUSDC: "0", tSKR: "0" } });
    expect(before.label).toBe(FAUCET_LABEL);
    expect(before.label).toMatch(/TEST TOKENS/);
    expect(before.tokens.map((t: { symbol: string }) => t.symbol)).toEqual(["tUSDC", "tSKR"]);

    const r = await claim(w, p);
    expect(r.statusCode).toBe(200);
    expect(r.json()).toMatchObject({ minted: { tUSDC: USDC_AMOUNT.toString(), tSKR: SKR_AMOUNT.toString() } });
    expect(chain.balance(p.wallet, usdc)).toBe(USDC_AMOUNT);
    expect(chain.balance(p.wallet, skr)).toBe(SKR_AMOUNT);
    const after = await status(w, p);
    expect(after.balances).toEqual({ tUSDC: USDC_AMOUNT.toString(), tSKR: SKR_AMOUNT.toString(), sol: "0" });
    expect(after.canClaim).toBe(false);
  });

  it("ignores any amount or recipient the caller tries to choose", async () => {
    const { w, chain, usdc, skr } = await faucetWorld();
    const p = await person(w);
    const other = await person(w);
    const r = await claim(w, p, { amount: "999999999999", usdc: "1", skr: "1", recipient: other.wallet, wallet: other.wallet });
    expect(r.statusCode).toBe(200);
    expect(chain.balance(p.wallet, usdc)).toBe(USDC_AMOUNT);
    expect(chain.balance(p.wallet, skr)).toBe(SKR_AMOUNT);
    expect(chain.balance(other.wallet, usdc)).toBe(0n);
  });

  it("allows one claim per wallet per 24 hours, then again after the window", async () => {
    const { w, chain, usdc } = await faucetWorld();
    const p = await person(w);
    const first = await claim(w, p);
    expect(first.statusCode).toBe(200);
    const again = await claim(w, p);
    expect(again.statusCode).toBe(429);
    expect(again.json().error).toMatchObject({ code: "faucet_cooldown" });
    expect(again.json().error.details.nextClaimAt).toBe(first.json().nextClaimAt);
    expect(chain.balance(p.wallet, usdc)).toBe(USDC_AMOUNT); // nothing extra was minted

    w.clock.wall += DAY - 1;
    expect((await claim(w, p)).statusCode).toBe(429); // one second early
    expect((await status(w, p)).canClaim).toBe(false);
    w.clock.wall += 1;
    expect((await status(w, p)).canClaim).toBe(true);
    expect((await claim(w, p)).statusCode).toBe(200);
    expect(chain.balance(p.wallet, usdc)).toBe(USDC_AMOUNT * 2n);
  });

  it("limits each wallet separately", async () => {
    const { w, chain, usdc } = await faucetWorld();
    const [a, b] = [await person(w), await person(w)];
    expect((await claim(w, a)).statusCode).toBe(200);
    expect((await claim(w, a)).statusCode).toBe(429);
    expect((await claim(w, b)).statusCode).toBe(200);
    expect(chain.balance(b.wallet, usdc)).toBe(USDC_AMOUNT);
  });

  it("stops everyone at the global daily cap and starts again on the next UTC day", async () => {
    const { w, chain, usdc } = await faucetWorld({ FAUCET_GLOBAL_DAILY_CLAIMS: "3" });
    // start a little before UTC midnight so "tomorrow" is close
    w.clock.wall = Math.floor(w.clock.wall / DAY) * DAY + DAY - 3_600;
    const people = [await person(w), await person(w), await person(w), await person(w)];
    for (const p of people.slice(0, 3)) expect((await claim(w, p)).statusCode).toBe(200);
    expect((await status(w, people[3]!)).claimsLeftToday).toBe(0);
    const capped = await claim(w, people[3]!);
    expect(capped.statusCode).toBe(429);
    expect(capped.json().error.code).toBe("faucet_daily_cap");
    expect(capped.json().error.details.nextClaimAt).toBe(Math.floor(w.clock.wall / DAY) * DAY + DAY);
    expect(chain.balance(people[3]!.wallet, usdc)).toBe(0n);

    w.clock.wall += 3_601; // past UTC midnight
    expect((await status(w, people[3]!)).claimsLeftToday).toBe(3);
    expect((await claim(w, people[3]!)).statusCode).toBe(200);
  });

  it("two simultaneous clicks mint once", async () => {
    const { w, chain, usdc } = await faucetWorld();
    const p = await person(w);
    chain.delayMs = 25;
    const results = await Promise.all([claim(w, p), claim(w, p), claim(w, p)]);
    expect(results.map((r) => r.statusCode).sort()).toEqual([200, 429, 429]);
    expect(chain.balance(p.wallet, usdc)).toBe(USDC_AMOUNT);
    expect(chain.sends).toBe(1);
  });

  it("a failed send is reported, mints nothing, and does not use up the wallet claim or the global cap", async () => {
    const { w, chain, usdc } = await faucetWorld({ FAUCET_GLOBAL_DAILY_CLAIMS: "1" });
    const p = await person(w);
    chain.failNext = 1;
    const bad = await claim(w, p);
    expect(bad.statusCode).toBe(502);
    expect(bad.json().error.code).toBe("faucet_failed");
    expect(chain.balance(p.wallet, usdc)).toBe(0n);
    expect((await status(w, p)).claimsLeftToday).toBe(1);
    expect((await claim(w, p)).statusCode).toBe(200); // the retry works and is the only claim counted
    expect(chain.balance(p.wallet, usdc)).toBe(USDC_AMOUNT);
    expect((await status(w, await person(w))).claimsLeftToday).toBe(0);
  });

  it("refuses when the fee wallet of the faucet is nearly empty, without minting", async () => {
    const { w, chain, authority, usdc } = await faucetWorld();
    chain.fund(authority.publicKey.toBase58(), FAUCET_MIN_AUTHORITY_LAMPORTS - 1n);
    const p = await person(w);
    const r = await claim(w, p);
    expect(r.statusCode).toBe(503);
    expect(r.json().error.code).toBe("faucet_unfunded");
    expect(chain.balance(p.wallet, usdc)).toBe(0n);
    expect(chain.sends).toBe(0);
    chain.fund(authority.publicKey.toBase58(), FAUCET_MIN_AUTHORITY_LAMPORTS);
    expect((await claim(w, p)).statusCode).toBe(200); // the failed attempt did not burn the claim of this wallet
  });

  it("only the configured mint authority can mint: a wrong key fails and nothing is recorded as sent", async () => {
    const wrong = Keypair.generate();
    const { w, chain, usdc } = await faucetWorld();
    chain.mintAuthority.set(usdc, wrong.publicKey.toBase58()); // the mint is controlled by somebody else
    const p = await person(w);
    expect((await claim(w, p)).statusCode).toBe(502);
    expect(chain.balance(p.wallet, usdc)).toBe(0n);
    expect(w.s.db.prepare("SELECT COUNT(*) AS n FROM faucet_claims WHERE status = 'sent'").get()).toEqual({ n: 0 });
  });

  it("never exposes the authority key in any response", async () => {
    const { w, authority } = await faucetWorld();
    const p = await person(w);
    const body = JSON.stringify([await status(w, p), (await claim(w, p)).json(), (await claim(w, p)).json()]);
    expect(body).not.toContain(Buffer.from(authority.secretKey).toString("base64"));
    expect(body).not.toContain(JSON.stringify(Array.from(authority.secretKey)));
    expect(body.toLowerCase()).not.toContain("secret");
  });
});
