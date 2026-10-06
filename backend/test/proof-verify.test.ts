import { describe, expect, it } from "vitest";
import { canonicalJson } from "../src/domain/plan.js";
import { canonicalPackageBytes, evaluateProof, LIMITS, ProofPackageSchema, toSeconds, verifyDeviceSignature, type ProofPackage } from "../src/proofs/verify.js";
import { makeDevice, samplePlan } from "./helpers/world.js";

const basePkg = (over: Partial<ProofPackage> = {}): Omit<ProofPackage, "signature"> => ({
  sessionId: "s".repeat(32),
  nonce: "n".repeat(32),
  challengeId: "P".repeat(44),
  dayIndex: 0,
  proofType: "CAMERA_POSE",
  metrics: { reps: 25, livenessPassed: true },
  startedAt: 1_800_000_000,
  endedAt: 1_800_000_060,
  evidenceHash: "a".repeat(64),
  deviceKeyId: "b".repeat(64),
  ...over,
});

describe("device signatures", () => {
  it("verifies a Keystore-style DER signature and a raw r||s signature", () => {
    const dev = makeDevice();
    const signed = dev.signPackage(basePkg());
    expect(verifyDeviceSignature(dev.spki, canonicalPackageBytes(signed), signed.signature)).toBe(true);
  });

  it("rejects any change to the signed fields, a different key, and garbage", () => {
    const dev = makeDevice();
    const signed = dev.signPackage(basePkg());
    for (const change of [{ metrics: { reps: 26, livenessPassed: true } }, { dayIndex: 1 }, { nonce: "m".repeat(32) }, { endedAt: 1_800_000_061 }, { evidenceHash: "c".repeat(64) }]) {
      const tampered = { ...signed, ...change };
      expect(verifyDeviceSignature(dev.spki, canonicalPackageBytes(tampered), tampered.signature)).toBe(false);
    }
    expect(verifyDeviceSignature(makeDevice().spki, canonicalPackageBytes(signed), signed.signature)).toBe(false);
    expect(verifyDeviceSignature(dev.spki, canonicalPackageBytes(signed), Buffer.alloc(70, 1).toString("base64"))).toBe(false);
    expect(verifyDeviceSignature(Buffer.from("junk"), canonicalPackageBytes(signed), signed.signature)).toBe(false);
  });

  it("signs canonical JSON, so key order never matters", () => {
    const a = canonicalPackageBytes({ ...basePkg(), metrics: { reps: 25, livenessPassed: true } });
    const b = canonicalPackageBytes({ ...basePkg(), metrics: { livenessPassed: true, reps: 25 } });
    expect(a.equals(b)).toBe(true);
    expect(canonicalJson({ b: 1, a: { d: [3, { y: 1, x: 2 }], c: null } })).toBe('{"a":{"c":null,"d":[3,{"x":2,"y":1}]},"b":1}');
  });

  it("schema rejects unknown fields, bad hashes and oversized metrics", () => {
    const dev = makeDevice();
    const ok = dev.signPackage(basePkg());
    expect(ProofPackageSchema.safeParse(ok).success).toBe(true);
    expect(ProofPackageSchema.safeParse({ ...ok, extra: 1 }).success).toBe(false);
    expect(ProofPackageSchema.safeParse({ ...ok, evidenceHash: "xyz" }).success).toBe(false);
    expect(ProofPackageSchema.safeParse({ ...ok, dayIndex: 60 }).success).toBe(false);
    expect(ProofPackageSchema.safeParse({ ...ok, metrics: { blob: "x".repeat(3000) } }).success).toBe(false);
  });
});

