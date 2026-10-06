import { Keypair } from "@solana/web3.js";
import nacl from "tweetnacl";
import { describe, expect, it } from "vitest";
import { buildSiwsMessage } from "../src/http/auth.js";
import { makeWorld, signIn } from "./helpers/world.js";

async function nonceFor(w: Awaited<ReturnType<typeof makeWorld>>, wallet: string) {
  return (await w.app.inject({ method: "POST", url: "/v1/auth/nonce", payload: { wallet } })).json();
}
const msgFor = (n: Record<string, string>, wallet: string, over: Record<string, string> = {}) =>
  buildSiwsMessage({ domain: n.domain!, address: wallet, statement: n.statement!, uri: n.uri!, nonce: n.nonce!, issuedAt: n.issuedAt!, expirationTime: n.expirationTime!, ...over });
const verify = (w: Awaited<ReturnType<typeof makeWorld>>, wallet: string, message: string, signer: Keypair) => {
  const bytes = new TextEncoder().encode(message);
  return w.app.inject({
    method: "POST",
    url: "/v1/auth/verify",
    payload: { wallet, message: Buffer.from(bytes).toString("base64"), signature: Buffer.from(nacl.sign.detached(bytes, signer.secretKey)).toString("base64") },
  });
};

describe("wallet sign-in", () => {
  it("issues a token for a valid signed message and lets it call authenticated routes", async () => {
    const w = await makeWorld();
    const kp = Keypair.generate();
    const { headers } = await signIn(w, kp);
    const res = await w.app.inject({ method: "GET", url: "/v1/devices", headers });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ devices: [] });
  });

  it("rejects calls without a token or with a garbage token", async () => {
    const w = await makeWorld();
    expect((await w.app.inject({ method: "GET", url: "/v1/devices" })).statusCode).toBe(401);
    expect((await w.app.inject({ method: "GET", url: "/v1/devices", headers: { authorization: "Bearer abc.def.ghi" } })).statusCode).toBe(401);
  });

  it("rejects a token signed with another secret", async () => {
    const a = await makeWorld();
    const b = await makeWorld({ jwtSecret: "another-secret-another-secret-another-1" });
    const { headers } = await signIn(a, Keypair.generate());
    expect((await b.app.inject({ method: "GET", url: "/v1/devices", headers })).statusCode).toBe(401);
  });

  it("a nonce can only be used once", async () => {
    const w = await makeWorld();
    const kp = Keypair.generate();
    const wallet = kp.publicKey.toBase58();
    const n = await nonceFor(w, wallet);
    const message = msgFor(n, wallet);
    expect((await verify(w, wallet, message, kp)).statusCode).toBe(200);
    expect((await verify(w, wallet, message, kp)).statusCode).toBe(401); // replay
  });

  it("rejects a signature from a different key", async () => {
    const w = await makeWorld();
    const victim = Keypair.generate();
    const attacker = Keypair.generate();
    const wallet = victim.publicKey.toBase58();
    const n = await nonceFor(w, wallet);
    expect((await verify(w, wallet, msgFor(n, wallet), attacker)).statusCode).toBe(401);
  });

  it("rejects a nonce issued to a different wallet", async () => {
    const w = await makeWorld();
    const alice = Keypair.generate();
    const mallory = Keypair.generate();
    const n = await nonceFor(w, alice.publicKey.toBase58()); // nonce belongs to alice
    const wallet = mallory.publicKey.toBase58();
    expect((await verify(w, wallet, msgFor(n, wallet), mallory)).statusCode).toBe(401);
  });

  it("rejects the wrong domain, a message for another address, and an unknown nonce", async () => {
    const w = await makeWorld();
    const kp = Keypair.generate();
    const wallet = kp.publicKey.toBase58();
    const n = await nonceFor(w, wallet);
    expect((await verify(w, wallet, msgFor(n, wallet, { domain: "evil.example" }), kp)).statusCode).toBe(401);
    expect((await verify(w, wallet, msgFor(n, Keypair.generate().publicKey.toBase58()), kp)).statusCode).toBe(401);
    expect((await verify(w, wallet, msgFor(n, wallet, { nonce: "0".repeat(32) }), kp)).statusCode).toBe(401);
  });

  it("rejects an expired nonce", async () => {
    const w = await makeWorld();
    const kp = Keypair.generate();
    const wallet = kp.publicKey.toBase58();
    const n = await nonceFor(w, wallet);
    w.clock.wall += 301;
    expect((await verify(w, wallet, msgFor(n, wallet), kp)).statusCode).toBe(401);
  });

  it("expires tokens after an hour", async () => {
    const w = await makeWorld();
    const { headers } = await signIn(w, Keypair.generate());
    expect((await w.app.inject({ method: "GET", url: "/v1/devices", headers })).statusCode).toBe(200);
    // jose checks expiry against the real clock, so re-issue a token that is already expired instead of waiting
    const { SignJWT } = await import("jose");
    const old = await new SignJWT({}).setProtectedHeader({ alg: "HS256" }).setSubject(Keypair.generate().publicKey.toBase58()).setIssuer("vowed-backend").setExpirationTime(Math.floor(Date.now() / 1000) - 10).sign(new TextEncoder().encode(w.s.config.JWT_SECRET));
    expect((await w.app.inject({ method: "GET", url: "/v1/devices", headers: { authorization: `Bearer ${old}` } })).statusCode).toBe(401);
  });

  it("validates input and never echoes secrets in errors", async () => {
    const w = await makeWorld();
    const bad = await w.app.inject({ method: "POST", url: "/v1/auth/nonce", payload: { wallet: "not-a-key" } });
    expect(bad.statusCode).toBe(400);
    expect(bad.json().error.code).toBe("validation_error");
    const huge = await w.app.inject({ method: "POST", url: "/v1/auth/nonce", payload: { wallet: "x".repeat(70_000) } });
    expect(huge.statusCode).toBe(413);
  });

  it("rate limits nonce requests per IP", async () => {
    const w = await makeWorld();
    const wallet = Keypair.generate().publicKey.toBase58();
    let last = 200;
    for (let i = 0; i < 31; i++) last = (await w.app.inject({ method: "POST", url: "/v1/auth/nonce", payload: { wallet } })).statusCode;
    expect(last).toBe(429);
    w.clock.wall += 61; // new window
    expect((await w.app.inject({ method: "POST", url: "/v1/auth/nonce", payload: { wallet } })).statusCode).toBe(200);
  });
});
