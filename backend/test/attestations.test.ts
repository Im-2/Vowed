import { generateKeyPairSync, sign as cryptoSign, randomBytes } from "node:crypto";
import { Keypair } from "@solana/web3.js";
import nacl from "tweetnacl";
import { describe, expect, it } from "vitest";
import { canonicalAttestationBytes, type Attestation } from "../src/proofs/attestation.js";
import { makeWorld, signIn } from "./helpers/world.js";

async function setup(env: Record<string, string> = { SAMPLE_PROVIDER_ENABLED: "true" }) {
  const w = await makeWorld({ env });
  const kp = Keypair.generate();
  const me = await signIn(w, kp);
  const wallet = kp.publicKey.toBase58();
  const ec = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const spki = ec.publicKey.export({ type: "spki", format: "der" }).toString("base64");
  return { w, kp, me, wallet, ec, spki };
}
type Ctx = Awaited<ReturnType<typeof setup>>;

const register = (x: Ctx, keyId = "k1", publicKey = x.spki) =>
  x.w.app.inject({ method: "POST", url: "/v1/providers/sample/register", headers: x.me.headers, payload: { keyId, publicKey, algorithm: "ES256" } });
const submit = (x: Ctx, a: unknown, headers = x.me.headers) => x.w.app.inject({ method: "POST", url: "/v1/attestations", headers, payload: a as object });

function statement(x: Ctx, over: Partial<Attestation> = {}): Omit<Attestation, "signature"> {
  const now = x.w.s.wallNow();
  return { v: 1, provider: "sample.focus", keyId: "k1", wallet: x.wallet, metric: "focus_seconds", value: 1500, unit: "seconds", windowStart: now - 1_600, windowEnd: now - 5, nonce: randomBytes(16).toString("base64"), issuedAt: now - 2, alg: "ES256", ...over };
}
const signEs = (x: Ctx, a: Omit<Attestation, "signature">, key = x.ec.privateKey): Attestation => ({ ...a, signature: cryptoSign("sha256", canonicalAttestationBytes(a), { key, dsaEncoding: "der" }).toString("base64") });

