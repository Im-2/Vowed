import { createHash } from "node:crypto";
import { z } from "zod";

export const PROOF_TYPES = ["CAMERA_POSE", "USAGE_LIMIT", "NO_USE_WINDOW", "STEPS", "GEOFENCE", "FOCUS_TIMER", "SELF_ATTEST"] as const;
export type ProofType = (typeof PROOF_TYPES)[number];
export type TrustTier = "high" | "medium" | "low";

/** Intrinsic trust of each proof type (SPEC 7.1). The plan's own claim is never trusted. */
export const PROOF_TRUST: Record<ProofType, TrustTier> = {
  CAMERA_POSE: "high",
  USAGE_LIMIT: "high",
  NO_USE_WINDOW: "high",
  STEPS: "medium",
  GEOFENCE: "medium",
  FOCUS_TIMER: "medium",
  SELF_ATTEST: "low",
};

const TIER_RANK: Record<TrustTier, number> = { low: 0, medium: 1, high: 2 };
export function weakestTier(tiers: TrustTier[]): TrustTier {
  return tiers.reduce((a, b) => (TIER_RANK[b] < TIER_RANK[a] ? b : a), "high" as TrustTier);
}
export function minTier(a: TrustTier, b: TrustTier): TrustTier {
  return TIER_RANK[a] <= TIER_RANK[b] ? a : b;
}

/** Share of config.max_stake allowed per trust tier (SPEC 7.1: stake caps scale with the weakest tier). Enforced by the backend. */
export const STAKE_CAP_PERCENT: Record<TrustTier, number> = { high: 100, medium: 50, low: 10 };

const time = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/);

export const GoalPlanSchema = z
  .object({
    title: z.string().min(1).max(120),
    category: z.enum(["fitness", "study", "detox", "sleep", "steps", "location", "custom"]),
    cadence: z.object({
      periodDays: z.literal(1),
      totalDays: z.number().int().min(1).max(60),
      requiredDays: z.number().int().min(1).max(60),
    }),
    target: z.object({
      metric: z.string().max(60),
      value: z.number().finite(),
      unit: z.string().max(30),
      direction: z.enum(["atLeast", "atMost"]),
    }),
    proofMethods: z
      .array(
        z.object({
          type: z.enum(PROOF_TYPES),
          params: z.record(z.string(), z.unknown()),
          trustTier: z.enum(["high", "medium", "low"]),
        }),
      )
      .min(1)
      .max(4),
    window: z.object({ startLocalTime: time, endLocalTime: time }).nullable(),
    difficulty: z.number().int().min(1).max(5),
    verifiable: z.boolean(),
    unverifiableReason: z.string().nullable(),
    suggestedAlternative: z.string().nullable(),
    clarifyingQuestions: z.array(z.string()).max(3),
  })
  .strict()
  .refine((p) => p.cadence.requiredDays <= p.cadence.totalDays, { message: "requiredDays must be <= totalDays", path: ["cadence", "requiredDays"] });

export type GoalPlan = z.infer<typeof GoalPlanSchema>;

/** Deterministic JSON: object keys sorted recursively, no whitespace. */
export function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  const obj = value as Record<string, unknown>;
  return `{${Object.keys(obj)
    .filter((k) => obj[k] !== undefined)
    .sort()
    .map((k) => `${JSON.stringify(k)}:${canonicalJson(obj[k])}`)
    .join(",")}}`;
}

export function sha256Hex(data: string | Uint8Array): string {
  return createHash("sha256").update(data).digest("hex");
}

export function planHash(plan: GoalPlan): string {
  return sha256Hex(canonicalJson(plan));
}

/** The weakest intrinsic tier among the plan's proof methods. */
export function planTrustTier(plan: GoalPlan): TrustTier {
  return weakestTier(plan.proofMethods.map((m) => PROOF_TRUST[m.type]));
}

/** Largest stake (base units) allowed for a plan, given the program's global cap. */
export function maxStakeForPlan(plan: GoalPlan, globalMaxStake: bigint): bigint {
  return (globalMaxStake * BigInt(STAKE_CAP_PERCENT[planTrustTier(plan)])) / 100n;
}
