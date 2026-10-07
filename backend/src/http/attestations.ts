import { createPublicKey } from "node:crypto";
import type { FastifyInstance } from "fastify";
import type { ZodTypeProvider } from "fastify-type-provider-zod";
import { z } from "zod";
import { ApiError, badRequest, conflict } from "../errors.js";
import { attestationSchema, checkFreshness, publicKeyFingerprint, verifyAttestationSignature } from "../proofs/attestation.js";
import type { Services } from "../services.js";
import { authenticate } from "./auth.js";
import { enforce } from "./ratelimit.js";

/** The one provider that ships inside the Vowed app, to prove the interface. Its key is created on the phone, never in the repo. */
export const SAMPLE_PROVIDER_ID = "sample.focus";

export function registerAttestationRoutes(app: FastifyInstance, s: Services) {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const auth = authenticate(s);

  r.post(
    "/v1/providers/sample/register",
    {
      preHandler: auth,
      schema: {
        tags: ["plugins"],
        summary: "Devnet demos only: the sample provider inside the app registers the public key it made on the phone, for this wallet",
        body: z.object({ keyId: z.string().regex(/^[A-Za-z0-9._-]{1,64}$/), publicKey: z.string().min(40).max(200), algorithm: z.literal("ES256") }),
        response: { 200: z.object({ provider: z.string(), keyId: z.string(), fingerprint: z.string() }) },
      },
    },
    async (req) => {
      if (!s.config.SAMPLE_PROVIDER_ENABLED) throw new ApiError(404, "sample_provider_disabled", "the sample provider is not enabled on this server");
      enforce(s, `wallet:${req.wallet}:provider-register`, 10, 3_600);
      try {
        const k = createPublicKey({ key: Buffer.from(req.body.publicKey, "base64"), format: "der", type: "spki" });
        if (k.asymmetricKeyType !== "ec" || k.asymmetricKeyDetails?.namedCurve !== "prime256v1") throw new Error("not P-256");
      } catch {
        throw badRequest("bad_public_key", "the key must be a P-256 public key in SPKI DER form (base64)");
      }
      s.db
        .prepare("INSERT INTO provider_keys (provider_id, key_id, public_key, algorithm, wallet, created_at) VALUES (?,?,?,?,?,?) ON CONFLICT(provider_id, key_id) DO UPDATE SET public_key = excluded.public_key WHERE provider_keys.wallet = excluded.wallet")
        .run(SAMPLE_PROVIDER_ID, req.body.keyId, req.body.publicKey, "ES256", req.wallet!, s.wallNow());
      return { provider: SAMPLE_PROVIDER_ID, keyId: req.body.keyId, fingerprint: publicKeyFingerprint(req.body.publicKey) };
    },
  );

  r.post(
    "/v1/attestations",
    {
      preHandler: auth,
      schema: {
        tags: ["plugins"],
        summary: "Hand in a statement signed by a proof provider (see docs/proof-provider-spec.md). Verified, then recorded with a LOW trust tier",
        body: attestationSchema,
        response: { 200: z.object({ accepted: z.literal(true), provider: z.string(), metric: z.string(), value: z.number(), trustTier: z.string(), note: z.string() }) },
      },
    },
    async (req) => {
      const a = req.body;
      enforce(s, `wallet:${req.wallet}:attestation`, 60, 3_600);
      const refusal = checkFreshness(a, s.wallNow(), req.wallet!);
      if (refusal) throw badRequest(`attestation_${refusal}`, `attestation refused: ${refusal.replace(/_/g, " ")}`);
      const key = s.db.prepare("SELECT public_key, algorithm, wallet FROM provider_keys WHERE provider_id = ? AND key_id = ?").get(a.provider, a.keyId) as { public_key: string; algorithm: string; wallet: string | null } | undefined;
      if (!key || key.algorithm !== a.alg || (key.wallet !== null && key.wallet !== req.wallet)) throw badRequest("unknown_provider_key", "this provider key is not registered for you");
      if (!verifyAttestationSignature(a, key.public_key)) throw badRequest("bad_signature", "the signature does not match the statement");
      try {
        s.db.prepare("INSERT INTO attestations (nonce, provider_id, wallet, metric, value, window_start, window_end, accepted_at) VALUES (?,?,?,?,?,?,?,?)").run(a.nonce, a.provider, req.wallet!, a.metric, a.value, a.windowStart, a.windowEnd, s.wallNow());
      } catch {
        throw conflict("attestation_replayed", "this statement was already handed in");
      }
      return {
        accepted: true as const,
        provider: a.provider,
        metric: a.metric,
        value: a.value,
        trustTier: "low",
        note: "Recorded. A provider's statement does not count as a daily check-in yet, and providers are not trusted for stakes until the operator adds them to an allow-list.",
      };
    },
  );
}
