import { readFileSync } from "node:fs";
import { GoalPlanSchema, PROOF_TRUST, type GoalPlan, type ProofType } from "../domain/plan.js";

export interface CatalogTemplate {
  id: string;
  title: string;
  example: string;
  summary: string;
  category: GoalPlan["category"];
  metric: string;
  value: number;
  unit: string;
  direction: "atLeast" | "atMost";
  proofType: ProofType;
  difficulty: number;
  window: { startLocalTime: string; endLocalTime: string } | null;
  proofParams: Record<string, string>;
  demo: { value: number; unit: string } | null;
  keywords: string[];
  unitWords: string[];
  range: { min: number; max: number };
  needsPlace: boolean;
  needsApp: boolean;
  limitations: string[];
}

export const CATALOG: CatalogTemplate[] = JSON.parse(readFileSync(new URL("./goal-templates.json", import.meta.url), "utf8")) as CatalogTemplate[];

export const templateById = (id: string): CatalogTemplate | undefined => CATALOG.find((t) => t.id === id);

/** What a built plan was based on, so the app can ask for the right extras (a place, an app). */
export interface PlanExtras {
  needsPlace: boolean;
  needsApp: boolean;
  limitations: string[];
}

const SECONDS: Record<string, number> = { seconds: 1, minutes: 60, hours: 3600 };
export const unitSeconds = (unit: string): number | null => SECONDS[unit.toLowerCase()] ?? null;

export interface BuildOptions {
  value?: number;
  unit?: string;
  app?: string;
  window?: { startLocalTime: string; endLocalTime: string } | null;
  totalDays?: number;
  requiredDays?: number;
  place?: string;
  radiusM?: number;
}

const fmtNumber = (n: number) => (Number.isInteger(n) ? n.toLocaleString("en-US") : String(n));

function fillTitle(t: CatalogTemplate, value: number, unit: string, app: string | undefined, window: BuildOptions["window"]): string {
  const time = window ? window.startLocalTime : "";
  const shownUnit = value === 1 ? unit.replace(/s$/, "") : unit;
  return t.title.replace("{value}", fmtNumber(value)).replace("{unit}", shownUnit).replace("{app}", app ?? "the app").replace("{time}", time);
}

/** Default (and the cadence the user can edit): 7 days, at least 6 of them. */
export const defaultCadence = (total = 7) => ({ periodDays: 1 as const, totalDays: total, requiredDays: Math.max(1, Math.round(total * 0.85)) });

export function buildFromTemplate(t: CatalogTemplate, o: BuildOptions = {}): GoalPlan {
  const value = o.value ?? t.value;
  const unit = o.unit ?? t.unit;
  const app = o.app ?? t.proofParams.app;
  const window = o.window === undefined ? t.window : o.window;
  const params: Record<string, string> = { ...t.proofParams };
  if (app && t.needsApp) params.app = app;
  if (t.needsPlace) {
    if (o.place) params.place = o.place;
    if (o.radiusM) params.radiusM = String(o.radiusM);
  }
  const total = o.totalDays ?? 7;
  const cadence = defaultCadence(total);
  if (o.requiredDays !== undefined) cadence.requiredDays = Math.min(total, Math.max(1, o.requiredDays));
  const plan: GoalPlan = {
    title: fillTitle(t, value, unit, app, window),
    category: t.category,
    cadence,
    target: { metric: t.metric, value, unit, direction: t.direction },
    proofMethods: [{ type: t.proofType, params, trustTier: PROOF_TRUST[t.proofType] }],
    window: window ?? null,
    difficulty: t.difficulty,
    verifiable: true,
    unverifiableReason: null,
    suggestedAlternative: null,
    clarifyingQuestions: [],
  };
  return GoalPlanSchema.parse(plan);
}

/**
 * The same goal with an amount a short demo "day" can actually reach (20 seconds of focus instead of 2 hours).
 * Only for DEMO pools; the amounts below are the demo limits that the validator allows and normal pools refuse.
 */
export function demoize(plan: GoalPlan): GoalPlan {
  const type = plan.proofMethods[0]!.type;
  const small: Partial<Record<ProofType, { value: number; unit: string }>> = {
    CAMERA_POSE: { value: 3, unit: "reps" },
    STEPS: { value: 20, unit: "steps" },
    FOCUS_TIMER: { value: 20, unit: "seconds" },
    GEOFENCE: { value: 10, unit: "seconds" },
  };
  const s = small[type];
  if (!s) return plan;
  const next: GoalPlan = { ...plan, target: { ...plan.target, value: s.value, unit: s.unit } };
  return GoalPlanSchema.parse(next);
}

export function extrasFor(plan: GoalPlan, templateId?: string): PlanExtras {
  const type = plan.proofMethods[0]!.type;
  const out: string[] = [...(templateId ? (templateById(templateId)?.limitations ?? []) : [])];
  if (type === "SELF_ATTEST") out.push("Self-reported: nothing checks it, so the stake cap is low.");
  if (type === "STEPS" && !out.some((l) => /Steps are counted/.test(l))) out.push("Steps are counted from when you first open that day's check-in on this phone.");
  if (type === "FOCUS_TIMER" && !out.some((l) => /timer/i.test(l))) out.push("The timer counts only while Vowed is open on screen.");
  if (type === "GEOFENCE") out.push("You choose the spot when you create the challenge; it is saved on your phone only.");
  if ((type === "USAGE_LIMIT" || type === "NO_USE_WINDOW") && !out.some((l) => /Usage access/.test(l))) out.push("Needs Usage access on this phone. Only the minutes for the app you name are read.");
  if (plan.window && type !== "NO_USE_WINDOW") out.push("The time of day is shown but not checked yet.");
  return { needsPlace: type === "GEOFENCE", needsApp: type === "USAGE_LIMIT" || type === "NO_USE_WINDOW", limitations: [...new Set(out)] };
}
