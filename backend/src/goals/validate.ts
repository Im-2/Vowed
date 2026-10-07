/**
 * Semantic validation of a GoalPlan. The structure is checked by GoalPlanSchema (strict: unknown fields are refused); this adds the rules
 * that make a plan safe to stake on: the proof type must fit the target, numbers are inside sensible bounds, only whitelisted
 * parameters survive, and the trust tier is always the intrinsic tier of the proof type (a plan can never claim a higher one).
 * Used for model output (never trusted), for plans edited on the phone, and again when a pool is created.
 */
import { GoalPlanSchema, PROOF_TRUST, type GoalPlan, type ProofType } from "../domain/plan.js";
import { unitSeconds } from "./catalog.js";

export type PlanResult = { ok: true; plan: GoalPlan; notes: string[] } | { ok: false; reason: string };

/** Bounds per proof type. Demo pools may ask for tiny amounts (a "day" is a few minutes); normal pools may not. */
interface Bounds {
  /** seconds for time-based targets, otherwise the plain unit */
  min: number;
  max: number;
}
const NORMAL: Partial<Record<ProofType, Bounds>> = {
  CAMERA_POSE: { min: 5, max: 500 },
  STEPS: { min: 200, max: 60_000 },
  FOCUS_TIMER: { min: 120, max: 43_200 },
  GEOFENCE: { min: 300, max: 86_400 },
  USAGE_LIMIT: { min: 0, max: 86_400 },
  NO_USE_WINDOW: { min: 0, max: 3_600 },
  SELF_ATTEST: { min: 1, max: 100 },
};
const DEMO: Partial<Record<ProofType, Bounds>> = {
  ...NORMAL,
  CAMERA_POSE: { min: 1, max: 500 },
  STEPS: { min: 5, max: 60_000 },
  FOCUS_TIMER: { min: 5, max: 43_200 },
  GEOFENCE: { min: 5, max: 86_400 },
};

