/**
 * Android Key Attestation verification (SPEC 7.3).
 * Follows https://developer.android.com/privacy-and-security/security-key-attestation :
 *  - the chain must be a valid signature chain ending at a Google attestation root (trusted by public key),
 *  - only the leaf may carry the attestation extension (a later occurrence could be forged by an attacker-controlled CA key),
 *  - the extension's challenge must be the one we issued, and the attested key must be the device key we are registering,
 *  - security level, verified-boot state and revocation status decide the trust cap.
 * Verification happens on the server only; never trust a device to verify itself.
 */
import { createHash, timingSafeEqual, X509Certificate } from "node:crypto";
import * as asn1js from "asn1js";

export type AttestationLevel = "strongbox" | "tee" | "software" | "none";
export type VerifiedBoot = "verified" | "self_signed" | "unverified" | "failed" | "unknown";

export interface AttestationInput {
  /** DER certificates, leaf first. Empty or missing means the device offered no attestation. */
  chainDer: Uint8Array[];
  expectedChallenge: Uint8Array;
  /** SPKI DER of the key the device wants to register. */
  devicePublicKeySpki: Uint8Array;
}

export interface AttestationResult {
  ok: boolean;
  level: AttestationLevel;
  verifiedBoot: VerifiedBoot;
  deviceLocked?: boolean;
  /** Short human-readable reason when ok is false, or a note when it is ok. Never contains certificate contents. */
  reason: string;
}

export interface AttestationVerifier {
  verify(input: AttestationInput): Promise<AttestationResult>;
}

export const ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17";
const VERIFIED_BOOT: VerifiedBoot[] = ["verified", "self_signed", "unverified", "failed"];

export interface KeyDescription {
  attestationSecurityLevel: number;
  keyMintSecurityLevel: number;
  attestationChallenge: Uint8Array;
  verifiedBootState?: VerifiedBoot;
  deviceLocked?: boolean;
}

const spkiHash = (c: X509Certificate) => createHash("sha256").update(c.publicKey.export({ type: "spki", format: "der" })).digest("hex");

function children(node: asn1js.BaseBlock): asn1js.BaseBlock[] {
  return ((node as unknown as { valueBlock: { value: asn1js.BaseBlock[] } }).valueBlock.value ?? []) as asn1js.BaseBlock[];
}
function asInt(node: asn1js.BaseBlock | undefined): number {
  if (!node) throw new Error("missing integer");
  return (node as unknown as { valueBlock: { valueDec: number } }).valueBlock.valueDec;
}
function asBytes(node: asn1js.BaseBlock | undefined): Uint8Array {
  if (!node) throw new Error("missing octet string");
  return new Uint8Array((node as unknown as { valueBlock: { valueHexView: Uint8Array } }).valueBlock.valueHexView);
}

/** Parses the KeyDescription ASN.1 structure from the extension's DER bytes. */
export function parseKeyDescription(extensionDer: Uint8Array): KeyDescription {
  const parsed = asn1js.fromBER(extensionDer.buffer.slice(extensionDer.byteOffset, extensionDer.byteOffset + extensionDer.byteLength) as ArrayBuffer);
  if (parsed.offset === -1) throw new Error("attestation extension is not valid DER");
  const seq = children(parsed.result);
  // [0] attestationVersion, [1] attestationSecurityLevel, [2] keyMintVersion, [3] keyMintSecurityLevel,
  // [4] attestationChallenge, [5] uniqueId, [6] softwareEnforced, [7] hardwareEnforced
  if (seq.length < 8) throw new Error("attestation extension too short");
  const out: KeyDescription = {
    attestationSecurityLevel: asInt(seq[1]),
    keyMintSecurityLevel: asInt(seq[3]),
    attestationChallenge: asBytes(seq[4]),
  };
  // RootOfTrust is tag [704] in the TEE/StrongBox-enforced list (and, on very old devices, the software list).
  for (const list of [seq[7], seq[6]]) {
    for (const item of children(list!)) {
      const id = (item as unknown as { idBlock: { tagClass: number; tagNumber: number } }).idBlock;
      if (id.tagClass === 3 && id.tagNumber === 704) {
        const rot = children(children(item)[0]!); // EXPLICIT wrapper -> SEQUENCE
        const state = (rot[2] as unknown as { valueBlock: { valueDec: number } }).valueBlock.valueDec;
        out.verifiedBootState = VERIFIED_BOOT[state] ?? "unknown";
        out.deviceLocked = (rot[1] as unknown as { valueBlock: { value: boolean } }).valueBlock.value === true;
      }
    }
    if (out.verifiedBootState) break;
  }
  return out;
}

/** Extension value (DER of the KeyDescription) for the attestation OID, or null. Reads the certificate's ASN.1 directly. */
export function attestationExtension(certDer: Uint8Array): Uint8Array | null {
  const parsed = asn1js.fromBER(certDer.buffer.slice(certDer.byteOffset, certDer.byteOffset + certDer.byteLength) as ArrayBuffer);
  if (parsed.offset === -1) return null;
  const tbs = children(children(parsed.result)[0]!);
  // extensions are the context-specific [3] element of TBSCertificate
  const extWrapper = tbs.find((n) => {
    const id = (n as unknown as { idBlock: { tagClass: number; tagNumber: number } }).idBlock;
    return id.tagClass === 3 && id.tagNumber === 3;
  });
  if (!extWrapper) return null;
  for (const ext of children(children(extWrapper)[0]!)) {
    const parts = children(ext); // SEQUENCE { OID, [BOOLEAN critical], OCTET STRING }
    const oid = (parts[0] as unknown as { valueBlock: { toString(): string } }).valueBlock.toString();
    if (oid === ATTESTATION_OID) return asBytes(parts[parts.length - 1]);
  }
  return null;
}

