import { createHash, createPublicKey, randomBytes } from "node:crypto";
import { PublicKey } from "@solana/web3.js";
import type { FastifyInstance } from "fastify";
import type { ZodTypeProvider } from "fastify-type-provider-zod";
import nacl from "tweetnacl";
import { z } from "zod";
import { NO_ATTESTATION, type AttestationResult } from "../devices/attestation.js";
import type { TrustTier } from "../domain/plan.js";
import { badRequest } from "../errors.js";
import type { Services } from "../services.js";
import { authenticate, decodeB64 } from "./auth.js";
import { enforce } from "./ratelimit.js";

const CHALLENGE_TTL_SEC = 600;

/** The exact text the wallet signs when registering a device (signed with MWA signMessages). */
export function deviceRegistrationMessage(wallet: string, deviceId: string, challengeB64: string): string {
  return `Vowed device registration\nwallet: ${wallet}\ndevice: ${deviceId}\nchallenge: ${challengeB64}`;
}

export function trustCapFor(a: AttestationResult): TrustTier {
  if (!a.ok || a.level === "none" || a.level === "software") return "low";
  if (a.verifiedBoot === "verified") return "high";
  if (a.verifiedBoot === "self_signed") return "medium";
  return "low";
}

/** Parses an EC P-256 public key in SPKI DER form; throws on anything else. */
export function parseDeviceKey(spki: Uint8Array): string {
  const key = createPublicKey({ key: Buffer.from(spki), format: "der", type: "spki" });
  if (key.asymmetricKeyType !== "ec" || key.asymmetricKeyDetails?.namedCurve !== "prime256v1") throw new Error("not a P-256 key");
  return createHash("sha256").update(spki).digest("hex");
}

export function registerDeviceRoutes(app: FastifyInstance, s: Services) {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const auth = authenticate(s);

  r.post(
    "/v1/devices/challenge",
    {
      preHandler: auth,
      schema: {
        tags: ["devices"],
        summary: "Get the challenge to pass to Android Keystore when generating the device key",
        response: { 200: z.object({ challenge: z.string(), expiresAt: z.number() }) },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:devchal`, 20, 3600);
      const challenge = randomBytes(32);
      const expiresAt = s.wallNow() + CHALLENGE_TTL_SEC;
      s.db.prepare("INSERT INTO nonces (nonce, purpose, wallet, expires_at) VALUES (?, 'device', ?, ?)").run(challenge.toString("hex"), req.wallet!, expiresAt);
      return { challenge: challenge.toString("base64"), expiresAt };
    },
  );

  r.post(
    "/v1/devices/register",
    {
      preHandler: auth,
      schema: {
        tags: ["devices"],
        summary: "Register the device proof key (P-256) with its Android key attestation",
        body: z.object({
          /** base64 SPKI DER of the Keystore public key */
          devicePublicKey: z.string().max(400),
          /** base64 DER certificates, leaf first (the Keystore certificate chain) */
          attestationChain: z.array(z.string().max(6000)).max(6).optional(),
          /** the challenge from /devices/challenge, base64 */
          challenge: z.string().max(100),
          /** wallet ed25519 signature (base64) over deviceRegistrationMessage(wallet, deviceId, challenge) */
          walletSignature: z.string().max(200),
        }),
        response: {
          200: z.object({
            deviceId: z.string(),
            trustCap: z.enum(["high", "medium", "low"]),
            attestation: z.object({ level: z.string(), verifiedBoot: z.string(), note: z.string() }),
          }),
        },
      },
    },
    async (req) => {
      const wallet = req.wallet!;
      enforce(s, `wallet:${wallet}:devreg`, 20, 3600);
      const spki = decodeB64(req.body.devicePublicKey, 300);
      let deviceId: string;
      try {
        deviceId = parseDeviceKey(spki);
      } catch {
        throw badRequest("bad_device_key", "device key must be an EC P-256 public key in SPKI DER form");
      }

      // 1. the wallet vouches for this exact key and challenge
      const msg = new TextEncoder().encode(deviceRegistrationMessage(wallet, deviceId, req.body.challenge));
      const sig = decodeB64(req.body.walletSignature, 64);
      if (sig.length !== 64 || !nacl.sign.detached.verify(msg, sig, new PublicKey(wallet).toBytes())) {
        throw badRequest("bad_wallet_signature", "the wallet signature over the registration message is invalid");
      }

      // 2. the challenge is ours, for this wallet, unused and fresh
      const challengeBytes = decodeB64(req.body.challenge, 64);
      const used = s.db
        .prepare("UPDATE nonces SET used_at = ? WHERE nonce = ? AND purpose = 'device' AND wallet = ? AND used_at IS NULL AND expires_at > ?")
        .run(s.wallNow(), Buffer.from(challengeBytes).toString("hex"), wallet, s.wallNow());
      if (Number(used.changes) !== 1) throw badRequest("bad_challenge", "challenge unknown, used or expired");

      // 3. attestation
      let attestation: AttestationResult;
      if (!req.body.attestationChain || req.body.attestationChain.length === 0) {
        if (s.config.REQUIRE_ATTESTATION) throw badRequest("attestation_required", "this deployment requires hardware key attestation");
        attestation = NO_ATTESTATION;
      } else {
        const chainDer = req.body.attestationChain.map((c) => decodeB64(c, 5000));
        attestation = await s.attestation.verify({ chainDer, expectedChallenge: challengeBytes, devicePublicKeySpki: spki });
        if (!attestation.ok) throw badRequest("attestation_failed", attestation.reason);
      }
      const trustCap = trustCapFor(attestation);

      s.db
        .prepare(
          `INSERT INTO devices (id, wallet, pubkey, attestation_level, trust_cap, attestation_note, created_at) VALUES (?,?,?,?,?,?,?)
           ON CONFLICT(id, wallet) DO UPDATE SET attestation_level=excluded.attestation_level, trust_cap=excluded.trust_cap, attestation_note=excluded.attestation_note`,
        )
        .run(deviceId, wallet, spki, attestation.level, trustCap, attestation.reason, s.wallNow());
      return { deviceId, trustCap, attestation: { level: attestation.level, verifiedBoot: attestation.verifiedBoot, note: attestation.reason } };
    },
  );

  r.get(
    "/v1/devices",
    {
      preHandler: auth,
      schema: {
        tags: ["devices"],
        summary: "List my registered devices",
        response: {
          200: z.object({
            devices: z.array(z.object({ deviceId: z.string(), attestationLevel: z.string(), trustCap: z.string(), note: z.string(), createdAt: z.number() })),
          }),
        },
      },
    },
    async (req) => {
      const rows = s.db.prepare("SELECT id, attestation_level, trust_cap, attestation_note, created_at FROM devices WHERE wallet = ? ORDER BY created_at DESC").all(req.wallet!) as {
        id: string;
        attestation_level: string;
        trust_cap: string;
        attestation_note: string;
        created_at: number;
      }[];
      return {
        devices: rows.map((d) => ({ deviceId: d.id, attestationLevel: d.attestation_level, trustCap: d.trust_cap, note: d.attestation_note, createdAt: d.created_at })),
      };
    },
  );
}
