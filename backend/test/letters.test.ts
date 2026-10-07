import { Keypair } from "@solana/web3.js";
import { describe, expect, it } from "vitest";
import { makeWorld, signIn } from "./helpers/world.js";

describe("letter trigger events", () => {
  it("sends a push that says a letter is waiting, with no letter content, only to the person who reported it", async () => {
    const w = await makeWorld();
    const kp = Keypair.generate();
    const me = await signIn(w, kp);
    const other = await signIn(w, Keypair.generate());
    await w.app.inject({ method: "POST", url: "/v1/push/register", headers: me.headers, payload: { token: "m".repeat(40) } });
    await w.app.inject({ method: "POST", url: "/v1/push/register", headers: other.headers, payload: { token: "o".repeat(40) } });
    const r = await w.app.inject({ method: "POST", url: "/v1/letters/trigger-events", headers: me.headers, payload: { event: "milestone" } });
    expect(r.statusCode).toBe(200);
    expect(r.json().delivered).toBe(1);
    expect(w.push.sent).toHaveLength(1);
    expect(w.push.sent[0]!.tokens).toEqual(["m".repeat(40)]);
    expect(w.push.sent[0]!.message.title).toBe("A letter from your past self");
    expect(JSON.stringify(w.push.sent[0]!.message)).not.toMatch(/Dear me|text/);
    expect(w.push.sent[0]!.message.data).toEqual({ type: "letter" });
  });

  it("needs a signed-in user, accepts only the two known events, and is rate limited", async () => {
    const w = await makeWorld();
    expect((await w.app.inject({ method: "POST", url: "/v1/letters/trigger-events", payload: { event: "milestone" } })).statusCode).toBe(401);
    const me = await signIn(w, Keypair.generate());
    const post = (event: string) => w.app.inject({ method: "POST", url: "/v1/letters/trigger-events", headers: me.headers, payload: { event, text: "Dear me" } });
    expect((await post("anything")).statusCode).toBe(400);
    for (let i = 0; i < 10; i++) expect((await post("streak_broken")).statusCode).toBe(200);
    expect((await post("streak_broken")).statusCode).toBe(429);
  });
});
