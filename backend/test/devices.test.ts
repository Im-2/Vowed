import { generateKeyPairSync } from "node:crypto";
import { Keypair } from "@solana/web3.js";
import nacl from "tweetnacl";
import { describe, expect, it } from "vitest";
import { trustCapFor } from "../src/http/devices.js";
import { deviceRegistrationMessage } from "../src/http/devices.js";
import { fakeAttestation, makeDevice, makeWorld, registerDevice, signIn } from "./helpers/world.js";

describe("device registration", () => {
  it("registers a device that passed attestation and caps trust by the attestation result", async () => {
    const w = await makeWorld({ attestation: fakeAttestation() });
    const kp = Keypair.generate();
    const auth = await signIn(w, kp);
    const dev = makeDevice();
    const res = await registerDevice(w, kp, auth, dev, ["Zm9v", "YmFy"]);
    expect(res.statusCode).toBe(200);
    expect(res.json()).toMatchObject({ deviceId: dev.id, trustCap: "high", attestation: { level: "tee", verifiedBoot: "verified" } });
    const list = (await w.app.inject({ method: "GET", url: "/v1/devices", headers: auth.headers })).json();
    expect(list.devices).toHaveLength(1);
    expect(list.devices[0]).toMatchObject({ deviceId: dev.id, trustCap: "high" });
  });

  it("without attestation the device is accepted in development but capped at low trust, and refused when attestation is required", async () => {
    const lax = await makeWorld();
    const kp = Keypair.generate();
    const auth = await signIn(lax, kp);
    const res = await registerDevice(lax, kp, auth, makeDevice());
    expect(res.statusCode).toBe(200);
    expect(res.json()).toMatchObject({ trustCap: "low", attestation: { level: "none" } });

    const strict = await makeWorld({ requireAttestation: true });
    const auth2 = await signIn(strict, kp);
    const refused = await registerDevice(strict, kp, auth2, makeDevice());
    expect(refused.statusCode).toBe(400);
    expect(refused.json().error.code).toBe("attestation_required");
  });

  it("refuses a device whose attestation does not verify (no silent downgrade of a bad chain)", async () => {
    const w = await makeWorld({ attestation: fakeAttestation({ ok: false, level: "none", reason: "attestation challenge does not match" }) });
    const kp = Keypair.generate();
    const res = await registerDevice(w, kp, await signIn(w, kp), makeDevice(), ["Zm9v", "YmFy"]);
    expect(res.statusCode).toBe(400);
    expect(res.json().error).toMatchObject({ code: "attestation_failed", message: "attestation challenge does not match" });
  });

  it("requires the wallet to have signed this exact device and challenge", async () => {
    const w = await makeWorld({ attestation: fakeAttestation() });
    const kp = Keypair.generate();
    const mallory = Keypair.generate();
    const auth = await signIn(w, kp);
    const dev = makeDevice();
    const { challenge } = (await w.app.inject({ method: "POST", url: "/v1/devices/challenge", headers: auth.headers })).json();
    const msg = new TextEncoder().encode(deviceRegistrationMessage(kp.publicKey.toBase58(), dev.id, challenge));
    const body = (sig: Uint8Array) => ({ devicePublicKey: dev.spki.toString("base64"), attestationChain: ["Zm9v", "YmFy"], challenge, walletSignature: Buffer.from(sig).toString("base64") });
    // signed by someone else
    let res = await w.app.inject({ method: "POST", url: "/v1/devices/register", headers: auth.headers, payload: body(nacl.sign.detached(msg, mallory.secretKey)) });
    expect(res.json().error.code).toBe("bad_wallet_signature");
    // signed for a different device id
    const other = makeDevice();
    const wrongMsg = new TextEncoder().encode(deviceRegistrationMessage(kp.publicKey.toBase58(), other.id, challenge));
    res = await w.app.inject({ method: "POST", url: "/v1/devices/register", headers: auth.headers, payload: body(nacl.sign.detached(wrongMsg, kp.secretKey)) });
    expect(res.json().error.code).toBe("bad_wallet_signature");
    // the failed attempts did not burn the challenge: the right signature still works once
    res = await w.app.inject({ method: "POST", url: "/v1/devices/register", headers: auth.headers, payload: body(nacl.sign.detached(msg, kp.secretKey)) });
    expect(res.statusCode).toBe(200);
    // and not twice
    res = await w.app.inject({ method: "POST", url: "/v1/devices/register", headers: auth.headers, payload: body(nacl.sign.detached(msg, kp.secretKey)) });
    expect(res.json().error.code).toBe("bad_challenge");
  });

  it("rejects an expired challenge and a challenge issued to another wallet", async () => {
    const w = await makeWorld({ attestation: fakeAttestation() });
    const kp = Keypair.generate();
    const other = Keypair.generate();
    const auth = await signIn(w, kp);
    const authOther = await signIn(w, other);
    const dev = makeDevice();
    // challenge issued to `other`, used by `kp`
    const { challenge } = (await w.app.inject({ method: "POST", url: "/v1/devices/challenge", headers: authOther.headers })).json();
    const msg = new TextEncoder().encode(deviceRegistrationMessage(kp.publicKey.toBase58(), dev.id, challenge));
    const payload = { devicePublicKey: dev.spki.toString("base64"), attestationChain: ["Zm9v", "YmFy"], challenge, walletSignature: Buffer.from(nacl.sign.detached(msg, kp.secretKey)).toString("base64") };
    expect((await w.app.inject({ method: "POST", url: "/v1/devices/register", headers: auth.headers, payload })).json().error.code).toBe("bad_challenge");
    // expired
    const mine = (await w.app.inject({ method: "POST", url: "/v1/devices/challenge", headers: auth.headers })).json();
    w.clock.wall += 601;
    const msg2 = new TextEncoder().encode(deviceRegistrationMessage(kp.publicKey.toBase58(), dev.id, mine.challenge));
    const res = await w.app.inject({
      method: "POST",
      url: "/v1/devices/register",
      headers: auth.headers,
      payload: { devicePublicKey: dev.spki.toString("base64"), attestationChain: ["Zm9v", "YmFy"], challenge: mine.challenge, walletSignature: Buffer.from(nacl.sign.detached(msg2, kp.secretKey)).toString("base64") },
    });
    expect(res.json().error.code).toBe("bad_challenge");
  });

  it("only accepts EC P-256 keys", async () => {
    const w = await makeWorld({ attestation: fakeAttestation() });
    const kp = Keypair.generate();
    const auth = await signIn(w, kp);
    const p384 = generateKeyPairSync("ec", { namedCurve: "secp384r1" }).publicKey.export({ type: "spki", format: "der" });
    const ed = generateKeyPairSync("ed25519").publicKey.export({ type: "spki", format: "der" });
    for (const spki of [p384, ed, Buffer.from("not a key")]) {
      const { challenge } = (await w.app.inject({ method: "POST", url: "/v1/devices/challenge", headers: auth.headers })).json();
      const res = await w.app.inject({
        method: "POST",
        url: "/v1/devices/register",
        headers: auth.headers,
        payload: { devicePublicKey: spki.toString("base64"), challenge, walletSignature: Buffer.alloc(64).toString("base64") },
      });
      expect(res.statusCode).toBe(400);
      expect(res.json().error.code).toBe("bad_device_key");
    }
  });

  it("maps attestation results to trust caps", () => {
    const base = { ok: true, level: "tee" as const, reason: "" };
    expect(trustCapFor({ ...base, verifiedBoot: "verified" })).toBe("high");
    expect(trustCapFor({ ...base, level: "strongbox", verifiedBoot: "verified" })).toBe("high");
    expect(trustCapFor({ ...base, verifiedBoot: "self_signed" })).toBe("medium");
    expect(trustCapFor({ ...base, verifiedBoot: "unverified" })).toBe("low");
    expect(trustCapFor({ ...base, verifiedBoot: "failed" })).toBe("low");
    expect(trustCapFor({ ...base, verifiedBoot: "unknown" })).toBe("low");
    expect(trustCapFor({ ...base, level: "software", verifiedBoot: "verified" })).toBe("low");
    expect(trustCapFor({ ok: false, level: "none", verifiedBoot: "unknown", reason: "" })).toBe("low");
  });
});