describe("proof plug-ins: signed attestations", () => {
  it("accepts the sample provider's signed statement, records it with a LOW trust tier, and says it is not a check-in yet", async () => {
    const x = await setup();
    expect((await register(x)).statusCode).toBe(200);
    const r = await submit(x, signEs(x, statement(x)));
    expect(r.statusCode).toBe(200);
    expect(r.json()).toMatchObject({ accepted: true, provider: "sample.focus", metric: "focus_seconds", value: 1500, trustTier: "low" });
    expect(r.json().note).toMatch(/not count as a daily check-in/);
    expect(x.w.s.db.prepare("SELECT COUNT(*) AS n FROM attestations").get()).toEqual({ n: 1 });
  });

  it("accepts an Ed25519 provider key too", async () => {
    const x = await setup();
    const k = nacl.sign.keyPair();
    x.w.s.db.prepare("INSERT INTO provider_keys (provider_id, key_id, public_key, algorithm, wallet, created_at) VALUES (?,?,?,?,?,?)").run("reading.app", "e1", Buffer.from(k.publicKey).toString("base64"), "EdDSA", null, 1);
    const base = statement(x, { provider: "reading.app", keyId: "e1", alg: "EdDSA", metric: "reading_minutes", value: 25, unit: "minutes" });
    const a: Attestation = { ...base, signature: Buffer.from(nacl.sign.detached(canonicalAttestationBytes(base), k.secretKey)).toString("base64") };
    expect((await submit(x, a)).statusCode).toBe(200);
  });

  it("rejects a forged signature, a changed value, and a key that was never registered", async () => {
    const x = await setup();
    await register(x);
    const good = signEs(x, statement(x));
    expect((await submit(x, { ...good, value: 9_999 })).json().error.code).toBe("bad_signature"); // changed after signing
    const attacker = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    expect((await submit(x, signEs(x, statement(x), attacker.privateKey))).json().error.code).toBe("bad_signature");
    expect((await submit(x, signEs(x, statement(x, { keyId: "unknown" })))).json().error.code).toBe("unknown_provider_key");
    expect((await submit(x, signEs(x, statement(x, { provider: "evil.app" })))).json().error.code).toBe("unknown_provider_key");
    expect((await submit(x, signEs(x, statement(x, { alg: "EdDSA" })))).json().error.code).toBe("unknown_provider_key"); // algorithm must match the registered key
  });

  it("rejects a replayed statement", async () => {
    const x = await setup();
    await register(x);
    const a = signEs(x, statement(x));
    expect((await submit(x, a)).statusCode).toBe(200);
    const again = await submit(x, a);
    expect(again.statusCode).toBe(409);
    expect(again.json().error.code).toBe("attestation_replayed");
  });

  it("rejects stale, future-dated, wrong-window and wrong-wallet statements", async () => {
    const x = await setup();
    await register(x);
    const now = x.w.s.wallNow();
    expect((await submit(x, signEs(x, statement(x, { windowStart: now - 5_000, windowEnd: now - 3_700, issuedAt: now - 3_600 })))).json().error.code).toBe("attestation_stale");
    expect((await submit(x, signEs(x, statement(x, { issuedAt: now + 3_600, windowEnd: now })))).json().error.code).toBe("attestation_from_the_future");
    expect((await submit(x, signEs(x, statement(x, { windowStart: now - 200_000, windowEnd: now - 5 })))).json().error.code).toBe("attestation_bad_window");
    expect((await submit(x, signEs(x, statement(x, { windowStart: now - 10, windowEnd: now - 100 })))).json().error.code).toBe("attestation_bad_window");
    expect((await submit(x, signEs(x, statement(x, { wallet: Keypair.generate().publicKey.toBase58() })))).json().error.code).toBe("attestation_wrong_wallet");
  });

  it("does not let one wallet use a key that another wallet registered", async () => {
    const x = await setup();
    await register(x);
    const other = Keypair.generate();
    const o = await signIn(x.w, other);
    const a = signEs(x, statement(x, { wallet: other.publicKey.toBase58() }));
    expect((await submit(x, a, o.headers)).json().error.code).toBe("unknown_provider_key");
  });

  it("needs a signed-in wallet and well-formed input, and the sample key registration is off unless enabled", async () => {
    const x = await setup();
    await register(x);
    expect((await x.w.app.inject({ method: "POST", url: "/v1/attestations", payload: signEs(x, statement(x)) })).statusCode).toBe(401);
    expect((await submit(x, { v: 1, provider: "x" })).statusCode).toBe(400);
    expect((await register(x, "k2", Buffer.from("not a key at all, just some text in base64").toString("base64"))).json().error.code).toBe("bad_public_key");
    const off = await setup({});
    expect((await register(off)).statusCode).toBe(404);
  });

  it("only the wallet that registered a key can replace it", async () => {
    const x = await setup();
    await register(x);
    const other = Keypair.generate();
    const o = await signIn(x.w, other);
    const ec2 = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    await x.w.app.inject({ method: "POST", url: "/v1/providers/sample/register", headers: o.headers, payload: { keyId: "k1", publicKey: ec2.publicKey.export({ type: "spki", format: "der" }).toString("base64"), algorithm: "ES256" } });
    // the original owner's key still verifies: the other wallet could not replace it
    expect((await submit(x, signEs(x, statement(x)))).statusCode).toBe(200);
  });
});

describe("shared attestation test vector", () => {
  it("verifies with the backend, so the Android sample provider and the server agree on the signed bytes", async () => {
    const { readFileSync } = await import("node:fs");
    const { verifyAttestationSignature, canonicalAttestationBytes } = await import("../src/proofs/attestation.js");
    const v = JSON.parse(readFileSync(new URL("../../shared/test-vectors/attestation.json", import.meta.url), "utf8"));
    expect(verifyAttestationSignature(v.attestation, v.publicKeySpkiBase64)).toBe(true);
    const { signature: _sig, ...rest } = v.attestation;
    void _sig;
    expect(canonicalAttestationBytes(rest).toString("utf8")).toBe(v.canonicalUtf8);
    expect(verifyAttestationSignature({ ...v.attestation, value: 1501 }, v.publicKeySpkiBase64)).toBe(false);
  });
});
