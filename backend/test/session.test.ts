import { Keypair } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { SESSION_MAX_SEC, TOKEN_TTL_SEC } from "../src/http/auth.js";
import { makeWorld, signIn } from "./helpers/world.js";

const payloadOf = (token: string) => JSON.parse(Buffer.from(token.split(".")[1]!, "base64url").toString("utf8")) as { sub: string; sat: number; exp: number };
const refresh = (w: Awaited<ReturnType<typeof makeWorld>>, token: string | null) =>
  w.app.inject({ method: "POST", url: "/v1/auth/refresh", headers: token ? { authorization: `Bearer ${token}` } : {} });

describe("session refresh", () => {
  it("swaps a valid token for a new one for the same wallet and keeps the original sign-in time", async () => {
    const w = await makeWorld();
    const kp = Keypair.generate();
    const { token } = await signIn(w, kp);
    w.clock.wall += 3_600;
    const r = await refresh(w, token);
    expect(r.statusCode).toBe(200);
    const j = r.json();
    expect(j.wallet).toBe(kp.publicKey.toBase58());
    expect(j.token).not.toBe(token);
    expect(payloadOf(j.token).sat).toBe(payloadOf(token).sat);
    expect(payloadOf(j.token).exp - payloadOf(token).exp).toBe(3_600);
    expect(TOKEN_TTL_SEC).toBe(21_600);
    // the new token works
    expect((await w.app.inject({ method: "GET", url: "/v1/faucet", headers: { authorization: `Bearer ${j.token}` } })).statusCode).toBe(200);
  });

  it("refuses without a token and with a made-up one", async () => {
    const w = await makeWorld();
    expect((await refresh(w, null)).statusCode).toBe(401);
    expect((await refresh(w, "abc.def.ghi")).statusCode).toBe(401);
  });

  it("cannot extend a session for ever: after 7 days the wallet must sign in again", async () => {
    const w = await makeWorld();
    const { token } = await signIn(w, Keypair.generate());
    w.clock.wall += SESSION_MAX_SEC + 10;
    const r = await refresh(w, token);
    expect(r.statusCode).toBe(401);
    expect(r.json().error.message).toContain("sign in with the wallet again");
  });
});
