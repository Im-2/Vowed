/**
 * Open proof plug-ins (SPEC F9 / 9.7). Another app (a "provider") can sign a short statement about a person ("12 minutes of reading in this
 * window") and the person hands it to Vowed. This file is the format and the verification; the format is written up for third parties in
 * docs/proof-provider-spec.md. Only the sample provider shipped inside Vowed exists today: no third-party integration is claimed.
 *
 * What an accepted attestation means today: it is genuine (signed by a key that the provider or the person registered), fresh, for that
 * wallet, and never seen before. It is recorded with a LOW trust tier. It does not yet satisfy a day's check-in, and providers are not
 * trusted for stakes until they are added to an allow-list by the operator.
 */
import { createHash, createPublicKey, verify as cryptoVerify } from "node:crypto";
import nacl from "tweetnacl";
import { z } from "zod";

export const ATTESTATION_PREFIX = "VOWED-ATTESTATION-v1\n";
export const MAX_AGE_SECS = 600;
export const MAX_WINDOW_SECS = 86_400;
export const MAX_FUTURE_SECS = 60;

export const attestationSchema = z.object({
  v: z.literal(1),
  provider: z.string().regex(/^[a-z0-9][a-z0-9._-]{2,63}$/),
  keyId: z.string().regex(/^[A-Za-z0-9._-]{1,64}$/),
  wallet: z.string().min(32).max(44),
  metric: z.string().regex(/^[a-z][a-z0-9_]{1,47}$/),
  value: z.number().finite().min(0).max(1e9),
  unit: z.string().min(1).max(16),
  windowStart: z.number().int().min(0),
  windowEnd: z.number().int().min(0),
  nonce: z.string().min(16).max(64),
  issuedAt: z.number().int().min(0),
  alg: z.enum(["ES256", "EdDSA"]),
  signature: z.string().min(40).max(200),
});
export type Attestation = z.infer<typeof attestationSchema>;

/** Canonical JSON of everything except the signature: keys sorted, no spaces. Both sides must build exactly these bytes. */
export function canonicalAttestationBytes(a: Omit<Attestation, "signature">): Buffer {
  const body: Record<string, unknown> = {};
  for (const k of Object.keys(a).sort()) body[k] = (a as Record<string, unknown>)[k];
  return Buffer.from(ATTESTATION_PREFIX + JSON.stringify(body), "utf8");
}

export function publicKeyFingerprint(publicKeyB64: string): string {
  return createHash("sha256").update(Buffer.from(publicKeyB64, "base64")).digest("hex").slice(0, 16);
}

/** Checks the signature only. [publicKeyB64] is SPKI DER for ES256 and the raw 32 bytes for EdDSA. */
export function verifyAttestationSignature(a: Attestation, publicKeyB64: string): boolean {
  const { signature, ...rest } = a;
  const bytes = canonicalAttestationBytes(rest);
  const sig = Buffer.from(signature, "base64");
  const key = Buffer.from(publicKeyB64, "base64");
  try {
    if (a.alg === "EdDSA") return key.length === 32 && sig.length === 64 && nacl.sign.detached.verify(bytes, sig, key);
    const pub = createPublicKey({ key, format: "der", type: "spki" });
    if (pub.asymmetricKeyType !== "ec" || pub.asymmetricKeyDetails?.namedCurve !== "prime256v1") return false;
    return cryptoVerify("sha256", bytes, { key: pub, dsaEncoding: "der" }, sig);
  } catch {
    return false;
  }
}

/** The ways an attestation can be refused, as codes the provider can act on. */
export type AttestationRefusal = "bad_window" | "stale" | "from_the_future" | "wrong_wallet";

export function checkFreshness(a: Attestation, now: number, wallet: string): AttestationRefusal | null {
  if (a.wallet !== wallet) return "wrong_wallet";
  if (a.windowEnd < a.windowStart || a.windowEnd - a.windowStart > MAX_WINDOW_SECS || a.windowEnd > a.issuedAt + MAX_FUTURE_SECS) return "bad_window";
  if (a.issuedAt > now + MAX_FUTURE_SECS) return "from_the_future";
  if (now - a.issuedAt > MAX_AGE_SECS) return "stale";
  return null;
}