const TIME_UNITS = new Set(["seconds", "minutes", "hours"]);
const APP_RE = /^[\p{L}\p{N} ._&'+-]{1,40}$/u;
const PLACE_RE = /^[\p{L}\p{N} ._&'+,-]{1,40}$/u;
/** Invisible and direction-changing characters are used to disguise text; none belongs in a goal. */
// eslint-disable-next-line no-misleading-character-class
const HIDDEN = /[\u0000-\u001f\u007f-\u009f​-‏‪-‮⁠-⁩﻿]/g;

export function cleanText(s: string, max: number): string {
  return s.replace(HIDDEN, " ").replace(/[<>`]/g, "").replace(/\s+/g, " ").trim().slice(0, max);
}

export function validatePlan(input: unknown, opts: { demo?: boolean } = {}): PlanResult {
  const parsed = GoalPlanSchema.safeParse(input);
  if (!parsed.success) return { ok: false, reason: `the plan does not match the expected structure (${parsed.error.issues[0]?.path.join(".") || "root"}: ${parsed.error.issues[0]?.message ?? "invalid"})` };
  const p = parsed.data;
  const notes: string[] = [];

  if (!p.verifiable) return { ok: false, reason: p.unverifiableReason ?? "this goal cannot be verified on a phone" };
  if (p.proofMethods.length !== 1) {
    if (p.proofMethods.length === 0) return { ok: false, reason: "the plan has no way to prove it" };
    notes.push("only the first proof method is used");
  }
  const method = p.proofMethods[0]!;
  const type = method.type;
  const bounds = (opts.demo ? DEMO : NORMAL)[type];
  if (!bounds) return { ok: false, reason: `proof type ${type} is not available for stakes yet` };

  const title = cleanText(p.title, 120);
  if (title.length < 3) return { ok: false, reason: "the goal needs a short readable title" };

  // target consistency per proof type
  const t = p.target;
  const unit = t.unit.toLowerCase();
  const expect = (cond: boolean, msg: string) => cond || (() => { throw new Error(msg); })();
  let value = t.value;
  let direction: "atLeast" | "atMost" = t.direction;
  let targetUnit = unit;
  const params: Record<string, string> = {};
  try {
    expect(Number.isFinite(value) && value >= 0, "the target amount must be a number");
    switch (type) {
      case "CAMERA_POSE": {
        expect(direction === "atLeast", "reps must be an 'at least' target");
        value = Math.round(value);
        targetUnit = "reps";
        if (method.params.exercise !== undefined) {
          const ex = String(method.params.exercise).toLowerCase();
          if (ex === "squat" || ex === "pushup") params.exercise = ex;
        }
        break;
      }
      case "STEPS":
        expect(direction === "atLeast", "steps must be an 'at least' target");
        value = Math.round(value);
        targetUnit = "steps";
        break;
      case "FOCUS_TIMER":
      case "GEOFENCE": {
        expect(direction === "atLeast", "time spent must be an 'at least' target");
        expect(TIME_UNITS.has(unit), "use seconds, minutes or hours");
        break;
      }
      case "USAGE_LIMIT":
      case "NO_USE_WINDOW": {
        expect(TIME_UNITS.has(unit), "use seconds, minutes or hours");
        direction = "atMost";
        const app = cleanText(String(method.params.app ?? ""), 40);
        expect(APP_RE.test(app), "name the app to watch");
        params.app = app;
        if (type === "NO_USE_WINDOW") expect(p.window !== null, "an app-free window needs start and end times");
        break;
      }
      case "SELF_ATTEST":
        expect(direction === "atLeast", "a self-reported goal must be an 'at least' target");
        targetUnit = cleanText(t.unit, 20) || "times";
        break;
      default:
        return { ok: false, reason: `proof type ${type} is not available for stakes yet` };
    }
    if (type === "GEOFENCE") {
      const place = cleanText(String(method.params.place ?? "Place"), 40);
      params.place = PLACE_RE.test(place) ? place : "Place";
      const r = Number(method.params.radiusM ?? 150);
      params.radiusM = String(Math.min(2000, Math.max(50, Number.isFinite(r) ? Math.round(r) : 150)));
    }
    const measured = TIME_UNITS.has(targetUnit) ? value * (unitSeconds(targetUnit) ?? 1) : value;
    expect(measured >= bounds.min, `that is too small to stake on (the minimum is ${describe(type, bounds.min)})`);
    expect(measured <= bounds.max, `that is too large to be realistic (the maximum is ${describe(type, bounds.max)})`);
  } catch (e) {
    return { ok: false, reason: (e as Error).message };
  }

  const total = p.cadence.totalDays;
  const required = Math.min(total, Math.max(1, p.cadence.requiredDays));
  if (required !== p.cadence.requiredDays) notes.push("required days adjusted to fit the length");
  if (p.clarifyingQuestions.length > 0) notes.push("the plan came with questions; check it before you stake");

  const plan: GoalPlan = {
    title,
    category: p.category,
    cadence: { periodDays: 1, totalDays: total, requiredDays: required },
    target: { metric: cleanText(t.metric, 60) || "goal", value, unit: targetUnit, direction },
    // the trust tier is a property of the proof type, never of what the plan says about itself
    proofMethods: [{ type, params, trustTier: PROOF_TRUST[type] }],
    window: p.window,
    difficulty: Math.min(5, Math.max(1, Math.round(p.difficulty))),
    verifiable: true,
    unverifiableReason: null,
    suggestedAlternative: null,
    clarifyingQuestions: p.clarifyingQuestions.map((q) => cleanText(q, 200)).filter(Boolean).slice(0, 3),
  };
  if (plan.proofMethods[0]!.trustTier !== method.trustTier) notes.push(`trust tier set to ${plan.proofMethods[0]!.trustTier} (decided by the proof type)`);
  return { ok: true, plan, notes };
}

/**
 * The check used when a pool is created: the plan must pass validation, and nothing that is shown to other people may need changing.
 * A title with hidden or direction-changing characters, or a parameter the validator would drop or alter, is refused instead of silently
 * rewritten (the plan hash on chain is of the plan exactly as sent). Returns the reason, or null when the plan is fine.
 */
export function planProblemForStake(input: GoalPlan, opts: { demo?: boolean } = {}): string | null {
  const v = validatePlan(input, opts);
  if (!v.ok) return v.reason;
  if (cleanText(input.title, 120) !== input.title) return "the title contains characters that are not allowed";
  const sent = input.proofMethods[0]!.params;
  const kept = v.plan.proofMethods[0]!.params;
  for (const [k, val] of Object.entries(sent)) {
    if (!(k in kept)) return `the parameter "${k}" is not allowed for this kind of goal`;
    if (String(val) !== kept[k]) return `the parameter "${k}" has a value that is not allowed`;
  }
  return null;
}

function describe(type: ProofType, n: number): string {
  if (type === "CAMERA_POSE") return `${n} reps`;
  if (type === "STEPS") return `${n} steps`;
  if (type === "SELF_ATTEST") return `${n}`;
  return n >= 3600 ? `${n / 3600} hours` : n >= 60 ? `${n / 60} minutes` : `${n} seconds`;
}
