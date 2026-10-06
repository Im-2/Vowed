import { generateKeyPairSync } from "node:crypto";
import { describe, expect, it } from "vitest";
import { FcmPushSender } from "../src/push/fcm.js";

const pem = generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey.export({ type: "pkcs8", format: "pem" }) as string;
const account = JSON.stringify({ project_id: "demo-proj", client_email: "svc@demo-proj.iam.gserviceaccount.com", private_key: pem });

describe("FCM HTTP v1 sender (fake network)", () => {
  it("authenticates once, sends one request per token, and reports unregistered tokens", async () => {
    const calls: { url: string; init?: RequestInit }[] = [];
    const fakeFetch = (async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      if (url.includes("oauth2")) return new Response(JSON.stringify({ access_token: "tok-1", expires_in: 3600 }), { status: 200 });
      const body = JSON.parse(init!.body as string) as { message: { token: string } };
      if (body.message.token === "dead") return new Response(JSON.stringify({ error: { status: "NOT_FOUND" } }), { status: 404 });
      return new Response("{}", { status: 200 });
    }) as unknown as typeof fetch;
    const sender = new FcmPushSender(account, fakeFetch);
    const res = await sender.send(["alive", "dead"], { title: "Hi", body: "There", data: { type: "nudge" } });
    expect(res.invalidTokens).toEqual(["dead"]);
    expect(calls.filter((c) => c.url.includes("oauth2"))).toHaveLength(1);
    const sends = calls.filter((c) => c.url.includes("fcm.googleapis.com"));
    expect(sends).toHaveLength(2);
    expect(sends[0]!.url).toBe("https://fcm.googleapis.com/v1/projects/demo-proj/messages:send");
    expect((sends[0]!.init!.headers as Record<string, string>).authorization).toBe("Bearer tok-1");
    expect(JSON.parse(sends[0]!.init!.body as string).message).toMatchObject({ token: "alive", notification: { title: "Hi", body: "There" }, data: { type: "nudge" } });
    await sender.send(["alive"], { title: "x", body: "y" });
    expect(calls.filter((c) => c.url.includes("oauth2"))).toHaveLength(1); // access token cached
  });

  it("rejects an incomplete service account without echoing it", () => {
    expect(() => new FcmPushSender(JSON.stringify({ project_id: "x" }))).toThrow("incomplete FCM service account");
  });
});
