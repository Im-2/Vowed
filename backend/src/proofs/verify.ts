import { createPublicKey, verify as cryptoVerify } from "node:crypto";
import { z } from "zod";
import { canonicalJson, PROOF_TYPES, type GoalPlan, type ProofType } from "../domain/plan.js";

export const ProofPackageSchema = z
  .object({
    sessionId: z.string().min(8).max(64),
    nonce: z.string().min(8).max(128),
    /** The pool address. */
    challengeId: z.string().min(32).max(44),
    dayIndex: z.number().int().min(0).max(59),
    proofType: z.enum(PROOF_TYPES),
    metrics: z.record(z.string(), z.unknown()),
    /** unix seconds */
    startedAt: z.number().int().positive(),
    endedAt: z.number().int().positive(),
    /** sha256 hex of the on-device evidence summary. The evidence itself never leaves the phone. */
    evidenceHash: z.string().regex(/^[0-9a-f]{64}$/),
    /** sha256 hex of the device public key (SPKI DER) */
    deviceKeyId: z.string().regex(/^[0-9a-f]{64}$/),
    /** base64 ECDSA P-256 / SHA-256 signature (DER or raw r||s) over canonicalPackageBytes */
    signature: z.string().min(80).max(200),
  })
  .strict()
  .refine((p) => JSON.stringify(p.metrics).length <= 2048, { message: "metrics too large", path: ["metrics"] });

export type ProofPackage = z.infer<typeof ProofPackageSchema>;

/** The exact bytes the device signs: canonical JSON of the package without the signature field. */
export function canonicalPackageBytes(pkg: Omit<ProofPackage, "signature"> | ProofPackage): Buffer {
  const { signature: _drop, ...rest } = pkg as ProofPackage;
  void _drop;
  return Buffer.from(canonicalJson(rest), "utf8");
}

/** Keystore's SHA256withECDSA yields DER; raw 64-byte r||s is accepted as well. */
export function verifyDeviceSignature(spkiDer: Uint8Array, data: Uint8Array, signatureB64: string): boolean {
  let key;
  try {
    key = createPublicKey({ key: Buffer.from(spkiDer), format: "der", type: "spki" });
  } catch {
    return false;
  }
  const sig = Buffer.from(signatureB64, "base64");
  try {
    if (cryptoVerify("sha256", data, { key, dsaEncoding: "der" }, sig)) return true;
  } catch {
    /* not DER */
  }
  if (sig.length === 64) {
    try {
      return cryptoVerify("sha256", data, { key, dsaEncoding: "ieee-p1363" }, sig);
    } catch {
      return false;
    }
  }
  return false;
}

const UNIT_SECONDS: Record<string, number> = { s: 1, sec: 1, second: 1, seconds: 1, min: 60, minute: 60, minutes: 60, h: 3600, hr: 3600, hour: 3600, hours: 3600 };

export function toSeconds(value: number, unit: string): number | null {
  const f = UNIT_SECONDS[unit.toLowerCase()];
  return f === undefined ? null : value * f;
}

export type MetricResult = { ok: true; summary: Record<string, number | boolean> } | { ok: false; reason: string };

const rejected = (reason: string): MetricResult => ({ ok: false, reason });
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) && v >= 0 ? v : null);

/** Hard plausibility limits. They exist to catch obviously fabricated numbers, not to prove honesty. */
export const LIMITS = { maxRepsPerSecond: 3, minPoseSeconds: 5, maxStepsPerDay: 100_000, maxSecondsPerDay: 86_400 };

/**
 * Does `metrics` satisfy the plan for this proof type? Pure function, no I/O.
 * The plan's own trust tier is ignored; trust comes from the proof type and the device.
 */
export function evaluateProof(plan: GoalPlan, type: ProofType, metrics: Record<string, unknown>, startedAt: number, endedAt: number): MetricResult {
  if (!plan.proofMethods.some((m) => m.type === type)) return rejected("proof type is not part of this goal's plan");
  const elapsed = endedAt - startedAt;
  if (elapsed < 0) return rejected("proof ends before it starts");
  const t = plan.target;

  switch (type) {
    case "CAMERA_POSE": {
      const reps = num(metrics.reps);
      if (reps === null || !Number.isInteger(reps)) return rejected("reps missing or invalid");
      if (metrics.livenessPassed !== true) return rejected("liveness check not passed");
      if (elapsed < LIMITS.minPoseSeconds) return rejected("session too short for the number of reps");
      if (reps > Math.ceil(elapsed * LIMITS.maxRepsPerSecond)) return rejected("rep rate is not humanly plausible");
      if (t.direction !== "atLeast" || reps < t.value) return rejected("not enough reps");
      return { ok: true, summary: { reps } };
    }
    case "STEPS": {
      const steps = num(metrics.steps);
      if (steps === null || !Number.isInteger(steps)) return rejected("steps missing or invalid");
      if (steps > LIMITS.maxStepsPerDay) return rejected("step count is not plausible");
      if (t.direction !== "atLeast" || steps < t.value) return rejected("not enough steps");
      return { ok: true, summary: { steps } };
    }
    case "FOCUS_TIMER": {
      const focused = num(metrics.focusedSeconds);
      const need = toSeconds(t.value, t.unit);
      if (focused === null || need === null) return rejected("focus time missing or unit unknown");
      if (focused > elapsed + 5 || focused > LIMITS.maxSecondsPerDay) return rejected("focus time exceeds the session length");
      if (t.direction !== "atLeast" || focused < need) return rejected("not enough focused time");
      return { ok: true, summary: { focusedSeconds: focused } };
    }
    case "USAGE_LIMIT": {
      const used = num(metrics.usageSeconds);
      const limit = toSeconds(t.value, t.unit);
      if (used === null || limit === null) return rejected("usage missing or unit unknown");
      if (!Array.isArray(metrics.packagesChecked) || metrics.packagesChecked.length === 0) return rejected("no apps were checked");
      if (t.direction !== "atMost" || used > limit) return rejected("usage over the limit");
      return { ok: true, summary: { usageSeconds: used } };
    }
    case "NO_USE_WINDOW": {
      const used = num(metrics.usageSecondsInWindow);
      const limit = toSeconds(t.value, t.unit);
      if (used === null || limit === null) return rejected("usage missing or unit unknown");
      if (!Array.isArray(metrics.packagesChecked) || metrics.packagesChecked.length === 0) return rejected("no apps were checked");
      if (t.direction !== "atMost" || used > limit) return rejected("used the app during the blocked window");
      return { ok: true, summary: { usageSecondsInWindow: used } };
    }
    case "GEOFENCE": {
      const dwell = num(metrics.dwellSeconds);
      const need = toSeconds(t.value, t.unit);
      if (metrics.inside !== true) return rejected("not inside the place");
      if (dwell === null || need === null) return rejected("dwell time missing or unit unknown");
      if (dwell > elapsed + 5) return rejected("dwell time exceeds the session length");
      if (t.direction !== "atLeast" || dwell < need) return rejected("did not stay long enough");
      return { ok: true, summary: { dwellSeconds: dwell } };
    }
    case "SELF_ATTEST": {
      if (metrics.done !== true) return rejected("not marked as done");
      return { ok: true, summary: { done: true } };
    }
  }
}