describe("metric evaluation", () => {
  const pose = samplePlan(); // 20 reps, camera pose
  const ev = (plan: ReturnType<typeof samplePlan>, type: Parameters<typeof evaluateProof>[1], metrics: Record<string, unknown>, secs = 60) =>
    evaluateProof(plan, type, metrics, 1_000, 1_000 + secs);

  it("camera pose: needs enough reps, liveness and a plausible rate", () => {
    expect(ev(pose, "CAMERA_POSE", { reps: 25, livenessPassed: true })).toMatchObject({ ok: true });
    expect(ev(pose, "CAMERA_POSE", { reps: 19, livenessPassed: true })).toMatchObject({ ok: false, reason: "not enough reps" });
    expect(ev(pose, "CAMERA_POSE", { reps: 25 })).toMatchObject({ ok: false, reason: "liveness check not passed" });
    expect(ev(pose, "CAMERA_POSE", { reps: 25, livenessPassed: true }, 3)).toMatchObject({ ok: false });
    // 200 reps in 30 seconds is not human
    expect(ev(pose, "CAMERA_POSE", { reps: 200, livenessPassed: true }, 30)).toMatchObject({ ok: false, reason: "rep rate is not humanly plausible" });
    expect(ev(pose, "CAMERA_POSE", { reps: 25.5, livenessPassed: true })).toMatchObject({ ok: false });
    expect(ev(pose, "CAMERA_POSE", { reps: "25", livenessPassed: true })).toMatchObject({ ok: false });
    expect(ev(pose, "CAMERA_POSE", { reps: -5, livenessPassed: true })).toMatchObject({ ok: false });
    expect(ev(pose, "CAMERA_POSE", { reps: Number.NaN, livenessPassed: true })).toMatchObject({ ok: false });
  });

  it("rejects a proof type that is not in the plan, whatever the numbers say", () => {
    expect(ev(pose, "SELF_ATTEST", { done: true })).toMatchObject({ ok: false, reason: "proof type is not part of this goal's plan" });
    expect(ev(pose, "STEPS", { steps: 50_000 })).toMatchObject({ ok: false });
  });

  it("steps: at least the target, and not absurd", () => {
    const plan = samplePlan({ proofMethods: [{ type: "STEPS", params: {}, trustTier: "medium" }], target: { metric: "steps", value: 8000, unit: "steps", direction: "atLeast" } });
    expect(ev(plan, "STEPS", { steps: 8000 })).toMatchObject({ ok: true });
    expect(ev(plan, "STEPS", { steps: 7999 })).toMatchObject({ ok: false });
    expect(ev(plan, "STEPS", { steps: LIMITS.maxStepsPerDay + 1 })).toMatchObject({ ok: false, reason: "step count is not plausible" });
  });

  it("focus timer: converts units and cannot exceed the session length", () => {
    const plan = samplePlan({ proofMethods: [{ type: "FOCUS_TIMER", params: {}, trustTier: "medium" }], target: { metric: "focus", value: 2, unit: "hours", direction: "atLeast" } });
    expect(ev(plan, "FOCUS_TIMER", { focusedSeconds: 7200 }, 7300)).toMatchObject({ ok: true });
    expect(ev(plan, "FOCUS_TIMER", { focusedSeconds: 7199 }, 7300)).toMatchObject({ ok: false });
    expect(ev(plan, "FOCUS_TIMER", { focusedSeconds: 7200 }, 600)).toMatchObject({ ok: false, reason: "focus time exceeds the session length" });
    const bad = samplePlan({ proofMethods: [{ type: "FOCUS_TIMER", params: {}, trustTier: "medium" }], target: { metric: "focus", value: 2, unit: "fortnights", direction: "atLeast" } });
    expect(ev(bad, "FOCUS_TIMER", { focusedSeconds: 7200 }, 7300)).toMatchObject({ ok: false });
  });

  it("usage limit and no-use window: at most, apps must have been checked", () => {
    const limit = samplePlan({ category: "detox", proofMethods: [{ type: "USAGE_LIMIT", params: {}, trustTier: "high" }], target: { metric: "social", value: 30, unit: "minutes", direction: "atMost" } });
    expect(ev(limit, "USAGE_LIMIT", { usageSeconds: 1800, packagesChecked: ["com.example"] })).toMatchObject({ ok: true });
    expect(ev(limit, "USAGE_LIMIT", { usageSeconds: 1801, packagesChecked: ["com.example"] })).toMatchObject({ ok: false });
    expect(ev(limit, "USAGE_LIMIT", { usageSeconds: 0, packagesChecked: [] })).toMatchObject({ ok: false, reason: "no apps were checked" });
    const noUse = samplePlan({ category: "detox", proofMethods: [{ type: "NO_USE_WINDOW", params: {}, trustTier: "high" }], target: { metric: "tiktok", value: 0, unit: "minutes", direction: "atMost" } });
    expect(ev(noUse, "NO_USE_WINDOW", { usageSecondsInWindow: 0, packagesChecked: ["com.zhiliaoapp.musically"] })).toMatchObject({ ok: true });
    expect(ev(noUse, "NO_USE_WINDOW", { usageSecondsInWindow: 5, packagesChecked: ["com.zhiliaoapp.musically"] })).toMatchObject({ ok: false });
  });

  it("geofence needs inside=true and enough dwell time; self-attest needs done=true", () => {
    const geo = samplePlan({ category: "location", proofMethods: [{ type: "GEOFENCE", params: {}, trustTier: "medium" }], target: { metric: "gym", value: 20, unit: "minutes", direction: "atLeast" } });
    expect(ev(geo, "GEOFENCE", { inside: true, dwellSeconds: 1200 }, 1300)).toMatchObject({ ok: true });
    expect(ev(geo, "GEOFENCE", { inside: false, dwellSeconds: 1200 }, 1300)).toMatchObject({ ok: false });
    expect(ev(geo, "GEOFENCE", { inside: true, dwellSeconds: 1199 }, 1300)).toMatchObject({ ok: false });
    expect(ev(geo, "GEOFENCE", { inside: true, dwellSeconds: 1200 }, 100)).toMatchObject({ ok: false });
    const self = samplePlan({ proofMethods: [{ type: "SELF_ATTEST", params: {}, trustTier: "low" }] });
    expect(ev(self, "SELF_ATTEST", { done: true })).toMatchObject({ ok: true });
    expect(ev(self, "SELF_ATTEST", { done: "yes" })).toMatchObject({ ok: false });
  });

  it("unit conversion", () => {
    expect(toSeconds(2, "hours")).toBe(7200);
    expect(toSeconds(30, "Minutes")).toBe(1800);
    expect(toSeconds(5, "reps")).toBeNull();
  });
});
