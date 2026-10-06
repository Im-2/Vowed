import { X509Certificate } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  attestationExtension,
  GoogleAttestationVerifier,
  googleRevocationChecker,
  parseKeyDescription,
} from "../src/devices/attestation.js";

const roots: string[] = JSON.parse(readFileSync(new URL("../src/devices/google-roots.json", import.meta.url), "utf8"));
const dir = new URL("./fixtures/attestation/", import.meta.url);

function loadChain(file: string): Uint8Array[] {
  const text = readFileSync(new URL(file, dir), "utf8");
  const pems = text.match(/-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/g)!;
  return pems.map((p) => new Uint8Array(new X509Certificate(p).raw));
}
const spki = (der: Uint8Array) => new Uint8Array(new X509Certificate(Buffer.from(der)).publicKey.export({ type: "spki", format: "der" }));
const challengeOf = (chain: Uint8Array[]) => parseKeyDescription(attestationExtension(chain[0]!)!).attestationChallenge;

const verifier = new GoogleAttestationVerifier(roots);
const files = readdirSync(dir).filter((f) => f.endsWith(".pem"));

describe("key attestation against real device chains (Google's keyattestation test data, Apache-2.0)", () => {
  it("has fixtures", () => expect(files.length).toBeGreaterThanOrEqual(6));

  for (const f of files) {
    it(`accepts ${f} and reports its security level`, async () => {
      const chain = loadChain(f);
      const res = await verifier.verify({ chainDer: chain, expectedChallenge: challengeOf(chain), devicePublicKeySpki: spki(chain[0]!) });
      expect(res.reason).toContain("verified");
      expect(res.ok).toBe(true);
      expect(res.level).toBe(f.includes("_SB_") ? "strongbox" : "tee");
    });
  }

  it("reads the verified boot state from the root of trust", async () => {
    const chain = loadChain("caiman_sdk36_TEE_EC_RKP.pem");
    const kd = parseKeyDescription(attestationExtension(chain[0]!)!);
    expect(kd.verifiedBootState).toBeDefined();
    expect(kd.attestationSecurityLevel).toBe(1);
  });

  it("rejects a different challenge (replayed attestation)", async () => {
    const chain = loadChain("caiman_sdk36_TEE_EC_RKP.pem");
    const res = await verifier.verify({ chainDer: chain, expectedChallenge: new Uint8Array(32).fill(1), devicePublicKeySpki: spki(chain[0]!) });
    expect(res).toMatchObject({ ok: false, reason: "attestation challenge does not match" });
  });

  it("rejects when the attested key is not the key being registered", async () => {
    const chain = loadChain("caiman_sdk36_TEE_EC_RKP.pem");
    const other = spki(loadChain("akita_sdk34_TEE_EC_NONE.pem")[0]!);
    const res = await verifier.verify({ chainDer: chain, expectedChallenge: challengeOf(chain), devicePublicKeySpki: other });
    expect(res).toMatchObject({ ok: false, reason: "attested key is not the device key" });
  });

  it("rejects a root that is not Google's", async () => {
    const chain = loadChain("caiman_sdk36_TEE_EC_RKP.pem");
    const strict = new GoogleAttestationVerifier([roots[1]!]); // only the new EC root; this chain uses the old RSA root
    const res = await strict.verify({ chainDer: chain, expectedChallenge: challengeOf(chain), devicePublicKeySpki: spki(chain[0]!) });
    expect(res).toMatchObject({ ok: false, reason: "attestation root is not a known Google root" });
  });

  it("rejects a chain whose certificates do not belong together", async () => {
    const a = loadChain("caiman_sdk36_TEE_EC_RKP.pem");
    const b = loadChain("akita_sdk34_TEE_EC_NONE.pem");
    const franken = [a[0]!, ...b.slice(1)];
    const res = await verifier.verify({ chainDer: franken, expectedChallenge: challengeOf(a), devicePublicKeySpki: spki(a[0]!) });
    expect(res.ok).toBe(false);
    expect(res.reason).toContain("signature");
  });

  it("rejects a tampered leaf certificate", async () => {
    const chain = loadChain("caiman_sdk36_TEE_EC_RKP.pem");
    const leaf = Uint8Array.from(chain[0]!);
    leaf[leaf.length - 10] ^= 0xff; // flip bits inside the signature
    const res = await verifier.verify({ chainDer: [leaf, ...chain.slice(1)], expectedChallenge: challengeOf(chain), devicePublicKeySpki: spki(chain[0]!) });
    expect(res.ok).toBe(false);
  });

  it("rejects an empty or truncated chain", async () => {
    const chain = loadChain("caiman_sdk36_TEE_EC_RKP.pem");
    const base = { expectedChallenge: challengeOf(chain), devicePublicKeySpki: spki(chain[0]!) };
    expect((await verifier.verify({ ...base, chainDer: [] })).reason).toBe("no attestation chain provided");
    expect((await verifier.verify({ ...base, chainDer: [chain[0]!] })).ok).toBe(false);
    expect((await verifier.verify({ ...base, chainDer: chain.slice(0, 3) })).ok).toBe(false); // no root
  });

  it("rejects when a certificate in the chain is on the revocation list", async () => {
    const chain = loadChain("caiman_sdk36_TEE_EC_RKP.pem");
    const serial = new X509Certificate(Buffer.from(chain[1]!)).serialNumber.toLowerCase().replace(/^0+/, "");
    const fakeFetch = (async () => new Response(JSON.stringify({ entries: { [serial]: { status: "REVOKED" } } }), { status: 200 })) as typeof fetch;
    const v = new GoogleAttestationVerifier(roots, googleRevocationChecker(fakeFetch));
    const res = await v.verify({ chainDer: chain, expectedChallenge: challengeOf(chain), devicePublicKeySpki: spki(chain[0]!) });
    expect(res).toMatchObject({ ok: false, reason: "an attestation certificate has been revoked" });
  });

  it("accepts but flags when the revocation list cannot be fetched", async () => {
    const chain = loadChain("caiman_sdk36_TEE_EC_RKP.pem");
    const failingFetch = (async () => {
      throw new Error("network down");
    }) as typeof fetch;
    const v = new GoogleAttestationVerifier(roots, googleRevocationChecker(failingFetch));
    const res = await v.verify({ chainDer: chain, expectedChallenge: challengeOf(chain), devicePublicKeySpki: spki(chain[0]!) });
    expect(res.ok).toBe(true);
    expect(res.reason).toContain("not checked");
  });
});
