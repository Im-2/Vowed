/**
 * Writes shared/test-vectors/attestation.json: one signed attestation made with a throwaway P-256 key (the private key is NOT stored), so the
 * Android sample provider and the backend verifier are pinned to the same canonical bytes.
 *   npx tsx scripts/gen-attestation-vector.ts
 */
import { generateKeyPairSync, sign as cryptoSign } from "node:crypto";
import { writeFileSync } from "node:fs";
import { canonicalAttestationBytes, verifyAttestationSignature, type Attestation } from "../src/proofs/attestation.js";

const kp = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
const spki = kp.publicKey.export({ type: "spki", format: "der" }).toString("base64");
const base: Omit<Attestation, "signature"> = {
  v: 1, provider: "sample.focus", keyId: "k1", wallet: "2YePEWRp8aTfqQnJHK2EBt4YkXRXWetzdmL8dDG7UFZf", metric: "focus_seconds", value: 1500, unit: "seconds",
  windowStart: 1_800_000_000, windowEnd: 1_800_001_600, nonce: "AAECAwQFBgcICQoLDA0ODw==", issuedAt: 1_800_001_605, alg: "ES256",
};
const bytes = canonicalAttestationBytes(base);
const signature = cryptoSign("sha256", bytes, { key: kp.privateKey, dsaEncoding: "der" }).toString("base64");
const a: Attestation = { ...base, signature };
if (!verifyAttestationSignature(a, spki)) throw new Error("self-check failed");
const out = new URL("../../shared/test-vectors/attestation.json", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
writeFileSync(out, JSON.stringify({ comment: "ES256 attestation signed with a throwaway key; the private key was discarded", publicKeySpkiBase64: spki, canonicalUtf8: bytes.toString("utf8"), attestation: a }, null, 2) + "\n");
console.log("wrote", out);