export type RevocationChecker = (serialHexes: string[]) => Promise<{ revoked: boolean; checked: boolean }>;

const NO_REVOCATION: RevocationChecker = async () => ({ revoked: false, checked: false });

/** Checks Google's published revocation list, cached for an hour. A fetch failure is reported as "not checked", not as revoked. */
export function googleRevocationChecker(fetchImpl: typeof fetch = fetch, ttlMs = 3_600_000): RevocationChecker {
  let cache: { at: number; entries: Record<string, unknown> } | null = null;
  return async (serials) => {
    try {
      if (!cache || Date.now() - cache.at > ttlMs) {
        const res = await fetchImpl("https://android.googleapis.com/attestation/status", { signal: AbortSignal.timeout(3_000) });
        if (!res.ok) throw new Error(`status ${res.status}`);
        const body = (await res.json()) as { entries?: Record<string, unknown> };
        cache = { at: Date.now(), entries: body.entries ?? {} };
      }
      return { revoked: serials.some((s) => s in cache!.entries), checked: true };
    } catch {
      return { revoked: false, checked: false };
    }
  };
}

const normalizeSerial = (hex: string) => hex.toLowerCase().replace(/^0+/, "") || "0";

export class GoogleAttestationVerifier implements AttestationVerifier {
  private readonly trustedSpki: Set<string>;
  constructor(
    rootsPem: string[],
    private readonly revocation: RevocationChecker = NO_REVOCATION,
  ) {
    this.trustedSpki = new Set(rootsPem.map((p) => spkiHash(new X509Certificate(p))));
  }

  async verify(input: AttestationInput): Promise<AttestationResult> {
    const fail = (reason: string, level: AttestationLevel = "none"): AttestationResult => ({ ok: false, level, verifiedBoot: "unknown", reason });
    const { chainDer } = input;
    if (chainDer.length === 0) return fail("no attestation chain provided");
    if (chainDer.length < 2 || chainDer.length > 6) return fail("attestation chain has an unexpected length");

    let certs: X509Certificate[];
    try {
      certs = chainDer.map((d) => new X509Certificate(Buffer.from(d)));
    } catch {
      return fail("attestation certificate could not be parsed");
    }

    // 1. each certificate is issued by, and signed with the key of, the next one; the last is a trusted self-signed root
    for (let i = 0; i < certs.length - 1; i++) {
      if (!certs[i]!.checkIssued(certs[i + 1]!) || !certs[i]!.verify(certs[i + 1]!.publicKey)) return fail("attestation chain signature is invalid");
    }
    const root = certs[certs.length - 1]!;
    if (!root.verify(root.publicKey)) return fail("attestation root is not self-signed");
    if (!this.trustedSpki.has(spkiHash(root))) return fail("attestation root is not a known Google root");

    // 2. only the leaf carries the attestation extension
    for (let i = 1; i < chainDer.length; i++) {
      if (attestationExtension(chainDer[i]!)) return fail("attestation extension appears in more than one certificate");
    }
    const extDer = attestationExtension(chainDer[0]!);
    if (!extDer) return fail("leaf certificate has no attestation extension");
    let kd: KeyDescription;
    try {
      kd = parseKeyDescription(extDer);
    } catch {
      return fail("attestation extension could not be parsed");
    }

    // 3. bound to our challenge and to the key being registered
    const a = Buffer.from(kd.attestationChallenge);
    const b = Buffer.from(input.expectedChallenge);
    if (a.length !== b.length || !timingSafeEqual(a, b)) return fail("attestation challenge does not match");
    const leafSpki = certs[0]!.publicKey.export({ type: "spki", format: "der" });
    const want = Buffer.from(input.devicePublicKeySpki);
    if (leafSpki.length !== want.length || !timingSafeEqual(leafSpki, want)) return fail("attested key is not the device key");

    // 4. revocation
    const rev = await this.revocation(certs.map((c) => normalizeSerial(c.serialNumber)));
    if (rev.revoked) return fail("an attestation certificate has been revoked");

    const hardware = kd.attestationSecurityLevel >= 1 && kd.keyMintSecurityLevel >= 1;
    const level: AttestationLevel = !hardware ? "software" : kd.attestationSecurityLevel === 2 && kd.keyMintSecurityLevel === 2 ? "strongbox" : "tee";
    return {
      ok: true,
      level,
      verifiedBoot: kd.verifiedBootState ?? "unknown",
      deviceLocked: kd.deviceLocked,
      reason: rev.checked ? "verified" : "verified (revocation list not reachable, not checked)",
    };
  }
}

/** Used when a device offers no attestation at all and the deployment allows that (development, emulator). */
export const NO_ATTESTATION: AttestationResult = { ok: false, level: "none", verifiedBoot: "unknown", reason: "device offered no attestation" };
