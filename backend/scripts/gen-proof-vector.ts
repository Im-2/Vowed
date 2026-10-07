// Writes shared/test-vectors/proof-package.json: unsigned proof packages and the exact bytes the backend verifies signatures over.
import { writeFileSync } from "node:fs";
import { canonicalPackageBytes } from "../src/proofs/verify.js";

const base = {
  sessionId: "0123456789abcdef0123456789abcdef", nonce: "fedcba9876543210fedcba9876543210",
  challengeId: "48rCRD6sE199jJ51WGyRfsiBZ6pq8MXpx6sKXCPtcmJp", dayIndex: 1, startedAt: 1791369700, endedAt: 1791369725,
  evidenceHash: "00".repeat(32), deviceKeyId: "ab".repeat(32),
};
const cases = [
  { ...base, proofType: "FOCUS_TIMER", metrics: { focusedSeconds: 23 } },
  { ...base, proofType: "STEPS", metrics: { steps: 8123 } },
  { ...base, proofType: "GEOFENCE", metrics: { inside: true, dwellSeconds: 1800 } },
  { ...base, proofType: "USAGE_LIMIT", metrics: { usageSeconds: 420, packagesChecked: ["com.instagram.android", "com.zhiliaoapp.musically"] } },
  { ...base, proofType: "SELF_ATTEST", metrics: { done: true, note: "quote \" slash \ tab \t unicode é" } },
];
const out = cases.map((pkg) => ({ package: pkg, canonical: canonicalPackageBytes(pkg as never).toString("utf8") }));
writeFileSync(new URL("../../shared/test-vectors/proof-package.json", import.meta.url), JSON.stringify(out, null, 1) + "\n");
console.log(`wrote ${out.length} vectors`);
