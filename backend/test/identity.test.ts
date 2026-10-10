import { Keypair } from "@solana/web3.js";
import nacl from "tweetnacl";
import { describe, expect, it } from "vitest";
import { siwsDomain } from "../src/config.js";
import { DEFAULT_CERT_SHA256 } from "../src/http/identity.js";
import { buildSiwsMessage } from "../src/http/auth.js";
import { makeWorld } from "./helpers/world.js";

describe("app identity for wallets", () => {
  it("publishes Digital Asset Links for the Android package and the release certificate, without signing in", async () => {
    const w = await makeWorld();
    const r = await w.app.inject({ method: "GET", url: "/.well-known/assetlinks.json" });
    expect(r.statusCode).toBe(200);
    expect(r.headers["content-type"]).toContain("application/json");
    const j = r.json();
    expect(j).toHaveLength(1);
    expect(j[0].relation).toEqual(["delegate_permission/common.handle_all_urls"]);
    expect(j[0].target).toEqual({ namespace: "android_app", package_name: "app.vowed", sha256_cert_fingerprints: [DEFAULT_CERT_SHA256] });
    expect(DEFAULT_CERT_SHA256).toMatch(/^([0-9A-F]{2}:){31}[0-9A-F]{2}$/);
    expect(DEFAULT_CERT_SHA256.startsWith("28:11:C2:29")).toBe(true);
  });

  it("can be pointed at another certificate or package with environment variables", async () => {
    const w = await makeWorld({ env: { ANDROID_CERT_SHA256: "AA:BB, CC:DD", ANDROID_PACKAGE: "app.vowed.test" } });
    const j = (await w.app.inject({ method: "GET", url: "/.well-known/assetlinks.json" })).json();
    expect(j[0].target.package_name).toBe("app.vowed.test");
    expect(j[0].target.sha256_cert_fingerprints).toEqual(["AA:BB", "CC:DD"]);
  });

  it("serves the app icon as a PNG at /icon.png and /favicon.ico", async () => {
    const w = await makeWorld();
    for (const url of ["/icon.png", "/favicon.ico"]) {
      const r = await w.app.inject({ method: "GET", url });
      expect(r.statusCode).toBe(200);
      expect(r.headers["content-type"]).toBe("image/png");
      expect(Array.from(r.rawPayload.subarray(0, 8))).toEqual([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
      expect(r.rawPayload.length).toBeGreaterThan(1000);
    }
  });

  it("the sign-in domain is SIWS_DOMAIN, else the Render hostname, else AUTH_DOMAIN", async () => {
    expect(siwsDomain((await makeWorld()).s.config)).toBe("vowed.app");
    expect(siwsDomain((await makeWorld({ env: { RENDER_EXTERNAL_HOSTNAME: "vowed-backend.onrender.com" } })).s.config)).toBe("vowed-backend.onrender.com");
    expect(siwsDomain((await makeWorld({ env: { RENDER_EXTERNAL_HOSTNAME: "a.onrender.com", SIWS_DOMAIN: "idp.example" } })).s.config)).toBe("idp.example");
  });

  it("nonces carry the identity host, and sign-in accepts both the new domain and the older one", async () => {
    const w = await makeWorld({ env: { RENDER_EXTERNAL_HOSTNAME: "vowed-backend.onrender.com" } });
    const kp = Keypair.generate();
    const wallet = kp.publicKey.toBase58();
    const sign = async (domainOverride?: string) => {
      const n = (await w.app.inject({ method: "POST", url: "/v1/auth/nonce", payload: {} })).json();
      expect(n.domain).toBe("vowed-backend.onrender.com");
      expect(n.uri).toBe("https://vowed-backend.onrender.com");
      const msg = new TextEncoder().encode(buildSiwsMessage({ domain: domainOverride ?? n.domain, address: wallet, statement: n.statement, uri: n.uri, nonce: n.nonce, issuedAt: n.issuedAt, expirationTime: n.expirationTime }));
      return w.app.inject({ method: "POST", url: "/v1/auth/verify", payload: { wallet, message: Buffer.from(msg).toString("base64"), signature: Buffer.from(nacl.sign.detached(msg, kp.secretKey)).toString("base64") } });
    };
    expect((await sign()).statusCode).toBe(200);
    expect((await sign("vowed.app")).statusCode).toBe(200); // an older build that was given the previous domain
    expect((await sign("evil.example")).statusCode).toBe(401);
  });
});
